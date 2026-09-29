package io.github.mangi.eta.ui.app

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.TextChunkRead
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolStepUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.UserQuestionMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

internal object AgentConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
        /**
         * history/journal 已从 checkpoint 解码进内存的会话。
         *
         * 冷启动只加载当前会话的完整 transcript；其余会话保留空列表并由
         * [loadConversationTranscript] 在真正需要时按需加载。保存时必须依靠这个集合
         * 跳过未加载会话的 checkpoint，否则空列表会把磁盘上的完整历史覆盖掉。
         */
        val transcriptLoadedIds: Set<String> = emptySet(),
    )

    /** 单个会话的模型投影历史与完整脱敏 journal。 */
    data class Transcript(
        val history: List<AgentModelClient.ConversationMessage>,
        val journal: List<AgentModelClient.ConversationMessage>,
    )

    private val saveMutex = Mutex()
    /** 上一次成功落盘的会话指纹；只在事务提交成功后推进，失败时保持旧值以便下次重写。 */
    private var savedConversations: Map<String, AgentConversationPersistence.Saved> = emptyMap()

    suspend fun load(
        context: Context,
        /** 测试和备份导入需要一次性拿到全部正文；正常 UI 冷启动保持 false。 */
        loadAllTranscripts: Boolean = false,
    ): Snapshot =
        withContext(Dispatchers.IO) {
            loadSnapshot(context.applicationContext, loadAllTranscripts)
        }

    /** 按需加载指定会话的 checkpoint 正文；旧格式内联正文与分块正文都由 DAO 统一还原。 */
    suspend fun loadConversationTranscript(
        context: Context,
        conversationId: String,
    ): Transcript =
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            val transcripts = loadCheckpointTranscripts(dao, conversationId)
            val history = (transcripts?.history ?: emptyList()).ifEmpty {
                loadMessages(dao, conversationId)
                    .sortedBy { it.sortIndex }
                    .toLegacyHistory()
            }
            Transcript(
                history = history,
                journal = transcripts?.journal ?: emptyList(),
            )
        }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
        /** null 表示所有会话正文都已加载（旧调用与测试默认行为）。 */
        transcriptLoadedConversationIds: Set<String>? = null,
    ) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                val sorted = conversationsById.entries
                    .sortedByDescending { (id, _) -> updatedAt[id] ?: 0L }

                val storedIds = sorted.mapTo(mutableSetOf()) { it.key }
                val selected = selectedConversationId
                    ?.takeIf { it in storedIds }
                    ?: sorted.firstOrNull()?.key
                val now = System.currentTimeMillis()
                val dao = EtaDatabase.get(appContext).conversationDao()
                // 保存前只查一次现有会话元数据：已存在的会话沿用数据库中的原始创建时间，避免反复保存时漂移；
                // 同时据此判定哪些会话已从内存里删除。
                val existingRows = dao.conversationMetadataRows()
                    .associateBy { row -> row.id }
                val removedConversationIds = (existingRows.keys - storedIds).toList()

                // 只构造并写入内容确实变化的会话；未变化的会话连检查点编码都不做。
                val conversations = mutableListOf<ConversationEntity>()
                val messagesByConversation = mutableMapOf<String, List<ConversationMessageEntity>>()
                val streamedCheckpoints = mutableMapOf<String, ConversationDao.StreamedCheckpoint>()
                val planned = mutableMapOf<String, AgentConversationPersistence.Saved>()
                val previousSaved = savedConversations
                sorted.forEach { (id, state) ->
                    val title = titles[id].orEmpty()
                    val previous = previousSaved[id]
                    val existing = existingRows[id]
                    val createdAt = AgentConversationPersistence.createdAt(
                        existing = existing?.createdAt,
                        previous = previous,
                        reported = updatedAt[id],
                        now = now,
                    )
                    val conversationUpdatedAt =
                        AgentConversationPersistence.updatedAt(updatedAt[id], previous, now)
                    val appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds)
                    val roleplayJson = state.roleplay?.let { json.encodeToString(it) }.orEmpty()
                    val revisionsJson =
                        if (state.roleplay == null) "" else json.encodeToString(state.roleplayMessages)
                    val fingerprint = AgentConversationPersistence.fingerprint(
                        state = state,
                        title = title,
                        createdAt = createdAt,
                        updatedAt = conversationUpdatedAt,
                        appliedRuntimeRunIdsJson = appliedRuntimeRunIdsJson,
                        roleplayJson = roleplayJson,
                        revisionsJson = revisionsJson,
                    )
                    val contentSignature = AgentConversationPersistence.contentSignature(state)
                    planned[id] = AgentConversationPersistence.Saved(
                        fingerprint = fingerprint,
                        createdAt = createdAt,
                        updatedAt = conversationUpdatedAt,
                        storedUpdatedAt = conversationUpdatedAt,
                        contentSignature = contentSignature,
                    )
                    if (!AgentConversationPersistence.shouldWrite(
                            fingerprint = fingerprint,
                            previous = previous,
                            storedUpdatedAt = existing?.updatedAt,
                        )
                    ) {
                        return@forEach
                    }
                    conversations += ConversationEntity(
                        id = id,
                        title = title,
                        thinkingEnabled = state.reasoningEffort.enablesReasoning,
                        reasoningEffort = state.reasoningEffort.wireValue,
                        appliedRuntimeRunIdsJson = appliedRuntimeRunIdsJson,
                        roleplayJson = roleplayJson,
                        revisionsJson = revisionsJson,
                        createdAt = createdAt,
                        updatedAt = conversationUpdatedAt,
                    )
                    // 消息与检查点要先把整份正文序列化成一个大字符串再分块（长会话下是单次几十 MB 的堆分配）。
                    // 手动压缩、切换会话这类保存只推进了时间戳，正文一字未动，跳过即可让库里已有的分块原样保留。
                    if (!AgentConversationPersistence.shouldWriteContent(
                            contentSignature = contentSignature,
                            previous = previous,
                            storedUpdatedAt = existing?.updatedAt,
                        )
                    ) {
                        return@forEach
                    }
                    messagesByConversation[id] = state.messages
                        .mapIndexedNotNull { index, message -> message.toEntityOrNull(id, index) }
                    // 未加载的会话 history/journal 是空占位，不能写回 checkpoint。
                    // 消息仍可安全写入；正文保持磁盘原样，等会话真正打开后再整份刷新。
                    if (transcriptLoadedConversationIds == null || id in transcriptLoadedConversationIds) {
                        // 检查点正文逐条产出分片直接写块：不先拼出整份 JSON，长会话下这一项就是几十 MB 的堆分配。
                        streamedCheckpoints[id] = ConversationDao.StreamedCheckpoint(
                            history = AgentConversationCodec.transcriptPieces(state.history),
                            journal = AgentConversationCodec.transcriptPieces(state.journal.ifEmpty { state.history }),
                        )
                    }
                }
                dao.saveIncremental(
                    conversations = conversations,
                    messagesByConversation = messagesByConversation,
                    streamedCheckpoints = streamedCheckpoints,
                    removedConversationIds = removedConversationIds,
                    state = selected?.let { ConversationStateEntity(selectedConversationId = it) },
                )
                savedConversations = planned
            }
        }
    }

    private suspend fun loadSnapshot(
        context: Context,
        loadAllTranscripts: Boolean,
    ): Snapshot {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = dao.conversations()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
                transcriptLoadedIds = emptySet(),
            )
        }

        val messagesByConversation = conversations.associate { conversation ->
            conversation.id to loadMessages(dao, conversation.id)
        }
        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()

        val storedSelected = dao.state()?.selectedConversationId
            ?.takeIf { selectedId -> conversations.any { it.id == selectedId } }
        val selected = storedSelected ?: conversations.first().id
        val transcriptLoadedIds = if (loadAllTranscripts) {
            conversations.mapTo(mutableSetOf()) { it.id }
        } else {
            mutableSetOf(selected)
        }

        conversations.forEach { conversation ->
            val transcriptLoaded = conversation.id in transcriptLoadedIds
            val transcripts = if (transcriptLoaded) {
                loadCheckpointTranscripts(dao, conversation.id)
            } else {
                null
            }
            val history = if (transcriptLoaded) {
                (transcripts?.history ?: emptyList()).ifEmpty {
                        messagesByConversation[conversation.id]
                            .orEmpty()
                            .sortedBy { it.sortIndex }
                            .toLegacyHistory()
                    }
            } else {
                emptyList()
            }
            states[conversation.id] = AgentChatHomeUiState(
                roleplay = conversation.roleplayJson.takeIf(String::isNotBlank)?.let { json.decodeFromString<RoleplayBinding>(it) },
                roleplayMessages = conversation.revisionsJson.takeIf(String::isNotBlank)?.let {
                    json.decodeFromString<RoleplayMessageState>(it)
                } ?: RoleplayMessageState(),
                journal = transcripts?.journal ?: emptyList(),
                messages = messagesByConversation[conversation.id]
                    .orEmpty()
                    .sortedBy { it.sortIndex }
                    .mapNotNull { it.toMessageOrNull() },
                history = history,
                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
                reasoningEffort = conversation.reasoningEffortValue,
            ).let(RoleplayConversationReducer::decorate)
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
        }

        return Snapshot(
            selectedConversationId = selected,
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
            transcriptLoadedIds = transcriptLoadedIds,
        )
    }

    private data class CheckpointTranscripts(
        val history: List<AgentModelClient.ConversationMessage>,
        val journal: List<AgentModelClient.ConversationMessage>,
    )

    /**
     * 读取 checkpoint 的两个 transcript。
     * 引用相同时只解码一次并共享列表；分块正文走流式 JSON 解码，不构造单个大字符串。
     */
    private suspend fun loadCheckpointTranscripts(
        dao: ConversationDao,
        conversationId: String,
    ): CheckpointTranscripts? {
        val row = dao.contextCheckpointRow(conversationId) ?: return null
        val history = decodeCheckpointField(dao, conversationId, "history", row.historyJson)
        val journal = if (row.historyJson.isNotBlank() && row.historyJson == row.journalJson) {
            history
        } else {
            decodeCheckpointField(dao, conversationId, "journal", row.journalJson)
        }
        return CheckpointTranscripts(history = history, journal = journal)
    }

    private suspend fun decodeCheckpointField(
        dao: ConversationDao,
        conversationId: String,
        field: String,
        stored: String,
    ): List<AgentModelClient.ConversationMessage> =
        when (
            val read = dao.readTextChunks(
                table = CHECKPOINT_TABLE,
                owner = conversationId,
                field = field,
                stored = stored,
            )
        ) {
            TextChunkRead.Inline -> AgentConversationCodec.decodeTranscript(stored)
            is TextChunkRead.Chunks -> if (read.contents.all(String::isEmpty)) {
                emptyList()
            } else {
                try {
                    decodeChunkedTranscript(read.contents)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent transcript stream decode failed: type=${throwable.safeLogType()}"
                    )
                    emptyList()
                }
            }
            TextChunkRead.Corrupted -> emptyList()
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeChunkedTranscript(
        chunks: List<String>,
    ): List<AgentModelClient.ConversationMessage> =
        json.decodeFromStream<List<AgentModelClient.ConversationMessage>>(
            ChunkedUtf8InputStream(chunks)
        )

    private suspend fun loadMessages(
        dao: ConversationDao,
        conversationId: String,
    ): List<ConversationMessageEntity> = buildList {
        var offset = 0
        while (true) {
            val page = dao.messagesPage(
                conversationId = conversationId,
                limit = MESSAGE_LOAD_PAGE_SIZE,
                offset = offset,
            )
            addAll(page)
            if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
            offset += page.size
        }
    }

    private val ConversationMetadata.reasoningEffortValue: ReasoningEffort
        get() = ReasoningEffort.fromWireValue(reasoningEffort) ?: ReasoningEffort.DEFAULT

    private fun AgentChatMessageUi.toEntityOrNull(
        conversationId: String,
        sortIndex: Int,
    ): ConversationMessageEntity? =
        when (this) {
            is UserMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER,
                content = content,
                imagesJson = images.toJsonArrayString(),
                isEdited = isEdited,
            )

            is AgentMessageUi -> {
                if (content.isBlank() && isStreaming) {
                    null
                } else {
                    ConversationMessageEntity(
                        id = id,
                        conversationId = conversationId,
                        sortIndex = sortIndex,
                        type = TYPE_ASSISTANT,
                        content = content,
                        renderMarkdown = renderMarkdown,
                        contextTokens = usage?.contextTokens,
                        inputTokens = usage?.inputTokens,
                        outputTokens = usage?.outputTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                    )
                }
            }

            is SystemNoticeMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SYSTEM_NOTICE,
                content = code.wireValue,
                resultSummary = detail,
                contextTokens = contextTokens,
                renderMarkdown = false,
            )

            is UserQuestionMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER_QUESTION,
                content = question,
                argumentsSummary = AgentUserQuestionPayload.encode(
                    questionId = questionId,
                    options = options,
                    multiSelect = multiSelect,
                    allowFreeform = allowFreeform,
                    timedOut = timedOut,
                    cancelled = cancelled,
                ),
                resultSummary = answer,
                renderMarkdown = false,
            )

            is ThinkingMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_THINKING,
                content = content,
                elapsedSeconds = elapsedSeconds,
            )

            is ToolActivityMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL,
                content = command.orEmpty(),
                toolName = toolName,
                toolStatus = status.name,
                argumentsSummary = argumentsSummary,
                resultSummary = resultSummary,
                detail = detail,
                stepsJson = steps.toStepsJson(),
                imageCount = imageCount,
            )

            is ToolSummaryMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL_SUMMARY,
                content = "",
                toolsJson = tools.toJsonArrayString(),
            )

            else -> null
        }

    private fun ConversationMessageEntity.toMessageOrNull(): AgentChatMessageUi? =
        when (type) {
            TYPE_USER -> UserMessageUi(
                id = id,
                content = content,
                images = imagesJson.toStringList(),
                isEdited = isEdited,
            )

            TYPE_ASSISTANT -> AgentMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                renderMarkdown = renderMarkdown ?: true,
                usage = TokenUsageUi(
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                ).takeUnless { it.isEmpty },
            )

            TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
                SystemNoticeMessageUi(
                    id = id,
                    code = code,
                    detail = resultSummary,
                    contextTokens = contextTokens,
                )
            }

            TYPE_USER_QUESTION -> {
                val payload = AgentUserQuestionPayload.decode(argumentsSummary)
                UserQuestionMessageUi(
                    id = id,
                    questionId = payload.questionId.ifBlank { id },
                    question = content,
                    options = payload.options,
                    multiSelect = payload.multiSelect,
                    allowFreeform = payload.allowFreeform,
                    answer = resultSummary?.takeIf { it.isNotBlank() },
                    timedOut = payload.timedOut,
                    cancelled = payload.cancelled,
                )
            }

            TYPE_THINKING -> ThinkingMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                collapsed = true,
            )

            TYPE_TOOL -> ToolActivityMessageUi(
                id = id,
                toolName = toolName.orEmpty(),
                status = toolStatus.orEmpty().toToolStatus(),
                argumentsSummary = argumentsSummary.orEmpty(),
                command = content.takeIf(String::isNotBlank),
                resultSummary = resultSummary,
                detail = detail,
                steps = stepsJson.toToolSteps(),
                imageCount = imageCount,
            )

            TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
                id = id,
                tools = toolsJson.toStringList(),
            )

            else -> null
        }

    private fun List<ToolStepUi>.toStepsJson(): String = JSONArray().also { array ->
        forEach { step ->
            array.put(
                JSONObject()
                    .put("id", step.id)
                    .put("tool", step.toolName)
                    .put("status", step.status.name)
                    .put("summary", step.summary)
                    .put("detail", step.detail)
            )
        }
    }.toString()

    private fun String.toToolSteps(): List<ToolStepUi> = runCatching {
        val array = JSONArray(this)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val toolName = item.optString("tool")
                if (toolName.isBlank()) continue
                add(
                    ToolStepUi(
                        id = item.optString("id"),
                        toolName = toolName,
                        status = item.optString("status").toToolStatus(),
                        summary = item.optString("summary"),
                        detail = item.optString("detail"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun String.toToolStatus(): ToolActivityStatusUi =
        runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
            ?.let { status ->
                if (status == ToolActivityStatusUi.Running) ToolActivityStatusUi.Unknown else status
            }
            ?: ToolActivityStatusUi.Unknown

    private fun List<String>.toJsonArrayString(): String =
        JSONArray().also { array ->
            forEach { array.put(it) }
        }.toString()

    private fun String.toStringList(): List<String> =
        runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
        mapNotNull { message ->
            when (message.type) {
                TYPE_USER -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )
                TYPE_ASSISTANT -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }
                else -> null
            }
        }

    private const val TYPE_USER = "user"
    private const val TYPE_ASSISTANT = "assistant"
    private const val TYPE_SYSTEM_NOTICE = "system_notice"
    private const val TYPE_USER_QUESTION = "user_question"
    private const val TYPE_THINKING = "thinking"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val MESSAGE_LOAD_PAGE_SIZE = 128
    private const val LEGACY_UNNAMED_TITLE = "新对话"
    private const val CHECKPOINT_TABLE = "conversation_context_checkpoints"
}

/**
 * 把已经按 chunk_index 排好序的分片按 UTF-8 字节流吐出。
 *
 * 分片边界由写侧保证不拆 UTF-16 代理对，因此逐片编码后再拼接与整串编码字节一致；
 * JSON 解码器按需读取，不需要在堆上构造完整的大字符串。
 */
private class ChunkedUtf8InputStream(
    private val chunks: List<String>,
) : InputStream() {
    private var chunkIndex = 0
    private var chunkBytes = ByteArray(0)
    private var byteOffset = 0

    override fun read(): Int {
        if (!ensureAvailable()) return -1
        return chunkBytes[byteOffset++].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return 0
        var written = 0
        while (written < length) {
            if (!ensureAvailable()) break
            val count = minOf(length - written, chunkBytes.size - byteOffset)
            System.arraycopy(chunkBytes, byteOffset, buffer, offset + written, count)
            byteOffset += count
            written += count
        }
        return if (written == 0) -1 else written
    }

    private fun ensureAvailable(): Boolean {
        if (byteOffset < chunkBytes.size) return true
        while (chunkIndex < chunks.size) {
            chunkBytes = chunks[chunkIndex++].toByteArray(Charsets.UTF_8)
            byteOffset = 0
            if (chunkBytes.isNotEmpty()) return true
        }
        return false
    }
}

/**
 * Options and flags of a question message go to the argumentsSummary column as JSON, the answer goes to
 * resultSummary.
 * That avoids a new table shape: older versions skip an unknown type instead of needing a migration.
 */
private object AgentUserQuestionPayload {
    fun encode(
        questionId: String,
        options: List<String>,
        multiSelect: Boolean,
        allowFreeform: Boolean,
        timedOut: Boolean,
        cancelled: Boolean = false,
    ): String = JSONObject()
        .put(KEY_QUESTION_ID, questionId)
        .put(KEY_OPTIONS, JSONArray(options))
        .put(KEY_MULTI_SELECT, multiSelect)
        .put(KEY_ALLOW_FREEFORM, allowFreeform)
        .put(KEY_TIMED_OUT, timedOut)
        .put(KEY_CANCELLED, cancelled)
        .toString()

    fun decode(raw: String?): Decoded {
        val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return Decoded()
        val array = json.optJSONArray(KEY_OPTIONS)
        val options = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return Decoded(
            questionId = json.optString(KEY_QUESTION_ID),
            options = options,
            multiSelect = json.optBoolean(KEY_MULTI_SELECT, false),
            allowFreeform = json.optBoolean(KEY_ALLOW_FREEFORM, true),
            timedOut = json.optBoolean(KEY_TIMED_OUT, false),
            cancelled = json.optBoolean(KEY_CANCELLED, false),
        )
    }

    data class Decoded(
        val questionId: String = "",
        val options: List<String> = emptyList(),
        val multiSelect: Boolean = false,
        val allowFreeform: Boolean = true,
        val timedOut: Boolean = false,
        val cancelled: Boolean = false,
    )

    private const val KEY_QUESTION_ID = "questionId"
    private const val KEY_OPTIONS = "options"
    private const val KEY_MULTI_SELECT = "multiSelect"
    private const val KEY_ALLOW_FREEFORM = "allowFreeform"
    private const val KEY_TIMED_OUT = "timedOut"
    private const val KEY_CANCELLED = "cancelled"
}

/**
 * 多个进程共享同一个 SQLite 库时，写方会瞬时拿到 busy/locked（例如另一个进程正持写锁）。
 * 这类失败重试即可，不代表数据有问题；损坏、磁盘满、约束冲突等一律不算。
 *
 * Android 没有公开 SQLite 的 result code，只能按异常类型与消息判定，因此只认最明确的形态：
 * [SQLiteDatabaseLockedException]，以及消息里带 locked/busy 的 [SQLiteException]。
 */
internal fun Throwable.isTransientDatabaseContention(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_DATABASE_CONTENTION_CAUSE_DEPTH) {
        val throwable = current
        if (throwable is SQLiteDatabaseLockedException) return true
        if (throwable is SQLiteException) {
            val message = throwable.message.orEmpty()
            if (message.contains("locked", ignoreCase = true) ||
                message.contains("busy", ignoreCase = true)
            ) {
                return true
            }
        }
        val cause = throwable.cause
        current = if (cause === throwable) null else cause
        depth += 1
    }
    return false
}

private const val MAX_DATABASE_CONTENTION_CAUSE_DEPTH = 4

/**
 * 落盘失败的诊断文本：总是给出异常类名。
 *
 * SQLite 异常的 message 只包含表名与错误码（如 `FOREIGN KEY constraint failed (code 787 ...)`），
 * 不含用户正文，因此一并给出；其它异常（反序列化等）的消息可能夹带会话内容，
 * 一律只保留类名，禁止外带。
 *
 * 这段文本会随失败提示一起展现给用户，用于定位无法复现的写盘失败。
 */
internal fun Throwable.persistenceFailureReason(): String {
    val type = safeLogType()
    val detail = (this as? SQLiteException)?.message?.takeIf { it.isNotBlank() } ?: return type
    return "$type: ${detail.take(MAX_PERSISTENCE_FAILURE_CHARS)}"
}

private const val MAX_PERSISTENCE_FAILURE_CHARS = 120
