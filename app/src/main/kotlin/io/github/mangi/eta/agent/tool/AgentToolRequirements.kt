package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION }
internal enum class ToolConcurrency { SERIAL, PARALLEL_READ_ONLY }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val colorOs: Boolean = false,
    val concurrency: ToolConcurrency = ToolConcurrency.SERIAL,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布。 */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(root: RootRequirement, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root)) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            RootRequirement.NONE,
            "observe_screen", "ui_action", "app_action", "device_info", "device_control",
            "clipboard", "skill", "skill_github", "memory", "web_search", "browser_use", "ask_user",
            "get_current_context", "search_apps", "launch_app", "open_uri",
            "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "media_control", "set_volume",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "character_memory_get", "character_memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github", "spawn_agents", "todo_write",
            // Only talks to the user and touches no device capability, so it needs no permission.
        )
        register(
            RootRequirement.PARTIAL,
            "file_ops",
            "press_key", "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal", "run_command", "read_file",
            "write_file", "edit_file", "delete_path", "search_code", "list_directory", "read_image",
        )
        register(
            RootRequirement.REQUIRED,
            "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat", "set_setting", "set_device_state", "app_state_control",
            "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads", "search_coloros_notes", "search_coloros_recordings",
            "search_recording_summaries", "search_coloros_memories", "search_saved_places",
            "search_qq_chat_images", "search_wechat_chat_images",
        )
        listOf(
            "observe_screen", "ui_action",
            "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
        listOf(
            "search_coloros_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_coloros_memories", "search_saved_places",
        ).forEach { name -> put(name, getValue(name).copy(colorOs = true)) }
        // 系统记忆优先使用 Hook 桥接，框架失联时仍有独立的 Root 快照来源。
        listOf("search_coloros_memories", "search_saved_places", "search_personal_orders").forEach { name ->
            put(name, getValue(name).copy(lsposedRequirement = LsposedRequirement.OPTIONAL))
        }
        // 只放行明确无副作用、且实现层没有共享 GUI/终端会话状态的只读工具。
        // 未登记工具默认串行，避免新增工具在不知情的情况下被并行调度。
        listOf(
            "device_info", "file_ops", "skill", "memory", "web_search",
            "read_file", "list_directory", "search_code",
            "get_current_context", "device_status", "network_info",
            "memory_get", "skills_list", "skills_read", "skills_read_resource",
            "search_files", "search_media", "search_audio", "search_recordings",
            "search_calendar_events", "search_contacts", "search_call_history",
            "search_messages", "search_downloads", "search_notification_history",
            "recent_notifications", "recent_app_activity", "app_usage_summary",
            "get_setting", "get_current_location", "get_device_environment",
        ).forEach { name ->
            put(name, getValue(name).copy(concurrency = ToolConcurrency.PARALLEL_READ_ONLY))
        }
        put("ui_action", getValue("ui_action").copy(accessibility = true))
        put("observe_screen", getValue("observe_screen").copy(accessibility = true))
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name]

    fun rootRequirement(name: String, arguments: JSONObject = JSONObject()): RootRequirement =
        requireNotNull(find(effectiveName(name, arguments))) { "Missing tool requirements: $name" }.rootRequirement

    fun requiresAccessibility(name: String, arguments: JSONObject = JSONObject()): Boolean =
        find(effectiveName(name, arguments))?.accessibility == true

    fun isParallelReadOnly(name: String, arguments: JSONObject = JSONObject()): Boolean =
        find(effectiveName(name, arguments))?.concurrency == ToolConcurrency.PARALLEL_READ_ONLY

    /** Resolves a compact operation to the legacy primitive used by the executor and policy gates. */
    fun effectiveName(name: String, arguments: JSONObject = JSONObject()): String = when (name) {
        "ui_action" -> when (arguments.optString("action").lowercase()) {
            "tap" -> if (arguments.has("index")) "tap_element" else "tap"
            "long_press" -> if (arguments.has("index")) "long_press_element" else "long_press"
            "swipe" -> "swipe"
            "scroll" -> if (arguments.has("index")) "scroll_element" else "scroll"
            "input" -> "input_text"
            "clear" -> "clear_text"
            "key" -> "press_key"
            "wait" -> when (arguments.optString("condition", "duration")) {
                "text" -> "wait_for_text"
                "package" -> "wait_for_package"
                else -> "wait"
            }
            "open_system_panel" -> "open_system_panel"
            else -> name
        }
        "app_action" -> when (arguments.optString("action")) {
            "search" -> "search_apps"
            "launch" -> "launch_app"
            "open_uri" -> "open_uri"
            else -> name
        }
        "device_info" -> when (arguments.optString("operation")) {
            "context" -> "get_current_context"
            "status" -> "device_status"
            "network" -> "network_info"
            "environment" -> "get_device_environment"
            "top_memory" -> "top_memory_apps"
            "top_storage" -> "top_storage_apps"
            else -> name
        }
        "device_control" -> when (arguments.optString("operation")) {
            "alarm" -> "set_alarm"
            "timer" -> "set_timer"
            "media" -> "media_control"
            "volume" -> "set_volume"
            else -> name
        }
        "clipboard" -> when (arguments.optString("operation")) {
            "get" -> "get_clipboard"
            "set" -> "set_clipboard"
            "paste" -> "paste_text"
            else -> name
        }
        "file_ops" -> when (arguments.optString("operation")) {
            "read" -> "read_file"
            "write" -> "write_file"
            "edit" -> "edit_file"
            "search" -> "search_code"
            "list" -> "list_directory"
            "delete" -> "delete_path"
            else -> name
        }
        "skill" -> when (arguments.optString("operation")) {
            "list" -> "skills_list"
            "read" -> "skills_read"
            "resource" -> "skills_read_resource"
            "curated" -> "skills_list_curated"
            else -> name
        }
        "skill_github" -> if (arguments.optString("operation") == "inspect") {
            "skills_inspect_github"
        } else if (arguments.optString("operation") == "install") {
            "skills_install_from_github"
        } else name
        "memory" -> when (arguments.optString("operation")) {
            "get" -> "memory_get"
            "write" -> "memory_write"
            else -> name
        }
        else -> name
    }

    fun rootDenied(name: String, arguments: JSONObject, rootAvailable: Boolean): Boolean {
        if (rootAvailable) return false
        if (rootRequirement(name, arguments) == RootRequirement.REQUIRED) return true
        return when (effectiveName(name, arguments)) {
            "terminal" -> arguments.optString("identity").equals("root", ignoreCase = true)
            "press_key" -> arguments.optString("button").equals("PASTE", ignoreCase = true)
            else -> false
        }
    }

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray, rootAvailable: Boolean): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val name = original.getJSONObject("function").getString("name")
            val requirement = rootRequirement(name)
            if (!rootAvailable && requirement == RootRequirement.REQUIRED) continue
            val tool = JSONObject(original.toString())
            if (!rootAvailable) projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        when (function.getString("name")) {
            "device_info" -> properties?.optJSONObject("operation")?.put(
                "enum", JSONArray().put("context").put("status").put("network").put("environment"),
            )
            "ui_action" -> properties?.optJSONObject("button")?.put(
                "description", "系统按键名称；需要粘贴时使用 clipboard operation=paste。",
            )
            "terminal" -> {
                function.put("description", "在当前设备管理普通 Android Shell 或用户选择的 Linux 环境。" +
                    "以 App UID 执行，支持会话、异步任务和后台服务；Linux 内的模拟身份不提供 Android 系统特权。" +
                    "使用 open_and_exec 执行单次命令，open/exec 复用会话，daemon_start/list/logs/stop 管理后台服务。")
                properties?.getJSONObject("identity")
                    ?.put("enum", JSONArray().put("user"))
                    ?.put("description", "宿主执行身份；当前仅支持 user，默认 user。")
                properties?.getJSONObject("environment")?.put("description",
                    "android 使用普通 Android Shell；linux 使用用户选择的发行版和免 Root 后端。" +
                        "未指定时运行期决定：已安装 Linux 环境则默认 linux（找代码/处理数据），否则回退 android；设备数据类命令请显式传 android。")
                properties?.getJSONObject("cwd")?.put("description",
                    "工作目录。Android 默认使用 Eta 私有工作区，Linux 默认 /workspace。")
            }
            "run_command" -> {
                function.put("description",
                    "通过普通 Android Shell 执行单次非交互命令，以 App UID 运行；只能访问当前应用有权访问的资源。")
                properties?.getJSONObject("cwd")?.put("description", "工作目录，默认使用 Eta 私有工作区。")
            }
            "list_directory" -> {
                function.put("description", "列出当前应用有权访问的目录（按名排序，支持 limit/offset 翻页、glob 过滤、recursive 递归），默认使用 Eta 私有工作区。")
                properties?.optJSONObject("path")?.apply {
                    put("description", "目录路径；未提供时使用 Eta 私有工作区。")
                    remove("default")
                }
            }
            "read_image" -> properties?.getJSONObject("path")?.put("description",
                "当前应用有权读取的绝对图片路径、file URI 或已授权的 content URI。")
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "无障碍支持的系统按键；粘贴文本请使用 paste_text。")
            }
            "search_personal_orders" -> function.put("description",
                "从用户已授权保存的通知历史检索外卖、购物、快递、票券和出行订单。")
        }
    }
}
