package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentLegacyConversationProjection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun toolRoundTripPreservesReasoningContentForCompatibleProviders() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("reasoning_content", "先分析工具参数")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "device_info")
                                .put("arguments", "{}")
                        )
                )
            )

        val durable = AgentConversationCodec.durableMessage(assistant)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("先分析工具参数", replayed.getString("reasoning_content"))
        assertEquals("call-1", replayed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun durableImageObservationNeverPersistsBase64Payload() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userMessage(
                text = "屏幕观察",
                images = listOf(
                    AgentModelClient.ModelImage(
                        reference = "data:image/png;base64,${"A".repeat(20_000)}",
                        mimeType = "image/png",
                        bytes = 15_000,
                    )
                ),
            )
        )

        assertFalse(message.contentJson.contains("base64"))
        assertTrue(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun ipcTranscriptHasHardBudgetAndNeverStartsWithOrphanToolResult() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
                add(
                    AgentModelClient.ConversationMessage(
                        role = "tool",
                        toolCallId = "call-$index",
                        content = "结果-${"y".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "最终答案"))
        }

        val encoded = AgentLegacyConversationProjection.encode(messages, AgentLegacyConversationProjection.DIRECT_CHARS)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentLegacyConversationProjection.DIRECT_CHARS)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.first().role == "tool")
        assertTrue(decoded.first().content.contains("容量上限已压缩"))
        assertTrue(decoded.last().content.contains("最终答案"))
    }

    @Test
    fun conversationCheckpointPreservesEveryMessageBeyondLegacyBudget() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "继续处理最新任务"))
        }

        val encoded = AgentConversationCodec.encodeConversationCheckpoint(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length > 96_000)
        assertEquals(messages, decoded)
        assertEquals("继续处理最新任务", decoded.last().content)
    }

    @Test
    fun responsesOutputItemsPersistInStableTranscriptForNonSensitiveMessages() {
        val source = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(
            source,
            JSONArray().put(
                JSONObject()
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque-secret"),
            ),
        )
        val history = AgentConversationCodec.assistantHistoryMessage(source, emptyList())
        assertTrue(ResponsesEphemeralState.outputItems(history) != null)

        val stable = AgentConversationCodec.durableMessage(history)
        val decoded = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(listOf(stable)),
        )

        assertEquals(1, decoded.size)
        assertTrue(decoded[0].responsesOutputItemsJson.contains("opaque-secret"))
        val replayed = AgentConversationCodec.toJsonObject(decoded[0])
        assertEquals(
            "opaque-secret",
            ResponsesEphemeralState.outputItems(replayed)
                ?.getJSONObject(0)
                ?.getString("encrypted_content"),
        )
    }

    @Test
    fun responsesOutputItemsRoundTripThroughMessageJson() {
        val raw = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(
            raw,
            JSONArray().put(JSONObject().put("type", "reasoning").put("encrypted_content", "opaque")),
        )

        val restored = AgentConversationCodec.fromJsonObject(raw)

        val replayed = AgentConversationCodec.toJsonObject(restored)
        val items = checkNotNull(ResponsesEphemeralState.outputItems(replayed))
        assertEquals(1, items.length())
        assertEquals("opaque", items.getJSONObject(0).getString("encrypted_content"))
        assertEquals("完成", replayed.getString("content"))
    }

    @Test
    fun oversizedResponsesOutputItemsAreDroppedBySizeGuardrail() {
        val raw = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(
            raw,
            JSONArray().put(
                JSONObject()
                    .put("type", "reasoning")
                    .put("payload", "x".repeat(ResponsesEphemeralState.MAX_PERSISTED_ITEMS_CHARS)),
            ),
        )
        assertTrue(
            ResponsesEphemeralState.outputItems(raw)!!.toString().length >
                ResponsesEphemeralState.MAX_PERSISTED_ITEMS_CHARS,
        )

        val restored = AgentConversationCodec.fromJsonObject(raw)

        assertTrue(restored.responsesOutputItemsJson.isEmpty())
        assertFalse(
            AgentConversationCodec.toJsonObject(restored)
                .has(ResponsesEphemeralState.OUTPUT_ITEMS_KEY),
        )
    }

    @Test
    fun sensitiveMessagesLoseResponsesOutputItemsAfterTranscript() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "读取凭据")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-secret")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", "read_file").put("arguments", "{}"),
                        ),
                ),
            )
            .also { message ->
                ResponsesEphemeralState.attachOutputItems(
                    message,
                    JSONArray().put(
                        JSONObject().put("type", "reasoning").put("encrypted_content", "assistant-secret"),
                    ),
                )
            }
        val toolResult = JSONObject()
            .put("role", "tool")
            .put("tool_call_id", "call-secret")
            .put("content", "secret-file-content")
            .also { message ->
                ResponsesEphemeralState.attachOutputItems(
                    message,
                    JSONArray().put(
                        JSONObject().put("type", "reasoning").put("encrypted_content", "tool-secret"),
                    ),
                )
            }

        val transcript = AgentConversationCodec.transcript(
            JSONArray().put(assistant).put(toolResult),
            0,
            setOf("call-secret"),
        )

        assertEquals(2, transcript.size)
        assertTrue(transcript.all { it.responsesOutputItemsJson.isEmpty() })
        val replayed = transcript.map { AgentConversationCodec.toJsonObject(it) }
        assertFalse(replayed[0].has(ResponsesEphemeralState.OUTPUT_ITEMS_KEY))
        assertFalse(replayed[1].has(ResponsesEphemeralState.OUTPUT_ITEMS_KEY))
        assertTrue(replayed[1].getString("content").contains("未写入持久会话"))
    }
}
