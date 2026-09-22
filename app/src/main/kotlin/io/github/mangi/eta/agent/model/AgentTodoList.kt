package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * todo_write：主代理的任务清单（Plan）。
 *
 * 整体替换语义：模型每次提交完整清单，Runtime 负责校验、计数并广播 [AgentEvent.TodoUpdated]。
 * 清单同时回填为工具结果 JSON（items/total/completed），供 UI 与后续轮次复用。
 */
internal class AgentTodoList {
    fun write(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        onEvent: (AgentEvent) -> Unit,
    ): AgentModelClient.ToolResult {
        if (toolCall.name != TOOL_NAME) {
            return errorResult("INVALID_ARGUMENT", "AgentTodoList 只处理 $TOOL_NAME 调用")
        }
        val args = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }.getOrElse {
            return errorResult("INVALID_ARGUMENT", "todo_write 参数不是 JSON object")
        }
        val todosJson = args.optJSONArray("todos")
            ?: return errorResult("INVALID_ARGUMENT", "缺少必填字段 todos")
        if (todosJson.length() < 1 || todosJson.length() > MAX_ITEMS) {
            return errorResult("INVALID_ARGUMENT", "todos 数量必须为 1-$MAX_ITEMS")
        }
        val items = JSONArray()
        var completed = 0
        var current = ""
        for (i in 0 until todosJson.length()) {
            val item = todosJson.optJSONObject(i)
                ?: return errorResult("INVALID_ARGUMENT", "todos[$i] 不是 object")
            val content = item.optString("content").trim()
            if (content.isBlank()) return errorResult("INVALID_ARGUMENT", "todos[$i].content 不能为空")
            if (content.length > MAX_CONTENT_CHARS) {
                return errorResult("INVALID_ARGUMENT", "todos[$i].content 超过 $MAX_CONTENT_CHARS 字符")
            }
            val status = item.optString("status").trim()
            if (status !in STATUSES) {
                return errorResult("INVALID_ARGUMENT", "todos[$i].status 仅支持 pending/in_progress/completed")
            }
            if (status == STATUS_COMPLETED) completed++
            if (status == STATUS_IN_PROGRESS && current.isEmpty()) current = content
            items.put(JSONObject().put("content", content).put("status", status))
        }
        val total = items.length()
        val content = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_NAME)
            .put("total", total)
            .put("completed", completed)
            .put("items", items)
            .toString()
        onEvent(
            AgentEvent.TodoUpdated(
                round = round,
                toolCallId = toolCall.id,
                total = total,
                completed = completed,
                current = current,
            ),
        )
        return AgentModelClient.ToolResult(content = content)
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    companion object {
        const val TOOL_NAME = "todo_write"
        const val MAX_ITEMS = 50
        const val MAX_CONTENT_CHARS = 500
        const val STATUS_PENDING = "pending"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"
        val STATUSES: Set<String> = setOf(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_COMPLETED)
    }
}
