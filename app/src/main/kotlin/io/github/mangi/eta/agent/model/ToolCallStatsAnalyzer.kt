package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunArchiveStore
import org.json.JSONObject

/**
 * 离线统计工具调用成功率的纯分析器，只读取已经持久化的归档数据（事件 + 脱敏 transcript），
 * 不参与运行时路径、不新增存储、不记录参数或结果正文。
 *
 * 口径：失败 = 结果 JSON 可见且 ok=false，或事件 success=false，或 argumentsJson 不是合法
 * JSON object；「有调用无结果」单列为 interrupted，不进入失败率分子；敏感工具原始结果已被
 * 脱敏替换时，按事件 success 判定，错误码归入 redacted。
 */
internal object ToolCallStatsAnalyzer {
    /** 聚合用的单条工具调用记录；仅含工具名、结果与错误码，不含参数/正文。 */
    data class ToolCallRecord(
        val toolName: String,
        val failed: Boolean,
        val errorCode: String?,
        val argumentsRedacted: Boolean = false,
        val interrupted: Boolean = false,
    )

    data class OutcomeStats(
        val toolName: String,
        val calls: Int,
        val failures: Int,
        val errorCodes: Map<String, Int>,
    ) {
        val failureRate: Double get() = if (calls == 0) 0.0 else failures.toDouble() / calls
    }

    data class Report(
        val totalCalls: Int,
        val failures: Int,
        val interrupted: Int,
        val byTool: Map<String, OutcomeStats>,
        val errorCodes: Map<String, Int>,
    ) {
        val failureRate: Double get() = if (totalCalls == 0) 0.0 else failures.toDouble() / totalCalls
    }

    fun analyze(runs: List<AgentRunArchiveStore.ArchivedRun>): Report =
        aggregate(runs.flatMap { run ->
            val transcript = run.result.transcript
            records(transcript, run.events) +
                interruptedRecords(transcript, run.events) +
                subagentTaskRecords(run.events)
        })

    /** 直接面向已解码 transcript：测试与 DB harness 都可复用。 */
    fun analyze(
        transcript: List<AgentModelClient.ConversationMessage>,
        events: List<AgentEvent> = emptyList(),
    ): Report = aggregate(
        records(transcript, events) + interruptedRecords(transcript, events) + subagentTaskRecords(events),
    )

    fun aggregate(records: List<ToolCallRecord>): Report {
        val byTool = linkedMapOf<String, MutableOutcome>()
        val errorCodes = linkedMapOf<String, Int>()
        var failures = 0
        var interrupted = 0
        records.forEach { record ->
            if (record.interrupted) {
                interrupted++
                return@forEach
            }
            if (record.failed) failures++
            val bucket = byTool.getOrPut(record.toolName) { MutableOutcome() }
            bucket.calls++
            if (record.failed) bucket.failures++
            record.errorCode?.takeIf { it.isNotBlank() }?.let { code ->
                bucket.errorCodes[code] = (bucket.errorCodes[code] ?: 0) + 1
                errorCodes[code] = (errorCodes[code] ?: 0) + 1
            }
        }
        return Report(
            totalCalls = records.count { !it.interrupted },
            failures = failures,
            interrupted = interrupted,
            byTool = byTool.mapValues { (name, bucket) ->
                OutcomeStats(
                    toolName = name,
                    calls = bucket.calls,
                    failures = bucket.failures,
                    errorCodes = bucket.errorCodes.toMap(),
                )
            },
            errorCodes = errorCodes,
        )
    }

    private fun records(
        transcript: List<AgentModelClient.ConversationMessage>,
        events: List<AgentEvent>,
    ): List<ToolCallRecord> {
        val calls = linkedMapOf<String, AgentModelClient.ToolCall>()
        transcript.forEach { message ->
            if (message.toolCallsJson.isBlank()) return@forEach
            runCatching { org.json.JSONArray(message.toolCallsJson) }.getOrNull()?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optJSONObject("function")?.optString("name").orEmpty()
                    if (id.isBlank() || name.isBlank()) continue
                    calls[id] = AgentModelClient.ToolCall(
                        id = id,
                        name = name,
                        argumentsJson = item.optJSONObject("function")?.optString("arguments").orEmpty(),
                    )
                }
            }
        }
        val results = transcript.associateBy(
            keySelector = { it.toolCallId },
            valueTransform = { it },
        )
        val successByCall = events.mapNotNull { event ->
            when (event) {
                is AgentEvent.ToolFinished -> event.toolCallId to event.success
                is AgentEvent.SubagentToolFinished -> event.innerToolCallId to event.success
                is AgentEvent.SubagentFinished ->
                    "subagent:${event.toolCallId}:${event.subIndex}" to event.ok
                else -> null
            }
        }.toMap()
        return calls.mapNotNull { (id, call) ->
            val result = results[id]
            val eventSuccess = successByCall[id]
            // 无事件、无结果：批次中断，单列在 interruptedRecords，不进入失败率分子。
            if (result == null && eventSuccess == null) return@mapNotNull null
            val content = result?.content.orEmpty()
            val parsed = if (content.isBlank()) null else runCatching { JSONObject(content) }.getOrNull()
            val argumentsInvalid = call.parsedArgs().isFailure
            val failed = when {
                argumentsInvalid -> true
                eventSuccess != null -> !eventSuccess
                parsed != null -> !parsed.optBoolean("ok", true)
                else -> true
            }
            val code = when {
                argumentsInvalid -> "INVALID_TOOL_ARGUMENTS"
                content.isBlank() -> if (eventSuccess != null) "redacted" else null
                parsed == null -> "unparseable_result"
                else -> parsed.optString("code").takeIf { it.isNotBlank() }
            }
            ToolCallRecord(
                toolName = call.name,
                failed = failed,
                errorCode = code,
                argumentsRedacted = content.isBlank() && eventSuccess != null,
            )
        }
    }

    private fun interruptedRecords(
        transcript: List<AgentModelClient.ConversationMessage>,
        events: List<AgentEvent>,
    ): List<ToolCallRecord> {
        val finished = events.mapNotNullTo(mutableSetOf()) { event ->
            when (event) {
                is AgentEvent.ToolFinished -> event.toolCallId
                is AgentEvent.SubagentToolFinished -> event.innerToolCallId
                else -> null
            }
        }
        val results = transcript.mapNotNullTo(mutableSetOf()) { message ->
            message.toolCallId.takeIf { it.isNotBlank() }
        }
        return transcript.flatMap { message ->
            if (message.toolCallsJson.isBlank()) return@flatMap emptyList()
            runCatching { org.json.JSONArray(message.toolCallsJson) }.getOrNull()?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    val name = item.optJSONObject("function")?.optString("name").orEmpty()
                    if (id.isBlank() || name.isBlank()) return@mapNotNull null
                    if (id in finished || id in results) return@mapNotNull null
                    ToolCallRecord(toolName = name, failed = true, errorCode = INTERRUPTED, interrupted = true)
                }
            }.orEmpty()
        }
    }

    /**
     * 子代理任务单列：一次 spawn_agents 调用扇出多个任务，任务级失败不能重复计入
     * 父 tool call，也不伪装成普通工具。没有 code 视为成功（子代理正文非空即成功）。
     */
    private fun subagentTaskRecords(events: List<AgentEvent>): List<ToolCallRecord> =
        events.mapNotNull { event ->
            when (event) {
                is AgentEvent.SubagentFinished -> ToolCallRecord(
                    toolName = AgentSubagentPolicy.TOOL_NAME,
                    failed = !event.ok,
                    errorCode = event.code?.takeIf { it.isNotBlank() },
                )
                else -> null
            }
        }

    private class MutableOutcome {
        var calls: Int = 0
        var failures: Int = 0
        val errorCodes = linkedMapOf<String, Int>()
    }

    /** 报告文本；真实/合成数据来源由调用方在 [source] 中标注。 */
    fun format(report: Report, source: String): String = buildString {
        appendLine("tool-call baseline ($source)")
        appendLine("total=${report.totalCalls} failures=${report.failures} " +
            "failure_rate=${formatRate(report.failureRate)} interrupted=${report.interrupted}")
        appendLine("by_tool:")
        report.byTool.toSortedMap().forEach { (name, stats) ->
            appendLine(
                "  $name calls=${stats.calls} failures=${stats.failures} " +
                    "failure_rate=${formatRate(stats.failureRate)}",
            )
        }
        appendLine("error_codes:")
        report.errorCodes.toSortedMap().forEach { (code, count) ->
            appendLine("  $code=$count")
        }
    }

    private fun formatRate(value: Double): String =
        String.format(java.util.Locale.US, "%.2f%%", value * 100)

    private const val INTERRUPTED = "interrupted"
}
