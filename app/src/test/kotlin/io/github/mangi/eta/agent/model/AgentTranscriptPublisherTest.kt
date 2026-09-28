package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transcript 增量发布：任何时刻的发布结果都必须与"整份重转"完全一致，
 * 同时保证正常流程下每个消息只被转换一次（不是每轮重转一遍）。
 */
class AgentTranscriptPublisherTest {
    private val sensitiveIds = linkedSetOf<String>()

    private fun message(role: String, content: String, toolCallId: String = ""): JSONObject =
        JSONObject().put("role", role).put("content", content).also {
            if (toolCallId.isNotBlank()) it.put("tool_call_id", toolCallId)
        }

    private fun assistantWithCalls(vararg calls: String): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", "")
        .put("tool_calls", JSONArray().apply {
            calls.forEach { id ->
                put(
                    JSONObject().put("id", id).put("type", "function")
                        .put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"$id\"}")),
                )
            }
        })

    private fun reference(transcript: JSONArray, ids: Set<String>) =
        AgentConversationCodec.transcript(transcript, 0, ids)

    @Test
    fun everyPublishMatchesFullConversion() {
        val publisher = AgentTranscriptPublisher { sensitiveIds }
        val transcript = JSONArray()
        var reconstructed = emptyList<AgentModelClient.ConversationMessage>()
        val publish = {
            val result = publisher.publish(transcript)
            reconstructed = if (result.fullRebuild) {
                result.messages
            } else {
                reconstructed + result.messages
            }
            assertEquals(reference(transcript, sensitiveIds), reconstructed)
        }

        transcript.put(message("user", "第一轮提问"))
        publish()
        transcript.put(assistantWithCalls("call-1"))
        transcript.put(message("tool", "读取结果", toolCallId = "call-1"))
        publish()
        transcript.put(message("assistant", "第一轮回答"))
        publish()
        transcript.put(message("user", "第二轮提问"))
        transcript.put(assistantWithCalls("call-secret"))
        // 真实顺序：调用消息先入 transcript，工具执行时才判定敏感，结果随后写入——都在同一次发布之前。
        sensitiveIds += "call-secret"
        transcript.put(message("tool", "敏感结果", toolCallId = "call-secret"))
        publish()
        transcript.put(message("assistant", "第二轮回答"))
        publish()

        assertEquals(0, publisher.fullPublishes)
        assertEquals(
            "正常流程下每个消息只转换一次",
            transcript.length(),
            publisher.convertedMessages,
        )
    }

    @Test
    fun sensitiveCallBecomingKnownAfterCallMessagePublishedForcesRebuild() {
        val publisher = AgentTranscriptPublisher { sensitiveIds }
        val transcript = JSONArray()
        transcript.put(message("user", "提问"))
        val first = publisher.publish(transcript)
        assertEquals(reference(transcript, sensitiveIds), first.messages)
        assertTrue(!first.fullRebuild)

        // 模拟"调用消息已发布、工具尚未执行"的发布边界：前缀里留有未闭合调用。
        transcript.put(assistantWithCalls("call-late"))
        publisher.publish(transcript)
        // 现在该调用变敏感，且其消息已在已发布前缀里。
        sensitiveIds += "call-late"
        transcript.put(message("tool", "敏感结果", toolCallId = "call-late"))
        val published = publisher.publish(transcript)
        assertTrue("敏感前缀变化必须返回 full rebuild", published.fullRebuild)
        assertEquals(reference(transcript, sensitiveIds), published.messages)
        assertTrue("前缀里有未闭合调用时必须整份重建", publisher.fullPublishes >= 1)
        assertTrue(
            "重建后调用参数与结果都必须被脱敏",
            published.messages.first { it.role == "assistant" && it.toolCallsJson.isNotBlank() }
                .toolCallsJson.contains("已省略") ||
                published.messages.first { it.role == "tool" }.content != "敏感结果",
        )
    }

    @Test
    fun repeatedPublishWithoutGrowthDoesNotReconvert() {
        val publisher = AgentTranscriptPublisher { sensitiveIds }
        val transcript = JSONArray().put(message("user", "提问")).put(message("assistant", "回答"))
        publisher.publish(transcript)
        val conversions = publisher.convertedMessages
        val publishes = publisher.incrementalPublishes
        repeat(20) {
            val result = publisher.publish(transcript)
            assertTrue(result.messages.isEmpty())
            assertTrue(!result.fullRebuild)
        }
        assertEquals(conversions, publisher.convertedMessages)
        assertEquals(publishes, publisher.incrementalPublishes)
        assertEquals(0, publisher.fullPublishes)
    }

    @Test
    fun convertingManyRoundsStaysLinearInsteadOfQuadratic() {
        val publisher = AgentTranscriptPublisher { sensitiveIds }
        val transcript = JSONArray()
        var reconstructed = emptyList<AgentModelClient.ConversationMessage>()
        // 200 轮、每轮 3 条消息：整份重转的总量是 O(轮数²)，增量发布应保持线性。
        repeat(200) { round ->
            transcript.put(message("user", "第 $round 轮提问"))
            transcript.put(message("assistant", "第 $round 轮回答"))
            transcript.put(message("tool", "", toolCallId = ""))
            val result = publisher.publish(transcript)
            reconstructed = if (result.fullRebuild) result.messages else reconstructed + result.messages
        }
        val total = transcript.length()
        assertEquals(reference(transcript, sensitiveIds), reconstructed)
        // 每条消息只转换一次（末尾那条空的 tool 消息也会被转换一次）。
        assertEquals(total, publisher.convertedMessages)
        assertEquals(200, publisher.incrementalPublishes)
        assertEquals(0, publisher.fullPublishes)
        assertTrue("总转换量必须是线性量级", publisher.convertedMessages < total * 2)
    }

    @Test
    fun cacheHitRateReportingStaysMeaningful() {
        val budget = AgentContextBudget(100_000)
        val tools = JSONArray().put(JSONObject().put("type", "function"))
        val messages = JSONArray().put(message("user", "提问"))
        repeat(5) {
            budget.rawEstimateCached(messages, tools)
            budget.rawEstimateCached(messages, tools)
        }
        val stats = budget.stats()
        assertEquals(1, stats.messageMisses)
        assertEquals(9, stats.messageHits)
        assertEquals(1, stats.toolSchemaMisses)
        assertEquals(9, stats.toolSchemaHits)
        assertTrue(stats.messageHitRate > 0.8)
        assertTrue(stats.toolSchemaHitRate > 0.8)
    }
}
