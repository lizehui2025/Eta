package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.OpenAiEndpointMode

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient =
        when (config.providerType) {
            // The OpenAI-compatible family goes through the adaptive facade: for a given address,
            // Chat Completions and Responses are decided by what actually works rather than fixed by
            // configuration and failing as soon as it is chosen wrong.
            ProviderTypes.OPENAI_COMPATIBLE -> OpenAiMixedEndpointProvider
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }

    /** Concrete protocol implementations inside the OpenAI-compatible family; selected only by [OpenAiMixedEndpointProvider]. */
    fun openAiEndpointClient(endpointMode: String): AgentProviderClient =
        when (OpenAiEndpointMode.normalize(endpointMode)) {
            OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
            else -> OpenAiChatCompletionsProvider
        }
}
