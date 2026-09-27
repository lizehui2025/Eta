package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end behaviour of mixed endpoint resolution: under a single `openai_compatible` provider,
 * when the configured protocol disagrees with what the endpoint actually serves, the other protocol
 * is tried automatically and the finding is remembered.
 *
 * A real HTTP server is used rather than stubbing the delegate: the value of this feature lies in how
 * the two protocol implementations and the failure classification combine, and stubbing would replace
 * exactly the evidence that matters (which paths were requested, with which failure codes).
 */
class OpenAiMixedEndpointProviderTest {

    @Test
    fun configuredResponsesFallsBackToChatCompletionsWhenPathIsMissing() {
        withServer(
            onResponses = { it.json(404, """{"error":{"message":"Not Found"}}""") },
            onChatCompletions = { it.sse(chatStream("你好")) },
        ) { baseUrl, requests ->
            val result = complete(baseUrl, OpenAiEndpointMode.RESPONSES)

            assertEquals("你好", result.assistantMessage.optString("content"))
            assertEquals(listOf("/v1/responses", "/v1/chat/completions"), requests)
        }
    }

    @Test
    fun configuredChatCompletionsFallsBackToResponsesWhenPathIsMissing() {
        withServer(
            onResponses = { it.sse(responsesStream("早上好")) },
            onChatCompletions = { it.json(404, """{"error":{"message":"Not Found"}}""") },
        ) { baseUrl, requests ->
            val result = complete(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS)

            assertEquals("早上好", result.assistantMessage.optString("content"))
            assertEquals(listOf("/v1/chat/completions", "/v1/responses"), requests)
        }
    }

    @Test
    fun learnedEndpointIsTriedFirstOnLaterRequests() {
        withServer(
            onResponses = { it.json(404, """{"error":{"message":"Not Found"}}""") },
            onChatCompletions = { it.sse(chatStream("一次")) },
        ) { baseUrl, requests ->
            complete(baseUrl, OpenAiEndpointMode.RESPONSES)
            assertEquals(listOf("/v1/responses", "/v1/chat/completions"), requests)

            // The second request must not probe the known-unavailable /responses again: that is the point of remembering.
            requests.clear()
            complete(baseUrl, OpenAiEndpointMode.RESPONSES)
            assertEquals(listOf("/v1/chat/completions"), requests)
        }
    }

    @Test
    fun foreignProtocolStreamOnConfiguredEndpointSwitchesToTheOther() {
        // The gateway answers /responses with 200 but the body is Chat Completions shaped. This
        // "address exists but speaks the wrong protocol" case previously produced only a stream
        // error, after three wasted retries.
        withServer(
            onResponses = { it.sse(chatStream("错协议")) },
            onChatCompletions = { it.sse(chatStream("对协议")) },
        ) { baseUrl, requests ->
            val result = complete(baseUrl, OpenAiEndpointMode.RESPONSES)

            assertEquals("对协议", result.assistantMessage.optString("content"))
            assertEquals(listOf("/v1/responses", "/v1/chat/completions"), requests)
        }
    }

    @Test
    fun unrelatedHttpFailureIsNotSwitched() {
        withServer(
            onResponses = { it.json(400, """{"error":{"message":"invalid model"}}""") },
            onChatCompletions = { it.sse(chatStream("不应到达")) },
        ) { baseUrl, requests ->
            val failure = runCatching { complete(baseUrl, OpenAiEndpointMode.RESPONSES) }
                .exceptionOrNull()

            assertTrue(failure is AgentModelFailure)
            assertEquals("HTTP_400", (failure as AgentModelFailure).code)
            // Argument errors are unrelated to the endpoint kind: only the preferred endpoint may be requested.
            assertEquals(listOf("/v1/responses"), requests)
        }
    }

    @Test
    fun bothEndpointsMissingReportsCombinedFailure() {
        withServer(
            onResponses = { it.json(404, """{"error":{"message":"Not Found"}}""") },
            onChatCompletions = { it.json(404, """{"error":{"message":"Not Found"}}""") },
        ) { baseUrl, requests ->
            val failure = runCatching { complete(baseUrl, OpenAiEndpointMode.RESPONSES) }
                .exceptionOrNull()

            assertTrue(failure is AgentModelFailure)
            val modelFailure = failure as AgentModelFailure
            assertEquals("ENDPOINT_UNAVAILABLE", modelFailure.code)
            assertFalse(modelFailure.retryable)
            assertTrue(modelFailure.message.orEmpty().contains("Anthropic"))
            // One attempt per endpoint, no loop.
            assertEquals(listOf("/v1/responses", "/v1/chat/completions"), requests)
        }
    }

    @Test
    fun cancellationDoesNotTriggerASecondRequest() {
        withServer(
            onResponses = { it.json(404, """{"error":{"message":"Not Found"}}""") },
            onChatCompletions = { it.sse(chatStream("不应到达")) },
        ) { baseUrl, requests ->
            val controller = AgentRunController()
            controller.cancel()

            val failure = runCatching { complete(baseUrl, OpenAiEndpointMode.RESPONSES, controller) }
                .exceptionOrNull()

            // After the user pressed stop, a 404 must not be read as "switch protocol and continue".
            assertTrue(failure is AgentRunCancelledException)
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun webSearchIntentPrefersResponsesEvenWhenConfiguredForChatCompletions() {
        // Both endpoints work and the user enabled provider-hosted web search, which only Responses
        // can serve — so the preference must become Responses, not the stored Chat Completions.
        withServer(
            onResponses = { it.sse(responsesStream("带搜索")) },
            onChatCompletions = { it.sse(chatStream("不带搜索")) },
        ) { baseUrl, requests ->
            val result = complete(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS, webSearch = true)

            assertEquals("带搜索", result.assistantMessage.optString("content"))
            assertEquals(listOf("/v1/responses"), requests)
        }
    }

    @Test
    fun webSearchOnAChatOnlyEndpointProbesResponsesOnlyOnce() {
        withServer(
            onResponses = { it.json(404, """{"error":{"message":"Not Found"}}""") },
            onChatCompletions = { it.sse(chatStream("降级可用")) },
        ) { baseUrl, requests ->
            complete(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS, webSearch = true)
            assertEquals(listOf("/v1/responses", "/v1/chat/completions"), requests)

            // Key regression: the successful mode equals the stored value, yet the preference was
            // "web search implies Responses". If the finding were judged "not worth remembering" and
            // dropped, the second call would hit the 404 again.
            requests.clear()
            complete(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS, webSearch = true)
            assertEquals(listOf("/v1/chat/completions"), requests)
        }
    }

    private fun complete(
        baseUrl: String,
        endpointMode: String,
        controller: AgentRunController = AgentRunController(),
        webSearch: Boolean = false,
    ): ProviderResponse = OpenAiMixedEndpointProvider.complete(
        ProviderRequest(
            config = AgentModelClient.ModelConfig(
                providerSourceType = "custom",
                baseUrl = baseUrl,
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "系统提示",
                openAiEndpointMode = endpointMode,
                hostedWebSearchEnabled = webSearch,
            ),
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            tools = JSONArray(),
        ),
        runController = controller,
    )

    /** Minimal valid Chat Completions stream: one content delta, a finish reason, then [DONE]. */
    private fun chatStream(text: String): String = buildString {
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[")
        append("{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n")
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[")
        append("{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
        append("data: [DONE]\n\n")
    }

    /** Minimal valid Responses stream: a text delta plus a terminal event carrying output. */
    private fun responsesStream(text: String): String = buildString {
        append("event: response.output_text.delta\n")
        append("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",")
        append("\"output_index\":0,\"content_index\":0,\"delta\":\"$text\"}\n\n")
        append("event: response.completed\n")
        append("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",")
        append("\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[")
        append("{\"type\":\"output_text\",\"text\":\"$text\"}]}]}}\n\n")
    }

    private fun HttpExchange.sse(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "text/event-stream")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.json(code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun withServer(
        onResponses: (HttpExchange) -> Unit,
        onChatCompletions: (HttpExchange) -> Unit,
        block: (String, MutableList<String>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val requests = Collections.synchronizedList(mutableListOf<String>())
        // Register full paths: HttpServer matches contexts by path prefix, so a `/responses`
        // context does not match `/v1/responses` and the request falls through to the JDK's
        // built-in 404 — making the test look like "endpoint missing" when really the test never
        // caught the request. Recording the real requestURI.path also verifies that the baseUrl's
        // `/v1` is joined to the endpoint path correctly.
        server.createContext("/v1/responses") { exchange ->
            requests += exchange.requestURI.path
            exchange.use { onResponses(it) }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            requests += exchange.requestURI.path
            exchange.use { onChatCompletions(it) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1", requests)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    /**
     * HttpExchange is not Closeable: the request body must be drained explicitly, otherwise
     * connection reuse and server shutdown can stall and surface as flaky timeouts rather than
     * assertion failures.
     */
    private fun HttpExchange.use(action: (HttpExchange) -> Unit) {
        try {
            requestBody.use { it.readBytes() }
            action(this)
        } finally {
            close()
        }
    }
}
