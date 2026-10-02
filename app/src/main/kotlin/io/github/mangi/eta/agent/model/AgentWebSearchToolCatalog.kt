package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Read-only network search exposed to Ask and the other agent modes. */
internal object AgentWebSearchToolCatalog {
    const val TOOL_NAME = "web_search"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                TOOL_NAME,
                "只读联网搜索和网页正文读取。search 返回标题、URL、摘要和时间；read 读取指定 URL 的正文。不能登录、提交表单或修改网页。",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("operation", JSONObject().put("type", "string").put("enum", JSONArray().put("search").put("read")))
                        .put("query", JSONObject().put("type", "string").put("minLength", 2).put("maxLength", 500))
                        .put("url", JSONObject().put("type", "string").put("minLength", 8).put("maxLength", 4_000))
                        .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000))
                        .put("max_chars", JSONObject().put("type", "integer").put("minimum", 512).put("maximum", 50_000))
                        .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 20))
                )
                    .put("required", JSONArray().put("operation"))
                    .put("additionalProperties", false),
            ),
        )
    }
}
