package io.github.mangi.eta.agent.model

import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentRequestProjectionCacheTest {
    @Test
    fun chatProjectionIsReusedForTheSameMessageObject() {
        val source = JSONObject().put("role", "user").put("content", "问题")
        val cache = AgentRequestProjectionCache()
        val calls = AtomicInteger()
        val project = { message: JSONObject ->
            calls.incrementAndGet()
            OpenAiRequestMessages.projectChatMessage(message, stripReasoning = false)
        }

        val first = cache.chatMessage(source, stripReasoning = false, project)
        val second = cache.chatMessage(source, stripReasoning = false, project)

        assertSame(first, second)
        assertEquals(1, calls.get())
    }

    @Test
    fun stripReasoningVariantBypassesTheNormalProjectionCache() {
        val source = JSONObject().put("role", "assistant").put("reasoning_content", "reason").put("content", "答")
        val cache = AgentRequestProjectionCache()
        val normal = cache.chatMessage(source, stripReasoning = false, project = { it })
        val stripped = cache.chatMessage(source, stripReasoning = true, project = { message ->
            JSONObject(message.toString()).apply { remove("reasoning_content") }
        })

        assertEquals("reason", normal.optString("reasoning_content"))
        assertEquals("", stripped.optString("reasoning_content"))
    }

    @Test
    fun responsesAndAnthropicCachesAreIdentityKeyed() {
        val source = JSONObject().put("role", "user").put("content", "问题")
        val cache = AgentRequestProjectionCache()
        val responses = cache.responsesInput(source) { JSONArray().put(JSONObject().put("type", "message")) }
        val anthropic = cache.anthropicMessage(source, cacheControl = true) { JSONObject().put("role", "user") }

        assertSame(
            responses,
            cache.responsesInput(source) { error("responses projection must be cached") },
        )
        assertSame(
            anthropic,
            cache.anthropicMessage(source, cacheControl = true) { error("anthropic projection must be cached") },
        )
    }
}
