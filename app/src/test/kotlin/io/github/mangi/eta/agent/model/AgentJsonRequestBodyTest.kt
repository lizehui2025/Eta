package io.github.mangi.eta.agent.model

import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentJsonRequestBodyTest {
    @Test
    fun streamingBodyMatchesJsonObjectToStringByteForByte() {
        val json = JSONObject()
            .put("model", "gpt-test")
            .put("stream", true)
            .put("temperature", 1.0)
            .put("nullable", JSONObject.NULL)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "中文 \"quoted\"\nline"),
                ),
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "function")
                        .put("function", JSONObject().put("name", "read_file").put("parameters", JSONObject().put("type", "object"))),
                ),
            )

        val sink = Buffer()
        AgentJsonRequestBody(json, "application/json; charset=utf-8".toMediaType()).writeTo(sink)

        assertEquals(json.toString(), sink.readUtf8())
    }
}
