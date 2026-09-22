package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 子代理执行模式：research 只读搜集；code 在声明范围内编辑文件。 */
internal enum class SubagentMode(val wireName: String) {
    RESEARCH("research"),
    CODE("code");

    companion object {
        /** 空值按 research 处理；无法识别返回 null，由调用方拒绝。 */
        fun parse(raw: String?): SubagentMode? {
            val value = raw?.trim()?.lowercase().orEmpty()
            return when (value) {
                "", RESEARCH.wireName -> RESEARCH
                CODE.wireName -> CODE
                else -> null
            }
        }
    }
}

/**
 * 子代理上下文模式：pure 纯净隔离；shared 非纯净共享主窗口。
 * shared 使用主 Agent 窗口快照作为前缀上下文（只读复制），但依旧独立运行：
 * 独立 controller、独立 transcript、独立 tool loop，不回写主 messages，
 * 终态只经 fanout 汇总进入主循环。
 */
internal enum class SubagentContextMode(val wireName: String) {
    PURE("pure"),
    SHARED("shared");

    companion object {
        /** 空值按 pure 处理；无法识别返回 null，由调用方拒绝。 */
        fun parse(raw: String?): SubagentContextMode? {
            val value = raw?.trim()?.lowercase().orEmpty()
            return when (value) {
                "", PURE.wireName -> PURE
                SHARED.wireName -> SHARED
                else -> null
            }
        }
    }
}

/** 并发子代理的工具隔离策略。只允许一层，不允许子代理再 spawn。 */
internal object AgentSubagentPolicy {
    const val TOOL_NAME = "spawn_agents"

    // 默认护栏（非硬上限）：省略即按默认执行，避免一个卡死拖住整批。
    // 显式传超大值仍允许（不限上限），等价于按需放开。
    const val DEFAULT_MAX_ROUNDS = 30
    const val DEFAULT_FANOUT_TIMEOUT_MS = 300_000
    const val MAX_PARALLEL_TASKS = 4
    const val MAX_PROMPT_CHARS = 4000
    const val MAX_LABEL_CHARS = 64
    const val MAX_WRITE_PATH_CHARS = 1024

    // 注意：不再对子代理输出做任何主上下文截断。
    // 历史上的 RESEARCH/CODE_MAIN_CONTEXT_CHARS、MAX_CHANGED_FILES_IN_CONTEXT、
    // MAX_ERROR_CHARS_IN_CONTEXT 会把完整结果截成“部分结果 + 追问指引”，主代理拿到残缺
    // 输出只能重试或再派发子任务，直接浪费一整轮算力。主窗口容量由正常的
    // AgentContextSession.compact() 按实时窗口统一裁决，子代理侧一律完整回填。

    /** 写工具集合：仅在 code 模式下按任务声明的 write_paths 放行。 */
    val writeTools: Set<String> = setOf("write_file", "edit_file")

    /** 前台独占 / 敏感写操作 / 安装类工具一律禁止进入子代理（两种模式相同）。 */
    private val alwaysBlockedTools: Set<String> = setOf(
        // 前台 GUI 与观察
        "observe_screen", "tap", "tap_area", "tap_element", "long_press",
        "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
        "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
        "wait_for_text", "wait_for_package", "launch_app", "open_uri", "browser_use",
        // 记忆写入、spawn 自身与主代理任务清单
        "memory_write", "character_memory_write", "spawn_agents", "todo_write",
        // 安装类
        "skills_install_from_github",
        // 敏感写操作
        "set_setting", "set_device_state", "app_state_control", "set_clipboard",
        // shell 可执行任意写操作与常驻任务，两种模式都禁入；
        // 文件写入选写工具在 code 模式下按声明范围单独放行。
        "terminal", "run_command",
    )

    fun isAllowed(toolName: String, mode: SubagentMode = SubagentMode.RESEARCH): Boolean {
        if (toolName in alwaysBlockedTools) return false
        if (mode == SubagentMode.RESEARCH && toolName in writeTools) return false
        // v1 暂不把 MCP 透给子代理，避免 token 与外网副作用失控。
        if (toolName.startsWith("mcp_")) return false
        if (toolName == AgentConversationToolCatalog.READ_HISTORY) return false
        return true
    }

    fun guardedExecutor(
        base: AgentModelClient.ToolExecutor,
        mode: SubagentMode = SubagentMode.RESEARCH,
    ): AgentModelClient.ToolExecutor =
        AgentModelClient.ToolExecutor { call ->
            if (call.name == TOOL_NAME) {
                rejectTool("NESTED_SPAWN_NOT_ALLOWED", "子代理不可再派生子代理，本次调用已拒绝；请由主代理直接派发新的子任务")
            } else if (!isAllowed(call.name, mode)) {
                val message = if (call.name in writeTools && mode == SubagentMode.RESEARCH) {
                    "research 模式不允许写操作：${call.name}；如需编辑文件请使用 mode=code 并声明 write_paths"
                } else if (call.name == "terminal" || call.name == "run_command") {
                    "子代理禁用 shell：${call.name}；文件读写请用 read_file/search_code/list_directory（code 模式写文件用 write_file/edit_file），构建与验证请交回主代理"
                } else if (call.name == "browser_use") {
                    "子代理禁用浏览器：browser_use；请在主代理中浏览，或把需要抓取的 URL 与问题收敛后交回主代理"
                } else if (call.name in setOf(
                        "observe_screen", "tap", "tap_area", "tap_element", "long_press",
                        "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
                        "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
                        "wait_for_text", "wait_for_package", "launch_app", "open_uri",
                    )
                ) {
                    "子代理禁用前台 GUI/观察操作：${call.name}；请在主代理中执行，子代理只做只读搜集或声明范围内的文件编辑"
                } else {
                    "该工具为前台独占、写操作或安装类工具，子代理不可调用：${call.name}，请在主代理中执行"
                }
                rejectTool("EXCLUSIVE_TOOL_BUSY", message)
            } else {
                base.execute(call)
            }
        }

    /** 按 allowedTools 收窄父工具全集；requested 为 null（未提供或显式空数组）时仅按模式白名单过滤。 */
    fun filterTools(
        allTools: JSONArray,
        requested: Set<String>?,
        mode: SubagentMode = SubagentMode.RESEARCH,
    ): JSONArray {
        val result = JSONArray()
        for (i in 0 until allTools.length()) {
            val obj = allTools.optJSONObject(i) ?: continue
            val name = obj.optJSONObject("function")?.optString("name") ?: continue
            if (name == TOOL_NAME) continue
            if (!isAllowed(name, mode)) continue
            if (requested != null && name !in requested) continue
            result.put(obj)
        }
        return result
    }

    fun parseRequestedAllowedTools(
        args: JSONObject,
        mode: SubagentMode = SubagentMode.RESEARCH,
    ): Set<String>? {
        if (!args.has("allowed_tools")) return null
        val arr = args.optJSONArray("allowed_tools") ?: return null
        // 显式空数组视为未提供：不启用白名单过滤，而不是把工具集清空。
        if (arr.length() == 0) return null
        val out = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optString(i).trim()
            if (name.isNotBlank() && isAllowed(name, mode)) out.add(name)
        }
        return out
    }

    /** 不做白名单过滤的原始 allowed_tools；仅用于自动推断模式与错误信息。 */
    fun parseRawRequestedAllowedTools(args: JSONObject): Set<String>? {
        if (!args.has("allowed_tools")) return null
        val arr = args.optJSONArray("allowed_tools") ?: return null
        // 显式空数组视为未提供：仅在非空时返回名单。
        if (arr.length() == 0) return null
        val out = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optString(i).trim()
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    private fun rejectTool(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )
}
