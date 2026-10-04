package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallStatsAnalyzerTest {
    @Test
    fun analyzerCountsVisibleFailuresByToolAndErrorCode() {
        val transcript = listOf(
            assistantCalls(
                "read_file" to """{"path":"/workspace/a"}""",
                "read_file" to """{"path":"/workspace/b"}""",
                "terminal" to """{"action":"open"}""",
            ),
            toolResult("1", """{"ok":true}"""),
            toolResult("2", """{"ok":false,"code":"FILE_NOT_FOUND","message":"missing"}"""),
            toolResult("3", """{"ok":false,"code":"ROOT_REQUIRED","message":"root"}"""),
        )
        val events = listOf(
            AgentEvent.ToolFinished(1, "1", "read_file", "完成", 0, 0, success = true),
            AgentEvent.ToolFinished(1, "2", "read_file", "失败", 0, 0, success = false),
            AgentEvent.ToolFinished(1, "3", "terminal", "失败", 0, 0, success = false),
        )

        val report = ToolCallStatsAnalyzer.analyze(transcript, events)

        assertEquals(3, report.totalCalls)
        assertEquals(2, report.failures)
        assertEquals(0, report.interrupted)
        assertEquals(0.5, report.byTool.getValue("read_file").failureRate, 0.0001)
        assertEquals(1, report.errorCodes.getValue("FILE_NOT_FOUND"))
        assertEquals(1, report.errorCodes.getValue("ROOT_REQUIRED"))
    }

    @Test
    fun malformedArgumentsAndSensitiveResultsAreCountedWithoutContent() {
        val transcript = listOf(
            assistantCalls(
                "ui_action" to "{not-json",
                "clipboard" to """{"operation":"get"}""",
            ),
            toolResult("2", ""),
        )
        val events = listOf(
            AgentEvent.ToolFinished(1, "1", "ui_action", "失败", 0, 0, success = false),
            AgentEvent.ToolFinished(1, "2", "clipboard", "完成", 0, 0, success = true),
        )

        val report = ToolCallStatsAnalyzer.analyze(transcript, events)

        assertEquals(2, report.totalCalls)
        assertEquals(1, report.failures)
        assertEquals(1, report.errorCodes.getValue("INVALID_TOOL_ARGUMENTS"))
        assertEquals(1, report.errorCodes.getValue("redacted"))
    }

    @Test
    fun interruptedCallIsListedSeparatelyFromFailureRate() {
        val transcript = listOf(
            assistantCalls(
                "read_file" to """{"path":"/workspace/a"}""",
                "write_file" to """{"path":"/workspace/b","content":"x"}""",
            ),
            toolResult("1", """{"ok":true}"""),
        )
        val events = listOf(
            AgentEvent.ToolFinished(1, "1", "read_file", "完成", 0, 0, success = true),
        )

        val report = ToolCallStatsAnalyzer.analyze(transcript, events)

        assertEquals(1, report.totalCalls)
        assertEquals(0, report.failures)
        assertEquals(1, report.interrupted)
        assertEquals(1, report.byTool.getValue("read_file").calls)
    }

    @Test
    fun subagentTaskOutcomesAreReportedSeparately() {
        val events = listOf(
            AgentEvent.SubagentFinished(1, "spawn-1", 0, "a", true, "完成"),
            AgentEvent.SubagentFinished(1, "spawn-1", 1, "b", false, "失败", code = "SUBAGENT_ERROR"),
        )

        val report = ToolCallStatsAnalyzer.analyze(emptyList(), events)

        val spawn = report.byTool.getValue(AgentSubagentPolicy.TOOL_NAME)
        assertEquals(2, spawn.calls)
        assertEquals(1, spawn.failures)
        assertEquals(1, report.errorCodes.getValue("SUBAGENT_ERROR"))
    }

    @Test
    fun emptyCorpusProducesZeroedReport() {
        val report = ToolCallStatsAnalyzer.analyze(emptyList(), emptyList())

        assertEquals(0, report.totalCalls)
        assertEquals(0, report.failures)
        assertEquals(0.0, report.failureRate, 0.0001)
        assertEquals(0, report.byTool.size)
    }

    @Test
    fun syntheticBaselineReportPinsTheMeasurementContract() {
        val report = ToolCallStatsAnalyzer.analyze(syntheticBaselineCorpus())
        val formatted = ToolCallStatsAnalyzer.format(report, "synthetic")

        // 报告落在 build/reports 下，作为可复查的合成基线；真实设备基线见 harness。
        java.io.File("build/reports/tool-call-baseline-synthetic.txt")
            .also { it.parentFile?.mkdirs() }
            .writeText(formatted)
        assertTrue(formatted.contains("tool-call baseline (synthetic)"))
        // 合成基线刻意包含一次中断：它计入 interrupted，不进入失败率分母。
        assertTrue(formatted.contains("total=3 failures=2"))
        assertTrue(formatted.contains("failure_rate=66.67%"))
        assertTrue(formatted.contains("read_file calls=2 failures=1"))
    }

    private fun syntheticBaselineCorpus() =
        listOf(
            assistantCalls(
                "read_file" to """{"path":"/workspace/a"}""",
                "read_file" to """{"path":"/workspace/b"}""",
                "ui_action" to "{not-json",
                "terminal" to """{"action":"open"}""",
            ),
            toolResult("1", """{"ok":true}"""),
            toolResult("2", """{"ok":false,"code":"FILE_NOT_FOUND"}"""),
            toolResult("4", """{"ok":false,"code":"ROOT_REQUIRED"}"""),
        )

    private fun assistantCalls(vararg calls: Pair<String, String>): AgentModelClient.ConversationMessage {
        val array = JSONArray()
        calls.forEachIndexed { index, (name, arguments) ->
            array.put(
                JSONObject()
                    .put("id", "${index + 1}")
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject().put("name", name).put("arguments", arguments),
                    ),
            )
        }
        return AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "",
            toolCallsJson = array.toString(),
        )
    }

    private fun toolResult(id: String, content: String): AgentModelClient.ConversationMessage =
        AgentModelClient.ConversationMessage(
            role = "tool",
            content = content,
            toolCallId = id,
        )
}
