package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** todo_write 的工具声明：整体替换的任务清单，条目数与文本长度受限。 */
internal object AgentPlanToolCatalog {
    fun appendTo(tools: JSONArray) {
        val todoItem = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "content",
                        JSONObject()
                            .put("type", "string")
                            .put("maxLength", AgentTodoList.MAX_CONTENT_CHARS)
                            .put("description", "任务内容，一行一句话。"),
                    )
                    .put(
                        "status",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "enum",
                                JSONArray().put("pending").put("in_progress").put("completed"),
                            ),
                    ),
            )
            .put("required", JSONArray().put("content").put("status"))
            .put("additionalProperties", false)
        val parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "todos",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", AgentTodoList.MAX_ITEMS)
                        .put("items", todoItem)
                        .put("description", "提交完整任务清单（整体替换）；开始某步标 in_progress，完成标 completed。"),
                ),
            )
            .put("required", JSONArray().put("todos"))
            .put("additionalProperties", false)
        tools.put(
            AgentToolSchema.function(
                name = AgentTodoList.TOOL_NAME,
                description = "维护当前任务的任务清单（Plan）：整体替换语义，每次提交完整列表。" +
                    "多步任务先建立清单，开始某步时标为 in_progress、完成后标为 completed，并随进度更新；" +
                    "简单任务不必调用。清单是工作辅助，不替代对用户的最终答复。",
                parameters = parameters,
            ),
        )
    }
}
