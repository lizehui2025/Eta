package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作过程分组：连续 思考 + 工具调用 收束为同一个块，正文/用户消息/系统通知才中断。
 */
class AgentTimelineGroupingTest {
    @Test
    fun consecutiveThinkingAndToolsShareOneWorkProcessBlock() {
        val entries = listOf(
            ThinkingMessageUi("t1", "a", isStreaming = false),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Success, "echo hi"),
            ToolActivityMessageUi("x2", "read_file", ToolActivityStatusUi.Success, "/tmp/a"),
            ThinkingMessageUi("t2", "b", isStreaming = false),
            AgentMessageUi("m1", "answer"),
        ).toTimelineEntries()

        assertEquals(2, entries.size)
        val block = entries[0] as AgentTimelineEntry.ThinkingBlock
        assertEquals(listOf("t1", "x1", "x2", "t2"), block.messages.map { it.id })
        assertTrue(entries[1] is AgentTimelineEntry.Message)
    }

    @Test
    fun userMessageAndNoticesBreakTheBlock() {
        val entries = listOf(
            ThinkingMessageUi("t1", "a", isStreaming = false),
            SystemNoticeMessageUi("n1", SystemNoticeCode.ContextCompaction),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Running, "ls"),
            UserMessageUi("u1", "next"),
        ).toTimelineEntries()

        assertEquals(4, entries.size)
        assertTrue((entries[0] as AgentTimelineEntry.ThinkingBlock).messages.map { it.id } == listOf("t1"))
        assertTrue(entries[1] is AgentTimelineEntry.Message)
        assertTrue((entries[2] as AgentTimelineEntry.ThinkingBlock).messages.map { it.id } == listOf("x1"))
        assertTrue(entries[3] is AgentTimelineEntry.Message)
    }

    @Test
    fun toolOnlyRunStillFormsAWorkProcessBlock() {
        val entries = listOf(
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Running, "ls"),
            ToolActivityMessageUi("x2", "shell", ToolActivityStatusUi.Success, "ls"),
        ).toTimelineEntries()

        assertEquals(1, entries.size)
        val block = entries[0] as AgentTimelineEntry.ThinkingBlock
        assertEquals("thinking-x1", block.key)
        assertEquals(listOf("x1", "x2"), block.messages.map { it.id })
    }
}
