package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSubagentToolCatalog {
    fun appendTo(tools: JSONArray) {
        val taskProps = JSONObject()
            .put("label", JSONObject().put("type", "string").put("maxLength", 64))
            .put("prompt", JSONObject().put("type", "string").put("maxLength", 4000))
        val taskSchema = JSONObject()
            .put("type", "object")
            .put("properties", taskProps)
            .put("required", JSONArray().put("prompt"))
            .put("additionalProperties", false)
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "tasks",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 4)
                            .put("items", taskSchema),
                    )
                    .put(
                        "max_rounds",
                        JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 8),
                    )
                    .put(
                        "timeout_ms",
                        JSONObject().put("type", "integer").put("minimum", 10000).put("maximum", 180000),
                    )
                    .put(
                        "allowed_tools",
                        JSONObject()
                            .put("type", "array")
                            .put("maxItems", 32)
                            .put("items", JSONObject().put("type", "string")),
                    ),
            )
            .put("required", JSONArray().put("tasks"))
            .put("additionalProperties", false)
        tools.put(
            AgentToolSchema.function(
                name = AgentSubagentPolicy.TOOL_NAME,
                description = "并行派生最多4个只读子代理并发搜集信息。子代理工具受限且禁GUI/浏览器/写操作，禁再派生。适用于多源并行搜集，一次调用内部并行汇总返回。",
                parameters = params,
            ),
        )
    }
}
