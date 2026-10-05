package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(delayTransform = ::defaultRetryJitter),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onCompactionCompleted: () -> Unit = {},
    private val onTranscript: (AgentTranscriptPublisher.PublishResult) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
    private val subagentHandler: ((Int, AgentModelClient.ToolCall) -> AgentModelClient.ToolResult?)? = null,
    private val todoHandler: ((Int, AgentModelClient.ToolCall) -> AgentModelClient.ToolResult?)? = null,
    private val askUserHandler: ((Int, AgentModelClient.ToolCall) -> AgentModelClient.ToolResult?)? = null,
    private val toolApprovalHandler: ((Int, AgentModelClient.ToolCall) -> Boolean)? = null,
    private val toolBatchExecutor: AgentToolBatchExecutor = AgentToolBatchExecutor(),
    private val projectionCache: AgentRequestProjectionCache = AgentRequestProjectionCache(),
    persistentRecovery: Boolean = false,
) {
    private val noProgressGuard = AgentNoProgressGuard()
    private val effectiveModelRetry = if (persistentRecovery) {
        AgentModelRetry(maxRetries = Int.MAX_VALUE, delayTransform = ::defaultRetryJitter)
    } else modelRetry
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    // 只读工具并行执行时，事件回调与敏感 id 收集仍可能来自多个工具线程；
    // 这里串行化事件发布，并用同步集合保护收集结果。
    private val eventLock = Any()
    private val sensitiveToolCallIds = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private var pendingToolImageMessage: JSONObject? = null

    /** 思考链有界：超限丢弃最旧的一半，避免单轮发散吃掉内存。 */
    private fun appendReasoning(delta: String) {
        if (delta.isEmpty()) return
        accumulatedReasoning.append(delta)
        if (accumulatedReasoning.length > MAX_REASONING_CHARS) {
            val drop = accumulatedReasoning.length - MAX_REASONING_CHARS / 2
            accumulatedReasoning.delete(0, drop)
        }
    }
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        sensitiveIds = { sensitiveToolCallIds },
        onEvent = onEvent,
        onContextSnapshot = onContextSnapshot,
        transcriptSize = { transcript.length() },
        roleplay = roleplayContext != null,
        sessionId = sessionId,
        onCompactionCompleted = onCompactionCompleted,
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    /** transcript 只追加；发布只转换新增部分，避免每轮整份重转导致的平方增长。 */
    private val transcriptPublisher = AgentTranscriptPublisher { sensitiveToolCallIds }

    private fun publishTranscript() {
        val result = transcriptPublisher.publish(transcript)
        if (result.messages.isEmpty() && !result.fullRebuild) return
        onTranscript(result)
    }

    fun compactOnly(): Result {
        context.compact(tools, force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> =
        synchronized(sensitiveToolCallIds) { sensitiveToolCallIds.toSet() }

    private fun publishEvent(event: AgentEvent) {
        synchronized(eventLock) { onEvent(event) }
    }

    fun run(): Result {
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            val roundTools = if (purpose.allowsTools) toolsForRound?.invoke() ?: tools else JSONArray()
            toolCallValidator = AgentToolCallValidator(roundTools)
            publishTranscript()
            context.compact(roundTools)
            // 屏幕上下文只投影到 Provider 请求；无上下文时 project 原样返回入参，
            // 因此下面的消息级估算缓存仍然命中。
            var requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages, roundTools) ?: messages,
            )
            // 复用会话 budget 的消息级缓存：非角色态下 requestMessages 与 messages 同身份，直接命中，
            // 避免每轮把上百 KB 的 tool 结果重新 toString 一遍。旧实现用无缓存的静态 rawEstimate。
            var requestEstimate = context.budget.rawEstimateCached(requestMessages, roundTools)
            var roundInputTokens: Int? = null
            var roundCachedTokens: Int? = null
            var overflowAttempts = 0
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            var completedResponse: AgentModelRetry.Result? = null
            val completedRound = try {
                while (true) {
                    try {
                        val response = effectiveModelRetry.complete(
                            initialRound = round,
                            request = ProviderRequest(
                                config = config,
                                messages = requestMessages,
                                tools = roundTools,
                                sessionId = sessionId,
                                purpose = purpose,
                                projectionCache = projectionCache,
                            ),
                            provider = provider,
                            controller = runController,
                            onEvent = onEvent,
                            onProviderEvent = { attemptRound, providerEvent ->
                                if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                        providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                                    throw AgentModelFailure(
                                        "REPLY_REWRITE_TOOL_CALL", false, purpose.noToolsFailureMessage,
                                    )
                                }
                                if (providerEvent is ProviderEvent.Usage) {
                                roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                                roundCachedTokens = providerEvent.usage.cachedTokens ?: roundCachedTokens
                            }
                                if (providerEvent is ProviderEvent.BlockDelta &&
                                    providerEvent.kind == AssistantBlockKind.THINKING
                                ) {
                                    appendReasoning(providerEvent.delta)
                                }
                                providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                            },
                            discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                        )
                        completedResponse = response
                        break
                    } catch (failure: AgentModelFailure) {
                        if (failure.code != "CONTEXT_OVERFLOW" || !failure.recoveryAllowed ||
                            overflowAttempts >= AgentContextBudget.MAX_OVERFLOW_ATTEMPTS) throw failure
                        overflowAttempts++
                        accumulatedReasoning.setLength(reasoningLengthBeforeRound)
                        context.compact(roundTools, force = true)
                        requestMessages = AssistantScreenContextProjection.project(
                            roleplayContext?.projectMessages(messages, roundTools) ?: messages,
                        )
                        requestEstimate = context.budget.rawEstimateCached(requestMessages, roundTools)
                        roundInputTokens = null
                        roundCachedTokens = null
                        round++
                    }
                }
                checkNotNull(completedResponse)
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            // 缓存命中率用于低命中时提前压缩；cached 是总输入的子集（Anthropic 已在 provider 侧加总，OpenAI prompt_tokens 本身含 cached）。
            context.budget.observe(
                roundInputTokens?.let { AgentTokenUsage(inputTokens = it, cachedTokens = roundCachedTokens) },
                requestEstimate,
            )
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, purpose.noToolsFailureMessage)
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                appendReasoning(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            publishEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                val outcomes = if (providerResponse.stopReason == AssistantStopReason.TOOL_USE) {
                    toolBatchExecutor.execute(
                        items = toolCalls,
                        parallelSafe = { call ->
                            AgentToolRequirements.isParallelReadOnly(
                                call.name,
                                call.parsedArgsOrNull() ?: JSONObject(),
                            )
                        },
                        execute = { call -> executeTool(round, call) },
                    )
                } else {
                    toolCalls.map { call ->
                        if (providerResponse.stopReason == AssistantStopReason.OUTPUT_LIMIT) {
                            rejectedToolOutcome(
                                round, call, "TRUNCATED_TOOL_CALL",
                                "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                            )
                        } else {
                            rejectedToolOutcome(
                                round, call, "UNEXPECTED_TOOL_CALL",
                                "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                            )
                        }
                    }
                }
                outcomes.forEach { outcome ->
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                }
                // Publish the batch's tool results once: publishing per tool would refresh the UI
                // once per tool, and the intermediate state (only some tool results written)
                // disagrees with the calls already declared in the assistant message. Publishing
                // itself is incremental (see transcriptPublisher); this only fixes the batch boundary.
                publishTranscript()
                appendToolImages(round, outcomes)
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(roundTools, final = true)
            publishEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeTool(
        round: Int,
        originalCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        // 参数宽容化：规范化 JSON、剔除未识别字段、可无损转换的类型自动转换；
        // 调整说明随结果回给模型（必填字段仍由下面的校验层严格把关）。
        val sanitized = ToolCallArgumentSanitizer.sanitize(
            originalCall,
            toolCallValidator.schemaFor(originalCall.name),
        )
        val outcome = executeSanitizedTool(round, sanitized.call)
        if (sanitized.adjusted.isEmpty()) return outcome
        return ToolOutcome(outcome.call, withArgumentAdjustment(outcome.result, sanitized.adjusted))
    }

    private fun executeSanitizedTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        val guardDecision = noProgressGuard.before(toolCall)
        if (guardDecision.reject) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "NO_PROGRESS_LOOP",
                message = guardDecision.message,
                retryHint = AgentToolRetryHints.CHANGE_STRATEGY,
            )
        }
        val softHint = guardDecision.softHint
        // Unified schema validation must precede every execution dispatch. spawn_agents and
        // todo_write have their own business checks (write-range conflicts, list entry validity,
        // ...), but the JSON Schema actually sent this round is the single source of truth for the
        // model's call contract; validating after the dedicated handlers lets those two bypass it and
        // grow a second contract.
        val validation = toolCallValidator.validateDetailed(toolCall)
        if (validation.message.isNotBlank()) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validation.message,
                metadata = invalidToolArgumentMetadata(toolCall, validation),
                retryHint = AgentToolRetryHints.FIX_ARGUMENTS,
            )
        }
        if (toolApprovalHandler?.invoke(round, toolCall) == false) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_REVIEW_REJECTED",
                message = "用户或自动审核拒绝了本次工具调用；不要通过改名、拆分或换工具绕过该决定。",
                retryHint = AgentToolRetryHints.RETRY_OR_REPORT,
            )
        }
        if (toolCall.name == AgentSubagentPolicy.TOOL_NAME && subagentHandler != null) {
            publishEvent(
                AgentEvent.ToolStarted(
                    round = round,
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    argsPreview = traceFormatter.summarizeArguments(toolCall),
                    command = traceFormatter.displayCommand(toolCall),
                ),
            )
            val subResult = try {
                subagentHandler.invoke(round, toolCall)
            } catch (throwable: Throwable) {
                // 取消必须继续向上传递；其他异常收敛为工具错误，避免整轮莫名失败并留下悬空批次。
                runController.throwIfCancelled()
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "SUBAGENT_FANOUT_FAILED")
                        .put("message", throwable.message ?: throwable.javaClass.simpleName)
                        .toString(),
                )
            }
            if (subResult != null) {
                if (subResult.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                    sensitiveToolCallIds += toolCall.id
                }
                emitToolFinished(round, toolCall, subResult)
                return ToolOutcome(toolCall, subResult)
            }
        }
        if (toolCall.name == AgentTodoList.TOOL_NAME && todoHandler != null) {
            publishEvent(
                AgentEvent.ToolStarted(
                    round = round,
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    argsPreview = traceFormatter.summarizeArguments(toolCall),
                    command = traceFormatter.displayCommand(toolCall),
                ),
            )
            val todoResult = todoHandler.invoke(round, toolCall)
            if (todoResult != null) {
                if (todoResult.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                    sensitiveToolCallIds += toolCall.id
                }
                emitToolFinished(round, toolCall, todoResult)
                return ToolOutcome(toolCall, todoResult)
            }
        }
        if (toolCall.name == AgentInteractionToolCatalog.TOOL_NAME && askUserHandler != null) {
            publishEvent(
                AgentEvent.ToolStarted(
                    round = round,
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    argsPreview = traceFormatter.summarizeArguments(toolCall),
                    command = traceFormatter.displayCommand(toolCall),
                ),
            )
            val askResult = try {
                askUserHandler.invoke(round, toolCall)
            } catch (throwable: Throwable) {
                // Cancellation must keep propagating; every other failure becomes a tool error so the model learns
                // the question was never delivered instead of losing the whole round.
                runController.throwIfCancelled()
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "USER_QUESTION_FAILED")
                        .put("message", throwable.message ?: throwable.javaClass.simpleName)
                        .toString(),
                )
            }
            if (askResult != null) {
                if (askResult.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                    sensitiveToolCallIds += toolCall.id
                }
                emitToolFinished(round, toolCall, askResult)
                return ToolOutcome(toolCall, askResult)
            }
        }
        publishEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )

        val result = try {
            toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .put("retry_hint", AgentToolRetryHints.RETRY_OR_REPORT)
                    .put("retry_hint_text", AgentToolRetryHints.instruction("TOOL_ERROR"))
                    .toString(),
            )
        }
        val finalResult = if (softHint.isNotBlank()) {
            withRetryHint(
                result,
                AgentToolRetryHints.CHANGE_STRATEGY,
                softHint,
            )
        } else {
            result
        }
        if (finalResult.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        emitToolFinished(round, toolCall, finalResult)
        return ToolOutcome(toolCall, finalResult)
    }

    /** 只给成功的 tool result 追加软提示；失败结果已在 rejectedToolOutcome 里带上 hint。 */
    private fun withRetryHint(
        result: AgentModelClient.ToolResult,
        hint: String,
        hintText: String,
    ): AgentModelClient.ToolResult {
        val parsed = runCatching { JSONObject(result.content) }.getOrNull() ?: return result
        if (!parsed.optBoolean("ok", true)) return result
        if (parsed.has("retry_hint")) return result
        parsed.put("retry_hint", hint).put("retry_hint_text", hintText)
        return result.copy(content = parsed.toString())
    }

    /**
     * 参数宽容化的说明：被忽略/自动修正的字段必须让模型知道，
     * 否则它会继续按错误格式调用；内容不是 JSON 时退化为追加一行文本。
     */
    private fun withArgumentAdjustment(
        result: AgentModelClient.ToolResult,
        adjusted: List<String>,
    ): AgentModelClient.ToolResult {
        val message = "参数说明：以下内容被自动忽略或修正——" + adjusted.joinToString("；") +
            "。请按工具参数说明核对后继续。"
        val content = runCatching {
            JSONObject(result.content).put("argument_adjustment", message).toString()
        }.getOrElse { result.content + "\n" + message }
        return result.copy(content = content)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
        metadata: JSONObject = JSONObject(),
        retryHint: String? = null,
    ): ToolOutcome {
        publishEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .put("tool", toolCall.name)
                .put("retryable", code == "INVALID_TOOL_ARGUMENTS")
                .put("retry_hint", retryHint ?: AgentToolRetryHints.forCode(code))
                .put("retry_hint_text", AgentToolRetryHints.instruction(code))
                .also { payload ->
                    metadata.keys().forEach { key -> payload.put(key, metadata.opt(key)) }
                }
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    /**
     * 结构化校验元数据直接来自同一次校验结果（[validation]），不再对错误文案做正则解析，
     * 保证 message 与 metadata 同源且逐字段一致。
     */
    private fun invalidToolArgumentMetadata(
        toolCall: AgentModelClient.ToolCall,
        validation: ValidationOutcome,
    ): JSONObject {
        val args = toolCall.parsedArgsOrNull() ?: JSONObject()
        val operation = args.optString("action").ifBlank { args.optString("operation") }
        val metadata = JSONObject().put("operation", operation)
        if (validation.missing.isNotEmpty()) metadata.put("missing", org.json.JSONArray(validation.missing))
        if (validation.expected.isNotBlank()) metadata.put("expected", validation.expected)
        if (validation.received.isNotBlank()) metadata.put("received", validation.received)
        metadata.put("example", minimalToolExample(toolCall.name, operation, validation))
        return metadata
    }

    private fun minimalToolExample(
        name: String,
        operation: String,
        validation: ValidationOutcome,
    ): JSONObject {
        // operation 缺失时优先给 schema 推导的必填字段示例，避免产出空壳 operation。
        val fallback = validation.example.takeIf { it.length() > 0 }
            ?: toolCallValidator.minimalExample(name)
        if (operation.isBlank()) return fallback
        val matched = when (name) {
            "ui_action" -> JSONObject().put("action", operation).also { example ->
                when (operation) {
                    "swipe" -> example.put("x1", 100).put("y1", 500).put("x2", 100).put("y2", 200)
                    "key" -> example.put("button", "BACK")
                    "wait" -> example.put("timeout_ms", 3_000)
                    "open_system_panel" -> example.put("panel", "notification")
                    else -> example.put("x", 100).put("y", 100)
                }
            }
            "app_action" -> JSONObject().put("action", operation).also { example ->
                when (operation) {
                    "launch" -> example.put("app_name", "相机")
                    "open_uri" -> example.put("uri", "https://example.com")
                    else -> example.put("query", "应用名")
                }
            }
            "file_ops" -> JSONObject().put("operation", operation).also { example ->
                when (operation) {
                    "write" -> example.put("path", "/workspace/file.txt").put("content", "内容")
                    "edit" -> example.put("path", "/workspace/file.txt")
                        .put("old_string", "旧内容").put("new_string", "新内容")
                    "search" -> example.put("path", "/workspace").put("query", "关键词")
                    else -> example.put("path", "/workspace/file.txt")
                }
            }
            "read_image" -> JSONObject().put("path", "/workspace/image.png")
            else -> null
        }
        return if (matched == null || matched.length() == 0) fallback else matched
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        publishEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                detail = traceFormatter.summarizeDetail(toolCall.name, toolCall.argumentsJson, result),
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            publishEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

    companion object {
        /** 单轮思考链上限：超限丢弃最旧部分，只防内存无界，不截断正常结果。 */
        const val MAX_REASONING_CHARS = 200_000
    }
}
