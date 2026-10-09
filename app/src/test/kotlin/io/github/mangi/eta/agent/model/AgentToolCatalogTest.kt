package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    @Test
    fun defaultCatalogPublishesOnlyCanonicalDomainTools() {
        val names = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            memoryTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
        ).toolNames()
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.size <= 18)
        assertTrue(names.containsAll(setOf(
            "observe_screen", "ui_action", "app_action", "device_info", "device_control",
            "browser_use", "terminal", "file_ops", "read_image", "skill", "skill_github",
            "memory", "ask_user", "todo_write", "spawn_agents",
        )))
        assertFalse("tap_element" in names)
        assertFalse("read_file" in names)
        assertFalse("skills_list" in names)
    }

    @Test
    fun planProjectsReadOperationsOnlyFromCanonicalTools() {
        val all = AgentToolCatalog.build(true, true, memoryTools = true)
        val plan = projectPlanTools(all).toolNames().toSet()
        assertTrue(plan.containsAll(setOf("observe_screen", "app_action", "device_info", "file_ops", "skill", "memory", "read_image")))
        assertFalse("ui_action" in plan)
        assertFalse("terminal" in plan)
        assertFalse("device_control" in plan)
        assertEquals(listOf("read", "search", "list"), projectPlanTools(all).function("file_ops")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("operation")
            .getJSONArray("enum").stringValues())
        assertEquals(listOf("search"), projectPlanTools(all).function("app_action")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("action")
            .getJSONArray("enum").stringValues())
    }

    @Test
    fun featureFlagsProduceExactUniqueToolUnions() {
        val base = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).toolNames()
        val baseTools = base.toSet()
        val variants = listOf(
            ToolVariant(terminalTools = false, browserTools = false, addedTools = emptySet()),
            ToolVariant(terminalTools = false, browserTools = true, addedTools = BROWSER_TOOLS),
            ToolVariant(terminalTools = true, browserTools = false, addedTools = TERMINAL_TOOLS),
            ToolVariant(
                terminalTools = true,
                browserTools = true,
                addedTools = BROWSER_TOOLS + TERMINAL_TOOLS,
            ),
        )

        assertEquals(base.size, baseTools.size)
        assertTrue(
            base.containsAll(
                setOf(
                    "observe_screen", "ui_action", "app_action", "device_info", "device_control",
                    "clipboard", "skill", "ask_user", "todo_write", "spawn_agents",
                ),
            ),
        )
        assertFalse("browser_use" in base)
        assertFalse("terminal" in base)

        variants.forEach { variant ->
            val names = AgentToolCatalog.build(
                terminalTools = variant.terminalTools,
                browserTools = variant.browserTools,
            ).toolNames()
            val label = "terminal=${variant.terminalTools}, browser=${variant.browserTools}"

            assertEquals("$label must not contain duplicate tools", names.size, names.toSet().size)
            assertEquals("$label must be an exact union", baseTools + variant.addedTools, names.toSet())
        }
    }

    @Test
    fun browserToolAllowsArbitraryUrlsAndFormSubmission() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = true,
        ).function("browser_use")
        val properties = function
            .getJSONObject("parameters")
            .getJSONObject("properties")

        assertEquals("string", properties.getJSONObject("url").getString("type"))
        assertEquals("boolean", properties.getJSONObject("submit").getString("type"))
        assertFalse(properties.getJSONObject("url").getString("description").contains("HTTPS"))
        assertFalse(function.getString("description").contains("拦截"))
    }

    @Test
    fun uiActionDeclaresObservationPairingAndAllSupportedActions() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val function = tools.function("ui_action")
        val parameters = function.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")
        assertEquals(
            listOf("tap", "long_press", "swipe", "scroll", "input", "clear", "key", "wait", "open_system_panel"),
            properties.getJSONObject("action").getJSONArray("enum").stringValues(),
        )
        assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
        assertTrue(function.getString("description").contains("observe_screen"))
    }

    @Test
    fun canonicalObjectSchemasRejectUnknownFields() {
        val tools = AgentToolCatalog.build(terminalTools = true, browserTools = true)
        tools.toolNames().forEach { name ->
            val parameters = tools.function(name).getJSONObject("parameters")
            if (parameters.getString("type") == "object") {
                assertFalse("$name must be a closed canonical contract", parameters.optBoolean("additionalProperties", true))
            }
        }
    }

    @Test
    fun screenObservationDefaultsToTreeAndDescribesVisualEscalation() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).function("observe_screen")
        val properties = function.getJSONObject("parameters").getJSONObject("properties")

        assertFalse(properties.getJSONObject("include_screenshot").getBoolean("default"))
        assertTrue(properties.getJSONObject("include_ui_tree").getBoolean("default"))
        assertEquals(60, properties.getJSONObject("max_nodes").getInt("default"))
        assertEquals(1, properties.getJSONObject("max_nodes").getInt("minimum"))
        assertEquals(120, properties.getJSONObject("max_nodes").getInt("maximum"))
        assertTrue(function.getString("description").contains("默认只返回"))
        assertTrue(function.getString("description").contains("include_screenshot=true"))
        assertTrue(function.getString("description").contains("保持 include_ui_tree=true"))
        assertTrue(function.getString("description").contains("禁止把新截图与旧节点混用"))
    }

    @Test
    fun scrollDirectionsUseContentBrowsingSemantics() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val expectedDirections = listOf("up", "down", "left", "right")

        val function = tools.function("ui_action")
        val directions = function.getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("direction").getJSONArray("enum").stringValues()
        assertEquals(expectedDirections, directions)
    }

    @Test
    fun uiActionTextOperationsDescribeObservationPairingWithoutRequiringItForFocusedInput() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val parameters = tools.function("ui_action").getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")
        assertEquals("integer", properties.getJSONObject("index").getString("type"))
        assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
        assertEquals("string", properties.getJSONObject("text").getString("type"))
        assertFalse("observation_id is optional for focused input", "observation_id" in parameters.requiredNames())
    }

    @Test
    fun textToolsDeclareTheSameLimitsAsRuntime() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        assertEquals(20_000, tools.maxTextLength("ui_action"))
        assertEquals(20_000, tools.maxTextLength("clipboard"))
    }

    @Test
    fun terminalSeparatesAndroidAndLinuxEnvironments() {
        val terminal = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
        ).function("terminal")
        val environment = terminal
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("environment")

        assertEquals(listOf("android", "linux"), environment.getJSONArray("enum").stringValues())
        assertTrue(terminal.getString("description").contains("environment=android"))
        assertTrue(terminal.getString("description").contains("environment=linux"))
    }

    @Test
    fun memoryToolsAreExposedOnlyWhenEnabledAndDeclareBoundedOperations() {
        val disabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = false,
        ).toolNames()
        assertFalse("memory" in disabled)

        val enabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = true,
        )
        assertTrue("memory" in enabled.toolNames())
        val write = enabled.function("memory")
        val properties = write.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(3_500, properties.getJSONObject("content").getInt("maxLength"))
        assertEquals(
            listOf("replace_range", "append", "clear"),
            properties.getJSONObject("mode").getJSONArray("enum").stringValues(),
        )
    }

    @Test
    fun memoryWriteToolIsHiddenWhenMemoryIsReadOnly() {
        // 编码模式（以及角色会话）传入 memoryWritable=false：只保留读取，不暴露写入。
        val readOnly = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = true,
            memoryWritable = false,
        ).toolNames()
        assertTrue("memory" in readOnly)
        assertEquals(listOf("get"), AgentToolCatalog.build(false, false, memoryTools = true, memoryWritable = false)
            .function("memory").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("operation").getJSONArray("enum").stringValues())

        val writable = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = true,
            memoryWritable = true,
        ).toolNames()
        assertTrue("memory" in writable)
        assertEquals(listOf("get", "write"), AgentToolCatalog.build(false, false, memoryTools = true)
            .function("memory").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("operation").getJSONArray("enum").stringValues())
    }

    @Test
    fun planToolDeclaresBoundedTodoListReplacement() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).function("todo_write")
        val parameters = function.getJSONObject("parameters")
        val todos = parameters.getJSONObject("properties").getJSONObject("todos")

        assertEquals(listOf("todos"), parameters.getJSONArray("required").stringValues())
        assertEquals(1, todos.getInt("minItems"))
        assertEquals(50, todos.getInt("maxItems"))
        assertTrue(function.getString("description").contains("整体替换"))
        assertEquals(
            listOf("pending", "in_progress", "completed"),
            todos.getJSONObject("items").getJSONObject("properties")
                .getJSONObject("status").getJSONArray("enum").stringValues(),
        )
    }

    @Test
    fun spawnAgentsDeclaresNoQuotaCaps() {
        val function = AgentToolCatalog.build(terminalTools = false, browserTools = false).function("spawn_agents")
        val properties = function.getJSONObject("parameters").getJSONObject("properties")

        // 子代理不设任何开销上限：任务数/工具数不设 maxItems，也不再有轮数与超时参数。
        assertFalse(properties.getJSONObject("tasks").has("maxItems"))
        assertFalse(properties.getJSONObject("allowed_tools").has("maxItems"))
        assertFalse("max_rounds 参数应已移除", properties.has("max_rounds"))
        assertFalse("timeout_ms 参数应已移除", properties.has("timeout_ms"))
    }

    @Test
    fun directoryToolDeclaresPaginationFilteringAndRecursion() {
        val function = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
        ).function("file_ops")
        val properties = function
            .getJSONObject("parameters")
            .getJSONObject("properties")

        assertTrue(properties.has("offset"))
        assertTrue(properties.has("glob"))
        assertTrue(properties.has("recursive"))
        assertTrue(function.getString("description").contains("读取、写入、编辑、搜索"))
        // delete 是有意新增的 file_ops 操作（校验/映射/硬拦/子代理拦截已全链路登记）。
        assertEquals(listOf("read", "write", "edit", "search", "list", "delete"),
            properties.getJSONObject("operation").getJSONArray("enum").stringValues())
    }

    @Test
    fun codeSearchDeclaresAbsolutePathsAndPatternErrors() {
        val function = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
        ).function("file_ops")

        val properties = function.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(properties.has("pattern"))
        assertTrue(properties.has("query"))
        assertTrue(properties.has("path"))
    }

    private fun JSONArray.toolNames(): List<String> =
        (0 until length()).map { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { index -> getJSONObject(index).getJSONObject("function") }
            .first { function -> function.getString("name") == name }

    private fun JSONArray.functionOrNull(name: String): JSONObject? =
        (0 until length()).asSequence().map { getJSONObject(it).getJSONObject("function") }
            .firstOrNull { it.getString("name") == name }

    private fun JSONObject.requiredNames(): Set<String> =
        optJSONArray("required")?.stringValues()?.toSet().orEmpty()

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map(::getString)

    private fun JSONArray.maxTextLength(name: String): Int =
        function(name)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("text")
            .getInt("maxLength")

    private data class ToolVariant(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val addedTools: Set<String>,
    )

    private companion object {
        val BROWSER_TOOLS = setOf("browser_use")
        val TERMINAL_TOOLS = setOf(
            "read_image",
            "terminal",
            "file_ops",
        )
    }
}
