package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfileTest {
    @Test
    fun workAndCodeProfilesStayInTheirOwnGroups() {
        assertEquals(listOf(AgentKind.ASK, AgentKind.WORK), AgentKind.entries.filterNot { it.isCodeAgent })
        assertEquals(
            listOf(AgentKind.PLAN, AgentKind.BUILD, AgentKind.GOAL, AgentKind.AUTO),
            AgentKind.entries.filter { it.isCodeAgent },
        )
    }

    @Test
    fun planToolProjectionKeepsOnlyReadOperations() {
        val tools = JSONArray()
            .put(tool("read_file"))
            .put(tool("search_code"))
            .put(tool("observe_screen"))
            .put(tool("write_file"))
            .put(tool("run_command"))
        val projected = projectPlanTools(tools)
        assertEquals(listOf("read_file", "search_code", "observe_screen"), (0 until projected.length()).map {
            projected.getJSONObject(it).getJSONObject("function").getString("name")
        })
    }

    @Test
    fun automaticReviewHardBlocksDestructiveShellCommands() {
        assertTrue(AutomaticInstructionReview.hasHardBlock("run_command", "{\"command\":\"rm -rf /\"}"))
        assertFalse(AutomaticInstructionReview.hasHardBlock("run_command", "{\"command\":\"./gradlew test\"}"))
    }

    private fun tool(name: String) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name))
}
