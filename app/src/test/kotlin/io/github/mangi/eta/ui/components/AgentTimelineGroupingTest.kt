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
 * 时间线分组：连续思考收束为「思考块」；工具调用留在主流逐条显示，
 * 正文/用户消息/系统通知同样独立成条目（对齐 VS Code reasoning/items 分离）。
 */
class AgentTimelineGroupingTest {
    @Test
    fun thinkingCollapsesIntoBlocksWhileToolsStayInMainStream() {
        val entries = listOf(
            ThinkingMessageUi("t1", "a", isStreaming = false),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Success, "echo hi"),
            ToolActivityMessageUi("x2", "read_file", ToolActivityStatusUi.Success, "/tmp/a"),
            ThinkingMessageUi("t2", "b", isStreaming = false),
            AgentMessageUi("m1", "answer"),
        ).toTimelineEntries()

        assertEquals(5, entries.size)
        assertEquals(listOf("t1"), (entries[0] as AgentTimelineEntry.ThinkingBlock).messages.map { it.id })
        assertTrue(entries[1] is AgentTimelineEntry.Message)
        assertEquals("x1", entries[1].key)
        assertTrue(entries[2] is AgentTimelineEntry.Message)
        assertEquals("x2", entries[2].key)
        assertEquals(listOf("t2"), (entries[3] as AgentTimelineEntry.ThinkingBlock).messages.map { it.id })
    }

    @Test
    fun userMessageAndNoticesBreakTheThinkingBlock() {
        val entries = listOf(
            ThinkingMessageUi("t1", "a", isStreaming = false),
            SystemNoticeMessageUi("n1", SystemNoticeCode.ContextCompaction),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Running, "ls"),
            UserMessageUi("u1", "next"),
        ).toTimelineEntries()

        assertEquals(4, entries.size)
        assertEquals(listOf("t1"), (entries[0] as AgentTimelineEntry.ThinkingBlock).messages.map { it.id })
        assertTrue(entries[1] is AgentTimelineEntry.Message)
        assertTrue(entries[2] is AgentTimelineEntry.Message)
        assertEquals("x1", entries[2].key)
        assertTrue(entries[3] is AgentTimelineEntry.Message)
    }

    @Test
    fun toolOnlyRunStaysInMainStream() {
        val entries = listOf(
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Running, "ls"),
            ToolActivityMessageUi("x2", "shell", ToolActivityStatusUi.Success, "ls"),
        ).toTimelineEntries()

        assertEquals(2, entries.size)
        assertEquals(listOf("x1", "x2"), entries.map { it.key })
        assertTrue(entries.all { it is AgentTimelineEntry.Message })
    }

    @Test
    fun completedTurnCollapsesWorkProcessBeforeFinalAnswer() {
        val entries = listOf(
            UserMessageUi("u1", "hi"),
            ThinkingMessageUi("t1", "a", isStreaming = false),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Success, "ls"),
            AgentMessageUi("m1", "answer"),
        ).toTimelineEntries()

        val ranges = entries.completedTurnRanges(isStreaming = false)
        assertEquals(1, ranges.size)
        assertEquals(2, ranges.first().stepCount)

        val collapsed = entries.withCompletedTurnCollapse(ranges, emptySet())
        assertEquals(3, collapsed.size)
        assertEquals("u1", collapsed[0].key)
        assertTrue(collapsed[1] is AgentTimelineEntry.CompletedSteps)
        assertEquals("m1", collapsed[2].key)

        val expanded = entries.withCompletedTurnCollapse(ranges, setOf(ranges.first().key))
        // 展开时保留摘要行，区间内容跟在它后面：u1 / 摘要 / 思考块 / 工具行 / m1。
        assertEquals(5, expanded.size)
        assertTrue(expanded[1] is AgentTimelineEntry.CompletedSteps)
        assertTrue(expanded[2] is AgentTimelineEntry.ThinkingBlock)
        assertTrue(expanded[3] is AgentTimelineEntry.Message)
    }

    @Test
    fun streamingLastTurnAndSingleStepTurnsAreNotCollapsed() {
        val streaming = listOf(
            UserMessageUi("u1", "hi"),
            ThinkingMessageUi("t1", "a", isStreaming = true),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Running, "ls"),
        ).toTimelineEntries()
        assertTrue(streaming.completedTurnRanges(isStreaming = true).isEmpty())

        val singleStep = listOf(
            UserMessageUi("u1", "hi"),
            ThinkingMessageUi("t1", "a", isStreaming = false),
            AgentMessageUi("m1", "answer"),
        ).toTimelineEntries()
        assertTrue(singleStep.completedTurnRanges(isStreaming = false).isEmpty())
    }

    @Test
    fun truncatedFragmentsAndTurnsWithNoticesAreNotCollapsed() {
        // 浮窗窗口可能从回合中段截断：不以用户消息开头的片段不折叠（步数会少算）。
        val windowedFragment = listOf(
            ThinkingMessageUi("t1", "a", isStreaming = false),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Success, "ls"),
            AgentMessageUi("m1", "answer"),
        ).toTimelineEntries()
        assertTrue(windowedFragment.completedTurnRanges(isStreaming = false).isEmpty())

        // 工作过程中夹系统通知（0 步）时整轮不折叠，保证通知不会被收走。
        val noticeInsideTurn = listOf(
            UserMessageUi("u1", "hi"),
            ThinkingMessageUi("t1", "a", isStreaming = false),
            SystemNoticeMessageUi("n1", SystemNoticeCode.Stopped),
            ToolActivityMessageUi("x1", "shell", ToolActivityStatusUi.Success, "ls"),
            AgentMessageUi("m1", "answer"),
        ).toTimelineEntries()
        assertTrue(noticeInsideTurn.completedTurnRanges(isStreaming = false).isEmpty())
    }
}
