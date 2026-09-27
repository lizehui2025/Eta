package io.github.mangi.eta.agent.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Provider JSON 与 Eta 稳定会话 DTO 之间的转换；脱敏不改变普通文本与工具批次。 */
internal object AgentConversationCodec {

    private const val IMAGE_OMITTED_TEXT = "[图片观察已在当前回合使用，未写入持久会话]"
    private const val SENSITIVE_TOOL_OMITTED_TEXT =
        "[敏感工具参数与原始结果仅供当前回合使用，未写入持久会话]"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeTranscriptForStorage(messages: List<AgentModelClient.ConversationMessage>): String =
        json.encodeToString(messages.map(::sanitizeMessage))

    fun encodeConversationCheckpoint(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeTranscriptForStorage(messages)

    /**
     * 按条产出 transcript 的 JSON 分片，拼接结果与 [encodeTranscriptForStorage] 逐字节相同。
     *
     * 存在的意义是内存：长会话整份序列化是一次几十 MB 的堆分配，逐条产出后
     * 任一刻只持有一条消息的 JSON，配合 [io.github.mangi.eta.data.db.ChunkedTextDao.storeTextPieces]
     * 就能边序列化边写分块。
     */
    fun transcriptPieces(
        messages: List<AgentModelClient.ConversationMessage>,
    ): Sequence<String> = sequence {
        yield("[")
        messages.forEachIndexed { index, message ->
            if (index > 0) yield(",")
            yield(json.encodeToString(sanitizeMessage(message)))
        }
        yield("]")
    }

    fun decodeTranscript(raw: String?): List<AgentModelClient.ConversationMessage> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            json.decodeFromString<List<AgentModelClient.ConversationMessage>>(raw)
        }

    fun toJsonObject(message: AgentModelClient.ConversationMessage): JSONObject =
        JSONObject()
            .put("role", message.role)
            .also { target ->
                if (message.messageId.isNotBlank()) target.put("_eta_message_id", message.messageId)
                if (message.contextSummary) target.put("_eta_context_summary", true)
                if (message.compactedUserTurns > 0) target.put("_eta_compacted_users", message.compactedUserTurns)
                if (message.summaryThroughUserTurn > 0) target.put("_eta_summary_through_user", message.summaryThroughUserTurn)
                when {
                    message.contentJson.isNotBlank() ->
                        target.put("content", JSONTokener(message.contentJson).nextValue())
                    else -> target.put("content", message.content)
                }
                if (message.toolCallId.isNotBlank()) {
                    target.put("tool_call_id", message.toolCallId)
                }
                if (message.reasoningContent.isNotBlank()) {
                    target.put("reasoning_content", message.reasoningContent)
                }
                if (message.toolCallsJson.isNotBlank()) {
                    target.put("tool_calls", JSONTokener(message.toolCallsJson).nextValue())
                }
                // output items 随消息持久化，跨 run 复用前缀缓存与推理链。
                if (message.responsesOutputItemsJson.isNotBlank()) {
                    runCatching { JSONArray(message.responsesOutputItemsJson) }
                        .getOrNull()
                        ?.let { items -> target.put(ResponsesEphemeralState.OUTPUT_ITEMS_KEY, items) }
                }
            }

    fun fromJsonObject(message: JSONObject): AgentModelClient.ConversationMessage {
        val contentValue = message.opt("content")
        return AgentModelClient.ConversationMessage(
            messageId = message.optString("_eta_message_id"),
            role = message.optString("role"),
            contextSummary = message.optBoolean("_eta_context_summary"),
            compactedUserTurns = message.optInt("_eta_compacted_users"),
            summaryThroughUserTurn = message.optInt("_eta_summary_through_user"),
            content = (contentValue as? String).orEmpty(),
            contentJson = if (
                contentValue == null ||
                contentValue == JSONObject.NULL ||
                contentValue is String
            ) {
                ""
            } else {
                contentValue.toString()
            },
            toolCallId = message.optString("tool_call_id"),
            reasoningContent = message.optString("reasoning_content"),
            toolCallsJson = message.optJSONArray("tool_calls")?.toString().orEmpty(),
            responsesOutputItemsJson = ResponsesEphemeralState.persistedItemsJson(message),
        )
    }

    fun userTextMessage(text: String): JSONObject =
        JSONObject()
            .put("role", "user")
            .put("content", text)

    fun userMessage(
        text: String,
        images: List<AgentModelClient.ModelImage>,
    ): JSONObject {
        if (images.isEmpty()) return userTextMessage(text)

        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text)
        )
        images.forEach { image ->
            require(image.reference.isProviderImageReference()) {
                "模型图片尚未在 Agent Runtime 中物化"
            }
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image.reference))
            )
        }
        return JSONObject()
            .put("role", "user")
            .put("content", content)
    }

    private fun String.isProviderImageReference(): Boolean =
        startsWith("https://", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true) ||
            startsWith("data:image/", ignoreCase = true)

    fun assistantHistoryMessage(
        source: JSONObject,
        toolCalls: List<AgentModelClient.ToolCall>,
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", source.opt("content") ?: JSONObject.NULL)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEach { call -> array.put(call.toHistoryJson()) }
                        },
                    )
                }
                if (source.has("reasoning_content") && !source.isNull("reasoning_content")) {
                    message.put("reasoning_content", source.optString("reasoning_content"))
                }
                ResponsesEphemeralState.copyOutputItems(source, message)
            }

    fun toolResultMessage(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", toolCall.id)
            .put("content", result.content)

    fun parseToolCalls(message: JSONObject): List<AgentModelClient.ToolCall> {
        val rawCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        val usedIds = mutableSetOf<String>()
        return buildList {
            for (index in 0 until rawCalls.length()) {
                val rawCall = rawCalls.optJSONObject(index) ?: JSONObject()
                val function = rawCall.optJSONObject("function")
                val arguments = function?.opt("arguments")
                val candidateId = rawCall.optString("id").ifBlank { "tool_call_$index" }
                val stableId = if (usedIds.add(candidateId)) {
                    candidateId
                } else {
                    generateSequence(1) { it + 1 }
                        .map { suffix -> "${candidateId}_$suffix" }
                        .first(usedIds::add)
                }
                add(
                    AgentModelClient.ToolCall(
                        id = stableId,
                        name = function?.optString("name")?.trim().orEmpty().ifBlank { "unknown_tool" },
                        argumentsJson = when (arguments) {
                            is JSONObject -> arguments.toString()
                            is String -> arguments.ifBlank { "{}" }
                            else -> "{}"
                        },
                    )
                )
            }
        }
    }

    private fun AgentModelClient.ToolCall.toHistoryJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", argumentsJson),
            )

    /**
     * 只取 tool_calls 的 id，不解析/序列化 arguments。
     * [parseToolCalls] 会把每个 arguments（write_file 可达 512KB）转成字符串，
     * 切分点扫描、敏感检测这类只关心 id/名字的场景应当用本方法，避免为每条调用付一次大拷贝。
     */
    fun toolCallIds(message: JSONObject): List<String> {
        val rawCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        return buildList {
            for (index in 0 until rawCalls.length()) {
                val call = rawCalls.optJSONObject(index) ?: continue
                add(call.optString("id").ifBlank { "tool_call_$index" })
            }
        }
    }

    /** 取 tool_calls 中命中敏感策略的 id，不解析 arguments。 */
    fun sensitiveCallIds(message: JSONObject): List<String> {
        val rawCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        return buildList {
            for (index in 0 until rawCalls.length()) {
                val call = rawCalls.optJSONObject(index) ?: continue
                val name = call.optJSONObject("function")?.optString("name").orEmpty()
                if (AgentSensitiveToolPolicy.isSensitive(name)) {
                    add(call.optString("id").ifBlank { "tool_call_$index" })
                }
            }
        }
    }

    fun transcript(
        messages: JSONArray,
        startIndex: Int,
        sensitiveToolCallIds: Set<String> = emptySet(),
    ): List<AgentModelClient.ConversationMessage> {
        val redactedIds = sensitiveToolCallIds.toMutableSet()
        for (index in startIndex until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            redactedIds += sensitiveCallIds(message)
        }
        return buildList {
            for (index in startIndex until messages.length()) {
                messages.optJSONObject(index)
                    ?.let { redactSensitiveToolData(it, redactedIds) }
                    ?.let(::fromJsonObject)
                    ?.let(::sanitizeMessage)
                    ?.let(::add)
            }
        }
    }

    private fun redactSensitiveToolData(
        source: JSONObject,
        sensitiveToolCallIds: Set<String>,
    ): JSONObject {
        if (sensitiveToolCallIds.isEmpty()) return source
        // 快路径：绝大多数消息与敏感 id 无关，直接复用原对象。
        // 旧实现只要集合非空就对每条消息 `JSONObject(source.toString())` 深拷贝，
        // 大文本历史下准备阶段被放大 N 倍，是“数分钟”级卡顿的主因之一。
        val role = source.optString("role")
        val toolCallId = if (role == "tool") source.optString("tool_call_id") else ""
        val calls = source.optJSONArray("tool_calls")
        var needsCopy = toolCallId in sensitiveToolCallIds
        if (!needsCopy && calls != null) {
            for (index in 0 until calls.length()) {
                if (calls.optJSONObject(index)?.optString("id") in sensitiveToolCallIds) {
                    needsCopy = true
                    break
                }
            }
        }
        if (!needsCopy) return source
        // 命中才浅拷贝：只重建受影响的字段，大正文直接丢弃不再拷贝。
        val copy = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // 敏感消息的 output items 一并剥离，不随脱敏拷贝继续传递。
            if (key == "content" || key == "tool_calls" || key == ResponsesEphemeralState.OUTPUT_ITEMS_KEY) {
                continue
            }
            copy.put(key, source.opt(key))
        }
        if (role == "tool" && toolCallId in sensitiveToolCallIds) {
            copy.put("content", SENSITIVE_TOOL_OMITTED_TEXT)
        } else {
            // 非 tool 角色或非敏感 tool 结果：复用原 content 引用，不序列化。
            if (source.has("content")) copy.put("content", source.opt("content"))
        }
        if (calls == null) return copy
        val newCalls = JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            if (call.optString("id") !in sensitiveToolCallIds) {
                // 未命中直接复用引用，不做 toString 深拷贝；调用方只读不改。
                newCalls.put(call)
                continue
            }
            val newCall = JSONObject()
            val callKeys = call.keys()
            while (callKeys.hasNext()) {
                val ck = callKeys.next()
                if (ck != "function") newCall.put(ck, call.opt(ck))
            }
            val fn = call.optJSONObject("function")
            val newFn = JSONObject()
            if (fn != null) {
                val fnKeys = fn.keys()
                while (fnKeys.hasNext()) {
                    val fk = fnKeys.next()
                    if (fk != "arguments") newFn.put(fk, fn.opt(fk))
                }
                newFn.put("name", fn.optString("name"))
            }
            newFn.put("arguments", JSONObject().put("redacted", true).toString())
            newCall.put("function", newFn)
            newCalls.put(newCall)
        }
        copy.put("tool_calls", newCalls)
        return copy
    }

    fun durableMessage(message: JSONObject): AgentModelClient.ConversationMessage =
        sanitizeMessage(fromJsonObject(message))

    fun encodedSize(messages: List<AgentModelClient.ConversationMessage>): Int =
        json.encodeToString(messages).length

    private fun sanitizeMessage(
        message: AgentModelClient.ConversationMessage,
    ): AgentModelClient.ConversationMessage =
        message.copy(
            role = message.role,
            content = message.content,
            contentJson = sanitizeContentJson(message.contentJson),
            toolCallId = message.toolCallId,
            reasoningContent = message.reasoningContent,
            toolCallsJson = message.toolCallsJson,
        )

    private fun sanitizeContentJson(raw: String): String {
        if (raw.isBlank()) return ""
        val content = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val sanitized = when (content) {
            is JSONArray -> sanitizeContentArray(content)
            is JSONObject -> sanitizeContentObject(content)
            else -> return ""
        }
        return sanitized.toString()
    }

    private fun sanitizeContentArray(source: JSONArray): JSONArray {
        val target = JSONArray()
        var omittedImage = false
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optString("type") in setOf("image_url", "input_image", "image") || item.has("source")) {
                omittedImage = true
                continue
            }
            target.put(sanitizeContentObject(item))
        }
        if (omittedImage) {
            target.put(JSONObject().put("type", "text").put("text", IMAGE_OMITTED_TEXT))
        }
        return target
    }

    private fun sanitizeContentObject(source: JSONObject): JSONObject {
        // 无图片字段时直接复用，避免每 part 一次 toString 深拷贝。
        if (!source.has("image_url") && !source.has("source")) {
            if (!source.has("text")) return source
            // text 字段本身是小字符串，原地规范化需要拷贝；但只拷贝 keys，不序列化正文。
            val copy = JSONObject()
            val keys = source.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                copy.put(k, source.opt(k))
            }
            copy.put("text", source.optString("text"))
            return copy
        }
        return JSONObject(source.toString()).also { target ->
            target.remove("image_url")
            target.remove("source")
            if (target.has("text")) {
                target.put("text", target.optString("text"))
            }
        }
    }

}
