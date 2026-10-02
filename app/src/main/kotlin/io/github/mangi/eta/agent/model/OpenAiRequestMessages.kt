package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 将 Eta 会话消息投影为 OpenAI-compatible 请求所需的系统指令结构。 */
internal object OpenAiRequestMessages {
    /**
     * Completed user turns keep their answers and tool results, but not internal reasoning.
     * Current-turn reasoning is retained for providers that require it across tool calls.
     * [stripReasoning] also removes current-turn reasoning after a provider rejects the field.
     */
    fun forChatCompletions(
        source: JSONArray,
        stripReasoning: Boolean = false,
        cache: AgentRequestProjectionCache? = null,
    ): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
        val latestUserIndex = (source.length() - 1 downTo 0).firstOrNull { index ->
            val message = source.optJSONObject(index)
            message?.optString("role") == "user" && !message.optBoolean("_eta_observation", false)
        } ?: -1
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) {
                    val removeReasoning = stripReasoning || index < latestUserIndex
                    val projected = cache?.chatMessage(message, removeReasoning) {
                        projectChatMessage(it, removeReasoning)
                    } ?: projectChatMessage(message, removeReasoning)
                    messages.put(projected)
                }
            }
        }
    }

    internal fun projectChatMessage(message: JSONObject, stripReasoning: Boolean): JSONObject =
        shallowCopy(message).apply {
            remove("_eta_context_summary")
            remove("_eta_compacted_users")
            remove("_eta_summary_through_user")
            remove("_eta_observation")
            remove("_eta_message_id")
            remove("_eta_character_profile")
            remove(ResponsesEphemeralState.OUTPUT_ITEMS_KEY)
            // Transcript content stays intact; this only changes the outgoing request.
            if (stripReasoning) remove(HISTORY_REASONING_CONTENT_KEY)
        }

    /**
     * 顶层浅拷贝：新对象、新键表，值沿用原引用。
     *
     * 旧实现 `JSONObject(message.toString())` 会先把整条消息（工具结果可达数百 KB）重写成
     * 字符串再解析回来，每轮请求都要为整份历史付一次序列化 + 解析的深拷贝开销。这里只需要
     * “顶层独立”：投影结果随后被塞进请求体并立刻 `toString()` 成 HTTP body，投影的顶层
     * remove（`_eta_*` 剥离）不会回写会话；嵌套节点（content/tool_calls）调用方只读，
     * 不会被就地修改。key 遍历顺序即原对象插入顺序，因此序列化结果逐字节一致。
     */
    private fun shallowCopy(source: JSONObject): JSONObject {
        val copy = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // Java null 在旧实现里经 toString/解析会变成 JSONObject.NULL（序列化同样是 null），保持等价。
            copy.put(key, source.opt(key) ?: JSONObject.NULL)
        }
        return copy
    }

    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")

    /** 历史思考文本的字段名；剥离逻辑与 `_eta_*` 顶层键同一处、同一风格。 */
    private const val HISTORY_REASONING_CONTENT_KEY = "reasoning_content"
}
