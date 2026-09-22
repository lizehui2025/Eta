package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBreakdownTest {
    private fun toolCalls(vararg pairs: Pair<String, String>): JSONArray =
        JSONArray().also { array ->
            pairs.forEach { (id, name) ->
                array.put(
                    JSONObject().put("id", id).put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", name).put("arguments", "{}"),
                        ),
                )
            }
        }

    @Test
    fun windowContentIsSplitIntoDialogueToolsThinkingAndCode() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "系统约束"))
            .put(JSONObject().put("role", "user").put("content", "你好"))
            .put(
                JSONObject().put("role", "assistant").put("content", "我来查一下")
                    .put("reasoning_content", "先读文件再回答")
                    .put("tool_calls", toolCalls("a" to "read_file", "b" to "get_setting")),
            )
            // 文件类工具结果进“代码”，普通工具结果进“工具”。
            .put(JSONObject().put("role", "tool").put("tool_call_id", "a").put("content", "文件内容".repeat(100)))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "b").put("content", "ok"))

        val breakdown = AgentContextBreakdownCounter.breakdown(messages)

        assertTrue("对话应包含用户与助手正文", breakdown.dialogueTokens > 0)
        assertTrue("思考链应计入 reasoning_content", breakdown.thinkingTokens > 0)
        assertTrue("工具参数与普通结果应计入工具", breakdown.toolCallTokens > 0)
        val fileTokens = AgentContextBudget.textTokens("文件内容".repeat(100))
        assertEquals(fileTokens, breakdown.codeDataTokens)
        assertTrue("系统提示独立计数", breakdown.systemTokens > 0)
        assertEquals(
            breakdown.dialogueTokens + breakdown.toolCallTokens + breakdown.thinkingTokens +
                breakdown.codeDataTokens + breakdown.systemTokens + breakdown.imageTokens +
                breakdown.schemaTokens,
            breakdown.totalTokens,
        )
        val line = breakdown.summaryLine()
        assertTrue(line.contains("对话"))
        assertTrue(line.contains("代码"))
        assertTrue(line.contains("思考"))
    }

    @Test
    fun thinkingAbsentWhenProviderDoesNotReturnIt() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "hi"))
            .put(JSONObject().put("role", "assistant").put("content", "hello"))
        val breakdown = AgentContextBreakdownCounter.breakdown(messages)
        assertEquals(0, breakdown.thinkingTokens)
        assertEquals(0, breakdown.codeDataTokens)
    }

    @Test
    fun imagesAreCountedSeparatelyFromText() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "看这张图"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "https://x/y.png")))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val breakdown = AgentContextBreakdownCounter.breakdown(messages)
        assertEquals(4096, breakdown.imageTokens)
        assertTrue(breakdown.dialogueTokens > 0)
    }

    @Test
    fun effectiveTokensPrefersRealtimeAnchorOverCalibration() {
        val budget = AgentContextBudget(100_000)
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("文本"))
        val tools = JSONArray()
        val raw = AgentContextBudget.rawEstimate(messages, tools)
        // 无真实值时回退校准估算（初始倍率 1）。
        assertEquals(raw, budget.effectiveTokens(messages, tools))

        // 上轮真实 50K、当时估算 30K：本轮估算 35K 应投影为 55K，
        // 而不是校准膨胀后的值；显示 50% 时不会误判 85% 触发压缩。
        budget.observe(AgentTokenUsage(inputTokens = 50_000), 30_000)
        val grown = JSONArray()
            .put(AgentConversationCodec.userTextMessage("文本"))
            .put(AgentConversationCodec.userTextMessage("新增内容".repeat(200)))
        val grownRaw = AgentContextBudget.rawEstimate(grown, tools)
        assertEquals(50_000 + (grownRaw - 30_000), budget.effectiveTokens(grown, tools))
        assertTrue(budget.shouldCompact(85_000))
        // 真实 50K + 小增量：不应压缩。
        assertTrue(!budget.shouldCompact(budget.effectiveTokens(messages, tools)))
        // 压缩后窗口变小：投影随 raw 下降，不会被旧锚点卡住。
        val shrunk = JSONArray().put(AgentConversationCodec.userTextMessage("短"))
        assertTrue(budget.effectiveTokens(shrunk, tools) < 50_000)
    }
}
