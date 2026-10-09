package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryToolTest {
    @Test fun pagesReconstructEveryCharacterOfLargeMessage() {
        val message = AgentModelClient.ConversationMessage("assistant", "事实😀".repeat(15_000))
        var loads = 0
        val tool = ConversationHistoryTool { loads++; listOf(message) }
        val rebuilt = StringBuilder()
        var index = 0
        var offset = 0
        do {
            val result = JSONObject(tool.execute(AgentModelClient.ToolCall("call", "conversation_history",
                JSONObject().put("message_index", index).put("offset", offset).put("max_chars", 8000).toString())).content)
            val entries = result.getJSONArray("entries")
            for (i in 0 until entries.length()) rebuilt.append(entries.getJSONObject(i).getString("text"))
            index = result.getInt("next_message_index")
            offset = result.getInt("next_offset")
        } while (result.getBoolean("has_more"))
        // 期望值同样剔除 `_eta_` 内部字段：拼接结果必须逐字符等于脱敏后的完整消息文本。
        assertEquals(visibleJson(message), rebuilt.toString())
        assertEquals(1, loads)
    }

    @Test fun searchReturnsOnlyMatchingCurrentSessionMessages() {
        val tool = ConversationHistoryTool { listOf(
            AgentModelClient.ConversationMessage("user", "旧请求"),
            AgentModelClient.ConversationMessage("tool", "关键标识 FileABC"),
        ) }
        val result = JSONObject(tool.execute(AgentModelClient.ToolCall("c", "conversation_history", """{"query":"fileabc"}""")).content)
        assertEquals(1, result.getJSONArray("entries").length())
        assertEquals(1, result.getJSONArray("entries").getJSONObject(0).getInt("message_index"))
    }

    @Test fun pagesExcludeReasoningContentButKeepBodyAndToolCalls() {
        val message = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "正文保留".repeat(500),
            reasoningContent = "内部推理".repeat(4_000),
            toolCallsJson = """[{"id":"t1","type":"function","function":{"name":"file_ops","arguments":"{}"}}]""",
            messageId = "m1",
        )
        var loads = 0
        val tool = ConversationHistoryTool { loads++; listOf(message) }
        val rebuilt = StringBuilder()
        var excludedFlag = false
        var internalFieldsExcludedFlag = false
        var pages = 0
        var index = 0
        var offset = 0
        do {
            val result = JSONObject(tool.execute(AgentModelClient.ToolCall("call", "conversation_history",
                JSONObject().put("message_index", index).put("offset", offset).put("max_chars", 256).toString())).content)
            excludedFlag = result.getBoolean("reasoning_excluded")
            internalFieldsExcludedFlag = result.getBoolean("internal_fields_excluded")
            val entries = result.getJSONArray("entries")
            for (i in 0 until entries.length()) rebuilt.append(entries.getJSONObject(i).getString("text"))
            index = result.getInt("next_message_index")
            offset = result.getInt("next_offset")
            pages++
        } while (result.getBoolean("has_more"))
        assertTrue(excludedFlag)
        assertTrue(internalFieldsExcludedFlag)
        assertTrue(pages > 1)
        assertFalse(rebuilt.contains("reasoning_content"))
        assertFalse(rebuilt.contains("内部推理"))
        assertTrue(rebuilt.contains("正文保留"))
        assertTrue(rebuilt.contains("file_ops"))
        // 内部持久化协议字段（如 _eta_message_id）绝不能随文本返回给模型。
        assertFalse(rebuilt.contains("_eta_"))
        assertEquals(1, loads)
    }

    @Test fun searchIgnoresExcludedReasoningText() {
        val tool = ConversationHistoryTool { listOf(
            AgentModelClient.ConversationMessage("assistant", "普通回答", reasoningContent = "只有推理里有这个词"),
        ) }
        val result = JSONObject(tool.execute(AgentModelClient.ToolCall(
            "c", "conversation_history", """{"query":"只有推理里有这个词"}""")).content)
        assertEquals(0, result.getJSONArray("entries").length())
        assertTrue(result.getBoolean("reasoning_excluded"))
    }

    @Test fun keepsToolCallsAndToolCallIdWhileDroppingInternalKeys() {
        // 只使用本测试已确认的构造参数；tool_call_id 由 Codec 决定是否写出，故以脱敏后的 Codec 输出为准做等价断言，
        // 保证 Codec 写出的 tool_calls、tool_call_id 等模型可见字段一个都不丢。
        val message = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "带工具调用的回答",
            toolCallsJson = """[{"id":"t1","type":"function","function":{"name":"file_ops","arguments":"{}"}}]""",
            messageId = "m1",
        )
        val tool = ConversationHistoryTool { listOf(message) }
        val result = JSONObject(tool.execute(AgentModelClient.ToolCall("c", "conversation_history",
            """{"max_chars":8000}""")).content)
        val text = result.getJSONArray("entries").getJSONObject(0).getString("text")
        assertTrue(text.contains("tool_calls"))
        assertTrue(text.contains("file_ops"))
        assertTrue(text.contains("\"id\":\"t1\""))
        assertFalse(text.contains("_eta_"))
        assertEquals(visibleJson(message), text)
    }

    /** 与工具一致的可见性规则：剔除 `_eta_` 前缀的持久化协议字段后，Codec 输出的模型可见文本。 */
    private fun visibleJson(message: AgentModelClient.ConversationMessage): String =
        JSONObject(AgentConversationCodec.toJsonObject(message).toString()).apply {
            keys().asSequence().filter { it.startsWith("_eta_") }.toList().forEach { remove(it) }
        }.toString()
}
