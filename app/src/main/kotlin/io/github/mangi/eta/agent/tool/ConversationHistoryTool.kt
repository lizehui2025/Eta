package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ResponsesEphemeralState
import org.json.JSONArray
import org.json.JSONObject

/** loader 由 Runtime 绑定当前会话；分页只限制返回内容，不修改持久历史。返回前剔除推理字段，对外是脱敏视图。 */
internal class ConversationHistoryTool(
    private val loader: () -> List<AgentModelClient.ConversationMessage>,
) : AgentModelClient.ToolExecutor {
    private var readSnapshot: List<AgentModelClient.ConversationMessage>? = null

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = JSONObject(toolCall.argumentsJson)
        val query = args.optString("query")
        require(query.length <= 500)
        var index = args.optInt("message_index", 0)
        var offset = args.optInt("offset", 0)
        var remaining = args.optInt("max_chars", 8000)
        require(index >= 0 && offset >= 0 && remaining in 256..8000)
        // 续读固定同一份历史，不能追着本工具新产生的日志无限读取。
        if (readSnapshot == null || (index == 0 && offset == 0)) readSnapshot = loader()
        val history = checkNotNull(readSnapshot)
        require(index <= history.size)
        val entries = JSONArray()
        while (index < history.size && remaining > 0 && entries.length() < 20) {
            val message = history[index]
            // output items 属于协议内部状态，reasoning_content 是模型的内部思考：
            // 都不通过历史读取工具暴露给模型；先过滤再切片，保证所有分页与 query 搜索都是脱敏视图。
            val text = AgentConversationCodec.toJsonObject(message)
                .apply {
                    remove(ResponsesEphemeralState.OUTPUT_ITEMS_KEY)
                    remove(REASONING_CONTENT_KEY)
                }
                .toString()
            if (query.isNotBlank() && !text.contains(query, ignoreCase = true)) {
                index++
                offset = 0
                continue
            }
            require(offset <= text.length)
            var end = minOf(text.length, offset + remaining)
            if (end < text.length && end > offset && text[end - 1].isHighSurrogate()) end--
            if (end == offset && offset < text.length) break
            entries.put(JSONObject().put("message_index", index).put("offset", offset)
                .put("text", text.substring(offset, end)).put("complete", end == text.length))
            remaining -= end - offset
            if (end < text.length) {
                offset = end
                break
            }
            index++
            offset = 0
        }
        return AgentModelClient.ToolResult(JSONObject()
            .put("ok", true).put("total_messages", history.size).put("entries", entries)
            .put("has_more", index < history.size)
            .put("next_message_index", index).put("next_offset", offset)
            // 告知调用方这是脱敏视图：推理字段已从返回文本中剔除。
            .put("reasoning_excluded", true).toString())
    }

    private companion object {
        /** 消息模型与 Codec 中的推理字段键名；历史读取工具不暴露内部思考。 */
        const val REASONING_CONTENT_KEY = "reasoning_content"
    }
}
