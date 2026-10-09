package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSubagentPolicy
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.SubagentMode
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRequirementsTest {
    @Test
    fun everyRegisteredToolHasExactlyOneRequirement() {
        val tools = catalog(root = true).also { CharacterMemoryTools.appendSchemas(it) }
        assertTrue(tools.names().all { AgentToolRequirements.find(it) != null })
        assertTrue(AgentToolRequirements.toolNames.containsAll(tools.names()))
        assertEquals(tools.length(), tools.names().size)
        assertFalse(tools.toString().contains("rootRequirement"))
    }

    @Test
    fun unknownToolCannotEnterTheModelCatalog() {
        val unknown = JSONArray().put(JSONObject().put("function", JSONObject().put("name", "new_tool")))
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolRequirements.project(unknown, rootAvailable = true)
        }
    }

    @Test
    fun rootlessProjectionRemovesPrivilegedToolsAndNarrowsMixedSchemasWithoutMutatingSource() {
        val original = catalog(root = true)
        val projected = AgentToolRequirements.project(original, rootAvailable = false)
        val required = AgentToolRequirements.toolNames.filter {
            AgentToolRequirements.rootRequirement(it) == RootRequirement.REQUIRED
        }
        assertTrue(required.isNotEmpty())
        assertTrue(required.none { it in projected.names() })
        assertTrue(setOf("terminal", "file_ops", "read_image", "observe_screen", "browser_use").all {
            it in projected.names()
        })
        assertEquals("[\"user\"]", projected.properties("terminal").getJSONObject("identity").getJSONArray("enum").toString())
        assertEquals(2, original.properties("terminal").getJSONObject("identity").getJSONArray("enum").length())
        assertTrue(projected.properties("ui_action").getJSONObject("button").getString("description").contains("clipboard"))
        assertFalse(projected.toString().contains("/data/local/tmp/eta"))
        assertFalse(projected.properties("read_image").getJSONObject("path").toString().contains("Root"))
        assertEquals(listOf("context", "status", "network", "environment"),
            projected.properties("device_info").getJSONObject("operation").getJSONArray("enum").stringValues())
    }

    @Test
    fun ordinaryAuthorizationIsIndependentFromRootAndForegroundIntentsDoNotNeedAccessibility() {
        val restricted = AgentToolCapabilities(
            rootAvailable = false, accessibilityAvailable = false,
            notificationsAllowed = false, usageAllowed = false, locationAllowed = false, colorOs = false,
        )
        val names = restricted.project(catalog(root = true)).names()
        assertTrue(setOf("app_action", "terminal", "browser_use").all { it in names })
        assertTrue(setOf("observe_screen", "ui_action", "recent_notifications", "app_usage_summary", "get_current_location").none { it in names })
        assertEquals("ROOT_REQUIRED", restricted.unavailableCode("search_coloros_notes"))
        assertEquals("DEVICE_UNSUPPORTED", restricted.copy(rootAvailable = true).unavailableCode("search_coloros_notes"))
        assertEquals(null, restricted.copy(notificationsAllowed = true).unavailableCode("recent_notifications"))
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", restricted.copy(rootAvailable = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(rootAvailable = true, colorOs = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(accessibilityAvailable = true).unavailableCode("observe_screen"))
        assertEquals(null, restricted.copy(accessibilityRecoveryAvailable = true).unavailableCode("observe_screen"))
    }

    @Test
    fun legacyRootArgumentsAreDeniedEvenWhenCallingAMixedToolDirectly() {
        assertTrue(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "root"), false))
        assertTrue(AgentToolRequirements.rootDenied("press_key", JSONObject().put("button", "PASTE"), false))
        assertTrue(AgentToolRequirements.rootDenied("set_setting", JSONObject(), false))
        assertFalse(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "user"), false))
        assertFalse(AgentToolRequirements.rootDenied("set_setting", JSONObject(), true))
    }

    @Test
    fun onlyExplicitlyMarkedReadOnlyToolsJoinParallelBatches() {
        assertTrue(AgentToolRequirements.isParallelReadOnly("read_file"))
        assertTrue(AgentToolRequirements.isParallelReadOnly("search_code"))
        assertTrue(AgentToolRequirements.isParallelReadOnly("device_status"))
        assertFalse(AgentToolRequirements.isParallelReadOnly("terminal"))
        assertFalse(AgentToolRequirements.isParallelReadOnly("browser_use"))
        assertFalse(AgentToolRequirements.isParallelReadOnly("set_setting"))
        assertFalse(AgentToolRequirements.isParallelReadOnly("unknown_tool"))
    }

    @Test
    fun frameworkConnectionDoesNotGrantRootAndRootSnapshotDoesNotRequireFramework() {
        assertEquals(LsposedRequirement.OPTIONAL, AgentToolRequirements.find("search_coloros_memories")?.lsposedRequirement)
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true)
            .unavailableCode("search_coloros_memories"))
        assertEquals(null, AgentToolCapabilities(rootAvailable = true, lsposedAvailable = false, colorOs = true)
            .unavailableCode("search_coloros_memories"))
    }

    @Test
    fun fileOpsDeleteResolvesToDeletePathAndIsNotParallelReadOnly() {
        val deleteArgs = JSONObject().put("operation", "delete").put("path", "/workspace/gone.txt")
        assertEquals("delete_path", AgentToolRequirements.effectiveName("file_ops", deleteArgs))
        assertEquals(RootRequirement.PARTIAL, AgentToolRequirements.rootRequirement("file_ops", deleteArgs))
        assertFalse(AgentToolRequirements.rootDenied("file_ops", deleteArgs, false))
        // delete_path 是写原语，不参与只读并行批次。
        assertFalse(AgentToolRequirements.isParallelReadOnly("file_ops", deleteArgs))
        assertTrue(AgentToolRequirements.isParallelReadOnly("file_ops", JSONObject().put("operation", "read")))
    }

    @Test
    fun deleteCountsAsWriteForResearchSubagentsOnly() {
        val executed = mutableListOf<String>()
        val base = AgentModelClient.ToolExecutor { call ->
            executed += call.name
            AgentModelClient.ToolResult(content = "{}")
        }
        val deleteCall = AgentModelClient.ToolCall(
            id = "delete-1",
            name = "file_ops",
            argumentsJson = """{"operation":"delete","path":"/workspace/gone.txt"}""",
        )

        val rejected = AgentSubagentPolicy.guardedExecutor(base, SubagentMode.RESEARCH).execute(deleteCall)
        assertTrue(
            "research 必须把 delete 当写操作拒绝：${rejected.content}",
            rejected.content.contains("research 模式不允许写操作"),
        )
        assertTrue(executed.isEmpty())

        AgentSubagentPolicy.guardedExecutor(base, SubagentMode.CODE).execute(deleteCall)
        assertEquals(listOf("file_ops"), executed)

        // 读操作在 research 下仍放行：删除规则不外溢到只读调用。
        AgentSubagentPolicy.guardedExecutor(base, SubagentMode.RESEARCH).execute(
            AgentModelClient.ToolCall("read-1", "file_ops", """{"operation":"read","path":"a.txt"}"""),
        )
        assertEquals(listOf("file_ops", "file_ops"), executed)
    }

    private fun catalog(root: Boolean) = AgentToolCatalog.build(
        terminalTools = true, browserTools = true, deviceDirectTools = true,
        deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
        capabilities = AgentToolCapabilities.full(rootAvailable = root),
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.properties(name: String): JSONObject = (0 until length())
        .map { getJSONObject(it).getJSONObject("function") }
        .single { it.getString("name") == name }.getJSONObject("parameters").getJSONObject("properties")

    private fun JSONArray.stringValues(): List<String> = (0 until length()).map(::getString)
}
