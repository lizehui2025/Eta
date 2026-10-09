package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Built-in agent personalities available from the chat composer. */
internal enum class AgentKind(val wireValue: String) {
    ASK("ask"), WORK("work"), PLAN("plan"), BUILD("build"), GOAL("goal"), AUTO("auto");

    val isCodeAgent: Boolean get() = this in setOf(PLAN, BUILD, GOAL, AUTO)

    companion object {
        fun fromWireValue(value: String?): AgentKind = entries.firstOrNull { it.wireValue == value } ?: WORK
        fun current(mode: AgentMode = AgentMode.current()): AgentKind =
            runCatching { fromWireValue(Prefs.agentKind(mode.wireValue)) }
                .getOrDefault(if (mode == AgentMode.CODING) BUILD else WORK)
                .takeIf { it.isCodeAgent == (mode == AgentMode.CODING) }
                ?: if (mode == AgentMode.CODING) BUILD else WORK
    }
}

internal enum class InstructionReview(val wireValue: String) {
    MANUAL("manual"), AUTOMATIC("automatic"), BYPASS("bypass");

    companion object {
        fun current(): InstructionReview = runCatching {
            entries.firstOrNull { it.wireValue == Prefs.instructionReview() } ?: MANUAL
        }.getOrDefault(MANUAL)
    }
}

/** Conservative local gate used by Automatic review before dispatching a tool call. */
internal object AutomaticInstructionReview {
    private val obviouslyRiskyShellFragments = listOf(
        "rm -rf /", "mkfs", "dd if=", "format", ":(){", "shutdown", "reboot", "su -c",
    )

    fun hasHardBlock(toolName: String, argumentsJson: String): Boolean {
        val normalized = argumentsJson.lowercase()
        if (toolName in setOf("run_command", "terminal")) {
            return obviouslyRiskyShellFragments.any(normalized::contains)
        }
        if (toolName == "file_ops") {
            val operation = runCatching { JSONObject(argumentsJson).optString("operation") }.getOrDefault("")
            if (operation == "write" || operation == "edit" || operation == "delete") {
                return normalized.contains("/system/") || normalized.contains("/data/")
            }
        }
        return false
    }
}

/** Plan may inspect project/device state, but cannot mutate anything. */
internal fun projectPlanTools(tools: JSONArray): JSONArray = JSONArray().also { result ->
    for (index in 0 until tools.length()) {
        val tool = tools.optJSONObject(index) ?: continue
        val name = tool.optJSONObject("function")?.optString("name").orEmpty()
        if (name !in PLAN_READ_ONLY_TOOLS) continue
        val projected = JSONObject(tool.toString())
        val function = projected.getJSONObject("function")
        val properties = function.optJSONObject("parameters")?.optJSONObject("properties")
        when (name) {
            "app_action" -> properties?.optJSONObject("action")?.put("enum", JSONArray().put("search"))
            "device_info" -> properties?.optJSONObject("operation")?.put(
                "enum", JSONArray().put("context").put("status").put("network").put("environment").put("top_memory").put("top_storage"),
            )
            "file_ops" -> properties?.optJSONObject("operation")?.put(
                "enum", JSONArray().put("read").put("search").put("list"),
            )
            "skill" -> properties?.optJSONObject("operation")?.put(
                "enum", JSONArray().put("list").put("read").put("resource").put("curated"),
            )
            "memory" -> properties?.optJSONObject("operation")?.put("enum", JSONArray().put("get"))
        }
        result.put(projected)
    }
}

/** Ask is conversational, but may gather public evidence and read enabled Skills. */
internal fun projectAskTools(tools: JSONArray): JSONArray = JSONArray().also { result ->
    for (index in 0 until tools.length()) {
        val tool = tools.optJSONObject(index) ?: continue
        val name = tool.optJSONObject("function")?.optString("name").orEmpty()
        if (name == "web_search") {
            result.put(JSONObject(tool.toString()))
            continue
        }
        if (name != "skill") continue
        val projected = JSONObject(tool.toString())
        val props = projected.optJSONObject("function")?.optJSONObject("parameters")?.optJSONObject("properties")
        props?.optJSONObject("operation")?.put("enum", JSONArray().put("list").put("read").put("resource").put("curated"))
        result.put(projected)
    }
}

private val PLAN_READ_ONLY_TOOLS = setOf(
    "observe_screen", "web_search", "app_action", "device_info", "file_ops", "skill", "memory", "read_image",
    "read_file", "list_directory", "search_code", "get_current_context", "search_apps", "get_setting",
    "network_info", "device_status", "get_device_environment", "skills_list", "skills_read",
    "skills_read_resource", "skills_list_curated",
)
