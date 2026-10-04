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

    // 子代理不设轮数上限与整体超时：长任务是正常的，把子代理在半途截断只会
    // 让主代理拿到残缺结果、再派一次，反而更贵。子代理只被两件事终止——父运行取消，
    // 或它自己自然结束。唯一保留的并发护栏是同时运行的子代理数量上限。
    const val MAX_PARALLEL_TASKS = 4
    const val MAX_PROMPT_CHARS = 4000
    const val MAX_LABEL_CHARS = 64
    const val MAX_WRITE_PATH_CHARS = 1024

    // 注意：不再对子代理输出做任何主上下文截断。
    // 历史上的 RESEARCH/CODE_MAIN_CONTEXT_CHARS、MAX_CHANGED_FILES_IN_CONTEXT、
    // MAX_ERROR_CHARS_IN_CONTEXT 会把完整结果截成“部分结果 + 追问指引”，主代理拿到残缺
    // 输出只能重试或再派发子任务，直接浪费一整轮算力。主窗口容量由正常的
    // AgentContextSession.compact() 按实时窗口统一裁决，子代理侧一律完整回填。

    /**
     * 上下文污染特征的任务分配：主上下文只保留决策与摘要，批量搜集一律走纯净子代理。
     *
     * pureOffloadableTools 是只读、高体量、适合并行的搜集工具：单个结果就可能很大，
     * 在主循环里直接循环调用会把文件内容、历史记录、个人数据列表等低信号密度文本
     * 全部压进主窗口，挤占后续推理。符合以下任一情形时，主代理必须用 spawn_agents
     * 以 pure + research 扇出，而不是自己逐个调用：
     * - 需要 2 次以上文件/代码读取（read_file/search_code/list_directory 组合）；
     * - 需要跨 2 个以上个人数据源取样（短信/通话/联系人/日历/相册/便签/录音/系统记忆等）；
     * - 开放式检索（先定位再细读、不确定哪份文件/哪条记录是答案）。
     * 子代理在隔离窗口内消化原文，只把蒸馏后的事实摘要回填主上下文。
     *
     * mainOnlyBoundedTools 是必须留在主代理、且必须单次有界调用的工具：
     * 前台 GUI/观察、离屏浏览器、shell、MCP 外部工具、图片与完整历史。
     * 它们或独占前台/共享浏览器状态无法安全并行，或携带 token/外部副作用，
     * 因此不进纯净子代理。主代理调用时只做单次最小探针（小 limit、小 max_bytes、
     * 小 max_chars、不递归、不翻页追全量），不循环、不追全量；需要深挖时把已拿到的
     * 最小证据拆成新的纯净搜集子任务，而不是在主循环里放大原始输出。
     */
    val pureOffloadableTools: Set<String> = setOf(
        "device_info", "file_ops", "skill", "memory", "read_image",
        "read_file", "search_code", "list_directory",
        "search_files", "search_downloads",
        "search_media", "search_audio", "search_recordings",
        "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
        "search_coloros_notes", "search_coloros_recordings", "search_recording_summaries",
        "search_coloros_memories", "search_saved_places", "search_personal_orders",
        "search_qq_chat_images", "search_wechat_chat_images",
        "search_notification_history", "recent_notifications", "recent_app_activity", "app_usage_summary",
        "search_clipboard_history", "get_logcat", "get_health_summary",
        "list_alarms", "list_active_timers", "get_device_environment",
        "device_status", "network_info", "top_memory_apps", "top_storage_apps",
        "memory_get", "skills_list", "skills_read", "skills_read_resource",
        "skills_list_curated", "skills_inspect_github",
        "search_apps", "get_current_context",
    )

    private val mainOnlyBoundedTools: Set<String> = setOf(
        "ui_action", "app_action", "device_control", "clipboard", "skill_github",
        "browser_use", "terminal", "run_command",
        "observe_screen",
        "conversation_history",
        "tap", "tap_area", "tap_element", "long_press", "long_press_element",
        "swipe", "scroll", "scroll_element",
        "input_text", "replace_text", "clear_text", "paste_text", "press_key",
        "open_system_panel", "wait_for_text", "wait_for_package", "launch_app", "open_uri",
    )

    fun isPureOffloadable(toolName: String): Boolean = toolName in pureOffloadableTools

    fun isMainOnlyBounded(toolName: String): Boolean {
        if (toolName.startsWith("mcp_")) return true
        return toolName in mainOnlyBoundedTools
    }

    /** 写工具集合：仅在 code 模式下按任务声明的 write_paths 放行。 */
    val writeTools: Set<String> = setOf("write_file", "edit_file", "file_ops", "memory")

    /** 前台独占 / 敏感写操作 / 安装类工具一律禁止进入子代理（两种模式相同）。 */
    private val alwaysBlockedTools: Set<String> = setOf(
        // 前台 GUI 与观察
        "observe_screen", "tap", "tap_area", "tap_element", "long_press",
        "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
        "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
        "wait_for_text", "wait_for_package", "launch_app", "open_uri", "browser_use",
        // 记忆写入、spawn 自身与主代理任务清单
        "memory_write", "character_memory_write", "spawn_agents", "todo_write",
        // Interrupting the user backwards: subagents are a read-only isolated fan-out and must not
        // stop to wait for an answer
        "ask_user",
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
        // Aggregated file_ops/memory stay visible so research agents can use their read
        // operations; guardedExecutor rejects write/edit operations after inspecting args.
        if (mode == SubagentMode.RESEARCH && toolName in writeTools &&
            toolName !in setOf("file_ops", "memory")
        ) return false
        // v1 暂不把 MCP 透给子代理，避免 token 与外网副作用失控。
        if (toolName.startsWith("mcp_")) return false
        if (toolName == AgentConversationToolCatalog.READ_HISTORY) return false
        return true
    }

    private fun isWriteCall(call: AgentModelClient.ToolCall): Boolean {
        if (call.name == "file_ops") return call.parsedArgsOrNull()?.optString("operation") in setOf("write", "edit")
        if (call.name == "memory") return call.parsedArgsOrNull()?.optString("operation") == "write"
        return call.name in setOf("write_file", "edit_file", "memory_write")
    }

    fun guardedExecutor(
        base: AgentModelClient.ToolExecutor,
        mode: SubagentMode = SubagentMode.RESEARCH,
    ): AgentModelClient.ToolExecutor =
        AgentModelClient.ToolExecutor { call ->
            if (call.name == TOOL_NAME) {
                rejectTool("NESTED_SPAWN_NOT_ALLOWED", "子代理不可再派生子代理，本次调用已拒绝；请由主代理直接派发新的子任务")
            } else if ((mode == SubagentMode.RESEARCH && isWriteCall(call)) || !isAllowed(call.name, mode)) {
                val message = if (isWriteCall(call) && mode == SubagentMode.RESEARCH) {
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
                .put("retry_hint", AgentToolRetryHints.forCode(code))
                .put("retry_hint_text", AgentToolRetryHints.instruction(code))
                .toString(),
        )
}
