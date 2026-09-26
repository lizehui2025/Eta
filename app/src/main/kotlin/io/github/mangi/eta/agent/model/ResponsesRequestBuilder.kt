package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelRequestOptions
import org.json.JSONArray
import org.json.JSONObject

internal object ResponsesRequestBuilder {
    fun build(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        promptCacheKey: String? = null,
        options: ModelRequestOptions? = null,
        codingMode: Boolean = false,
    ): JSONObject {
        val input = buildInput(messages)
        val responseTools = buildTools(tools, config.hostedWebSearchEnabled)
        val instructions = OpenAiRequestMessages.responsesInstructions(messages)
            .ifBlank { config.systemPrompt }
        val request = JSONObject()
        // typed 请求参数最先写入；extraBody/customBody 随后合并，用户原始覆盖优先。
        RequestOptionsApplicator.applyResponses(request, options, codingMode)
        mergeExtraBody(request, config.extraBodyJson)
        RequestBodyMerge.mergeCustomBody(request, config.customBody)

        // 这些字段决定协议正确性、隐私边界和 Eta 本轮行为，必须由运行时最终写入。
        request.put("model", config.model)
        request.put("instructions", instructions)
        request.put("input", input)
        request.put("stream", true)
        request.put("store", false)
        if (promptCacheKey != null && !request.has(ProviderPromptCache.PROMPT_CACHE_KEY_FIELD)) {
            request.put(ProviderPromptCache.PROMPT_CACHE_KEY_FIELD, promptCacheKey)
        }
        if (responseTools.length() > 0) {
            request.put("tools", responseTools)
            request.put("tool_choice", "auto")
        } else {
            request.remove("tools")
            request.remove("tool_choice")
        }
        request.remove("previous_response_id")
        request.remove("reasoning")
        ProviderReasoning.applyResponsesRequest(request, config)
        return request
    }

    private fun buildInput(messages: JSONArray): JSONArray = JSONArray().also { input ->
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            ResponsesEphemeralState.outputItems(message)?.let { items ->
                for (itemIndex in 0 until items.length()) input.put(copyJsonValue(items.opt(itemIndex)))
                continue
            }
            when (message.optString("role")) {
                "tool" -> input.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", message.optString("tool_call_id"))
                        .put("output", message.optString("content")),
                )
                "assistant" -> appendAssistantInput(input, message)
                "system", "developer" -> Unit
                else -> input.put(
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", convertUserContent(message.opt("content"))),
                )
            }
        }
    }

    private fun appendAssistantInput(input: JSONArray, message: JSONObject) {
        val content = message.optString("content")
        if (content.isNotBlank() && content != "null") {
            input.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("content", content),
            )
        }
        val calls = message.optJSONArray("tool_calls") ?: return
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function") ?: continue
            input.put(
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", call.optString("id").ifBlank { "tool_call_$index" })
                    .put("name", function.optString("name"))
                    .put("arguments", function.optString("arguments").ifBlank { "{}" }),
            )
        }
    }

    private fun convertUserContent(raw: Any?): Any {
        if (raw is String) return raw
        val source = raw as? JSONArray ?: return ""
        return JSONArray().also { content ->
            for (index in 0 until source.length()) {
                val part = source.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "text", "input_text" -> content.put(
                        JSONObject().put("type", "input_text").put("text", part.optString("text")),
                    )
                    "image_url", "input_image" -> {
                        val url = part.optJSONObject("image_url")?.optString("url")
                            ?: part.optString("image_url")
                        if (url.isNotBlank()) {
                            content.put(JSONObject().put("type", "input_image").put("image_url", url))
                        }
                    }
                }
            }
        }
    }

    private fun buildTools(tools: JSONArray, hostedWebSearchEnabled: Boolean): JSONArray =
        JSONArray().also { result ->
            for (index in 0 until tools.length()) {
                val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                result.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", function.optString("name"))
                        .put("description", function.optString("description"))
                        .put("parameters", copyJsonValue(function.opt("parameters") ?: JSONObject()))
                        .put("strict", false),
                )
            }
            if (hostedWebSearchEnabled) result.put(JSONObject().put("type", "web_search"))
        }

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extra = JSONObject(extraBodyJson)
        extra.keys().forEach { key -> request.put(key, extra.get(key)) }
    }

    /**
     * 顶层浅拷贝：新对象/新数组、新键表/新元素表，值沿用原引用。
     *
     * 旧实现用 `JSONObject(value.toString())` / `JSONArray(value.toString())` 序列化再解析，
     * 每次构建请求都要把 output items 或整份工具 parameters 重写一遍字符串。调用点只把结果
     * 放进请求体（随后立刻 `toString()` 成 HTTP body），请求体内没有对嵌套节点的就地
     * put/remove，因此只需保证容器独立：装配期的顶层写入不会回写会话里的 tool schema 与
     * items。key/元素遍历顺序即原容器顺序，序列化结果逐字节一致。
     */
    private fun copyJsonValue(value: Any?): Any = when (value) {
        is JSONObject -> shallowCopyObject(value)
        is JSONArray -> shallowCopyArray(value)
        null -> JSONObject.NULL
        else -> JSONObject.wrap(value) ?: JSONObject.NULL
    }

    private fun shallowCopyObject(source: JSONObject): JSONObject {
        val copy = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // Java null 经旧的 toString/解析路径会变成 JSONObject.NULL（序列化同为 null），保持等价。
            copy.put(key, source.opt(key) ?: JSONObject.NULL)
        }
        return copy
    }

    private fun shallowCopyArray(source: JSONArray): JSONArray {
        val copy = JSONArray()
        for (index in 0 until source.length()) copy.put(source.opt(index) ?: JSONObject.NULL)
        return copy
    }
}
