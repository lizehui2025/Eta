package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiChatCompletionsProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "openai_chat_completions"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.effectiveConfig
        require(config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
            "当前 Provider 未配置为 Chat Completions API"
        }
        val url = ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders, request.sessionId) }
            .build()

        var usePromptCacheKey = ProviderPromptCache.openAiPromptCacheKeyAllowed(config.baseUrl)
        // 推理链默认原样回传（缓存前缀才稳定）；端点在上次拒绝的 TTL 内直接全量剥离，跳过必败请求。
        var stripReasoning = ProviderPromptCache.isReasoningContentRejected(config.baseUrl)
        // 降级预算：单次完成最多再发一次请求。prompt_cache_key 与 reasoning_content 两类降级
        // 由同一次失败的一次判定选出（if/else 互斥），加上各自的“已经降过”守卫，
        // 因此最多 2 次 HTTP 尝试，不会叠加、也不会循环。
        var degradedRetriesLeft = 1
        while (true) {
            val promptCacheKey = if (usePromptCacheKey) {
                ProviderPromptCache.promptCacheKey(request.sessionId)
            } else {
                null
            }
            val requestJson = buildRequestJson(
                config,
                request.messages,
                request.effectiveTools,
                promptCacheKey,
                stripReasoning,
                request.projectionCache,
            ).apply {
                if (!request.purpose.allowsTools) {
                    remove("tools")
                    remove("tool_choice")
                }
            }
            val requestBody = AgentJsonRequestBody(requestJson, JSON_MEDIA_TYPE)

            val httpRequest = Request.Builder()
                .url(url)
                .headers(headers)
                .post(requestBody)
                .build()

            val call = AgentHttpClient.modelClientFor(request.purpose).newCall(httpRequest)
            val binding = runController.register { call.cancel() }
            var retryWithoutCacheKey = false
            var retryWithoutReasoningContent = false

            try {
                runController.throwIfCancelled()
                onEvent(ProviderEvent.RequestStarted)

                call.execute().use { response ->
                    val code = response.code
                    onEvent(ProviderEvent.ResponseHeaders(code))
                    runController.throwIfCancelled()

                    if (!response.isSuccessful) {
                        val errorBody = response.peekBody(16_384).string()
                        if (
                            degradedRetriesLeft > 0 &&
                            usePromptCacheKey &&
                            ProviderPromptCache.isUnsupportedFieldRejection(
                                code,
                                errorBody,
                                ProviderPromptCache.PROMPT_CACHE_KEY_FIELD
                            )
                        ) {
                            ProviderPromptCache.markOpenAiPromptCacheKeyRejected(config.baseUrl)
                            retryWithoutCacheKey = true
                        } else if (
                            degradedRetriesLeft > 0 &&
                            !stripReasoning &&
                            ProviderPromptCache.isReasoningContentRejection(code, errorBody)
                        ) {
                            // 服务端拒绝 reasoning_content（跨模型会话、老网关常见）：剥离后重试一次，
                            // 并把结论记入该地址（TTL 内后续请求直接剥离，避免每个完成都付一次必败请求）。
                            // 判定用字段专用版本 [ProviderPromptCache.isReasoningContentRejection]，
                            // 它覆盖通用“未知/不支持字段”措辞，并额外认中文等更宽的拒绝说法。
                            // `!stripReasoning` 保证剥离降级在单次完成内只发生一次。
                            ProviderPromptCache.markReasoningContentRejected(config.baseUrl)
                            retryWithoutReasoningContent = true
                        } else {
                            throw AgentModelFailure.http(code, errorBody)
                        }
                    } else {
                        val assistantMessage = readStreamingAssistantMessage(
                            response.body.byteStream(),
                            runController,
                            onEvent
                        )
                        onEvent(ProviderEvent.Completed(assistantMessage.optString("finish_reason").ifBlank { null }))
                        return ProviderResponse(assistantMessage)
                    }
                }
            } catch (throwable: Throwable) {
                runCatching { runController.throwIfCancelled() }
                    .getOrElse { interruption -> throw interruption }
                throw throwable
            } finally {
                binding.close()
            }

            if (retryWithoutCacheKey) {
                degradedRetriesLeft--
                usePromptCacheKey = false
                continue
            }
            if (retryWithoutReasoningContent) {
                degradedRetriesLeft--
                stripReasoning = true
                continue
            }
            error("模型接口请求未产生结果")
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        promptCacheKey: String?,
        stripReasoning: Boolean,
        projectionCache: AgentRequestProjectionCache,
    ): JSONObject {
        val sourceType = ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
        return JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put(
                "messages",
                OpenAiRequestMessages.forChatCompletions(
                    messages,
                    stripReasoning,
                    cache = projectionCache,
                ),
            )
            .put("tools", tools)
            .put("tool_choice", "auto")
            .also { request ->
                if (sourceType != ProviderSourceTypes.OPENROUTER) {
                    request.put("stream_options", JSONObject().put("include_usage", true))
                }
                if (promptCacheKey != null) {
                    request.put(ProviderPromptCache.PROMPT_CACHE_KEY_FIELD, promptCacheKey)
                }
                // typed 请求参数最先写入；extraBody/customBody 随后合并，用户原始覆盖优先。
                RequestOptionsApplicator.applyChatCompletions(
                    request,
                    config.requestOptions,
                    sourceType,
                    config.codingMode,
                )
                mergeExtraBody(request, config.extraBodyJson)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                ProviderReasoning.applyOpenAiCompatibleRequest(request, config)
            }
    }

    private fun readStreamingAssistantMessage(
        stream: java.io.InputStream?,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        if (stream == null) error("模型接口未返回响应流")
        val content = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: AgentTokenUsage? = null
        var sawStreamData = false
        // Whether a Chat Completions shaped chunk (non-empty choices) was ever seen. Tracked
        // separately from sawStreamData: data with no choices at all means the peer speaks another
        // protocol rather than the stream being cut short — both used to collapse into
        // "SSE stream did not end normally", leaving no way to tell them apart.
        var sawChoiceChunk = false
        var sawDone = false
        var finishReason: String? = null
        var nextContentIndex = 0
        var activeVisibleBlock: StreamingVisibleBlock? = null

        fun finishActiveVisibleBlock() {
            val block = activeVisibleBlock ?: return
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = block.kind,
                    index = block.contentIndex,
                    content = block.content.toString(),
                )
            )
            activeVisibleBlock = null
        }

        fun appendVisibleDelta(kind: AssistantBlockKind, delta: String) {
            if (delta.isEmpty()) return
            var block = activeVisibleBlock
            if (block?.kind != kind) {
                finishActiveVisibleBlock()
                block = StreamingVisibleBlock(
                    kind = kind,
                    contentIndex = nextContentIndex++,
                ).also { created ->
                    activeVisibleBlock = created
                    onEvent(ProviderEvent.BlockStart(kind, created.contentIndex))
                }
            }
            block.content.append(delta)
            onEvent(ProviderEvent.BlockDelta(kind, block.contentIndex, delta))
        }

        readProviderSse(stream, runController) { _, data ->
            sawStreamData = true
            val payload = data.trim()
            if (payload == "[DONE]") {
                sawDone = true
                return@readProviderSse false
            }
            val chunk = JSONObject(payload)
            throwStreamingErrorIfPresent(chunk)
            parseUsage(chunk)?.let { parsedUsage ->
                usage = parsedUsage
                onEvent(ProviderEvent.Usage(parsedUsage))
            }
            val choices = chunk.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return@readProviderSse true
            sawChoiceChunk = true
            val choice = choices.optJSONObject(0) ?: return@readProviderSse true
            val reason = choice.optString("finish_reason")
            if (reason.isNotBlank() && reason != "null") {
                finishReason = reason
            }
            if (reason == "error") {
                error("模型接口 SSE 以 error 结束")
            }
            val delta = choice.optJSONObject("delta") ?: JSONObject()
            val reasoningDelta = visibleReasoningDelta(delta)
            if (reasoningDelta.isNotEmpty()) {
                reasoningContent.append(reasoningDelta)
                appendVisibleDelta(AssistantBlockKind.THINKING, reasoningDelta)
            }
            if (delta.has("content") && !delta.isNull("content")) {
                val text = delta.optString("content")
                if (text.isNotEmpty()) {
                    content.append(text)
                    appendVisibleDelta(AssistantBlockKind.TEXT, text)
                }
            }
            val deltaToolCalls = delta.optJSONArray("tool_calls") ?: JSONArray()
            if (deltaToolCalls.length() > 0) finishActiveVisibleBlock()
            for (i in 0 until deltaToolCalls.length()) {
                val item = deltaToolCalls.optJSONObject(i) ?: continue
                val index = item.optInt("index", i)
                val call = toolCalls.getOrPut(index) {
                    StreamingToolCall(
                        index = index,
                        contentIndex = nextContentIndex++,
                    ).also { created ->
                        onEvent(
                            ProviderEvent.BlockStart(
                                kind = AssistantBlockKind.TOOL_CALL,
                                index = created.contentIndex,
                            )
                        )
                    }
                }
                if (item.has("id") && !item.isNull("id")) call.id = item.optString("id")
                if (item.has("type") && !item.isNull("type")) call.type = item.optString("type").ifBlank { "function" }
                val function = item.optJSONObject("function")
                val nameDelta = function?.takeIf { it.has("name") && !it.isNull("name") }?.optString("name").orEmpty()
                val argsDelta = function?.takeIf { it.has("arguments") && !it.isNull("arguments") }?.optString("arguments").orEmpty()
                if (nameDelta.isNotEmpty()) call.name.append(nameDelta)
                if (argsDelta.isNotEmpty()) call.arguments.append(argsDelta)
                if (argsDelta.isNotEmpty()) {
                    onEvent(
                        ProviderEvent.BlockDelta(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = call.contentIndex,
                            delta = argsDelta,
                        )
                    )
                }
            }
            if (finishReason != null) finishActiveVisibleBlock()
            true
        }

        if (!sawStreamData) throw AgentModelFailure.incompleteStream("模型接口未返回 SSE data chunk")
        // Data arrived but not a single choices array: this is a protocol shape mismatch, not
        // truncation. Retrying the same endpoint would fail identically, so it throws a non-retryable
        // dedicated code that lets the endpoint adaptation layer retry the other protocol once.
        if (!sawChoiceChunk) throw AgentModelFailure.endpointProtocolMismatch(
            "模型接口返回了 SSE 数据，但没有一条是 Chat Completions 格式（缺少 choices）：" +
                "该地址很可能使用 Responses API。",
        )
        if (!sawDone && finishReason == null) throw AgentModelFailure.incompleteStream("模型接口 SSE 流未正常结束")

        finishActiveVisibleBlock()
        toolCalls.values.sortedBy { it.contentIndex }.forEach { call ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = call.contentIndex,
                    blockId = call.id,
                    name = call.name.toString().ifBlank { null },
                    content = call.arguments.toString(),
                )
            )
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
            }
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.values.sortedBy { it.index }.forEachIndexed { position, call ->
                                array.put(call.toJson(position))
                            }
                        }
                    )
                }
            }
    }

    private data class StreamingToolCall(
        val index: Int,
        val contentIndex: Int,
        var id: String? = null,
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toJson(position: Int): JSONObject {
            val functionName = name.toString().trim()
            return JSONObject()
                .put("id", id ?: "tool_call_$position")
                .put("type", type.ifBlank { "function" })
                .put(
                    "function",
                    JSONObject()
                        .put("name", functionName)
                        .put("arguments", arguments.toString())
                )
        }
    }

    private data class StreamingVisibleBlock(
        val kind: AssistantBlockKind,
        val contentIndex: Int,
        val content: StringBuilder = StringBuilder(),
    )

    private fun visibleReasoningDelta(delta: JSONObject): String {
        // 同一分片的纯文本与结构化字段可重复携带相同思考，只消费一种表示。
        for (key in listOf("reasoning_content", "reasoning")) {
            (delta.opt(key) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val details = delta.optJSONArray("reasoning_details") ?: return ""
        val text = StringBuilder()
        val summary = StringBuilder()
        for (index in 0 until details.length()) {
            val detail = details.optJSONObject(index) ?: continue
            when (detail.optString("type")) {
                "reasoning.text" -> (detail.opt("text") as? String)?.let(text::append)
                "reasoning.summary" -> (detail.opt("summary") as? String)?.let(summary::append)
            }
        }
        // Raw text and its summary are alternative representations, not successive tokens.
        return text.toString().ifEmpty { summary.toString() }
    }

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extraBody = JSONObject(extraBodyJson)
        extraBody.keys().forEach { key ->
            request.put(key, extraBody.get(key))
        }
    }

    private fun throwStreamingErrorIfPresent(chunk: JSONObject) {
        val streamError = chunk.optJSONObject("error") ?: return
        val code = streamError.opt("code")
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val errorType = streamError.optJSONObject("metadata")
            ?.optString("error_type")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val context = listOfNotNull(
            code?.let { "code=$it" },
            errorType?.let { "type=$it" },
        ).joinToString(", ")
        val message = streamError.optString("message")
            .ifBlank { "未提供错误信息" }
            .compactError()
        throw AgentModelFailure.stream(
            streamError,
            "模型接口 SSE 返回错误${context.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}：$message",
        )
    }

    private fun parseUsage(chunk: JSONObject): AgentTokenUsage? {
        val usage = chunk.optJSONObject("usage") ?: return null
        return AgentTokenUsage(
            contextTokens = usage.firstInt("total_tokens"),
            inputTokens = usage.firstInt("prompt_tokens", "input_tokens"),
            outputTokens = usage.firstInt("completion_tokens", "output_tokens"),
            reasoningTokens = usage.firstNestedInt(
                "completion_tokens_details",
                "output_tokens_details",
                childKey = "reasoning_tokens"
            ),
            // 缓存命中字段兼容：OpenAI/网关 details、Moonshot 顶层 cached_tokens、Anthropic 中转、DeepSeek prompt_cache_hit_tokens
            cachedTokens = usage.firstNestedInt(
                "prompt_tokens_details",
                "input_tokens_details",
                childKey = "cached_tokens"
            ) ?: usage.firstInt("cached_tokens", "cache_read_input_tokens", "prompt_cache_hit_tokens")
        ).takeUnless { it.isEmpty }
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val raw = opt(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstNestedInt(
        vararg parentKeys: String,
        childKey: String
    ): Int? {
        for (parentKey in parentKeys) {
            val parent = optJSONObject(parentKey) ?: continue
            parent.firstInt(childKey)?.let { return it }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("total_tokens", it) }
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
