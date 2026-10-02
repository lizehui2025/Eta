package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** forChatCompletions 的投影只保留 role/content，内部字段（含 output items）必须剥离。 */
class OpenAiRequestMessagesTest {
    @Test
    fun completedThoughtsAreRemovedButCurrentToolThoughtAndTranscriptStayIntact() {
        val oldThought = JSONObject().put("role", "assistant").put("content", "Previous answer")
            .put("reasoning_content", "Repeated old thought")
        val currentThought = JSONObject().put("role", "assistant").put("content", "")
            .put("reasoning_content", "Current tool reasoning")
        val source = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "Old question"))
            .put(oldThought)
            .put(JSONObject().put("role", "user").put("content", "Current question"))
            .put(currentThought)
            .put(JSONObject().put("role", "tool").put("content", "Evidence").put("tool_call_id", "call"))
            .put(JSONObject().put("role", "user").put("content", "Tool image").put("_eta_observation", true))
        val cache = AgentRequestProjectionCache()
        repeat(2) {
            val projected = OpenAiRequestMessages.forChatCompletions(source, cache = cache)
            assertFalse(projected.getJSONObject(1).has("reasoning_content"))
            assertEquals("Previous answer", projected.getJSONObject(1).getString("content"))
            assertEquals("Current tool reasoning", projected.getJSONObject(3).getString("reasoning_content"))
            assertEquals("Evidence", projected.getJSONObject(4).getString("content"))
        }
        assertTrue(oldThought.has("reasoning_content"))
        assertTrue(currentThought.has("reasoning_content"))
        val stripped = OpenAiRequestMessages.forChatCompletions(source, stripReasoning = true, cache = cache)
        assertFalse(stripped.getJSONObject(3).has("reasoning_content"))
    }

    @Test
    fun forChatCompletionsStripsResponsesItemsAndEtaMessageId() {
        val source = JSONArray()
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "完成")
                    .put("_eta_message_id", "assistant-1")
                    .also { message ->
                        ResponsesEphemeralState.attachOutputItems(
                            message,
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "reasoning")
                                    .put("encrypted_content", "opaque"),
                            ),
                        )
                    },
            )
            .put(JSONObject().put("role", "user").put("content", "继续"))

        val projected = OpenAiRequestMessages.forChatCompletions(source)

        assertEquals(2, projected.length())
        val assistant = projected.getJSONObject(0)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("完成", assistant.getString("content"))
        assertFalse(assistant.has("_eta_message_id"))
        assertFalse(assistant.has(ResponsesEphemeralState.OUTPUT_ITEMS_KEY))
        val user = projected.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        assertEquals("继续", user.getString("content"))
    }
}
