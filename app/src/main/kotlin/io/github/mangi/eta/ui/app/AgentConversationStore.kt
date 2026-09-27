package io.github.mangi.eta.ui.app

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

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
    )

    private val saveMutex = Mutex()
    /** 上一次成功落盘的会话指纹；只在事务提交成功后推进，失败时保持旧值以便下次重写。 */
    private var savedConversations: Map<String, AgentConversationPersistence.Saved> = emptyMap()

    fun load(context: Context): Snapshot =
        runBlocking(Dispatchers.IO) {
            loadSnapshot(context.applicationContext)
        }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
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
                    // 检查点正文逐条产出分片直接写块：不先拼出整份 JSON，长会话下这一项就是几十 MB 的堆分配。
                    streamedCheckpoints[id] = ConversationDao.StreamedCheckpoint(
                        history = AgentConversationCodec.transcriptPieces(state.history),
                        journal = AgentConversationCodec.transcriptPieces(state.journal.ifEmpty { state.history }),
                    )
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

    private suspend fun loadSnapshot(context: Context): Snapshot {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = dao.conversations()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val messagesByConversation = conversations.associate { conversation ->
            conversation.id to buildList {
                var offset = 0
                while (true) {
                    val page = dao.messagesPage(
                        conversationId = conversation.id,
                        limit = MESSAGE_LOAD_PAGE_SIZE,
                        offset = offset,
                    )
                    addAll(page)
                    if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                    offset += page.size
                }
            }
        }
        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()

        conversations.forEach { conversation ->
            val checkpoint = dao.contextCheckpoint(conversation.id)
            states[conversation.id] = AgentChatHomeUiState(
                roleplay = conversation.roleplayJson.takeIf(String::isNotBlank)?.let { json.decodeFromString<RoleplayBinding>(it) },
                roleplayMessages = conversation.revisionsJson.takeIf(String::isNotBlank)?.let {
                    json.decodeFromString<RoleplayMessageState>(it)
                } ?: RoleplayMessageState(),
                journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson),
                messages = messagesByConversation[conversation.id]
                    .orEmpty()
                    .sortedBy { it.sortIndex }
                    .mapNotNull { it.toMessageOrNull() },
                history = AgentConversationCodec.decodeTranscript(
                    checkpoint?.historyJson
                )
                    .ifEmpty {
                        messagesByConversation[conversation.id]
                            .orEmpty()
                            .sortedBy { it.sortIndex }
                            .toLegacyHistory()
                    },
                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
                reasoningEffort = conversation.reasoningEffortValue,
            ).let(RoleplayConversationReducer::decorate)
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
        }

        val selected = dao.state()?.selectedConversationId
            ?.takeIf { it in states }
            ?: states.keys.first()

        return Snapshot(
            selectedConversationId = selected,
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
        )
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
    private const val TYPE_THINKING = "thinking"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val MESSAGE_LOAD_PAGE_SIZE = 128
    private const val LEGACY_UNNAMED_TITLE = "新对话"
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
