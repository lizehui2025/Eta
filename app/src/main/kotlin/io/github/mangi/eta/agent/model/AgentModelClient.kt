package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelRequestOptions
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val traceFormatter = AgentTraceFormatter()

    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val effort = if (thinkingAllowed) {
                    runtime.effectiveReasoningEffort
                } else {
                    ReasoningEffort.OFF
                }
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                    deviceSensitiveReadTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                    deviceSensitiveActionTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            reasoningEffort = ReasoningEffort.fromLegacy(
                Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
            ),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        additionalTools: JSONArray = JSONArray(),
        /**
         * Capability capture entry point. The default covers only the offline "nothing available"
         * case; production must pass `AgentToolCapabilities.capture(context)` (see
         * AgentRuntimeRunExecutor), otherwise the model is offered tools it cannot execute.
         */
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        sessionId: String = java.util.UUID.randomUUID().toString(),
        compactOnly: Boolean = false,
        operationId: String = sessionId,
        initialUserMessageId: String = "user-$operationId",
        initialSupplementIndex: Int = 0,
        roleplayContext: RoleplayRunContext? = null,
        /**
         * 交互模式快照：决定本次 run 是否保存持久记忆（编码模式不主动保存）。
         * 写入许可在执行期由工具层动态复查；这里只影响提示词与工具表。
         */
        agentMode: AgentMode = AgentMode.CHAT,
        rewriteReply: Boolean = false,
        assistantScreenContext: String = "",
        onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
        onTranscript: (List<ConversationMessage>) -> Unit = {},
        onEvent: (AgentEvent) -> Unit = {}
    ): ModelResponse.Text {
        config.validate()
        val initialCapabilities = capabilitiesProvider()
        // 角色会话语义上永远对现实记忆只读；编码模式不主动保存记忆。
        val memoryWritable = roleplayContext == null && agentMode.memoryWritable
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            images,
            history,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            roleplayContext = roleplayContext,
            memoryWritable = memoryWritable,
        )
        if (rewriteReply) {
            messages.put(messages.length() - 1, AgentConversationCodec.userTextMessage(
                "请只改写下面这条角色回复，保持已有事实与实际工具结果，以当前角色设定改善表达。" +
                    "这不是重新执行任务；不得调用任何工具、重读设备、更新记忆或编造缺失证据。只输出替代正文。\n" +
                    "<reply_to_rewrite>\n$prompt\n</reply_to_rewrite>",
            ))
        } else if (!compactOnly) {
            messages.getJSONObject(messages.length() - 1).put("_eta_message_id", initialUserMessageId)
            AssistantScreenContextProjection.attach(messages.getJSONObject(messages.length() - 1), assistantScreenContext)
        }
        if (compactOnly) messages.remove(messages.length() - 1)
        val transcript = JSONArray()
        // 旧 history 中的无效消息可能在组装时被跳过，系统边界不能由 history 条数倒推。
        val systemCount = AgentPromptBuilder.buildSystemMessages(
            config, skillContext, memoryContext, initialCapabilities.rootAvailable, roleplayContext,
        ).length()
        // 工具 schema 只随能力变化：缓存整份（含附加工具）并复用同一实例，避免每轮重建全部 schema，
        // 也让窗口预算按同一实例命中工具表估算缓存，而不是每轮把整份 schema 重新序列化一遍。
        val toolsByCapabilities = AgentToolSchemaCache { capabilities ->
            AgentToolCatalog.build(
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                deviceDirectTools = config.deviceDirectTools,
                deviceSensitiveReadTools = config.deviceSensitiveReadTools,
                deviceSensitiveActionTools = config.deviceSensitiveActionTools,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = memoryContext.enabled,
                memoryWritable = memoryWritable,
                capabilities = capabilities,
            ).also { tools ->
                for (index in 0 until additionalTools.length()) {
                    tools.put(additionalTools.opt(index))
                }
            }
        }
        fun toolsFor(capabilities: AgentToolCapabilities): JSONArray {
            if (rewriteReply) return JSONArray()
            return toolsByCapabilities.tools(capabilities)
        }
        val tools = toolsFor(initialCapabilities)
        onEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        var promptRootAvailable = initialCapabilities.rootAvailable
        val subagentExecutor: AgentSubagentExecutor? = if (rewriteReply || compactOnly) {
            null
        } else {
            runCatching {
                val sysSnapshot = AgentPromptBuilder.buildSystemMessages(
                    config, skillContext, memoryContext, initialCapabilities.rootAvailable, roleplayContext,
                    planGuidance = false,
                    memoryWritable = memoryWritable,
                )
                AgentSubagentExecutor(
                    config = config,
                    provider = provider,
                    parentRunController = runController,
                    parentOperationId = operationId,
                    parentTools = JSONArray(tools.toString()),
                    systemMessages = JSONArray(sysSnapshot.toString()),
                    baseToolExecutor = toolExecutor,
                    traceFormatter = traceFormatter,
                    onEvent = onEvent,
                    depth = 0,
                    parentMessagesProvider = { messages },
                    parentSystemCount = systemCount,
                )
            }.getOrNull()
        }
        val todoList = AgentTodoList()
        val askUserTool = AgentUserQuestionTool(controller = runController, onEvent = onEvent)
        val loop = AgentLoop(
            transcript = transcript,
            systemCount = systemCount,
            operationId = operationId,
            onContextSnapshot = if (rewriteReply) ({ _ -> }) else onContextSnapshot,
            onTranscript = onTranscript,
            sessionId = sessionId,
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = toolExecutor,
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = onEvent,
            purpose = if (rewriteReply) ProviderRequestPurpose.REPLY_REWRITE else ProviderRequestPurpose.CHAT,
            roleplayContext = roleplayContext,
            initialSupplementIndex = initialSupplementIndex,
            subagentHandler = subagentExecutor?.let { exec ->
                { round: Int, call: ToolCall ->
                    if (call.name == AgentSubagentPolicy.TOOL_NAME) exec.fanout(round, call) else null
                }
            },
            todoHandler = { round: Int, call: ToolCall -> todoList.write(round, call, onEvent) },
            askUserHandler = { round: Int, call: ToolCall ->
                if (call.name == AgentInteractionToolCatalog.TOOL_NAME) askUserTool.ask(round, call) else null
            },
            toolsForRound = {
                val capabilities = capabilitiesProvider()
                if (capabilities.rootAvailable != promptRootAvailable) {
                    val systemMessages = AgentPromptBuilder.buildSystemMessages(
                        config, skillContext, memoryContext, capabilities.rootAvailable, roleplayContext,
                        memoryWritable = memoryWritable,
                    )
                    for (index in 0 until systemMessages.length()) {
                        messages.put(index, systemMessages.getJSONObject(index))
                    }
                    promptRootAvailable = capabilities.rootAvailable
                }
                toolsFor(capabilities)
            },
        )
        val result = try {
            if (compactOnly) loop.compactOnly() else loop.run()
        } catch (throwable: Throwable) {
            throw AgentModelExecutionException(
                cause = throwable,
                contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.transcript(
                    transcript,
                    0,
                    loop.sensitiveToolCallIdsSnapshot(),
                )),
            )
        }
        return ModelResponse.Text(
            content = result.content,
            contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                transcript,
                0,
                result.sensitiveToolCallIds,
            ),
        )
    }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(apiKey.isNotBlank()) { "请先配置 API Key" }
        require(model.isNotBlank()) { "请先配置模型名" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "当前模型强制启用思考，不能选择 Off 或禁用思考权限" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("额外请求体 JSON 无效：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage>,
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(AgentConversationCodec.userMessage(text, images))

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        traceFormatter.summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        traceFormatter.summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        traceFormatter.summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val hostedWebSearchEnabled: Boolean = false,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList(),
        /**
         * 模型级 typed 采样参数（温度等）；仅主对话请求携带，压缩/改写等内部调用不继承。
         * 缺失（旧 JSON/Bundle 未含该键）时解析为 null，行为与未配置一致。
         */
        val requestOptions: ModelRequestOptions? = null,
        /**
         * 编码模式快照：编码模式下所有请求统一 temperature=0.1，用户自定义请求体（extraBody/customBody）
         * 仍可覆盖。默认 false，旧 JSON/Bundle 兼容。
         */
        val codingMode: Boolean = false
    ) {
        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val responsesOutputItemsJson: String = "",
        val contextSummary: Boolean = false,
        val compactedUserTurns: Int = 0,
        val summaryThroughUserTurn: Int = 0,
        val messageId: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    ) {
        @Volatile
        private var cachedArgs: Result<JSONObject>? = null

        /**
         * 工具参数的缓存解析：同一 ToolCall 在校验、执行、轨迹摘要中会被多次读取，
         * 缓存避免每层重复 JSONObject 解析。返回的 JSONObject 只读使用，不要修改。
         */
        fun parsedArgs(): Result<JSONObject> {
            cachedArgs?.let { return it }
            val parsed = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }
            cachedArgs = parsed
            return parsed
        }

        fun parsedArgsOrNull(): JSONObject? = parsedArgs().getOrNull()
    }

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * 敏感结果仍会供当前 Agent loop 使用，但工具参数与原始结果不会进入持久会话。
         * 最终 assistant 自己组织的答复不受此标记影响。
         */
        val sensitive: Boolean = false,
    )

    /** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
        /** 截图已具有可上传编码，跨进程物化时保留字节，不走附件转码。 */
        val preserveOriginal: Boolean = false,
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
            val contextSnapshot: AgentContextSnapshot? = null,
        ) : ModelResponse
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
    val contextSnapshot: AgentContextSnapshot? = null,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)
