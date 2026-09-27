package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision and memory semantics for endpoint adaptation.
 *
 * The "does it remember" half of `rememberWorkingMode` is deliberately not covered here — that is
 * end-to-end behaviour (see OpenAiMixedEndpointProviderTest). This file only covers which kinds of
 * failure are allowed to switch protocol, because that decides whether an error unrelated to the
 * endpoint kind (network, quota, arguments) is misread as "just try the other protocol".
 */
class ProviderEndpointFallbackTest {
    @Test
    fun onlyEndpointShapeFailuresAreSwitchable() {
        // Missing, method-not-allowed or unimplemented endpoint, plus data shaped for the other protocol.
        assertTrue(ProviderEndpointFallback.isSwitchable(AgentModelFailure("HTTP_404", false, "x")))
        assertTrue(ProviderEndpointFallback.isSwitchable(AgentModelFailure("HTTP_405", false, "x")))
        assertTrue(ProviderEndpointFallback.isSwitchable(AgentModelFailure("HTTP_501", false, "x")))
        assertTrue(
            ProviderEndpointFallback.isSwitchable(
                AgentModelFailure.endpointProtocolMismatch("x"),
            ),
        )
    }

    @Test
    fun unrelatedFailuresNeverSwitchProtocol() {
        // 403/429/quota/argument errors say nothing about which endpoint this address speaks,
        // so switching would only waste a request.
        assertFalse(ProviderEndpointFallback.isSwitchable(AgentModelFailure("HTTP_403", false, "x")))
        assertFalse(ProviderEndpointFallback.isSwitchable(AgentModelFailure("HTTP_429", true, "x")))
        assertFalse(ProviderEndpointFallback.isSwitchable(AgentModelFailure("CONTEXT_OVERFLOW", false, "x")))
        assertFalse(ProviderEndpointFallback.isSwitchable(AgentModelFailure("HTTP_500", true, "x")))
        assertFalse(ProviderEndpointFallback.isSwitchable(AgentModelFailure("STREAM_INCOMPLETE", true, "x")))
        // Non-model failures (cancellation, local exceptions) never trigger a switch either.
        assertFalse(ProviderEndpointFallback.isSwitchable(IllegalStateException("cancel")))
    }

    @Test
    fun protocolMismatchIsNotRetryableWhileTruncationIs() {
        // A protocol mismatch would fail again against the same endpoint, while truncation is
        // transient and must stay retryable.
        assertFalse(AgentModelFailure.endpointProtocolMismatch("x").retryable)
        assertTrue(AgentModelFailure.incompleteStream("x").retryable)
        assertEquals(
            AgentModelFailure.CODE_ENDPOINT_PROTOCOL_MISMATCH,
            AgentModelFailure.endpointProtocolMismatch("x").code,
        )
    }

    @Test
    fun preferredModeFallsBackToConfigurationWithoutEvidence() {
        val baseUrl = "http://no-evidence-${System.nanoTime()}.invalid/v1"

        assertEquals(
            OpenAiEndpointMode.RESPONSES,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.RESPONSES),
        )
        assertEquals(
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS),
        )
        // A dirty value must not become a third endpoint kind.
        assertEquals(
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            ProviderEndpointFallback.preferredMode(baseUrl, "some-future-mode"),
        )
        assertEquals(
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            ProviderEndpointFallback.preferredMode(baseUrl, null),
        )
    }

    @Test
    fun rememberedModeOverridesConfigurationAndClearsWhenTheyAgree() {
        val baseUrl = "http://remember-${System.nanoTime()}.invalid/v1"

        // Responses observed working while configured for Chat Completions: the observation wins.
        ProviderEndpointFallback.rememberWorkingMode(
            baseUrl,
            OpenAiEndpointMode.RESPONSES,
            OpenAiEndpointMode.CHAT_COMPLETIONS,
        )
        assertEquals(
            OpenAiEndpointMode.RESPONSES,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS),
        )

        // Agreement with the configured value leaves no entry: there is no divergence to remember.
        ProviderEndpointFallback.rememberWorkingMode(
            baseUrl,
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            OpenAiEndpointMode.CHAT_COMPLETIONS,
        )
        assertEquals(
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS),
        )
        assertEquals(
            OpenAiEndpointMode.RESPONSES,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.RESPONSES),
        )
    }

    @Test
    fun bothEndpointsFailedExplainsTheLikelyCauseAndDoesNotRetry() {
        val failure = ProviderEndpointFallback.bothEndpointsFailed(
            baseUrl = "https://gateway.example.com/v1",
            firstMode = OpenAiEndpointMode.RESPONSES,
            firstFailure = AgentModelFailure("HTTP_404", false, "x"),
            secondFailure = AgentModelFailure("HTTP_404", false, "x"),
        )

        assertEquals("ENDPOINT_UNAVAILABLE", failure.code)
        // Neither endpoint working is a configuration problem: retrying the same address cannot change it.
        assertFalse(failure.retryable)
        val message = failure.message.orEmpty()
        assertTrue(message.contains("gateway.example.com:443"))
        assertTrue(message.contains("Responses API"))
        assertTrue(message.contains("Chat Completions API"))
        // Must offer an actionable next step instead of only reporting a 404.
        assertTrue(message.contains("/v1"))
        assertTrue(message.contains("Anthropic"))
        // Both failure codes must appear, to tell "neither exists" apart from "one exists but refused".
        assertTrue(message.contains("HTTP_404 / HTTP_404"))
    }

    @Test
    fun webSearchIntentOverridesTheSavedPreference() {
        val baseUrl = "http://websearch-${System.nanoTime()}.invalid/v1"

        // Provider-hosted web search is served only over Responses, so enabling it means
        // "prefer Responses" even when the stored preference is Chat Completions.
        assertEquals(
            OpenAiEndpointMode.RESPONSES,
            ProviderEndpointFallback.declaredPreference(OpenAiEndpointMode.CHAT_COMPLETIONS, true),
        )
        assertEquals(
            OpenAiEndpointMode.RESPONSES,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS, true),
        )
        // Without web search the stored preference still applies.
        assertEquals(
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            ProviderEndpointFallback.declaredPreference(OpenAiEndpointMode.CHAT_COMPLETIONS, false),
        )
    }

    @Test
    fun learnedResultWinsOverTheWebSearchIntent() {
        val baseUrl = "http://learned-over-intent-${System.nanoTime()}.invalid/v1"

        // The address is observed to speak Chat Completions only.
        ProviderEndpointFallback.rememberWorkingMode(
            baseUrl = baseUrl,
            mode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            declaredPreference = OpenAiEndpointMode.RESPONSES,
        )

        // Even with web search on, Responses must not be probed first again: an observation outranks intent.
        assertEquals(
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS, true),
        )
    }

    @Test
    fun webSearchOnAChatOnlyEndpointProbesResponsesOnlyOnce() {
        val baseUrl = "http://chat-only-${System.nanoTime()}.invalid/v1"

        // Key regression for the memory semantics: with "web search on + Chat Completions-only
        // endpoint" the successful mode equals the stored value. Judging "worth remembering"
        // against the stored value would discard the finding, so every later request would probe
        // an endpoint that is certain to fail.
        assertEquals(
            OpenAiEndpointMode.RESPONSES,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS, true),
        )
        ProviderEndpointFallback.rememberWorkingMode(
            baseUrl = baseUrl,
            mode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            declaredPreference = OpenAiEndpointMode.RESPONSES,
        )
        assertEquals(
            OpenAiEndpointMode.CHAT_COMPLETIONS,
            ProviderEndpointFallback.preferredMode(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS, true),
        )
    }

    @Test
    fun webSearchUnavailableWarningNeverThrows() {
        // Visibility only: a logging failure must never change the request outcome.
        ProviderEndpointFallback.warnWebSearchUnavailable(
            "http://warn-${System.nanoTime()}.invalid/v1",
            OpenAiEndpointMode.CHAT_COMPLETIONS,
        )
    }
}
