package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubagentPolicyTest {
    private fun toolArray(vararg names: String): JSONArray {
        val arr = JSONArray()
        names.forEach { name ->
            arr.put(JSONObject().put("function", JSONObject().put("name", name).put("description", "t").put("parameters", JSONObject().put("type", "object"))))
        }
        return arr
    }

    private fun stubCapabilities(): ProviderCapabilities = ProviderCapabilities(
        endpoint = EndpointKind.CHAT_COMPLETIONS,
        streamingText = false,
        streamingToolCalls = false,
        imageInput = false,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = false,
    )

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            browserTools = false,
        )

    private fun stubProvider(block: (ProviderRequest) -> ProviderResponse): AgentProviderClient =
        object : AgentProviderClient {
            override val id: String = "stub"
            override val capabilities: ProviderCapabilities = stubCapabilities()
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse = block(request)
        }

    private fun subagentExecutor(parentTools: JSONArray): AgentSubagentExecutor = AgentSubagentExecutor(
        config = modelConfig(),
        provider = stubProvider {
            ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
            )
        },
        parentRunController = AgentRunController(),
        parentOperationId = "op-validation",
        parentTools = parentTools,
        systemMessages = JSONArray(),
        baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
        traceFormatter = AgentTraceFormatter(),
        onEvent = {},
        depth = 0,
    )

    @Test
    fun blockedToolsAreRejected() {
        listOf(
            "observe_screen", "tap_element", "browser_use", "memory_write", "spawn_agents",
            "todo_write", "set_setting", "mcp_fetch",
            // shell 与文件写入可绕过只读约束，同样禁入子代理
            "terminal", "run_command", "write_file", "edit_file",
        ).forEach {
            assertFalse("$it should be blocked", AgentSubagentPolicy.isAllowed(it))
        }
        assertTrue(AgentSubagentPolicy.isAllowed("search_files"))
        assertTrue(AgentSubagentPolicy.isAllowed("search_calendar_events"))
        assertTrue(AgentSubagentPolicy.isAllowed("search_code"))
    }

    @Test
    fun filterToolsDropsBlockedAndSpawn() {
        val filtered = AgentSubagentPolicy.filterTools(
            toolArray(
                "search_files", "observe_screen", "spawn_agents", "search_calendar_events",
                "mcp_fetch", "terminal", "run_command", "write_file",
            ),
            null,
        )
        val names = (0 until filtered.length()).map { filtered.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        assertEquals(setOf("search_files", "search_calendar_events"), names)
    }

    @Test
    fun guardedExecutorRejectsExclusiveTools() {
        var called = false
        val base = AgentModelClient.ToolExecutor { called = true; AgentModelClient.ToolResult("ok") }
        val guarded = AgentSubagentPolicy.guardedExecutor(base)
        val blocked = guarded.execute(AgentModelClient.ToolCall("1", "tap_element", "{}"))
        assertFalse(called)
        assertTrue(blocked.content.contains("EXCLUSIVE_TOOL_BUSY"))
        val shellBlocked = guarded.execute(AgentModelClient.ToolCall("3", "terminal", "{}"))
        assertFalse(called)
        assertTrue(shellBlocked.content.contains("EXCLUSIVE_TOOL_BUSY"))
        val ok = guarded.execute(AgentModelClient.ToolCall("2", "search_files", "{}"))
        assertTrue(called)
        assertEquals("ok", ok.content)
    }

    @Test
    fun nestedSpawnReturnsDedicatedCode() {
        var called = false
        val base = AgentModelClient.ToolExecutor { called = true; AgentModelClient.ToolResult("ok") }
        val guarded = AgentSubagentPolicy.guardedExecutor(base)
        val nested = guarded.execute(AgentModelClient.ToolCall("9", "spawn_agents", "{}"))
        assertFalse(called)
        assertTrue(nested.content.contains("NESTED_SPAWN_NOT_ALLOWED"))
    }

    @Test
    fun catalogDeclaresSpawnToolWithoutQuotaCaps() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue("spawn_agents" in names)
        assertEquals(names.size, names.toSet().size)
        val fn = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "spawn_agents" }
        val props = fn.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(1, fn.getJSONObject("parameters").getJSONArray("required").length())
        // 默认不再限制子代理开销：schema 不声明任务数/工具数/轮数/超时的配额上限。
        assertFalse("tasks 不应设数量上限", props.getJSONObject("tasks").has("maxItems"))
        assertEquals(1, props.getJSONObject("tasks").getInt("minItems"))
        assertFalse("allowed_tools 不应设数量上限", props.getJSONObject("allowed_tools").has("maxItems"))
        assertFalse("max_rounds 不应设上限", props.getJSONObject("max_rounds").has("maximum"))
        assertFalse("timeout_ms 不应设上限", props.getJSONObject("timeout_ms").has("maximum"))
    }

    @Test
    fun nestedSpawnIsRejected() {
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = stubProvider { throw UnsupportedOperationException("no provider in nested test") },
            parentRunController = AgentRunController(),
            parentOperationId = "op-test",
            parentTools = toolArray("search_files"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            depth = 1,
        )
        val call = AgentModelClient.ToolCall("c1", "spawn_agents", JSONObject().put("tasks", JSONArray().put(JSONObject().put("prompt", "hi"))).toString())
        val result = exec.fanout(1, call)
        assertTrue(result.content.contains("NESTED_SPAWN_NOT_ALLOWED"))
    }

    @Test
    fun loopMaxRoundsTruncatesEndlessToolCalls() {
        var providerCalls = 0
        val looping = stubProvider {
            providerCalls++
            val tc = JSONObject().put("id", "c$providerCalls").put("type", "function")
                .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))
            ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().put(tc)),
            )
        }
        val loop = AgentLoop(
            config = modelConfig(),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("hi")),
            tools = toolArray("get_current_context"),
            provider = looping,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{}") },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            maxRounds = 2,
        )
        val result = loop.run()
        assertTrue(result.roundLimited)
        assertEquals(2, providerCalls)
    }

    @Test
    fun fanoutRunsSubagentsInParallel() {
        // 两个子代理必须同时进入 provider 才能越过 barrier；串行执行会在 15s 内超时失败。
        val barrier = CyclicBarrier(2)
        val rendezvous = stubProvider {
            barrier.await(15, TimeUnit.SECONDS)
            ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
            )
        }
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = rendezvous,
            parentRunController = AgentRunController(),
            parentOperationId = "op-parallel",
            parentTools = toolArray("search_files"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            depth = 0,
        )
        val args = JSONObject().put(
            "tasks",
            JSONArray()
                .put(JSONObject().put("label", "a").put("prompt", "task a"))
                .put(JSONObject().put("label", "b").put("prompt", "task b")),
        )
        val result = exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString()))
        val json = JSONObject(result.content)
        assertEquals(2, json.getInt("succeeded"))
        //取证字段：整体耗时与各子耗时/线程必须存在，否则无法区分串行与并行。
        assertTrue(json.has("fanout_elapsed_ms"))
        val results = json.getJSONArray("results")
        assertEquals(2, results.length())
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            assertTrue(r.has("duration_ms"))
            assertTrue(r.has("thread"))
            assertTrue(r.optLong("duration_ms", -1) >= 0)
        }
    }

    @Test
    fun fanoutCapsParallelismButNotTaskCount() {
        // 任务数不限：12 个任务全部成功；并行封顶 MAX_PARALLEL_TASKS，同时只跑 4 个。
        assertEquals(4, AgentSubagentPolicy.MAX_PARALLEL_TASKS)
        val exec = subagentExecutor(toolArray("search_files"))
        val tasks = JSONArray()
        for (i in 1..12) tasks.put(JSONObject().put("label", "t$i").put("prompt", "task $i"))
        val args = JSONObject().put("tasks", tasks)
        val result = exec.fanout(1, AgentModelClient.ToolCall("c12", "spawn_agents", args.toString()))
        val json = JSONObject(result.content)
        assertEquals(12, json.getInt("succeeded"))
        assertEquals(0, json.optInt("timed_out", -1))
    }

    @Test
    fun codeModeEnablesDeclaredWriteToolsOnly() {
        assertFalse(AgentSubagentPolicy.isAllowed("write_file"))
        assertFalse(AgentSubagentPolicy.isAllowed("edit_file"))
        assertTrue(AgentSubagentPolicy.isAllowed("write_file", SubagentMode.CODE))
        assertTrue(AgentSubagentPolicy.isAllowed("edit_file", SubagentMode.CODE))
        assertFalse(AgentSubagentPolicy.isAllowed("terminal", SubagentMode.CODE))
        assertFalse(AgentSubagentPolicy.isAllowed("run_command", SubagentMode.CODE))
        assertFalse(AgentSubagentPolicy.isAllowed("set_setting", SubagentMode.CODE))
        assertTrue(AgentSubagentPolicy.isAllowed("search_code", SubagentMode.CODE))
    }

    @Test
    fun explicitRoundAndTimeoutLimitsAreHonoredWithoutCeilings() {
        // 显式声明远超旧上限的 max_rounds / timeout_ms（旧上限 16 / 600000）不再被拒绝或截断。
        val exec = subagentExecutor(toolArray("search_files"))
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
            .put("max_rounds", 500)
            .put("timeout_ms", 3_600_000)
        val result = exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString()))
        val json = JSONObject(result.content)
        assertEquals(1, json.getInt("succeeded"))
        assertEquals(0, json.optInt("timed_out", -1))
    }

    @Test
    fun modeParsingDefaultsToResearchAndRejectsUnknownValues() {
        assertEquals(SubagentMode.RESEARCH, SubagentMode.parse(null))
        assertEquals(SubagentMode.RESEARCH, SubagentMode.parse(""))
        assertEquals(SubagentMode.CODE, SubagentMode.parse("CODE"))
        assertEquals(null, SubagentMode.parse("shell"))
    }

    @Test
    fun rawAllowedToolsParsingKeepsHintsForModeInference() {
        assertNull(AgentSubagentPolicy.parseRawRequestedAllowedTools(JSONObject()))
        // 显式空数组视为未提供：白名单整段不启用（此前语义为 emptySet 清空工具集）。
        assertNull(
            AgentSubagentPolicy.parseRawRequestedAllowedTools(JSONObject().put("allowed_tools", JSONArray())),
        )
        assertNull(
            AgentSubagentPolicy.parseRequestedAllowedTools(JSONObject().put("allowed_tools", JSONArray())),
        )
        assertEquals(
            setOf("edit_file", "read_file"),
            AgentSubagentPolicy.parseRawRequestedAllowedTools(
                JSONObject().put("allowed_tools", JSONArray().put("edit_file").put(" read_file ")),
            ),
        )
    }

    @Test
    fun fanoutInfersCodeModeFromWriteHintsAndDefaultsToResearch() {
        val provider = stubProvider {
            ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
            )
        }
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = provider,
            parentRunController = AgentRunController(),
            parentOperationId = "op-infer",
            parentTools = toolArray("search_files", "read_file", "edit_file", "write_file"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            depth = 0,
        )

        fun fanout(json: JSONObject): JSONObject =
            JSONObject(exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", json.toString())).content)

        val withPaths = fanout(
            JSONObject().put(
                "tasks",
                JSONArray().put(JSONObject().put("prompt", "task").put("write_paths", JSONArray().put("/tmp/a"))),
            ),
        )
        assertEquals("code", withPaths.getString("mode"))
        assertEquals(1, withPaths.getInt("succeeded"))

        val withWriteToolHint = fanout(
            JSONObject()
                .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
                .put("allowed_tools", JSONArray().put("edit_file")),
        )
        assertEquals("code", withWriteToolHint.getString("mode"))

        val plain = fanout(
            JSONObject().put("tasks", JSONArray().put(JSONObject().put("prompt", "task"))),
        )
        assertEquals("research", plain.getString("mode"))

        val unknownMode = exec.fanout(
            1,
            AgentModelClient.ToolCall(
                "c2",
                "spawn_agents",
                JSONObject()
                    .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
                    .put("mode", "shell")
                    .toString(),
            ),
        )
        assertTrue(unknownMode.content.contains("INVALID_ARGUMENT"))
    }

    @Test
    fun guardedExecutorInCodeModeKeepsDeclaredWritesButBlocksShell() {
        var called = false
        val base = AgentModelClient.ToolExecutor { called = true; AgentModelClient.ToolResult("ok") }
        val guarded = AgentSubagentPolicy.guardedExecutor(base, SubagentMode.CODE)
        val shell = guarded.execute(AgentModelClient.ToolCall("1", "terminal", "{}"))
        assertFalse(called)
        assertTrue(shell.content.contains("EXCLUSIVE_TOOL_BUSY"))
        val write = guarded.execute(AgentModelClient.ToolCall("2", "write_file", "{}"))
        assertTrue(called)
        assertEquals("ok", write.content)
        val nested = guarded.execute(AgentModelClient.ToolCall("3", "spawn_agents", "{}"))
        assertTrue(nested.content.contains("NESTED_SPAWN_NOT_ALLOWED"))
    }

    @Test
    fun fanoutRejectsModeWritePathConflicts() {
        val exec = subagentExecutor(toolArray("search_files", "read_file", "write_file", "edit_file"))
        val researchWithPaths = exec.fanout(
            1,
            AgentModelClient.ToolCall(
                "c2",
                "spawn_agents",
                JSONObject()
                    .put("mode", "research")
                    .put(
                        "tasks",
                        JSONArray().put(
                            JSONObject().put("prompt", "task").put("write_paths", JSONArray().put("/tmp/a")),
                        ),
                    )
                    .toString(),
            ),
        )
        assertTrue(researchWithPaths.content.contains("INVALID_ARGUMENT"))

        val codeWithoutPaths = exec.fanout(
            1,
            AgentModelClient.ToolCall(
                "c3",
                "spawn_agents",
                JSONObject()
                    .put("mode", "code")
                    .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
                    .toString(),
            ),
        )
        assertTrue(codeWithoutPaths.content.contains("INVALID_ARGUMENT"))
    }

    @Test
    fun fanoutDoesNotCapTaskCountOrAllowedToolCount() {
        // 不再限制任务数与 allowed_tools 数量：9 个任务（旧上限 8）与 41 项白名单（旧上限 32）正常执行。
        val exec = subagentExecutor(toolArray("search_files"))
        val tasks = JSONArray()
        for (i in 1..9) tasks.put(JSONObject().put("prompt", "task $i"))
        val allowed = JSONArray().put("search_files")
        for (i in 1..40) allowed.put("tool_$i")
        val args = JSONObject().put("tasks", tasks).put("allowed_tools", allowed)
        val json = JSONObject(
            exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content,
        )
        assertEquals(9, json.getInt("total"))
        assertEquals(9, json.getInt("succeeded"))
    }

    @Test
    fun oversizedSubagentOutputIsBoundedInMainContextButKeptWholeInDetailEvent() {
        // 超长子代理输出：进入主循环的副本按模式截断并打标记（防主上下文被单次扇出撑爆而被迫压缩），
        // SubagentFinished 事件仍保留完整结果供详情窗口展示。
        val huge = "F".repeat(100_000)
        val events = java.util.Collections.synchronizedList(mutableListOf<AgentEvent>())
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = stubProvider {
                ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", huge).put("finish_reason", "stop"),
                )
            },
            parentRunController = AgentRunController(),
            parentOperationId = "op-huge",
            parentTools = toolArray("search_files"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = events::add,
            depth = 0,
        )
        val args = JSONObject().put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
        val json = JSONObject(exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content)
        assertEquals(1, json.getInt("succeeded"))
        val taskResult = json.getJSONArray("results").getJSONObject(0)
        assertTrue(taskResult.optBoolean("content_truncated", false))
        assertEquals(huge.length, taskResult.optInt("content_chars", -1))
        val contextCopy = taskResult.getString("content")
        assertTrue(contextCopy.contains("主上下文保护"))
        assertTrue(contextCopy.length < huge.length / 10)
        val finished = events.filterIsInstance<AgentEvent.SubagentFinished>().single()
        assertEquals(huge.length, finished.content.length)
    }

    @Test
    fun fanoutTreatsExplicitEmptyAllowedToolsAsUnfiltered() {
        val exec = subagentExecutor(toolArray("search_files", "read_file", "edit_file", "write_file"))
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
            .put("allowed_tools", JSONArray())
        val json = JSONObject(exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content)
        assertEquals("research", json.getString("mode"))
        assertEquals(1, json.getInt("succeeded"))
    }

    @Test
    fun fanoutListsFilteredNamesWhenWhitelistRemovesAllTools() {
        val exec = subagentExecutor(toolArray("search_files"))
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
            .put("allowed_tools", JSONArray().put("terminal").put("observe_screen"))
        val result = exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString()))
        assertTrue(result.content.contains("NO_ALLOWED_TOOLS"))
        assertTrue(result.content.contains("terminal"))
        assertTrue(result.content.contains("observe_screen"))
    }

    @Test
    fun fanoutAppliesDefaultGuardrailsWhenOmitted() {
        val exec = subagentExecutor(toolArray("search_files"))
        val args = JSONObject().put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
        val json = JSONObject(
            exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content,
        )
        assertEquals(1, json.getInt("succeeded"))
        assertEquals(AgentSubagentPolicy.DEFAULT_MAX_ROUNDS, json.getInt("max_rounds"))
        assertEquals(AgentSubagentPolicy.DEFAULT_FANOUT_TIMEOUT_MS, json.getInt("timeout_ms"))
    }

    @Test
    fun fanoutReportsSilentlyFilteredTools() {
        val exec = subagentExecutor(toolArray("search_files"))
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
            .put("allowed_tools", JSONArray().put("search_files").put("terminal"))
        val json = JSONObject(
            exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content,
        )
        assertEquals(1, json.getInt("succeeded"))
        val filtered = json.optJSONArray("filtered_tools")
        assertTrue(filtered != null)
        assertTrue((0 until filtered.length()).map { filtered.getString(it) }.contains("terminal"))
    }

    @Test
    fun fanoutTimeoutConvergesWithVerifyHint() {
        // provider 无视中断睡 15s：300ms 到点取消时 runSingle 不可能已提交，必走超时收敛分支。
        val hanging = stubProvider {
            try {
                Thread.sleep(15_000)
            } catch (_: InterruptedException) {
                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                    // 仍被中断则如实抛，由 runSingle 收敛；此时超时分支大概率已先提交。
                    throw UnsupportedOperationException("interrupted twice")
                }
            }
            ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
            )
        }
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = hanging,
            parentRunController = AgentRunController(),
            parentOperationId = "op-timeout",
            parentTools = toolArray("search_files"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            depth = 0,
        )
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("label", "slow").put("prompt", "task")))
            .put("timeout_ms", 300)
        val json = JSONObject(
            exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content,
        )
        assertEquals(1, json.getInt("total"))
        assertEquals(0, json.getInt("succeeded"))
        assertEquals(1, json.getInt("timed_out"))
        val r = json.getJSONArray("results").getJSONObject(0)
        assertEquals("SUBAGENT_TIMEOUT", r.getString("code"))
        assertTrue(r.getString("content").contains("核实"))
        assertTrue(r.has("verify_hint"))
        assertEquals(300, r.getInt("timeout_ms"))
    }

    @Test
    fun fanoutRejectsOverlappingWritePathsWithLabels() {
        val exec = subagentExecutor(toolArray("search_files", "read_file", "write_file", "edit_file"))
        val args = JSONObject()
            .put("mode", "code")
            .put(
                "tasks",
                JSONArray()
                    .put(
                        JSONObject().put("label", "a").put("prompt", "task a")
                            .put("write_paths", JSONArray().put("/tmp/shared")),
                    )
                    .put(
                        JSONObject().put("label", "b").put("prompt", "task b")
                            .put("write_paths", JSONArray().put("/tmp/shared/sub")),
                    ),
            )
        val result = exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString()))
        assertTrue(result.content.contains("WRITE_CONFLICT"))
        assertTrue(result.content.contains("a"))
        assertTrue(result.content.contains("b"))
    }

    @Test
    fun subagentErrorTruncationMarksContentChars() {
        val hugeMsg = "E".repeat(5_000)
        val failing = stubProvider { throw RuntimeException(hugeMsg) }
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = failing,
            parentRunController = AgentRunController(),
            parentOperationId = "op-err",
            parentTools = toolArray("search_files"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            depth = 0,
        )
        val args = JSONObject().put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
        val json = JSONObject(
            exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content,
        )
        // provider 抛异常：子代理收敛为 SUBAGENT_ERROR，且 2000 字符界截断并标记。
        val r = json.getJSONArray("results").getJSONObject(0)
        assertEquals("SUBAGENT_ERROR", r.optString("code"))
        assertTrue(r.optBoolean("content_truncated", false))
        assertEquals(hugeMsg.length, r.optInt("content_chars", -1))
    }

    @Test
    fun spawnToolDeclaresDefaultGuardrails() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val fn = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "spawn_agents" }
        val props = fn.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.getJSONObject("max_rounds").getString("description").contains("12"))
        assertTrue(props.getJSONObject("timeout_ms").getString("description").contains("180000"))
        assertTrue(fn.getString("description").contains("4"))
    }
    @Test
    fun loopMaxRoundsKeepsLastPartialAssistantText() {
        var providerCalls = 0
        val looping = stubProvider {
            providerCalls++
            val tc = JSONObject().put("id", "c$providerCalls").put("type", "function")
                .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))
            ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "阶段性结论$providerCalls")
                    .put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().put(tc)),
            )
        }
        val loop = AgentLoop(
            config = modelConfig(),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("hi")),
            tools = toolArray("get_current_context"),
            provider = looping,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{}") },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            maxRounds = 2,
        )
        val result = loop.run()
        assertTrue(result.roundLimited)
        assertTrue(result.content.contains("ROUND_LIMIT_TRUNCATED: maxRounds=2"))
        assertTrue(result.content.contains("阶段性结论2"))
    }
}
