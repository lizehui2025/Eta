package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun blockedToolsAreRejected() {
        listOf("observe_screen", "tap_element", "browser_use", "memory_write", "spawn_agents", "set_setting", "mcp_fetch").forEach {
            assertFalse("$it should be blocked", AgentSubagentPolicy.isAllowed(it))
        }
        assertTrue(AgentSubagentPolicy.isAllowed("search_files"))
        assertTrue(AgentSubagentPolicy.isAllowed("search_calendar_events"))
    }

    @Test
    fun filterToolsDropsBlockedAndSpawn() {
        val filtered = AgentSubagentPolicy.filterTools(
            toolArray("search_files", "observe_screen", "spawn_agents", "search_calendar_events", "mcp_fetch"),
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
        val ok = guarded.execute(AgentModelClient.ToolCall("2", "search_files", "{}"))
        assertTrue(called)
        assertEquals("ok", ok.content)
    }

    @Test
    fun catalogDeclaresBoundedSpawnTool() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue("spawn_agents" in names)
        assertEquals(names.size, names.toSet().size)
        val fn = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "spawn_agents" }
        val props = fn.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(1, fn.getJSONObject("parameters").getJSONArray("required").length())
        assertEquals(4, props.getJSONObject("tasks").getInt("maxItems"))
    }

    @Test
    fun nestedSpawnIsRejected() {
        val stubProvider = object : AgentProviderClient {
            override val id: String = "stub"
            override val capabilities: ProviderCapabilities = ProviderCapabilities(
                endpoint = EndpointKind.CHAT_COMPLETIONS,
                streamingText = false,
                streamingToolCalls = false,
                imageInput = false,
                toolResultImages = false,
                strictTools = false,
                parallelToolCalls = false,
            )
            override fun complete(
                request: ProviderRequest,
                runController: io.github.mangi.eta.agent.runtime.AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse = throw UnsupportedOperationException("no provider in nested test")
        }
        val exec = AgentSubagentExecutor(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                browserTools = false,
            ),
            provider = stubProvider,
            parentRunController = io.github.mangi.eta.agent.runtime.AgentRunController(),
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
}
