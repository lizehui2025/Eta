package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 任务清单解析：输入为 todo_write 工具结果摘要（与 AgentTraceFormatter.summarizeTodoResult
 * 的输出格式一致）：首行“进度 X/Y”，其后每行“✓/◐/○ 条目”。
 */
class TodoCardTest {
    @Test
    fun parsesFormatterStyleDetail() {
        val todo = parseTodoListDetail("进度 2/4\n✓ 完成项\n◐ 进行中项\n○ 待办项")
        assertNotNull(todo)
        requireNotNull(todo)
        assertEquals(4, todo.total)
        assertEquals(2, todo.completed)
        assertEquals(
            listOf(
                TodoEntryUi("完成项", TodoEntryStatusUi.Completed),
                TodoEntryUi("进行中项", TodoEntryStatusUi.InProgress),
                TodoEntryUi("待办项", TodoEntryStatusUi.Pending),
            ),
            todo.entries,
        )
    }

    @Test
    fun ignoresTrailingLinesLikeTruncationNotice() {
        val todo = parseTodoListDetail("进度 1/2\n✓ a\n○ b\n…共 3 项")
        assertNotNull(todo)
        assertEquals(2, requireNotNull(todo).entries.size)
    }

    @Test
    fun acceptsListWithNoInProgressEntry() {
        val todo = parseTodoListDetail("进度 2/2\n✓ a\n✓ b")
        assertNotNull(todo)
        assertEquals(2, requireNotNull(todo).completed)
        assertEquals(TodoEntryStatusUi.Completed, todo.entries[1].status)
    }

    @Test
    fun malformedDetailsFallBackToNull() {
        assertNull(parseTodoListDetail(null))
        assertNull(parseTodoListDetail(""))
        assertNull(parseTodoListDetail("任务清单已更新"))
        assertNull(parseTodoListDetail("进度 0/0\n✓ a"))
        assertNull(parseTodoListDetail("进度 1/2"))
        assertNull(parseTodoListDetail("进度 1/2\n没有任何标记的行"))
    }

    @Test
    fun parsesRealFormatterOutputFromToolResult() {
        // 契约测试：解析器必须能解析 AgentTraceFormatter.summarizeResult 的真实输出
        // （即 UI 拿到的 resultSummary），格式变化会立刻在此失败。
        val content = JSONObject()
            .put("ok", true)
            .put("tool", "todo_write")
            .put("total", 3)
            .put("completed", 1)
            .put(
                "items",
                JSONArray()
                    .put(JSONObject().put("content", "第一步").put("status", "completed"))
                    .put(JSONObject().put("content", "第二步").put("status", "in_progress"))
                    .put(JSONObject().put("content", "第三步").put("status", "pending")),
            )
            .toString()
        val summary = AgentTraceFormatter().summarizeResult(
            "todo_write",
            AgentModelClient.ToolResult(content = content),
        )
        val todo = parseTodoListDetail(summary)
        assertNotNull("解析器必须能解析 formatter 的真实输出：$summary", todo)
        requireNotNull(todo)
        assertEquals(3, todo.total)
        assertEquals(1, todo.completed)
        assertEquals(TodoEntryStatusUi.Completed, todo.entries[0].status)
        assertEquals(TodoEntryStatusUi.InProgress, todo.entries[1].status)
        assertEquals(TodoEntryStatusUi.Pending, todo.entries[2].status)
    }
}
