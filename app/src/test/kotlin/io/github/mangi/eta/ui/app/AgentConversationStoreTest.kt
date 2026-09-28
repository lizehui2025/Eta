package io.github.mangi.eta.ui.app

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.AgentTextChunkEntity
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolStepUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    /**
     * 初始会话快照在 IO 线程读取、回主线程应用；Robolectric 的主 Looper 不会自动执行，
     * 因此手动推进主 Looper，直到 [AgentAppState.awaitInitialLoad] 表明门控已打开。
     */
    private fun awaitInitialConversationLoad(state: AgentAppState) {
        val deadlineMillis = System.currentTimeMillis() + 10_000
        while (state.conversationsLoading) {
            check(System.currentTimeMillis() < deadlineMillis) { "初始会话加载未在超时内完成" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        runBlocking { state.awaitInitialLoad() }
    }

    @Test
    fun repeatedSavePreservesRoleBindingRevisionsPendingRewriteAndOriginalJournal() = runBlocking {
        val original = AgentModelClient.ConversationMessage(
            role = "assistant", content = "原始回答", messageId = "assistant-role-1",
        )
        val binding = RoleplayBinding(
            characterId = "character-1",
            cardSnapshotJson = CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")),
            characterName = "旅人", userName = "朋友", userDescription = "同行的伙伴",
        )
        val state = AgentChatHomeUiState(
            messages = listOf(AgentMessageUi(id = original.messageId, content = original.content, isStreaming = false)),
            input = "", isStreaming = false, thinkingEnabled = false,
            journal = listOf(original), history = listOf(original), roleplay = binding,
            roleplayMessages = RoleplayMessageState(
                links = mapOf(original.messageId to RoleplayMessageLink(original.messageId)),
                pendingRewrites = mapOf("rewrite-in-flight" to original.messageId),
            ),
        )
        var role = RoleplayConversationReducer.edit(state, original.messageId, "用户修订的回答")!!
        repeat(2) {
            AgentConversationStore.save(
                context, "role", mapOf("role" to role, "ordinary" to AgentChatHomeUiState(
                    messages = listOf(UserMessageUi(id = "ordinary-user", content = "查看电量")),
                    input = "", isStreaming = false, thinkingEnabled = false,
                )), mapOf("role" to "旅人", "ordinary" to "查看电量"), mapOf("role" to 1L, "ordinary" to 2L),
            )
            val restored = AgentConversationStore.load(context)
            role = restored.conversationsById.getValue("role")
            assertEquals(binding, role.roleplay)
            assertEquals(listOf(original), role.journal)
            assertEquals("用户修订的回答", role.history.single().content)
            assertEquals("rewrite-in-flight", role.roleplayMessages.pendingRewrites.keys.single())
            assertEquals(listOf("原始回答", "用户修订的回答"), role.roleplayMessages.revisions.getValue(original.messageId).candidates)
            assertEquals(2, (role.messages.single() as AgentMessageUi).candidateCount)
            assertEquals(null, restored.conversationsById.getValue("ordinary").roleplay)
            assertTrue(restored.conversationsById.getValue("ordinary").roleplayMessages.revisions.isEmpty())
        }
        val switched = RoleplayConversationReducer.select(role, original.messageId, 0)!!
        assertEquals("原始回答", switched.history.single().content)
        assertEquals(listOf(original), switched.journal)
    }

    @Test
    fun saveAndLoadPreservesConversations() {
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(
                    id = "user-1",
                    content = "看一下当前屏幕",
                    isEdited = true,
                ),
                ThinkingMessageUi(
                    id = "thinking-1",
                    content = "需要先观察屏幕",
                    isStreaming = false,
                    elapsedSeconds = 3,
                    collapsed = true,
                ),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "执行命令 · Android · root",
                    command = "pm list packages | head",
                    resultSummary = "ok=true, chars=100",
                    imageCount = 1,
                    detail = "退出码 0\n\n输出（前 1 行）\ncom.android.settings",
                ),
                ToolActivityMessageUi(
                    id = "subagent-1",
                    toolName = SUBAGENT_TOOL_NAME,
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "查相册",
                    resultSummary = "相册 3 张",
                    detail = "相册 3 张 · 已修改 1 个文件",
                    steps = listOf(
                        ToolStepUi(
                            id = "step-1",
                            toolName = "search_media",
                            status = ToolActivityStatusUi.Success,
                            summary = "最近 7 天",
                            detail = "找到 3 张照片",
                        ),
                    ),
                ),
                AgentMessageUi(
                    id = "assistant-1",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                    isStreaming = false,
                    renderMarkdown = true,
                    usage = TokenUsageUi(
                        contextTokens = 100,
                        inputTokens = 30,
                        outputTokens = 40,
                        reasoningTokens = 20,
                        cachedTokens = 10,
                    ),
                ),
            ),
            history = listOf(
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "看一下当前屏幕",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    reasoningContent = "需要先观察屏幕",
                    toolCallsJson = """[{"id":"toolu_1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "tool",
                    content = "{\"ok\":true}",
                    toolCallId = "toolu_1",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                ),
            ),
            input = "不应该保存草稿",
            isStreaming = true,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
        )

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf("conv-1" to conversation),
                titles = mapOf("conv-1" to "屏幕分析"),
                updatedAt = mapOf("conv-1" to 1234L),
            )
        }

        val snapshot = runBlocking { AgentConversationStore.load(context) }

        assertEquals("conv-1", snapshot.selectedConversationId)
        assertEquals("屏幕分析", snapshot.titles.getValue("conv-1"))
        assertEquals(1234L, snapshot.updatedAt.getValue("conv-1"))
        val restored = snapshot.conversationsById.getValue("conv-1")
        assertEquals("", restored.input)
        assertFalse(restored.isStreaming)
        assertTrue(restored.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, restored.reasoningEffort)
        assertEquals(conversation.messages, restored.messages)
        assertEquals(conversation.history, restored.history)
    }

    @Test
    fun saveAndLoadPreservesSemanticSystemNoticesWithoutTranslatedContent() {
        val notice = SystemNoticeMessageUi(
            id = "assistant-run-1-1",
            code = SystemNoticeCode.RuntimeFailed,
            detail = "upstream timeout",
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-notice",
                conversationsById = mapOf(
                    "conv-notice" to AgentChatHomeUiState(
                        messages = listOf(notice),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-notice" to ""),
                updatedAt = mapOf("conv-notice" to 1L),
            )
        }

        val snapshot = runBlocking { AgentConversationStore.load(context) }
        assertEquals("", snapshot.titles.getValue("conv-notice"))
        assertEquals(
            notice,
            snapshot.conversationsById.getValue("conv-notice").messages.single(),
        )
    }

    @Test
    fun unknownStoredEffortFallsBackToDefault() {
        runBlocking {
            EtaDatabase.get(context).conversationDao().replaceAll(
                conversations = listOf(
                    ConversationEntity(
                        id = "conv-unknown",
                        title = "Unknown",
                        thinkingEnabled = false,
                        reasoningEffort = "future_effort",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                ),
                messages = emptyList(),
                state = ConversationStateEntity(selectedConversationId = "conv-unknown"),
            )
        }

        val restored = runBlocking { AgentConversationStore.load(context) }
            .conversationsById
            .getValue("conv-unknown")

        assertEquals(ReasoningEffort.DEFAULT, restored.reasoningEffort)
        assertTrue(restored.thinkingEnabled)
    }

    @Test
    fun saveAndLoadPreservesAllConversationsAndMessagesWithoutClipping() {
        val longContent = "x".repeat(20_000)
        val primaryMessages = buildList {
            add(UserMessageUi(id = "conv-0-user-long", content = longContent))
            repeat(130) { index ->
                add(
                    AgentMessageUi(
                        id = "conv-0-assistant-$index",
                        content = "assistant-$index",
                        isStreaming = false,
                    )
                )
            }
        }
        val conversations = buildMap {
            put(
                "conv-0",
                AgentChatHomeUiState(
                    messages = primaryMessages,
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                )
            )
            repeat(59) { index ->
                val id = "conv-${index + 1}"
                put(
                    id,
                    AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "$id-user", content = "message-$id")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                )
            }
        }
        val titles = conversations.keys.associateWith { id -> "title-$id" }
        val updatedAt = conversations.keys.associateWith { id -> id.removePrefix("conv-").toLong() }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-0",
                conversationsById = conversations,
                titles = titles,
                updatedAt = updatedAt,
            )
        }

        val snapshot = runBlocking { AgentConversationStore.load(context) }

        assertEquals(60, snapshot.conversationsById.size)
        val restored = snapshot.conversationsById.getValue("conv-0")
        assertEquals(131, restored.messages.size)
        assertEquals(longContent, (restored.messages.first() as UserMessageUi).content)
        assertEquals("assistant-129", (restored.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun savePreservesCompleteContextAndDisplayedMessages() {
        val displayedContent = "展示消息-${"d".repeat(120_000)}"
        val history = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "历史-$index-${"h".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "最新上下文"))
        }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-large",
                conversationsById = mapOf(
                    "conv-large" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "user-large", content = displayedContent)
                        ),
                        history = history,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-large" to "长对话"),
                updatedAt = mapOf("conv-large" to 1L),
            )
        }

        val checkpoint = runBlocking {
            EtaDatabase.get(context)
                .conversationDao()
                .contextCheckpoint("conv-large")!!
        }
        val restored = runBlocking { AgentConversationStore.load(context) }
            .conversationsById
            .getValue("conv-large")

        assertTrue(checkpoint.historyJson.length > 96_000)
        assertEquals(history, restored.history)
        assertEquals(history, restored.journal)
        assertEquals(displayedContent, (restored.messages.single() as UserMessageUi).content)
        assertEquals("最新上下文", restored.history.last().content)
    }

    @Test
    fun loadIgnoresLegacyHistoryColumnAndFallsBackToMessageRows() {
        runBlocking {
            val dao = EtaDatabase.get(context).conversationDao()
            dao.insertConversations(
                listOf(
                    ConversationEntity(
                        id = "conv-legacy-large",
                        title = "旧长对话",
                        thinkingEnabled = false,
                        historyJson = "x".repeat(2_500_000),
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
            )
            dao.insertMessages(
                listOf(
                    ConversationMessageEntity(
                        id = "legacy-user",
                        conversationId = "conv-legacy-large",
                        sortIndex = 0,
                        type = "user",
                        content = "从消息记录恢复",
                    )
                )
            )
        }

        val restored = runBlocking { AgentConversationStore.load(context) }
            .conversationsById
            .getValue("conv-legacy-large")

        assertEquals("从消息记录恢复", restored.history.single().content)
        assertEquals("从消息记录恢复", (restored.messages.single() as UserMessageUi).content)
    }

    @Test
    fun loadKeepsDatabaseEmptyUntilFirstMessageIsSent() {
        val snapshot = runBlocking { AgentConversationStore.load(context) }

        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun characterGreetingIsLocalAndOrdinaryNewConversationReturnsToEta() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)
            awaitInitialConversationLoad(state)
            val binding = RoleplayBinding(
                "local-character", CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")),
                "旅人", userName = "小林",
            )
            state.startCharacterConversation(binding, "你好，{{user}}，我是{{char}}。")
            assertFalse(state.homeState.isStreaming)
            assertEquals("你好，小林，我是旅人。", (state.homeState.messages.single() as AgentMessageUi).content)
            assertEquals(binding, state.homeState.roleplay)
            assertTrue(state.homeState.appliedRuntimeRunIds.isEmpty())
            assertTrue(state.homeState.roleplayMessages.pendingRewrites.isEmpty())

            state.createConversation()
            assertEquals(null, state.homeState.roleplay)
            assertTrue(state.homeState.history.isEmpty())
            assertTrue(state.homeState.messages.isEmpty())
            assertFalse(state.homeState.isStreaming)
            // 冲掉在途落盘：角色会话创建会异步写盘，晚到的写会污染下一个用例的库。
            idleUntil { !state.hasPendingPersistence() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun creatingConversationKeepsEmptyStateOutOfHistoryAndDatabase() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)
            awaitInitialConversationLoad(state)
            // 用"前后差"判定：应用自身的后台恢复与其它用例的落盘都可能在这条用例运行期间补上会话，
            // 本用例只关心 createConversation 自身不新增面板项、也不写库。
            val paneBefore = state.conversationPaneState.conversations
            val rowsBefore = conversationRowCount()

            state.createConversation()
            state.createConversation()

            assertEquals(null, state.conversationPaneState.selectedConversationId)
            assertEquals(paneBefore, state.conversationPaneState.conversations)
            assertEquals(rowsBefore, conversationRowCount())
        } finally {
            scope.cancel()
        }
    }

    private fun conversationRowCount(): Int = runBlocking {
        EtaDatabase.get(context).conversationDao().conversationMetadataRows().size
    }

    @Test
    fun savingEmptySnapshotClearsPreviouslyPersistedConversations() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-1" to "hello"),
                updatedAt = mapOf("conv-1" to 1L),
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val snapshot = runBlocking { AgentConversationStore.load(context) }
        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun repeatedSaveKeepsOriginalCreatedAt() {
        // 多次保存同一会话时 created_at 必须保持首次写入值，不能随 updated_at 漂移。
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-created",
                conversationsById = mapOf(
                    "conv-created" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "created-user", content = "第一次保存")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-created" to "创建时间"),
                updatedAt = mapOf("conv-created" to 1_000L),
            )
            val first = EtaDatabase.get(context).conversationDao().conversationMetadataRows()
                .single { it.id == "conv-created" }

            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-created",
                conversationsById = mapOf(
                    "conv-created" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "created-user", content = "第二次保存")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-created" to "创建时间"),
                updatedAt = mapOf("conv-created" to 2_000L),
            )
            val second = EtaDatabase.get(context).conversationDao().conversationMetadataRows()
                .single { it.id == "conv-created" }

            assertEquals(1_000L, first.createdAt)
            assertEquals(1_000L, second.createdAt)
            assertEquals(2_000L, second.updatedAt)
        }
    }

    /**
     * 只改时间戳的保存（手动压缩、切换会话）不得重写正文分块 —— 那是长会话下"无法保存对话
     * （OutOfMemoryError）"的来源：整份正文要重新序列化再分块，单次就是几十 MB 的堆分配。
     *
     * 观测方式：先落盘，再把库里的分块改成哨兵值，然后做一次只推进时间戳的保存；
     * 哨兵值仍在即证明正文确实没被重写。
     */
    @Test
    fun timestampOnlySaveLeavesStoredTranscriptUntouched() {
        val body = buildString {
            repeat(2_000) { append("第 ").append(it).append(" 行：这条正文要撑过 16 KiB 分块阈值。\n") }
        }
        val seeded = AgentChatHomeUiState(
            messages = listOf(AgentMessageUi(id = "assistant-long", content = body, isStreaming = false)),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "assistant", content = body, messageId = "assistant-long")
            ),
            journal = listOf(
                AgentModelClient.ConversationMessage(role = "assistant", content = body, messageId = "assistant-long")
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-stamp",
                conversationsById = mapOf("conv-stamp" to seeded),
                titles = mapOf("conv-stamp" to "只改时间戳"),
                updatedAt = mapOf("conv-stamp" to 1L),
            )
            EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
                "UPDATE agent_text_chunks SET content = 'STALE' " +
                    "WHERE owner_table = 'conversation_context_checkpoints' AND field = 'journal'"
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-stamp",
                conversationsById = mapOf("conv-stamp" to seeded.copy(isStreaming = true, isCompacting = true)),
                titles = mapOf("conv-stamp" to "只改时间戳"),
                updatedAt = mapOf("conv-stamp" to 2L),
            )
        }

        val staleChunks = runBlocking {
            EtaDatabase.get(context).openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM agent_text_chunks WHERE content = 'STALE'")
                .use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
        }
        assertTrue(
            "只改时间戳的保存重写了正文分块：长会话下这次重写要整份序列化 + 分块，是 OOM 的来源",
            staleChunks > 0,
        )
    }

    /**
     * 分片写入必须与整份写入完全等价：写同样的分块、返回同样的引用串。
     * 这是"不再构造整份正文"能安全替换旧路径的前提——长会话的整份序列化会直接 OOM。
     */
    @Test
    fun streamedStoreWritesSameChunksAndReferenceAsWholeTextStore() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val table = "conversation_context_checkpoints"
        val field = "journal"
        val line = "第 %d 行：中文正文，用于校验分块与代理对边界。\n"
        val samples = listOf(
            "",
            "[]",
            "短文本",
            "@eta:chunks:v1:3:100",
            buildString { repeat(3_000) { append(line.format(it)) } },
            buildString { repeat(3_000) { append(if (it % 2 == 0) "\uD83D\uDE00" else "字") } },
        )
        samples.forEachIndexed { index, sample ->
            val wholeOwner = "whole-$index"
            val streamedOwner = "streamed-$index"
            val wholeRef = dao.storeText(table, wholeOwner, field, sample)
            val wholeChunks = dao.textChunks(table, wholeOwner, field, CHUNK_READ_LIMIT, 0)
                .map { it.chunkIndex to it.content }
            val streamedRef = dao.storeTextPieces(table, streamedOwner, field, sample.asPieces())
            val streamedChunks = dao.textChunks(table, streamedOwner, field, CHUNK_READ_LIMIT, 0)
                .map { it.chunkIndex to it.content }
            assertEquals("引用串必须一致（正文长度=${sample.length}）", wholeRef, streamedRef)
            assertEquals("分块必须一致（正文长度=${sample.length}）", wholeChunks, streamedChunks)
        }

        // 带损坏哨兵行时，退化值写回必须被同样拒绝（两个路径都返回哨兵行保存的原始引用）。
        val sentinel = "@eta:chunks:v1:9:9"
        dao.insertTextChunk(
            AgentTextChunkEntity(table, "whole-sentinel", field, ChunkedTextDao.CORRUPTED_CHUNK_INDEX, sentinel)
        )
        dao.insertTextChunk(
            AgentTextChunkEntity(table, "streamed-sentinel", field, ChunkedTextDao.CORRUPTED_CHUNK_INDEX, sentinel)
        )
        assertEquals(
            dao.storeText(table, "whole-sentinel", field, "[]"),
            dao.storeTextPieces(table, "streamed-sentinel", field, sequenceOf("[]")),
        )
    }

    /** 按不等长分片喂入，覆盖缓冲、拆点与代理对边界。 */
    private fun String.asPieces(): Sequence<String> = sequence {
        if (isEmpty()) {
            yield("")
            return@sequence
        }
        val sizes = intArrayOf(1, 7, 4_096, 16_384, 65_537)
        var offset = 0
        var index = 0
        while (offset < length) {
            val end = minOf(offset + sizes[index % sizes.size], length)
            yield(substring(offset, end))
            offset = end
            index++
        }
    }

    /** 分片拼出的 JSON 必须与整份序列化逐字节相同，否则库里会写成非法 JSON。 */
    @Test
    fun transcriptPiecesConcatenateToTheSameJsonAsWholeEncoding() {
        val messages = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "问题", messageId = "m1"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "",
                toolCallsJson = """[{"id":"t","function":{"name":"x","arguments":"{}"}}]""",
            ),
            AgentModelClient.ConversationMessage(role = "tool", content = "{\"ok\":true}", toolCallId = "t"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "含 Unicode 😀 与转义 \\ \" 的正文",
                compactedUserTurns = 2,
            ),
        )
        assertEquals(
            AgentConversationCodec.encodeTranscriptForStorage(messages),
            AgentConversationCodec.transcriptPieces(messages).joinToString(""),
        )
        assertEquals("[]", AgentConversationCodec.transcriptPieces(emptyList()).joinToString(""))
    }

    /**
     * 手动压缩的写前闸门回归：压缩 run 必须在落盘成功后才会交给 Runtime。
     * 落盘失败时压缩结果标记的 detail 会写成 conversation_persistence_failed 文案，
     * 这里断言该文案不出现，即写前落盘成功、压缩已进入 Runtime 准备阶段。
     */
    @Test
    fun compactionRunPassesWriteAheadPersistence() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)
            awaitInitialConversationLoad(state)
            state.startCharacterConversation(
                RoleplayBinding(
                    characterId = "compaction-character",
                    cardSnapshotJson = CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")),
                    characterName = "旅人",
                    userName = "小林",
                ),
                "你好，{{user}}",
            )
            state.compactCurrentContext()
            idleUntil { !state.homeState.isStreaming }
            // 冲掉在途落盘：压缩起跑与终态各会写一次盘。
            idleUntil { !state.hasPendingPersistence() }

            val notices = state.homeState.messages.filterIsInstance<SystemNoticeMessageUi>()
            val persistenceFailure = context.getString(R.string.conversation_persistence_failed)
            assertTrue(
                "压缩未走到终态标记：$notices",
                notices.any { it.code == SystemNoticeCode.ContextCompaction },
            )
            assertTrue(
                "压缩写前落盘失败，压缩标记被写成失败提示：$notices",
                notices.none { it.detail?.contains(persistenceFailure) == true },
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * 长会话的压缩回归：需要压缩的会话正是正文远超 16 KiB 分块阈值的会话，
     * 而压缩前的落盘与聊天落盘走同一条路径、写同一份数据，长文本不应让落盘失败。
     */
    @Test
    fun compactionRunPassesWriteAheadPersistenceForLargeConversation() {
        val body = buildString {
            repeat(1_500) { append("第 ").append(it).append(" 行：用于撑大持久化载荷的正文，远超分块阈值。\n") }
        }
        val history = (0 until 8).map { index ->
            AgentModelClient.ConversationMessage(
                role = if (index % 2 == 0) "user" else "assistant",
                content = body,
                messageId = "large-$index",
            )
        }
        val messages = (0 until 8).map { index ->
            if (index % 2 == 0) {
                UserMessageUi(id = "large-$index", content = body)
            } else {
                AgentMessageUi(id = "large-$index", content = body, isStreaming = false)
            }
        } + ToolActivityMessageUi(
            id = "large-tool",
            toolName = "open_and_exec",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "读取大日志",
            command = "cat big.log",
            resultSummary = "ok=true",
            detail = body,
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-large",
                conversationsById = mapOf(
                    "conv-large" to AgentChatHomeUiState(
                        messages = messages,
                        history = history,
                        journal = history,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-large" to "长会话"),
                updatedAt = mapOf("conv-large" to 1L),
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)
            awaitInitialConversationLoad(state)
            assertEquals("conv-large", state.conversationPaneState.selectedConversationId)
            assertTrue("长会话未加载出历史", state.homeState.history.isNotEmpty())

            state.compactCurrentContext()
            idleUntil { !state.homeState.isStreaming }
            idleUntil { !state.hasPendingPersistence() }

            val notices = state.homeState.messages.filterIsInstance<SystemNoticeMessageUi>()
            val persistenceFailure = context.getString(R.string.conversation_persistence_failed)
            assertTrue(
                "压缩未走到终态标记：$notices",
                notices.any { it.code == SystemNoticeCode.ContextCompaction },
            )
            assertTrue(
                "长会话压缩写前落盘失败：$notices",
                notices.none { it.detail?.contains(persistenceFailure) == true },
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * 落盘重试只针对"库被别的进程持写锁"这一类瞬时失败；
     * 损坏、缺表、已关闭、OOM 都必须照旧直接报失败，不能被重试掩盖。
     */
    @Test
    fun transientDatabaseContentionOnlyMatchesLockAndBusy() {
        assertTrue(
            SQLiteDatabaseLockedException("database is locked (code 5 SQLITE_BUSY)")
                .isTransientDatabaseContention()
        )
        assertTrue(
            SQLiteException("database table is locked: conversation_messages")
                .isTransientDatabaseContention()
        )
        assertTrue(
            SQLiteException("wrapped", SQLiteDatabaseLockedException("database is locked"))
                .isTransientDatabaseContention()
        )
        assertFalse(SQLiteException("no such table: conversations").isTransientDatabaseContention())
        assertFalse(
            IllegalStateException("attempt to re-open an already-closed object")
                .isTransientDatabaseContention()
        )
        assertFalse(OutOfMemoryError().isTransientDatabaseContention())
    }

    /** 推进主 Looper 直到条件成立；Robolectric 的主 Looper 不会自动执行。 */
    private fun idleUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadlineMillis = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadlineMillis) { "等待条件成立超时" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}

/** 读分块用的上限：远大于用例构造的任何正文所需的分块数。 */
private const val CHUNK_READ_LIMIT = 10_000
