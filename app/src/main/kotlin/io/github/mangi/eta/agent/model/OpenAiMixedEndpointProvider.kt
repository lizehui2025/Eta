package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode

/**
 * Adaptive facade for the OpenAI-compatible family: merges the `chat_completions` and `responses`
 * paths into a single provider.
 *
 * The happy path costs nothing extra — one delegation using the protocol chosen from observed
 * result, user intent or the stored hint, byte-for-byte identical to calling the concrete provider
 * directly. Only when a failure states "this address is not this endpoint" (404/405/501, or an SSE
 * stream shaped for the other protocol) does it retry the other endpoint **once** and record the
 * outcome in [ProviderEndpointFallback].
 *
 * The cross-protocol retry deliberately bypasses the model retry layer: [AgentModelRetry]'s budget
 * covers transient failures of the *same* request, while switching protocol changes the *target*.
 * Mixing them would burn three back-off attempts on an endpoint already known to be missing, so a
 * protocol mismatch is marked non-retryable (see AgentModelFailure.endpointProtocolMismatch).
 *
 * Cancellation is unchanged: a cancellation raised by either attempt propagates immediately and is
 * never treated as a reason to keep going.
 */
internal object OpenAiMixedEndpointProvider : AgentProviderClient {
    override val id: String = "openai_compatible"

    /**
     * `ProviderCapabilities` is not consumed by any runtime logic (only declarations and test
     * fakes), and the effective endpoint is only known per request, so this declares the minimal
     * set both protocols share rather than pretending to be one of them.
     */
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        endpoint = EndpointKind.CHAT_COMPLETIONS,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = false,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val configuredMode = request.config.openAiEndpointMode
        val baseUrl = request.config.baseUrl
        // Protocol is no longer user-selectable: which one is used is decided at runtime from what
        // the endpoint actually speaks. The only protocol preference the user can still express is
        // provider-hosted web search, which only Responses can serve — enabling it therefore means
        // "prefer Responses".
        val webSearchRequested = request.config.hostedWebSearchEnabled
        val declaredPreference =
            ProviderEndpointFallback.declaredPreference(configuredMode, webSearchRequested)
        val firstMode =
            ProviderEndpointFallback.preferredMode(baseUrl, configuredMode, webSearchRequested)

        val firstFailure: Throwable = try {
            val response = attempt(firstMode, request, runController, onEvent)
            rememberSuccess(baseUrl, firstMode, declaredPreference, webSearchRequested)
            return response
        } catch (failure: Throwable) {
            // Cancellation is not "endpoint unavailable": let it propagate first, so pressing stop
            // never causes a request to the other endpoint.
            runController.throwIfCancelled()
            if (!ProviderEndpointFallback.isSwitchable(failure)) throw failure
            failure
        }

        // Alternative protocol: at most one more attempt per run; there is no loop by construction.
        val secondMode = OpenAiEndpointMode.alternative(firstMode)
        ProviderEndpointFallback.logSwitch(baseUrl, firstMode, secondMode, firstFailure)
        try {
            val response = attempt(secondMode, request, runController, onEvent)
            rememberSuccess(baseUrl, secondMode, declaredPreference, webSearchRequested)
            ProviderEndpointFallback.logSwitched(baseUrl, declaredPreference, secondMode)
            return response
        } catch (failure: Throwable) {
            runController.throwIfCancelled()
            throw ProviderEndpointFallback.bothEndpointsFailed(
                baseUrl = baseUrl,
                firstMode = firstMode,
                firstFailure = firstFailure,
                secondFailure = failure,
            )
        }
    }

    /** Shared tail of a successful attempt: record the outcome, and expose an unserviceable web-search switch. */
    private fun rememberSuccess(
        baseUrl: String,
        mode: String,
        declaredPreference: String,
        webSearchRequested: Boolean,
    ) {
        ProviderEndpointFallback.rememberWorkingMode(baseUrl, mode, declaredPreference)
        if (webSearchRequested && OpenAiEndpointMode.normalize(mode) != OpenAiEndpointMode.RESPONSES) {
            ProviderEndpointFallback.warnWebSearchUnavailable(baseUrl, mode)
        }
    }

    /**
     * Delegates to the concrete protocol implementation, rewriting the config's endpoint mode to
     * the mode being attempted: the providers' existing `require` self-checks therefore still hold
     * and request building takes the matching branch, with no changes needed on their side.
     */
    private fun attempt(
        mode: String,
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val delegate = ProviderClientFactory.openAiEndpointClient(mode)
        val delegatedRequest = if (request.config.openAiEndpointMode == mode) {
            request
        } else {
            request.copy(config = request.config.copy(openAiEndpointMode = mode))
        }
        return delegate.complete(delegatedRequest, runController, onEvent)
    }
}
