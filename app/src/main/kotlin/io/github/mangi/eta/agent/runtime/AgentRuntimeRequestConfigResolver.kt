package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient

internal object AgentRuntimeRequestConfigResolver {
    /**
     * 请求是否需要 Runtime 侧介入配置：
     * - eta_voice 入口请求内的配置不可用，需要整体替换为内部存储配置（既有语义，保持不变）；
     * - 其它入口（breeno/xiaoai/agent_ui 等）在 RemotePreferences 不再下发 apiKey 后，
     *   请求 config 的 apiKey 为空，需要 Runtime 用内部存储回填，避免入口功能回归。
     *
     * 判定为真时，调用方（AgentRuntimeService）会先取一次内部存储配置再传入
     * [applyRuntimeConfig]，回填始终使用请求发生时的最新配置。
     */
    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        isEtaVoice(request) || requiresApiKeyBackfill(request)

    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        // 非 eta_voice 入口只回填缺失的 apiKey，provider/baseUrl/model/systemPrompt/
        // reasoning/thinkingEnabledOverride 等其它字段一律保持请求原值。
        if (!isEtaVoice(request)) return applyApiKeyBackfill(request, config)
        val handoff = request.handoff ?: return request.copy(config = config)
        val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
        return request.copy(
            config = config,
            handoff = archivePayload?.let { payload ->
                handoff.copy(
                    payload = payload.copy(
                        thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                        reasoningEffort = config.effectiveReasoningEffort,
                    ).toJson(),
                )
            } ?: handoff,
        )
    }

    /**
     * 仅回填 apiKey：请求已有 key、或内部存储也没有可用 key 时原样返回请求，
     * 由下游模型调用前的配置校验报「请先配置 API Key」。
     */
    fun applyApiKeyBackfill(
        request: AgentRuntimeWire.RunRequest,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (request.config.apiKey.isNotBlank()) return request
        val apiKey = config.apiKey.trim()
        if (apiKey.isBlank()) return request
        return request.copy(config = request.config.copy(apiKey = apiKey))
    }

    private fun isEtaVoice(request: AgentRuntimeWire.RunRequest): Boolean =
        request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE

    /** 非 eta_voice 入口在请求 config 缺少 apiKey 时，需要 Runtime 从内部存储回填。 */
    private fun requiresApiKeyBackfill(request: AgentRuntimeWire.RunRequest): Boolean =
        !isEtaVoice(request) && request.config.apiKey.isBlank()
}
