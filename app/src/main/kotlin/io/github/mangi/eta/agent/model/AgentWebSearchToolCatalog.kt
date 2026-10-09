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
                "只读联网搜索与网页正文读取。search 走 HTTP 直接抓取结果列表（标题、URL、摘要），不驱动共享浏览器；" +
                    "read 优先用共享 Agent 浏览器打开页面取正文（返回体标注 shared_browser=true 与 current_url），" +
                    "浏览器不可用或导航失败则回退 HTTP 正文抽取；search 抽取失败时返回 parse_failed=true 的空结果与页面诊断，不回整页原文。" +
                    "不能登录、提交表单或修改网页。",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put(
                            "operation",
                            JSONObject().put("type", "string").put("enum", JSONArray().put("search").put("read"))
                                .put("description", "search=联网搜索 / read=读取指定 URL 正文（必填）"),
                        )
                        .put(
                            "query",
                            JSONObject().put("type", "string").put("minLength", 2).put("maxLength", 500)
                                .put("description", "search 的搜索词（2–500 字符）"),
                        )
                        .put(
                            "url",
                            JSONObject().put("type", "string").put("minLength", 8).put("maxLength", 4_000)
                                .put("description", "read 的 http/https 公共地址，不得带账号凭据"),
                        )
                        .put(
                            "offset",
                            JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1_000_000)
                                .put("description", "read 正文起始偏移（字符），默认 0；配合返回体的 has_more 翻页"),
                        )
                        .put(
                            "max_chars",
                            JSONObject().put("type", "integer").put("minimum", 512).put("maximum", 50_000)
                                .put("description", "search=结果条目总字符预算（默认 8000）；read=返回正文长度（默认 12000）"),
                        )
                        .put(
                            "limit",
                            JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 20)
                                .put("description", "search 返回条数（默认 8）"),
                        )
                )
                    .put("required", JSONArray().put("operation"))
                    .put("additionalProperties", false),
            ),
        )
    }
}
