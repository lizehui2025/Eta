package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 上下文窗口内容分类统计：把“窗口里到底是什么”拆成对话 / 工具调用 / 思考链 /
 * 代码数据 / 系统提示 / 图片 / 工具 schema 七类，每类沿用
 * [AgentContextBudget.textTokens] 同一启发式口径估算 token，便于和压缩判据对账。
 *
 * - 对话：user 文本与 assistant 回复正文。
 * - 工具调用：assistant 的 tool_calls 参数，以及非文件类工具的 tool 结果。
 * - 思考链：assistant 的 reasoning_content（是否进窗口取决于服务商是否回传；
 *   回传即计入，不回传即为 0，如实反映）。
 * - 代码数据：读文件 / 写文件 / 编辑 / 代码搜索 / 列目录 / 共享文件等
 *   文件类工具的 tool 结果正文。读文件时抓到的文件内容落在这里，可直接看到
 *   它们占了窗口多少。
 * - 图片沿用预算口径按 4096 token/张计入。
 */
internal data class AgentContextBreakdown(
    val dialogueTokens: Int = 0,
    val toolCallTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val codeDataTokens: Int = 0,
    val systemTokens: Int = 0,
    val imageTokens: Int = 0,
    val schemaTokens: Int = 0,
) {
    val totalTokens: Int
        get() = dialogueTokens + toolCallTokens + thinkingTokens +
            codeDataTokens + systemTokens + imageTokens + schemaTokens

    /** 单行摘要，供日志与用量提示展示；只列非零项。 */
    fun summaryLine(): String =
        listOf(
            "对话" to dialogueTokens,
            "工具" to toolCallTokens,
            "思考" to thinkingTokens,
            "代码" to codeDataTokens,
            "系统" to systemTokens,
            "图片" to imageTokens,
            "工具表" to schemaTokens,
        ).filter { (_, tokens) -> tokens > 0 }
            .joinToString(" · ") { (label, tokens) -> "$label $tokens" }
            .ifBlank { "空窗口" }
}

internal object AgentContextBreakdownCounter {
    /** tool 结果正文计入“代码数据”的文件类工具名。 */
    val CODE_DATA_TOOLS: Set<String> = setOf(
        "read_file",
        "write_file",
        "edit_file",
        "search_code",
        "list_directory",
        "search_files",
        "search_downloads",
    )

    fun breakdown(messages: JSONArray, tools: JSONArray = JSONArray()): AgentContextBreakdown {
        var dialogue = 0
        var toolCalls = 0
        var thinking = 0
        var codeData = 0
        var system = 0
        var images = 0
        val schema = AgentContextBudget.textTokens(tools.toString())

        // tool_call_id -> 工具名：tool 结果按发起方工具名归类到“工具”或“代码”。
        val toolNameById = mutableMapOf<String, String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            message.optJSONArray("tool_calls")?.let { calls ->
                for (callIndex in 0 until calls.length()) {
                    val call = calls.optJSONObject(callIndex) ?: continue
                    val id = call.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = call.optJSONObject("function")?.optString("name").orEmpty()
                    if (name.isNotBlank()) toolNameById[id] = name
                }
            }
        }

        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system", "developer" -> system += messageTokens(message)
                "user" -> {
                    val (textTokens, imageCount) = userContentTokens(message)
                    dialogue += textTokens
                    images += imageCount * IMAGE_TOKENS
                }
                "assistant" -> {
                    dialogue += assistantTextTokens(message)
                    thinking += AgentContextBudget.textTokens(message.optString("reasoning_content"))
                    toolCalls += toolCallsTokens(message)
                }
                "tool" -> {
                    val content = message.optString("content")
                    val tokens = AgentContextBudget.textTokens(content)
                    val toolName = toolNameById[message.optString("tool_call_id")].orEmpty()
                    if (toolName in CODE_DATA_TOOLS) codeData += tokens else toolCalls += tokens
                }
            }
        }
        return AgentContextBreakdown(
            dialogueTokens = dialogue,
            toolCallTokens = toolCalls,
            thinkingTokens = thinking,
            codeDataTokens = codeData,
            systemTokens = system,
            imageTokens = images,
            schemaTokens = schema,
        )
    }

    /** 持久化会话（ConversationMessage 列表）同样是窗口内容，直接复用同一口径。 */
    fun breakdown(history: List<AgentModelClient.ConversationMessage>): AgentContextBreakdown {
        val messages = JSONArray()
        history.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        return breakdown(messages)
    }

    private const val IMAGE_TOKENS = 4096

    private fun messageTokens(message: JSONObject): Int =
        AgentContextBudget.textTokens(message.optString("content"))

    private fun userContentTokens(message: JSONObject): Pair<Int, Int> {
        val content = message.opt("content")
        if (content !is JSONArray) return AgentContextBudget.textTokens(content?.toString().orEmpty()) to 0
        var text = 0
        var imageCount = 0
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "image_url", "input_image", "image" -> imageCount++
                else -> text += AgentContextBudget.textTokens(part.optString("text"))
            }
        }
        // 数组信封本身的括号与键名开销，与 rawEstimate 按整串计入的思路对齐。
        text += AgentContextBudget.textTokens("""{"role":"user"}""")
        return text to imageCount
    }

    private fun assistantTextTokens(message: JSONObject): Int {
        val content = message.opt("content")
        if (content is JSONArray) {
            var text = 0
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                text += AgentContextBudget.textTokens(part.optString("text"))
            }
            return text
        }
        val text = (content as? String).orEmpty()
        return AgentContextBudget.textTokens(text)
    }

    private fun toolCallsTokens(message: JSONObject): Int {
        val calls = message.optJSONArray("tool_calls") ?: return 0
        // 参数原文就是下一次请求里实际发送的形态，直接按整串估算。
        return AgentContextBudget.textTokens(calls.toString())
    }
}
