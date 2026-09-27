package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话保存判定：未变化的会话必须被跳过，且任何会让库里内容变化的状态都必须触发写入。
 * 判定错误的方向很关键——漏写会丢内容，多写只是白干，因此这里两侧都覆盖。
 */
class AgentConversationPersistenceTest {
    private val history = listOf(
        AgentModelClient.ConversationMessage("user", "问题"),
        AgentModelClient.ConversationMessage("assistant", "回答"),
    )

    private fun state(
        messages: List<AgentChatMessageUi> = listOf(UserMessageUi("m1", "问题"), AgentMessageUi("m2", "回答")),
        history: List<AgentModelClient.ConversationMessage> = this.history,
        journal: List<AgentModelClient.ConversationMessage> = emptyList(),
        input: String = "",
        isStreaming: Boolean = false,
        isCompacting: Boolean = false,
        reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
        appliedRuntimeRunIds: List<String> = emptyList(),
        pendingImages: List<PendingImageUi> = emptyList(),
    ) = AgentChatUiState(
        messages = messages,
        history = history,
        journal = journal,
        input = input,
        isStreaming = isStreaming,
        isCompacting = isCompacting,
        thinkingEnabled = reasoningEffort.enablesReasoning,
        reasoningEffort = reasoningEffort,
        appliedRuntimeRunIds = appliedRuntimeRunIds,
        pendingImages = pendingImages,
    )

    private fun fingerprint(
        state: AgentChatUiState,
        title: String = "会话",
        createdAt: Long = 1_000L,
        updatedAt: Long = 2_000L,
        runsJson: String = state.appliedRuntimeRunIds.joinToString(prefix = "[", postfix = "]"),
        roleplayJson: String = "",
        revisionsJson: String = "",
    ) = AgentConversationPersistence.fingerprint(
        state = state,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        appliedRuntimeRunIdsJson = runsJson,
        roleplayJson = roleplayJson,
        revisionsJson = revisionsJson,
    )

    @Test
    fun unchangedConversationKeepsSameFingerprint() {
        assertEquals(fingerprint(state()), fingerprint(state()))
        val saved = AgentConversationPersistence.Saved(7L, 1L, 2L, 2L)
        assertFalse(AgentConversationPersistence.shouldWrite(7L, saved, 2L))
    }

    @Test
    fun draftAndTransientUiStateDoNotTriggerWrites() {
        val base = state()
        val typed = state(
            input = "正在输入的内容",
            isStreaming = true,
            isCompacting = true,
            pendingImages = listOf(PendingImageUi("img", "content://x", "data:image/png;base64,AA", "image/png")),
        )
        assertEquals(fingerprint(base), fingerprint(typed))
    }

    @Test
    fun persistedContentChangesAreDetected() {
        val base = fingerprint(state())
        assertNotEquals(base, fingerprint(state(messages = listOf(UserMessageUi("m1", "改过的问题")))))
        assertNotEquals(base, fingerprint(state(messages = listOf(UserMessageUi("m1", "问题"), AgentMessageUi("m2", "回答"), UserMessageUi("m3", "新消息")))))
        assertNotEquals(base, fingerprint(state(history = history + AgentModelClient.ConversationMessage("assistant", "新历史"))))
        assertNotEquals(base, fingerprint(state(journal = history + AgentModelClient.ConversationMessage("user", "补记"))))
        assertNotEquals(base, fingerprint(state(reasoningEffort = ReasoningEffort.HIGH)))
        assertNotEquals(base, fingerprint(state(appliedRuntimeRunIds = listOf("run-1"))))
        assertNotEquals(base, fingerprint(state(), title = "改了标题"))
        assertNotEquals(base, fingerprint(state(), updatedAt = 2_001L))
        assertNotEquals(base, fingerprint(state(), runsJson = "[\"run-1\"]"))
        assertNotEquals(base, fingerprint(state(), roleplayJson = "{\"characterId\":\"c\"}"))
        assertNotEquals(base, fingerprint(state(), revisionsJson = "{\"links\":{}}"))
    }

    @Test
    fun emptyJournalFallsBackToHistoryLikeTheWriterDoes() {
        // store 写库时用 journal.ifEmpty { history }，判定必须用同一口径，否则会漏写。
        assertEquals(fingerprint(state()), fingerprint(state(history = history, journal = emptyList())))
        assertNotEquals(
            fingerprint(state()),
            fingerprint(state(history = emptyList(), journal = history)),
        )
    }

    @Test
    fun timestampResolutionStaysStableWhenReportedValueIsMissing() {
        val now = 5_000L
        val reported = 30L
        val previous = AgentConversationPersistence.Saved(1L, 10L, 20L, 20L)
        assertEquals(99L, AgentConversationPersistence.createdAt(99L, previous, reported, now))
        assertEquals(10L, AgentConversationPersistence.createdAt(null, previous, reported, now))
        // 库与上次落盘都不知道创建时间时，用调用方上报的时间戳（会话自己的时间），
        // 最后才退回当次写盘时刻。
        assertEquals(30L, AgentConversationPersistence.createdAt(null, null, reported, now))
        assertEquals(now, AgentConversationPersistence.createdAt(null, null, null, now))
        assertEquals(99L, AgentConversationPersistence.updatedAt(99L, previous, now))
        assertEquals(20L, AgentConversationPersistence.updatedAt(null, previous, now))
        assertEquals(now, AgentConversationPersistence.updatedAt(null, null, now))
    }

    @Test
    fun writeIsForcedWhenDatabaseRowWasReplacedByAnotherWriter() {
        val saved = AgentConversationPersistence.Saved(fingerprint = 42L, createdAt = 1L, updatedAt = 2L, storedUpdatedAt = 2L)
        assertTrue("库中还没有该会话时必须写入", AgentConversationPersistence.shouldWrite(42L, saved, null))
        assertTrue("没有上次落盘记录时必须写入", AgentConversationPersistence.shouldWrite(42L, null, 2L))
        assertTrue("内容变了必须写入", AgentConversationPersistence.shouldWrite(43L, saved, 2L))
        assertTrue(
            "库里该行的 updated_at 不是上次写的值（例如备份恢复整库覆盖）时必须写入",
            AgentConversationPersistence.shouldWrite(42L, saved, 777L),
        )
        assertFalse(AgentConversationPersistence.shouldWrite(42L, saved, 2L))
    }

    /**
     * 正文写入判定的唯一目的：把"只改时间戳"的保存与"正文真的变了"的保存区分开。
     * 前者（手动压缩、切换会话）必须跳过，否则长会话每次都要把整份正文序列化 + 分块，
     * 这是"无法保存对话（OutOfMemoryError）"的来源。
     */
    @Test
    fun contentWriteIsSkippedOnlyWhenStoredContentStillMatches() {
        val saved = AgentConversationPersistence.Saved(
            fingerprint = 42L, createdAt = 1L, updatedAt = 2L, storedUpdatedAt = 2L, contentSignature = 7L,
        )
        assertTrue(
            "库中还没有该会话时正文必须写入",
            AgentConversationPersistence.shouldWriteContent(7L, null, null),
        )
        assertTrue(
            "没有上次落盘记录时必须写入正文",
            AgentConversationPersistence.shouldWriteContent(7L, null, 2L),
        )
        assertTrue(
            "库里该行被别的写入方整份改写过时必须重写正文",
            AgentConversationPersistence.shouldWriteContent(7L, saved, 777L),
        )
        assertTrue(
            "正文变化时必须重写",
            AgentConversationPersistence.shouldWriteContent(8L, saved, 2L),
        )
        assertFalse(
            "正文未变（只推进了时间戳）时应跳过整份重写",
            AgentConversationPersistence.shouldWriteContent(7L, saved, 2L),
        )
    }

    @Test
    fun contentSignatureTracksOnlyTranscriptPayload() {
        val base = AgentConversationPersistence.contentSignature(state())
        assertEquals("流式/压缩标志不落盘，不得影响正文签名", base, AgentConversationPersistence.contentSignature(state(isStreaming = true)))
        assertEquals("输入框草稿不落盘，不得影响正文签名", base, AgentConversationPersistence.contentSignature(state(input = "草稿")))
        assertNotEquals("消息变化必须影响正文签名", base, AgentConversationPersistence.contentSignature(state(messages = listOf())))
        assertNotEquals(
            "历史变化必须影响正文签名",
            base,
            AgentConversationPersistence.contentSignature(state(history = history.drop(1))),
        )
    }
}
