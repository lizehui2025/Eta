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
        // 子代理不设任何开销上限：schema 不声明任务数/工具数，也不再提供轮数与超时参数。
        assertFalse("tasks 不应设数量上限", props.getJSONObject("tasks").has("maxItems"))
        assertEquals(1, props.getJSONObject("tasks").getInt("minItems"))
        assertFalse("allowed_tools 不应设数量上限", props.getJSONObject("allowed_tools").has("maxItems"))
        assertFalse("max_rounds 参数应已移除", props.has("max_rounds"))
        assertFalse("timeout_ms 参数应已移除", props.has("timeout_ms"))
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
    fun loopRunsUntilProviderStopsWithoutRoundQuota() {
        // 子代理不再有轮数上限：循环只由模型自然结束（或取消）终止。
        var providerCalls = 0
        val finishing = stubProvider {
            providerCalls++
            if (providerCalls < 5) {
                val tc = JSONObject().put("id", "c$providerCalls").put("type", "function")
                    .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))
                ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(tc)),
                )
            } else {
                ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "完成").put("finish_reason", "stop"),
                )
            }
        }
        val loop = AgentLoop(
            config = modelConfig(),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("hi")),
            tools = toolArray("get_current_context"),
            provider = finishing,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{}") },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
        )
        val result = loop.run()
        assertEquals("完成", result.content)
        assertEquals(5, providerCalls)
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
        assertEquals(0, json.optInt("interrupted", -1))
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
    fun legacyRoundAndTimeoutArgumentsAreIgnored() {
        // 旧客户端仍可能传已移除的 max_rounds / timeout_ms：不报错、也不生效，任务照常跑完。
        val exec = subagentExecutor(toolArray("search_files"))
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
            .put("max_rounds", 500)
            .put("timeout_ms", 3_600_000)
        val result = exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString()))
        val json = JSONObject(result.content)
        assertEquals(1, json.getInt("succeeded"))
        assertEquals(0, json.optInt("interrupted", -1))
        assertFalse("汇总不再回显轮数", json.has("max_rounds"))
        assertFalse("汇总不再回显超时", json.has("timeout_ms"))
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
    fun oversizedSubagentOutputIsPassedThroughWholeWithoutTruncation() {
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
        assertFalse(taskResult.optBoolean("content_truncated", false))
        assertEquals(huge, taskResult.getString("content"))
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
    fun fanoutSummaryOmitsRemovedQuotaFields() {
        val exec = subagentExecutor(toolArray("search_files"))
        val args = JSONObject().put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
        val json = JSONObject(
            exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content,
        )
        assertEquals(1, json.getInt("succeeded"))
        assertFalse("不再回显轮数上限", json.has("max_rounds"))
        assertFalse("不再回显整体超时", json.has("timeout_ms"))
        assertFalse("不再回显超时计数", json.has("timed_out"))
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
    fun subagentRunsFarBeyondTheOldRoundQuota() {
        // 子代理不再有轮数上限：40 轮工具往返（旧默认只有 30 轮）必须能跑完并正常收尾。
        var providerCalls = 0
        val longRunning = stubProvider {
            providerCalls++
            if (providerCalls <= 40) {
                val tc = JSONObject().put("id", "c$providerCalls").put("type", "function")
                    .put("function", JSONObject().put("name", "search_files").put("arguments", "{}"))
                ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(tc)),
                )
            } else {
                ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "长任务完成").put("finish_reason", "stop"),
                )
            }
        }
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = longRunning,
            parentRunController = AgentRunController(),
            parentOperationId = "op-long",
            parentTools = toolArray("search_files"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{\"ok\":true}") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            depth = 0,
        )
        val args = JSONObject().put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
        val json = JSONObject(
            exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content,
        )
        assertEquals(1, json.getInt("succeeded"))
        assertEquals(0, json.optInt("interrupted", -1))
        assertEquals(41, providerCalls)
        val r = json.getJSONArray("results").getJSONObject(0)
        assertFalse("不应再有轮数触顶标记", r.has("code"))
        assertEquals("长任务完成", r.getString("content"))
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
    fun subagentErrorIsPassedThroughWholeWithoutTruncation() {
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
        // provider 抛异常：子代理收敛为 SUBAGENT_ERROR，异常全文完整回填不截断。
        val r = json.getJSONArray("results").getJSONObject(0)
        assertEquals("SUBAGENT_ERROR", r.optString("code"))
        assertFalse(r.optBoolean("content_truncated", false))
        assertTrue(r.getString("content").contains(hugeMsg.take(100)))
    }

    @Test
    fun spawnToolDeclaresParallelCapButNoQuotaArguments() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val fn = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "spawn_agents" }
        val props = fn.getJSONObject("parameters").getJSONObject("properties")
        assertFalse("轮数参数应已移除", props.has("max_rounds"))
        assertFalse("超时参数应已移除", props.has("timeout_ms"))
        assertTrue(props.has("context_mode"))
        assertTrue(fn.getString("description").contains("4"))
        assertTrue(
            "描述必须说明不设超时且长时间运行是正常的",
            fn.getString("description").contains("不设轮数与整体超时") &&
                fn.getString("description").contains("长时间运行"),
        )
    }
    @Test
    fun loopKeepsRunningUntilProviderStopsOnItsOwn() {
        // 不再有轮数护栏：循环只在模型给出终止原因（或取消）时结束。
        var providerCalls = 0
        val finishing = stubProvider {
            providerCalls++
            if (providerCalls < 3) {
                val tc = JSONObject().put("id", "c$providerCalls").put("type", "function")
                    .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))
                ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "阶段性结论$providerCalls")
                        .put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(tc)),
                )
            } else {
                ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "最终结论").put("finish_reason", "stop"),
                )
            }
        }
        val loop = AgentLoop(
            config = modelConfig(),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("hi")),
            tools = toolArray("get_current_context"),
            provider = finishing,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{}") },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
        )
        val result = loop.run()
        assertEquals("最终结论", result.content)
        assertEquals(3, providerCalls)
    }

    @Test
    fun contextModeParsingDefaultsToPureAndRejectsUnknown() {
        assertEquals(SubagentContextMode.PURE, SubagentContextMode.parse(null))
        assertEquals(SubagentContextMode.PURE, SubagentContextMode.parse(""))
        assertEquals(SubagentContextMode.SHARED, SubagentContextMode.parse("SHARED"))
        assertEquals(null, SubagentContextMode.parse("full"))
    }

    @Test
    fun sharedContextModeSeesParentWindowButRunsIndependently() {
        var sawParent = false
        val parentMessages = JSONArray()
            .put(AgentConversationCodec.userTextMessage("主窗口关键背景：项目根在 /tmp/proj"))
        val sharedProvider = stubProvider { request ->
            val dump = request.messages.toString()
            if (dump.contains("主窗口关键背景")) sawParent = true
            ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "shared-done").put("finish_reason", "stop"),
            )
        }
        val events = java.util.Collections.synchronizedList(mutableListOf<AgentEvent>())
        val exec = AgentSubagentExecutor(
            config = modelConfig(),
            provider = sharedProvider,
            parentRunController = AgentRunController(),
            parentOperationId = "op-shared",
            parentTools = toolArray("search_files"),
            systemMessages = JSONArray(),
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            traceFormatter = AgentTraceFormatter(),
            onEvent = events::add,
            depth = 0,
            parentMessagesProvider = { parentMessages },
            parentSystemCount = 0,
        )
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("prompt", "复述背景")))
            .put("context_mode", "shared")
        val json = JSONObject(exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString())).content)
        assertEquals("shared", json.getString("context_mode"))
        assertEquals(1, json.getInt("succeeded"))
        assertTrue(sawParent)
        // 独立运行：主 messages 未被回写，只多出 fanout 汇总由主循环处理。
        assertEquals(1, parentMessages.length())
    }

    @Test
    fun pollutionAllocationKeepsBulkReadsInPureSubagents() {
        // 高污染只读搜集：纯净子代理可并行消化，不进主窗口。
        listOf(
            "read_file", "search_code", "list_directory",
            "search_files", "search_messages", "search_contacts", "search_calendar_events",
            "search_media", "search_coloros_notes", "search_notification_history",
            "get_logcat", "memory_get", "skills_read", "read_image",
        ).forEach {
            assertTrue("$it should be pure-offloadable", AgentSubagentPolicy.isPureOffloadable(it))
            assertTrue("$it should stay allowed in research", AgentSubagentPolicy.isAllowed(it))
        }
        // 主代理单次有界：前台/浏览器/shell/MCP/图片与完整历史不进纯净子代理。
        listOf(
            "browser_use", "terminal", "run_command",
            "observe_screen", "tap_element", "conversation_history",
            "mcp_abc123_tool_deadbeef",
        ).forEach {
            assertTrue("$it should be main-only bounded", AgentSubagentPolicy.isMainOnlyBounded(it))
            assertFalse("$it should stay blocked in subagents", AgentSubagentPolicy.isAllowed(it))
        }
        assertFalse(AgentSubagentPolicy.isPureOffloadable("browser_use"))
        assertFalse(AgentSubagentPolicy.isMainOnlyBounded("search_code"))
    }

    @Test
    fun invalidContextModeIsRejected() {

        val exec = subagentExecutor(toolArray("search_files"))
        val args = JSONObject()
            .put("tasks", JSONArray().put(JSONObject().put("prompt", "task")))
            .put("context_mode", "full")
        val result = exec.fanout(1, AgentModelClient.ToolCall("c1", "spawn_agents", args.toString()))
        assertTrue(result.content.contains("INVALID_ARGUMENT"))
    }
}
