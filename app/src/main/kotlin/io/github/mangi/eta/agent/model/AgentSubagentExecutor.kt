package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 并发子代理 v1：一次 spawn_agents 调用内部扇出并行。
 * 只允许一层，子代理复用父配置与 Provider，工具集按白名单裁剪并由 guardedExecutor 兜底拦截。
 */
internal class AgentSubagentExecutor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val parentRunController: AgentRunController,
    private val parentOperationId: String,
    private val parentTools: JSONArray,
    private val systemMessages: JSONArray,
    private val baseToolExecutor: AgentModelClient.ToolExecutor,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val depth: Int = 0,
) {
    data class SubTask(val label: String, val prompt: String)

    fun fanout(round: Int, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (depth > 0) {
            return err("NESTED_SPAWN_NOT_ALLOWED", "子代理不可再派生子代理，本次调用已拒绝")
        }
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrElse {
            return err("INVALID_ARGUMENT", "spawn_agents 参数不是 JSON object")
        }
        val tasksJson = args.optJSONArray("tasks")
            ?: return err("INVALID_ARGUMENT", "缺少必填字段 tasks")
        if (tasksJson.length() < 1 || tasksJson.length() > AgentSubagentPolicy.MAX_TASKS_PER_CALL) {
            return err("INVALID_ARGUMENT", "tasks 数量必须为 1-4")
        }
        val tasks = mutableListOf<SubTask>()
        for (i in 0 until tasksJson.length()) {
            val o = tasksJson.optJSONObject(i) ?: return err("INVALID_ARGUMENT", "tasks[$i] 不是 object")
            val prompt = o.optString("prompt").trim()
            if (prompt.isBlank()) return err("INVALID_ARGUMENT", "tasks[$i].prompt 不能为空")
            if (prompt.length > AgentSubagentPolicy.MAX_PROMPT_CHARS) {
                return err("INVALID_ARGUMENT", "tasks[$i].prompt 超过 4000 字符")
            }
            val label = o.optString("label").trim().take(AgentSubagentPolicy.MAX_LABEL_CHARS)
                .ifBlank { "task-${i + 1}" }
            tasks += SubTask(label, prompt)
        }
        val maxRounds = args.optInt("max_rounds", AgentSubagentPolicy.DEFAULT_MAX_ROUNDS)
            .coerceIn(1, AgentSubagentPolicy.MAX_ROUNDS)
        val timeoutMs = args.optInt("timeout_ms", AgentSubagentPolicy.DEFAULT_TIMEOUT_MS)
            .coerceIn(AgentSubagentPolicy.MIN_TIMEOUT_MS, AgentSubagentPolicy.MAX_TIMEOUT_MS)
        val requested = AgentSubagentPolicy.parseRequestedAllowedTools(args)
        val subTools = AgentSubagentPolicy.filterTools(parentTools, requested)
        if (subTools.length() == 0) {
            return err("NO_ALLOWED_TOOLS", "按白名单过滤后子代理无可用工具")
        }

        onEvent(
            AgentEvent.SubagentsStarted(
                round = round,
                toolCallId = call.id,
                count = tasks.size,
                labels = tasks.map { it.label },
            ),
        )

        val poolSize = minOf(tasks.size, AgentSubagentPolicy.MAX_PARALLEL_SUBAGENTS)
        val pool = Executors.newFixedThreadPool(poolSize) { r ->
            Thread(r, "agent-subagent-${parentOperationId.takeLast(6)}").apply { isDaemon = true }
        }
        try {
            parentRunController.throwIfCancelled()
            val callables = tasks.mapIndexed { index, task ->
                Callable<JSONObject> {
                    runSingle(index, task, maxRounds, subTools)
                }
            }
            val futures = pool.invokeAll(callables, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            val results = JSONArray()
            var okCount = 0
            var timeoutCount = 0
            for (i in tasks.indices) {
                val f = futures[i]
                val label = tasks[i].label
                if (f.isCancelled) {
                    timeoutCount++
                    results.put(
                        JSONObject()
                            .put("label", label)
                            .put("ok", false)
                            .put("code", "SUBAGENT_TIMEOUT")
                            .put("content", "子代理超时未完成（整体 ${timeoutMs}ms 预算耗尽）"),
                    )
                } else {
                    val r = runCatching { f.get() }.getOrElse { t ->
                        JSONObject()
                            .put("label", label)
                            .put("ok", false)
                            .put("code", "SUBAGENT_ERROR")
                            .put("content", (t.message ?: t.javaClass.simpleName).take(1000))
                    }
                    if (r.optBoolean("ok", false)) okCount++
                    results.put(r)
                }
            }
            val summary = JSONObject()
                .put("ok", true)
                .put("total", tasks.size)
                .put("succeeded", okCount)
                .put("timed_out", timeoutCount)
                .put("results", results)
                .toString()
            val result = AgentModelClient.ToolResult(content = summary)
            onEvent(
                AgentEvent.SubagentsFinished(
                    round = round,
                    toolCallId = call.id,
                    total = tasks.size,
                    succeeded = okCount,
                ),
            )
            return result
        } catch (t: Throwable) {
            parentRunController.throwIfCancelled()
            return err("SUBAGENT_FANOUT_FAILED", t.message ?: t.javaClass.simpleName)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runSingle(
        index: Int,
        task: SubTask,
        maxRounds: Int,
        subTools: JSONArray,
    ): JSONObject {
        val subController = AgentRunController()
        val binding = parentRunController.register { subController.cancel() }
        try {
            parentRunController.throwIfCancelled()
            val guarded = AgentSubagentPolicy.guardedExecutor(baseToolExecutor)
            val subMessages = JSONArray(systemMessages.toString())
            val isolatedPrompt = buildString {
                append("你是 Eta 主代理派生的只读子代理（label=")
                append(task.label)
                append("）。\n")
                append("只做事实搜集与整理，不做最终决策；只使用本轮公开的只读工具；")
                append("禁止 GUI/浏览器/前台操作与任何写操作，禁止再调用 spawn_agents；")
                append("如需的工具不可用，直接如实返回缺失，不要编造。\n")
                append("请在最多 ")
                append(maxRounds)
                append(" 轮模型调用内完成，最后用简洁中文给出事实结果。\n")
                append("<subtask>\n")
                append(task.prompt)
                append("\n</subtask>")
            }
            subMessages.put(AgentConversationCodec.userTextMessage(isolatedPrompt))
            val toolsCopy = JSONArray(subTools.toString())
            val loop = AgentLoop(
                config = config,
                messages = subMessages,
                tools = toolsCopy,
                provider = provider,
                toolExecutor = guarded,
                runController = subController,
                traceFormatter = traceFormatter,
                onEvent = onEvent,
                sessionId = UUID.randomUUID().toString(),
                transcript = JSONArray(),
                systemCount = systemMessages.length(),
                operationId = "$parentOperationId-sub-$index",
            )
            val r = loop.run()
            val content = r.content.trim().take(AgentSubagentPolicy.MAX_SUB_OUTPUT_CHARS)
            return JSONObject()
                .put("label", task.label)
                .put("ok", content.isNotBlank())
                .put("content", content.ifBlank { "(子代理返回为空)" })
        } catch (t: Throwable) {
            val msg = if (subController.isCancelled || parentRunController.isCancelled) {
                "子代理已取消"
            } else {
                (t.message ?: t.javaClass.simpleName).take(1000)
            }
            return JSONObject()
                .put("label", task.label)
                .put("ok", false)
                .put("code", "SUBAGENT_ERROR")
                .put("content", msg)
        } finally {
            runCatching { binding.close() }
        }
    }

    private fun err(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )
}
