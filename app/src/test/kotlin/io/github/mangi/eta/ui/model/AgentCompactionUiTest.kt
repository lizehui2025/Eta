package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionUiTest {
    @Test
    fun manualActionRequiresIdleCompletedHistory() {
        val empty = AgentChatUiState(emptyList(), input = "草稿", isStreaming = false, thinkingEnabled = false)
        assertFalse(empty.canCompactContext)
        val ready = empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "已完成")))
        assertTrue(ready.canCompactContext)
        assertFalse(ready.copy(isStreaming = true).canCompactContext)
        assertFalse(empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "仅摘要", contextSummary = true))).canCompactContext)
    }

    @Test
    fun compactionEstimateReplacesPreviousUsageUntilNextModelResponse() {
        val response = AgentMessageUi("answer", "答案", usage = TokenUsageUi(contextTokens = 30_000))
        val compaction = SystemNoticeMessageUi("compact", SystemNoticeCode.ContextCompaction,
            "已压缩", contextTokens = 3_000)
        val estimate = latestContextUsage(listOf(response, compaction), null)
        assertEquals(3_000, estimate.contextTokens)
        assertTrue(estimate.estimated)
        val next = latestContextUsage(listOf(response, compaction, response.copy(id = "next", usage = TokenUsageUi(contextTokens = 4_000))), null)
        assertEquals(4_000, next.contextTokens)
        assertFalse(next.estimated)
    }

    @Test
    fun windowBreakdownSeparatesDialogueToolsThinkingAndCode() {
        val messages = listOf(
            UserMessageUi("u", "你好"),
            ThinkingMessageUi("t", "先想一下", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-file", toolName = "read_file",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "读文件", resultSummary = "文件正文".repeat(50),
            ),
            ToolActivityMessageUi(
                id = "tool-other", toolName = "get_setting",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "读设置", resultSummary = "ok",
            ),
            AgentMessageUi("a", "查到了"),
        )
        val usage = latestContextUsage(messages, null)
        val breakdown = checkNotNull(usage.breakdown)
        assertTrue(breakdown.dialogueTokens > 0)
        assertTrue(breakdown.thinkingTokens > 0)
        assertTrue(breakdown.toolCallTokens > 0)
        assertTrue(breakdown.codeDataTokens > breakdown.toolCallTokens)
        val line = formatContextBreakdown(breakdown, java.util.Locale.US)
        assertTrue(line.contains("对话"))
        assertTrue(line.contains("思考链"))
        assertTrue(line.contains("代码数据"))
    }
}
