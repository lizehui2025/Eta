package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** forChatCompletions 的投影只保留 role/content，内部字段（含 output items）必须剥离。 */
class OpenAiRequestMessagesTest {
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
