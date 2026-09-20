package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 并发子代理 v1 的工具隔离与限额策略。只允许一层，不允许子代理再 spawn。 */
internal object AgentSubagentPolicy {
    const val TOOL_NAME = "spawn_agents"
    const val MAX_TASKS_PER_CALL = 4
    const val MAX_PARALLEL_SUBAGENTS = 3
    const val MAX_PROMPT_CHARS = 4000
    const val MAX_LABEL_CHARS = 64
    const val MAX_ROUNDS = 8
    const val DEFAULT_MAX_ROUNDS = 8
    const val DEFAULT_TIMEOUT_MS = 120_000
    const val MAX_TIMEOUT_MS = 180_000
    const val MIN_TIMEOUT_MS = 10_000
    const val MAX_SUB_OUTPUT_CHARS = 4000

    /** 前台独占 / 写操作 / 安装类工具一律禁止进入子代理。 */
    val blockedTools: Set<String> = setOf(
        // 前台 GUI 与观察
        "observe_screen", "tap", "tap_area", "tap_element", "long_press",
        "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
        "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
        "wait_for_text", "wait_for_package", "launch_app", "open_uri", "browser_use",
        // 记忆写入与 spawn 自身
        "memory_write", "character_memory_write",
        "spawn_agents",
        // 安装类
        "skills_install_from_github",
        // 敏感写操作
        "set_setting", "set_device_state", "app_state_control",
        "set_clipboard",
    )

    fun isAllowed(toolName: String): Boolean {
        if (toolName in blockedTools) return false
        // v1 暂不把 MCP 透给子代理，避免 token 与外网副作用失控。
        if (toolName.startsWith("mcp_")) return false
        if (toolName == AgentConversationToolCatalog.READ_HISTORY) return false
        return true
    }

    fun guardedExecutor(base: AgentModelClient.ToolExecutor): AgentModelClient.ToolExecutor =
        AgentModelClient.ToolExecutor { call ->
            if (!isAllowed(call.name)) {
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "EXCLUSIVE_TOOL_BUSY")
                        .put("message", "该工具为前台独占或写操作，子代理不可调用：${call.name}，请在主代理中执行")
                        .toString(),
                )
            } else {
                base.execute(call)
            }
        }

    /** 按 allowedTools（如为空则按默认允许集）过滤父工具全集。 */
    fun filterTools(allTools: JSONArray, requested: Set<String>?): JSONArray {
        val result = JSONArray()
        for (i in 0 until allTools.length()) {
            val obj = allTools.optJSONObject(i) ?: continue
            val name = obj.optJSONObject("function")?.optString("name") ?: continue
            if (name == TOOL_NAME) continue
            if (!isAllowed(name)) continue
            if (requested != null && name !in requested) continue
            result.put(obj)
        }
        return result
    }

    fun parseRequestedAllowedTools(args: JSONObject): Set<String>? {
        if (!args.has("allowed_tools")) return null
        val arr = args.optJSONArray("allowed_tools") ?: return null
        if (arr.length() == 0) return emptySet()
        val out = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optString(i).trim()
            if (name.isNotBlank() && isAllowed(name)) out.add(name)
        }
        return out
    }
}
