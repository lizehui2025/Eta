package io.github.mangi.eta.agent.model

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String {
        // 参数只解析一次：ToolCall 内缓存复用，避免每个分支重复 JSONObject 解析。
        val args = toolCall.parsedArgsOrNull()
        return when (toolCall.name) {
            "web_search" -> summarizeCanonicalAction("联网搜索", args)
            "ui_action" -> summarizeCanonicalAction("界面操作", args)
            "app_action" -> summarizeCanonicalAction("应用操作", args)
            "device_info" -> summarizeCanonicalAction("设备信息", args)
            "device_control" -> summarizeCanonicalAction("设备控制", args)
            "clipboard" -> summarizeCanonicalAction("剪贴板", args)
            "file_ops" -> summarizeCanonicalAction("文件操作", args)
            "skill" -> summarizeCanonicalAction("技能", args)
            "skill_github" -> summarizeCanonicalAction("GitHub 技能", args)
            "memory" -> summarizeCanonicalAction("记忆", args)
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(args)
            "open_uri" -> summarizeOpenUriArguments(args)
            "terminal" -> summarizeTerminalArguments(args)
            "run_command" -> "执行命令 · Android · root"
            "write_file" -> summarizeWriteFileArguments(args)
            "edit_file" -> summarizeEditFileArguments(args)
            "search_code" -> summarizeCodeSearchArguments(args)
            "read_file" -> summarizeReadFileArguments(args)
            "list_directory" -> summarizeListDirectoryArguments(args)
            "input_text" -> summarizeTextLength("输入文本", args, "text")
            "replace_text" -> summarizeTextLength("替换文本", args, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength("粘贴文本", args, "text")
            "clear_text" -> "清空文本"
            "get_clipboard" -> "读取剪贴板"
            "search_apps" -> summarizeQueryArguments("搜索应用", args)
            "launch_app" -> "打开应用"
            "get_current_context" -> "读取当前上下文"
            "observe_screen" -> summarizeObservationArguments(args)
            "tap" -> summarizePointArguments("点击屏幕", args)
            "long_press" -> summarizePointArguments("长按屏幕", args)
            "tap_area" -> "点击区域"
            "tap_element" -> summarizeElementArguments("点击元素", args)
            "long_press_element" -> summarizeElementArguments("长按元素", args)
            "swipe" -> "滑动屏幕"
            "scroll" -> summarizeScrollArguments("滚动屏幕", args)
            "scroll_element" ->
                summarizeScrollArguments("滚动元素", args, withIndex = true)
            "press_key" -> summarizePressKeyArguments(args)
            "wait" -> summarizeWaitArguments(args)
            "wait_for_text" -> "等待文本出现"
            "wait_for_package" -> "等待应用就绪"
            "open_system_panel" -> "打开系统面板"
            "read_image" -> "查看图片"
            "spawn_agents" -> summarizeSpawnAgentsArguments(args)
            "todo_write" -> summarizeTodoArguments(args)
            AgentConversationToolCatalog.READ_HISTORY -> "读取当前会话历史"
            "memory_get", "character_memory_get" -> summarizeMemoryGetArguments(args)
            "memory_write", "character_memory_write" -> summarizeMemoryWriteArguments(args)
            "skills_list" -> "查看技能列表"
            "skills_read" -> "读取技能"
            "skills_read_resource" -> "读取技能资源"
            "skills_list_curated" -> "浏览精选技能"
            "skills_inspect_github" -> "查看技能详情"
            "skills_install_from_github" -> "安装技能"
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]
                when {
                    label == null -> "准备执行"
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, args)
                    else -> label
                }
            }
        }
    }

    private fun summarizeCanonicalAction(label: String, arguments: JSONObject?): String {
        val operation = arguments?.optString("action")?.ifBlank { null }
            ?: arguments?.optString("operation")?.ifBlank { null }
            ?: return label
        return "$label · $operation"
    }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == "terminal" || toolCall.name == "run_command") {
            runCatching {
                toolCall.parsedArgsOrNull()
                    ?.optString("command")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=<已隐藏>"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}<已隐藏>"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<已隐藏>"
            }

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        summarizeOpenUriArguments(runCatching { JSONObject(argumentsJson) }.getOrNull())

    private fun summarizeOpenUriArguments(arguments: JSONObject?): String {
        val raw = arguments?.optString("uri").orEmpty().trim()
        if (raw.isEmpty()) return "交给外部应用"
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull("交给外部应用", scheme, host).joinToString(" · ")
        }.getOrDefault("交给外部应用")
    }

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        summarizeBrowserArguments(runCatching { JSONObject(argumentsJson) }.getOrNull())

    private fun summarizeBrowserArguments(arguments: JSONObject?): String {
        if (arguments == null) return "浏览器操作"
        val action = arguments.optString("action").browserActionLabel()
        val host = safeHttpHost(arguments.optString("url"))
        return listOfNotNull(action, host).joinToString(" · ")
    }

    private fun summarizeTerminalArguments(arguments: JSONObject?): String {
        if (arguments == null) return "终端"
        val action = arguments.optString("action").terminalActionLabel()
        val environment = arguments.optString("environment", "android")
            .terminalEnvironmentLabel()
        val identity = arguments.optString("identity", "root")
            .takeIf { it == "root" || it == "user" }
        return buildList {
            add("终端")
            add(action)
            add(environment)
            identity?.let(::add)
            if (arguments.optBoolean("async", false)) add("后台")
        }.joinToString(" · ")
    }

    private fun summarizeTextLength(
        label: String,
        arguments: JSONObject?,
        key: String,
    ): String {
        if (arguments == null) return label
        val chars = arguments.optString(key).length
        return "$label · $chars 字符"
    }

    private fun summarizeEditFileArguments(arguments: JSONObject?): String {
        val path = arguments?.optString("path").orEmpty().substringAfterLast('/')
        return if (path.isBlank()) "编辑文件" else "编辑文件 · $path"
    }

    /** 读取文件只展示文件名，不暴露完整路径。 */
    private fun summarizeReadFileArguments(arguments: JSONObject?): String {
        if (arguments == null) return "读取文件"
        val name = sanitizeSummaryValue(
            arguments.optString("path").substringAfterLast('/'),
            MAX_FILE_NAME_SUMMARY_CHARS,
        )
        return if (name.isBlank()) "读取文件" else "读取文件 · $name"
    }

    private fun summarizeWriteFileArguments(arguments: JSONObject?): String {
        if (arguments == null) return "写入文件"
        val name = sanitizeSummaryValue(
            arguments.optString("path").substringAfterLast('/'),
            MAX_FILE_NAME_SUMMARY_CHARS,
        )
        val chars = arguments.optString("content").length
        return listOfNotNull(
            "写入文件",
            name.takeIf { it.isNotBlank() },
            "$chars 字符",
        ).joinToString(" · ")
    }

    private fun summarizeCodeSearchArguments(arguments: JSONObject?): String {
        if (arguments == null) return "搜索代码"
        val pattern = sanitizeSummaryValue(
            arguments.optString("pattern"),
            MAX_QUERY_SUMMARY_CHARS,
        )
        val glob = sanitizeSummaryValue(
            arguments.optString("glob"),
            MAX_QUERY_SUMMARY_CHARS,
        )
        return buildList {
            add("搜索代码")
            if (pattern.isNotBlank()) add(pattern)
            if (glob.isNotBlank()) add(glob)
        }.joinToString(" · ")
    }

    /** 列出目录只展示目录名与过滤状态，不暴露完整路径。 */
    private fun summarizeListDirectoryArguments(arguments: JSONObject?): String {
        if (arguments == null) return "列出目录"
        val name = sanitizeSummaryValue(
            arguments.optString("path").substringAfterLast('/'),
            MAX_FILE_NAME_SUMMARY_CHARS,
        )
        return buildList {
            add(if (name.isBlank()) "列出目录" else "列出目录 · $name")
            if (arguments.optBoolean("recursive", false)) add("递归")
            arguments.optString("glob").trim().takeIf { it.isNotBlank() }?.let { add(it) }
            if (arguments.optBoolean("show_hidden", false)) add("含隐藏")
            if (arguments.optInt("offset", 0) > 0) add("翻页")
        }.joinToString(" · ")
    }

    private fun summarizeSpawnAgentsArguments(arguments: JSONObject?): String {
        if (arguments == null) return "并行派生子代理"
        val count = arguments.optJSONArray("tasks")?.length() ?: 0
        val mode = arguments.optString("mode").trim().takeIf { it.isNotBlank() }
        return buildList {
            add("并行派生子代理")
            if (count > 0) add("$count 个任务")
            mode?.let(::add)
        }.joinToString(" · ")
    }

    /** 搜索关键词是用户自己发起的查询，直接展示；仍做单行化与长度截断。 */
    private fun summarizeQueryArguments(label: String, arguments: JSONObject?): String {
        if (arguments == null) return label
        val query = sanitizeSummaryValue(
            arguments.optString("query"),
            MAX_QUERY_SUMMARY_CHARS,
        )
        return if (query.isNotBlank()) "$label · $query" else label
    }

    private fun summarizePointArguments(label: String, arguments: JSONObject?): String {
        if (arguments == null) return label
        return "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
    }

    private fun summarizeElementArguments(label: String, arguments: JSONObject?): String {
        if (arguments == null) return label
        val index = arguments.optInt("index", -1)
        return if (index >= 0) "$label · #$index" else label
    }

    private fun summarizeScrollArguments(
        label: String,
        arguments: JSONObject?,
        withIndex: Boolean = false,
    ): String {
        if (arguments == null) return label
        return buildList {
            add(label)
            if (withIndex) {
                arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
            }
            arguments.optString("direction").scrollDirectionLabel()?.let(::add)
        }.joinToString(" · ")
    }

    private fun summarizePressKeyArguments(arguments: JSONObject?): String {
        if (arguments == null) return "按键"
        val button = arguments.optString("button").pressKeyLabel()
        return listOfNotNull("按键", button).joinToString(" · ")
    }

    private fun summarizeWaitArguments(arguments: JSONObject?): String {
        if (arguments == null) return "等待"
        val durationMs = arguments.optInt("duration_ms", 1_000)
            .coerceAtLeast(0)
        val duration = if (durationMs >= 1_000) {
            String.format(Locale.US, "%.1f", durationMs / 1_000f)
                .trimEnd('0').trimEnd('.') + " 秒"
        } else {
            "$durationMs 毫秒"
        }
        return "等待 · $duration"
    }

    private fun summarizeObservationArguments(arguments: JSONObject?): String {
        if (arguments == null) return "观察屏幕"
        return runCatching {
            val options = AgentScreenObservationContract.resolve(arguments)
            buildList {
                add("观察屏幕")
                if (options.includeScreenshot) add("含截图")
                if (options.includeUiTree) add("含界面树")
            }.joinToString(" · ")
        }.getOrDefault("观察屏幕")
    }

    private fun summarizeMemoryGetArguments(arguments: JSONObject?): String {
        if (arguments == null) return "读取记忆"
        return if (arguments.optString("query").isNotBlank()) "检索记忆" else "读取记忆"
    }

    private fun summarizeMemoryWriteArguments(arguments: JSONObject?): String {
        if (arguments == null) return "更新记忆"
        val mode = when (arguments.optString("mode")) {
            "replace_range" -> "替换片段"
            "append" -> "追加"
            "clear" -> "清空"
            else -> null
        }
        val content = arguments.optString("content")
        val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        return buildList {
            add("更新记忆")
            mode?.let(::add)
            add("$lines 行")
            add("${content.toByteArray(Charsets.UTF_8).size} 字节")
        }.joinToString(" · ")
    }

    /** 结果成败供事件与 UI 状态使用，不再依赖摘要文本里的标记。 */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        // 终端 exit_code != 0 时 ok=false 但没有 code 字段，必须走专用分支保留退出码与输出
        if (toolName == "terminal" || toolName == "run_command") {
            return summarizeTerminalResult(json)
        }
        if (!isSuccessResult(result)) return summarizeFailure(json)
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult) ?: "浏览器操作完成"
            "spawn_agents" -> json?.let(::summarizeSubagentsResult) ?: "子代理完成"
            "todo_write" -> json?.let(::summarizeTodoResult) ?: "任务清单已更新"
            AgentConversationToolCatalog.READ_HISTORY -> "已读取历史分页"
            "memory_get", "memory_write", "character_memory_get", "character_memory_write" ->
                json?.let { summarizeMemoryResult(toolName, it) } ?: "完成"
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: "完成"
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: "已打开"
            "edit_file" -> json?.let(::summarizeEditFileResult) ?: "完成"
            "search_code" -> json?.let(::summarizeCodeSearchResult) ?: "完成"
            "list_directory" -> json?.let(::summarizeListDirectoryResult) ?: "完成"
            else -> json?.let { summarizeGenericResult(it, result) } ?: "完成"
        }
    }

    /**
     * 展开态的详细信息：文件读写展示变更，目录/搜索展示命中与截断指引，
     * 子代理展示取消与过滤信息，终端展示更完整的输出。返回空串表示没有可展示的详情。
     */
    fun summarizeDetail(
        toolName: String,
        argumentsJson: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val detail = when (toolName) {
            "read_file" -> readFileDetail(argumentsJson, result)
            "write_file" -> writeFileDetail(argumentsJson, result)
            "edit_file" -> editFileDetail(argumentsJson, result)
            "list_directory" -> listDirectoryDetail(argumentsJson, result)
            "search_code" -> codeSearchDetail(result)
            "spawn_agents" -> subagentsDetail(result)
            "terminal", "run_command" -> terminalResultDetail(result)
            "file_ops" -> canonicalFileOpsDetail(argumentsJson, result)
            "web_search" -> webSearchDetail(result)
            BROWSER_TOOL_NAME -> browserDetail(result)
            else -> genericResultDetail(toolName, result)
        }
        // 展开详情不能再做展示层截断。工具自身返回的分页/容量限制仍由结果中的
        // truncated、stdout_truncated 等字段明确标记，这里只负责完整呈现已返回内容。
        return detail
    }

    private fun genericResultDetail(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolName)) return ""
        val json = parseResultJson(result) ?: return result.content.trim().redactDisplaySecrets()
        return readableJsonDetail(json)
    }

    /** 将搜索结果投影成 RikkaHub 风格的结果列表，而不是把供应商 JSON 原样放入卡片。 */
    private fun webSearchDetail(result: AgentModelClient.ToolResult): String {
        if (result.sensitive) return ""
        val json = parseResultJson(result) ?: return result.content.trim().redactDisplaySecrets()
        val builder = StringBuilder()
        if (!json.optBoolean("ok", true)) return readableJsonDetail(json)
        json.optString("query").takeIf(String::isNotBlank)?.let {
            builder.append("搜索：").append(it.redactDisplaySecrets()).append('\n')
        }
        json.optString("provider").takeIf(String::isNotBlank)?.let {
            builder.append("来源：").append(it.redactDisplaySecrets()).append('\n')
        }
        val results = json.optJSONArray("results")
        if (results != null) {
            builder.append("\n结果 · ").append(results.length()).append(" 条")
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val title = item.optString("title").ifBlank { "未命名结果" }
                builder.append("\n\n").append(index + 1).append(". ")
                    .append(title.redactDisplaySecrets())
                item.optString("url").takeIf(String::isNotBlank)?.let {
                    builder.append('\n').append(it.redactDisplaySecrets())
                }
                item.optString("snippet").takeIf(String::isNotBlank)?.let {
                    builder.append('\n').append(it.redactDisplaySecrets())
                }
                item.optString("retrieved_at").takeIf(String::isNotBlank)?.let {
                    builder.append("\n检索时间：").append(it)
                }
            }
        }
        json.optString("text").takeIf(String::isNotBlank)?.let {
            builder.append("\n\n正文\n").append(it.redactDisplaySecrets())
            json.optInt("offset", 0).takeIf { offset -> offset > 0 }?.let { offset ->
                builder.append("\n起始偏移：").append(offset)
            }
            if (json.optBoolean("has_more", false)) builder.append("\n后续内容可继续读取")
        }
        return builder.toString().trim().ifBlank { readableJsonDetail(json) }
    }

    /** 浏览器结果保留页面标题、地址、正文和元素信息，但隐藏协议字段和 JSON 结构。 */
    private fun browserDetail(result: AgentModelClient.ToolResult): String {
        if (result.sensitive) return ""
        val json = parseResultJson(result) ?: return result.content.trim().redactDisplaySecrets()
        val builder = StringBuilder()
        json.optString("action").takeIf(String::isNotBlank)?.let {
            builder.append("操作：").append(it.browserActionLabel())
        }
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val sources = listOfNotNull(json, page)
        fun firstString(vararg keys: String): String? = sources.asSequence()
            .mapNotNull { source -> keys.asSequence().map(source::optString).firstOrNull(String::isNotBlank) }
            .firstOrNull()
        firstString("title")?.let { builder.appendLine("标题：${it.redactDisplaySecrets()}") }
        firstString("url", "current_url", "currentUrl", "final_url", "finalUrl")?.let {
            builder.appendLine("地址：${it.redactDisplaySecrets()}")
        }
        firstString("text", "readable", "content")?.let {
            builder.appendLine("\n正文：")
            builder.append(it.redactDisplaySecrets())
        }
        json.firstNonNegativeInt("element_count", "elementCount", "elements_count")?.let {
            builder.appendLine("\n元素：$it 个")
        }
        return builder.toString().trim().ifBlank { readableJsonDetail(json) }
    }

    /** 通用工具结果投影：完整保留字段和值，但不显示 JSON 括号、键名引号或转义结构。 */
    private fun readableJsonDetail(json: JSONObject): String = buildString {
        appendReadableObject(this, json, 0)
    }.trim()

    private fun appendReadableObject(builder: StringBuilder, json: JSONObject, depth: Int) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "ok" || key == "tool") continue
            val value = json.opt(key)
            appendReadableEntry(builder, key, value, depth)
        }
        if (json.optBoolean("ok", true) && builder.isEmpty()) builder.append("已完成")
    }

    private fun appendReadableEntry(builder: StringBuilder, key: String, value: Any?, depth: Int) {
        val indent = "  ".repeat(depth)
        val label = displayFieldLabel(key)
        when (value) {
            is JSONObject -> {
                builder.append(indent).append(label).append('\n')
                appendReadableObject(builder, value, depth + 1)
            }
            is JSONArray -> {
                builder.append(indent).append(label).append(" · ").append(value.length()).append(" 项")
                for (index in 0 until value.length()) {
                    builder.append('\n').append(indent).append("  ").append(index + 1).append(". ")
                    appendReadableInline(builder, value.opt(index), depth + 1)
                }
                builder.append('\n')
            }
            JSONObject.NULL -> Unit
            else -> builder.append(indent).append(label).append("：")
                .append(displayFieldValue(value.toString())).append('\n')
        }
    }

    private fun appendReadableInline(builder: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            is JSONObject -> {
                builder.append('\n')
                appendReadableObject(builder, value, depth + 1)
            }
            is JSONArray -> builder.append("包含 ").append(value.length()).append(" 项")
            JSONObject.NULL -> Unit
            else -> builder.append(displayFieldValue(value.toString()))
        }
    }

    private fun displayFieldLabel(key: String): String = when (key) {
        "message" -> "说明"
        "code" -> "状态码"
        "url", "current_url", "currentUrl", "final_url", "finalUrl" -> "地址"
        "title" -> "标题"
        "text", "content", "bodyMarkdown", "readable" -> "正文"
        "path", "relativePath", "skillFilePath" -> "路径"
        "count", "total" -> "数量"
        "has_more", "hasMore" -> "还有后续"
        "truncated" -> "返回不完整"
        "retrieved_at" -> "检索时间"
        else -> key.replace('_', ' ')
    }

    private fun displayFieldValue(value: String): String = value
        .redactDisplaySecrets()
        .replace("\\n", "\n")

    private fun canonicalFileOpsDetail(
        argumentsJson: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val operation = runCatching { JSONObject(argumentsJson) }
            .getOrNull()
            ?.optString("operation")
            ?.lowercase()
        return when (operation) {
            "read" -> readFileDetail(argumentsJson, result)
            "write" -> writeFileDetail(argumentsJson, result)
            "edit" -> editFileDetail(argumentsJson, result)
            "list" -> listDirectoryDetail(argumentsJson, result)
            "search" -> codeSearchDetail(result)
            else -> ""
        }
    }

    /** 复用缓存解析的详情入口：调用方已有 ToolCall 时避免重复解析参数。 */
    fun summarizeDetail(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ): String = summarizeDetail(toolCall.name, toolCall.argumentsJson, result)

    private fun readFileDetail(argumentsJson: String, result: AgentModelClient.ToolResult): String {
        val json = parseResultJson(result) ?: return ""
        if (!json.optBoolean("ok", true)) return ""
        val name = fileDisplayName(
            json.optString("path").ifBlank {
                runCatching { JSONObject(argumentsJson).optString("path") }.getOrDefault("")
            },
        )
        val content = json.optString("content")
        val bytesRead = json.optInt("bytes_read", content.toByteArray(Charsets.UTF_8).size)
        val offset = json.optInt("offset_bytes", 0)
        val lines = if (content.isEmpty()) 0 else content.lines().size
        val builder = StringBuilder()
        if (name.isNotBlank()) builder.append("文件：").append(name).append('\n')
        builder.append(
            if (lines > 0) {
                // offset_bytes 是字节偏移，不是行偏移。非零偏移时不能把本段行号伪装成
                // 文件绝对行号；只报告本段行数，并保留字节偏移提示。
                if (offset == 0) {
                    "行：${lineRangeLabel(lines)} · 读取 $bytesRead 字节"
                } else {
                    "行：本段 ${lineCountLabel(lines)} · 读取 $bytesRead 字节"
                }
            } else {
                "读取 $bytesRead 字节"
            },
        )
        if (offset > 0) builder.append("（自字节 $offset 起）")
        if (content.isNotBlank()) {
            builder.append("\n\n内容：\n").append(content.redactDisplaySecrets())
        }
        if (json.optBoolean("truncated", false)) {
            builder.append("\n已截断，可继续用 offset_bytes 读取后续内容")
        }
        return builder.toString()
    }

    private fun writeFileDetail(argumentsJson: String, result: AgentModelClient.ToolResult): String {
        val json = parseResultJson(result) ?: return ""
        if (!json.optBoolean("ok", true)) return ""
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return ""
        val name = fileDisplayName(arguments.optString("path"))
        val append = arguments.optBoolean("append", false)
        val content = arguments.optString("content")
        val lines = if (content.isEmpty()) 0 else content.lines().size
        val bytes = content.toByteArray(Charsets.UTF_8).size
        val builder = StringBuilder()
        if (name.isNotBlank()) builder.append("文件：").append(name).append('\n')
        builder.append(if (append) "追加写入" else "覆盖写入")
            .append(" · ").append(lineCountLabel(lines)).append(" · ").append(bytes).append(" 字节")
        val diff = json.optString("diff").takeIf { it.isNotBlank() }
        if (diff != null) {
            builder.append("\n\n旧内容对比：\n").append(diff.redactDisplaySecrets())
        } else {
            content.takeIf(String::isNotBlank)?.let { fullContent ->
                builder.append("\n\n")
                    .append(if (append) "追加内容：" else "新内容：")
                    .append('\n').append(fullContent.redactDisplaySecrets())
            }
        }
        return builder.toString()
    }

    private fun editFileDetail(argumentsJson: String, result: AgentModelClient.ToolResult): String {
        val json = parseResultJson(result)
        if (json != null && !json.optBoolean("ok", true)) return ""
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return ""
        val name = fileDisplayName(arguments.optString("path"))
        val oldText = arguments.optString("old_string")
        val newText = arguments.optString("new_string")
        val builder = StringBuilder()
        if (name.isNotBlank()) builder.append("文件：").append(name)
        val replacements = json?.optInt("replacements", 0) ?: 0
        if (replacements > 0) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append("替换 ").append(replacements).append(" 处")
        }
        val change = AgentTextDiff.summarizeReplacementFull(oldText, newText)
            ?: "非文本内容，无法展示差异"
        builder.append("\n\n").append(change.redactDisplaySecrets())
        return builder.toString()
    }

    /** 列出目录展开详情：条目预览 + 翻页指引，不暴露完整路径。 */
    private fun listDirectoryDetail(argumentsJson: String, result: AgentModelClient.ToolResult): String {
        val json = parseResultJson(result) ?: return ""
        if (!json.optBoolean("ok", true)) return ""
        val total = json.optInt("total", -1)
        if (total < 0) return ""
        val count = json.optInt("count", 0)
        val offset = json.optInt("offset", 0)
        val builder = StringBuilder()
        builder.append("共 $total 项 · 本页 $count 项")
        if (offset > 0) builder.append("（自第 ${offset + 1} 项起）")
        if (json.optBoolean("truncated", false)) {
            builder.append("\n已截断：用 offset=${offset + count} 继续翻页，或加 glob 缩小范围")
        }
        appendDetailBlock(
            builder,
            title = "目录条目",
            text = json.optString("entries_text"),
            truncatedFlag = false,
        )
        return builder.toString()
    }

    /** 代码搜索展开详情：命中列表预览 + 截断时给 glob 指引。 */
    private fun codeSearchDetail(result: AgentModelClient.ToolResult): String {
        val json = parseResultJson(result) ?: return ""
        if (!json.optBoolean("ok", true)) return ""
        if (!json.has("count")) return ""
        val builder = StringBuilder()
        builder.append("命中 ${json.optInt("count")} 条")
        if (json.optBoolean("truncated", false)) {
            builder.append("\n已截断：加 glob 缩小范围（如 *.kt），或换更具体的 pattern")
        }
        val hits = json.optJSONArray("results")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        if (hits.isNotEmpty()) {
            appendDetailBlock(
                builder,
                title = "命中列表",
                text = hits.joinToString("\n"),
                truncatedFlag = false,
            )
        }
        return builder.toString()
    }

    /**
     * 子代理展开详情：汇总行（超时/护栏/被过滤工具）+ 逐项一行。
     * 完整输出仍在子代理详情窗口，这里只给快速扫描与失败改法。
     */
    private fun subagentsDetail(result: AgentModelClient.ToolResult): String {
        val json = parseResultJson(result) ?: return ""
        if (!json.optBoolean("ok", true)) return ""
        val total = json.optInt("total", 0)
        val succeeded = json.optInt("succeeded", 0)
        val builder = StringBuilder()
        builder.append("共 $total 项 · 成功 $succeeded 项")
        json.optInt("interrupted", 0).takeIf { it > 0 }?.let { builder.append(" · 未完成 $it 项") }
        if (json.has("fanout_elapsed_ms")) builder.append(" · 用时 ${json.optLong("fanout_elapsed_ms")}ms")
        val mode = json.optString("mode").takeIf { it.isNotBlank() }
        mode?.let {
            builder.append("\n").append("模式 $it")
        }
        json.optJSONArray("filtered_tools")?.takeIf { it.length() > 0 }?.let { array ->
            val names = (0 until array.length())
                .mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
                .map(::sanitizeSummaryValue)
            if (names.isNotEmpty()) {
                builder.append("\n被模式过滤的工具：").append(names.joinToString("、"))
            }
        }
        val results = json.optJSONArray("results") ?: return builder.toString()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val label = sanitizeSummaryValue(item.optString("label")).takeIf { it.isNotBlank() } ?: "子代理${i + 1}"
            val duration = item.optLong("duration_ms", -1)
            val marker = if (item.optBoolean("ok", false)) "✓" else "✗"
            val line = buildList {
                add("$marker $label")
                if (duration >= 0) add("${duration}ms")
                when (item.optString("code")) {
                    "SUBAGENT_INTERRUPTED" -> add("被取消：先读 changed_files 核实，再决定重试")
                    "SUBAGENT_ERROR" -> add("失败")
                    else -> Unit
                }
                if (item.optBoolean("still_running", false)) add("线程仍在收尾")
                if (item.optBoolean("content_truncated", false)) add("输出已截断")
                if (item.optBoolean("changed_files_truncated", false)) add("改动列表已截断")
                item.optJSONArray("changed_files")?.takeIf { it.length() > 0 }?.let {
                    add("改动 ${it.length()} 处")
                }
            }.joinToString(" · ")
            builder.append("\n").append(line)
        }
        return builder.toString()
    }

    private fun terminalResultDetail(result: AgentModelClient.ToolResult): String {        val json = parseResultJson(result) ?: return ""
        if (json.optString("code").isNotBlank()) return ""
        if (!json.has("exit_code") || json.isNull("exit_code")) return ""
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val builder = StringBuilder()
        builder.append(if (timedOut) "执行超时 · 退出码 $exitCode" else "退出码 $exitCode")
        appendDetailBlock(
            builder,
            title = "输出（stdout）",
            text = json.optString("stdout"),
            truncatedFlag = json.optBoolean("stdout_truncated", false),
        )
        appendDetailBlock(
            builder,
            title = "错误输出（stderr）",
            text = json.optString("stderr"),
            truncatedFlag = json.optBoolean("stderr_truncated", false),
        )
        return builder.toString()
    }

    private fun appendDetailBlock(
        builder: StringBuilder,
        title: String,
        text: String,
        truncatedFlag: Boolean,
    ) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        builder.append("\n\n").append(title).append('\n').append(normalized.redactDisplaySecrets())
        if (truncatedFlag) builder.append("\n（工具返回不完整，可继续分页读取）")
    }

    private fun contentPreview(content: String, maxLines: Int, maxChars: Int): String? {
        val normalized = content.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(maxLines).joinToString("\n")
        var capped = allLines.size > maxLines
        if (preview.length > maxChars) {
            preview = preview.take(maxChars)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun fileDisplayName(path: String): String =
        sanitizeSummaryValue(path.substringAfterLast('/'), MAX_FILE_NAME_SUMMARY_CHARS)

    private fun lineRangeLabel(lines: Int): String =
        if (lines <= 1) "第 1 行" else "第 1–$lines 行"

    private fun lineCountLabel(lines: Int): String =
        if (lines <= 0) "空内容" else "$lines 行"

    private fun summarizeSubagentsResult(json: JSONObject): String {
        if (!json.optBoolean("ok", true)) return summarizeFailure(json)
        val total = json.optInt("total", 0)
        val okCount = json.optInt("succeeded", 0)
        val base = buildList {
            add("子代理完成 · $okCount/$total 成功")
            json.optInt("interrupted", 0).takeIf { it > 0 }?.let { add("未完成 $it") }
            json.optJSONArray("filtered_tools")?.takeIf { it.length() > 0 }?.let {
                add("已过滤 ${it.length()} 个工具")
            }
        }.joinToString(" · ")
        if (!json.has("fanout_elapsed_ms")) return base
        val elapsed = json.optLong("fanout_elapsed_ms", -1)
        val results = json.optJSONArray("results")
        var maxChild = -1L
        if (results != null) {
            for (i in 0 until results.length()) {
                val d = results.optJSONObject(i)?.optLong("duration_ms", -1) ?: -1
                if (d > maxChild) maxChild = d
            }
        }
        return if (elapsed >= 0 && maxChild >= 0) base + " · ${elapsed}ms（最慢子" + maxChild + "ms）" else base
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的中文原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add("失败")
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeMemoryResult(toolName: String, json: JSONObject): String =
        buildList {
            add(if (toolName.endsWith("memory_get")) "已读取记忆" else "已更新记忆")
            if (json.has("line_count")) add("${json.optInt("line_count")} 行")
            if (json.has("bytes")) add("${json.optInt("bytes")} 字节")
        }.joinToString(" · ")

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            add("完成")
            json.optJSONArray("apps")?.let { add("找到 ${it.length()} 个应用") }
            json.optJSONArray("candidates")?.let { add("${it.length()} 个候选") }
            if (result.images.isNotEmpty()) add("${result.images.size} 张图片")
        }.joinToString(" · ")

    private fun summarizeEditFileResult(json: JSONObject): String =
        buildList {
            add("已编辑")
            if (json.has("replacements")) add("替换 ${json.optInt("replacements")} 处")
            if (json.has("bytes_written")) add("${json.optInt("bytes_written")} 字节")
        }.joinToString(" · ")

    private fun summarizeCodeSearchResult(json: JSONObject): String =
        buildList {
            add("搜索完成")
            if (json.has("count")) add("命中 ${json.optInt("count")} 条")
            if (json.optBoolean("truncated", false)) add("已截断")
        }.joinToString(" · ")

    /** 列出目录结果摘要：总数 + 本页数 + 截断标记，翻页指引进展开详情。 */
    private fun summarizeListDirectoryResult(json: JSONObject): String {
        if (!json.optBoolean("ok", true)) return summarizeFailure(json)
        val total = json.optInt("total", -1)
        if (total < 0) return "完成"
        if (total == 0) return "空目录"
        return buildList {
            add("共 $total 项")
            if (json.has("count")) add("显示 ${json.optInt("count")} 项")
            if (json.optBoolean("truncated", false)) add("已截断")
        }.joinToString(" · ")
    }

    /** 任务清单入参摘要：标题 + 进度 + 进行中条目，供工具卡片折叠行展示。 */
    private fun summarizeTodoArguments(arguments: JSONObject?): String {
        val todos = arguments?.optJSONArray("todos")
        if (todos == null || todos.length() == 0) return "任务清单"
        val total = todos.length()
        val completed = countTodoStatus(todos, "completed")
        return buildList {
            add("任务清单")
            add("已完成 $completed/$total")
            firstTodoContent(todos, "in_progress")?.let { add("进行中：$it") }
        }.joinToString(" · ")
    }

    /** 任务清单结果摘要：逐项列出状态，供卡片展开详情预览。 */
    private fun summarizeTodoResult(json: JSONObject): String {
        if (!json.optBoolean("ok", true)) return summarizeFailure(json)
        val items = json.optJSONArray("items")
        val total = json.optInt("total", items?.length() ?: 0)
        val completed = json.optInt("completed", 0)
        val lines = mutableListOf("进度 $completed/$total")
        if (items != null) {
            val shown = items.length().coerceAtMost(MAX_TODO_PREVIEW_ITEMS)
            for (i in 0 until shown) {
                val obj = items.optJSONObject(i) ?: continue
                val content = sanitizeSummaryValue(obj.optString("content"))
                if (content.isBlank() || content == "null") continue
                val marker = when (obj.optString("status")) {
                    "completed" -> "✓"
                    "in_progress" -> "◐"
                    else -> "○"
                }
                lines += "$marker $content"
            }
            if (items.length() > MAX_TODO_PREVIEW_ITEMS) lines += "…共 $total 项"
        }
        return lines.joinToString("\n")
    }

    private fun countTodoStatus(todos: JSONArray, status: String): Int {
        var count = 0
        for (i in 0 until todos.length()) {
            if (todos.optJSONObject(i)?.optString("status") == status) count++
        }
        return count
    }

    private fun firstTodoContent(todos: JSONArray, status: String): String? {
        for (i in 0 until todos.length()) {
            val obj = todos.optJSONObject(i) ?: continue
            if (obj.optString("status") == status) {
                val content = sanitizeSummaryValue(obj.optString("content"))
                if (content.isNotBlank() && content != "null") return content
            }
        }
        return null
    }

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps") ?: return "未找到匹配应用"
        val total = apps.length()
        if (total == 0) return "未找到匹配应用"
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("已找到 $total 个应用")
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                append(" · ").append(shown.joinToString("、"))
                if (total > shown.size) append(" 等")
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "已打开 · $appName" else "已打开"
    }

    /**
     * 终端结果面向用户展示退出状态与输出预览；输出可能很长，
     * 只保留开头几行，截断时追加省略标记。
     */
    private fun summarizeTerminalResult(json: JSONObject?): String {
        if (json == null) return "终端"
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = json.optString("action").terminalActionLabel()
            return if (json.optBoolean("ok", true)) "终端 · $action" else "失败 · $action"
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> "失败 · 执行超时"
            exitCode == 0 -> "执行完成"
            else -> "失败 · 退出码 $exitCode"
        }
        val output = if (exitCode == 0) {
            json.optString("stdout")
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout") }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add("《$it》") }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add("约 ${formatCharCount(it)}") }
            }
            elementCount?.let { add("$it 个元素") }
            if (json.optBoolean("truncated", false)) add("已截断")
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.') + " 万字"
        } else {
            "$chars 字"
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> "打开网页"
        "get_readable" -> "提取正文"
        "get_text" -> "读取文本"
        "find_elements" -> "查找元素"
        "click" -> "点击网页"
        "type" -> "输入内容"
        "scroll" -> "滚动网页"
        "screenshot" -> "网页截图"
        "get_page_info" -> "查看网页信息"
        "go_back" -> "网页后退"
        "go_forward" -> "网页前进"
        "reload" -> "刷新网页"
        "wait_for_selector" -> "等待网页元素"
        else -> "浏览器操作"
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> "已打开"
        "get_readable" -> "已提取正文"
        "get_text" -> "已读取文本"
        "find_elements" -> "已找到元素"
        "click" -> "已点击网页"
        "type" -> "已输入内容"
        "scroll" -> "已滚动网页"
        "screenshot" -> "已截图"
        "get_page_info" -> "已读取页面信息"
        "go_back" -> "已后退"
        "go_forward" -> "已前进"
        "reload" -> "已刷新"
        "wait_for_selector" -> "已等到目标元素"
        else -> "浏览器操作完成"
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> "向上"
        "down" -> "向下"
        "left" -> "向左"
        "right" -> "向右"
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> "返回"
        "home" -> "主页"
        "recents", "recent" -> "最近任务"
        "notifications" -> "通知栏"
        "quick_settings" -> "控制中心"
        "power" -> "电源"
        "volume_up" -> "音量加"
        "volume_down" -> "音量减"
        "mute" -> "静音"
        else -> null
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "open" -> "创建会话"
        "exec" -> "执行命令"
        "open_and_exec" -> "单次执行"
        "read_async_result" -> "读取后台输出"
        "close" -> "关闭终端"
        "daemon_start" -> "启动守护任务"
        "daemon_list" -> "守护任务列表"
        "daemon_logs" -> "查看守护日志"
        "daemon_stop" -> "停止守护任务"
        else -> "终端操作"
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
    }

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TODO_PREVIEW_ITEMS = 7
        const val MAX_TERMINAL_PREVIEW_LINES = 3
        const val MAX_TERMINAL_PREVIEW_CHARS = 240
        const val MAX_FILE_NAME_SUMMARY_CHARS = 60
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** 结构化设备工具只展示动作标签，不暴露任何参数。 */
        val DEVICE_ACTION_LABELS = mapOf(
            "set_alarm" to "设置闹钟",
            "set_timer" to "设置计时器",
            "device_status" to "查看设备状态",
            "network_info" to "查看网络信息",
            "top_memory_apps" to "查看内存占用排行",
            "top_storage_apps" to "查看存储占用排行",
            "media_control" to "控制媒体播放",
            "set_volume" to "调整音量",
            "get_setting" to "读取系统设置",
            "wifi_credentials" to "读取 Wi-Fi 密码",
            "recent_notifications" to "读取最近通知",
            "search_notification_history" to "搜索通知历史",
            "recent_app_activity" to "查看应用活动",
            "app_usage_summary" to "查看应用使用统计",
            "get_current_location" to "获取当前位置",
            "get_device_environment" to "查看设备环境",
            "list_alarms" to "查看闹钟列表",
            "list_active_timers" to "查看计时器",
            "search_clipboard_history" to "搜索剪贴板历史",
            "get_health_summary" to "查看健康摘要",
            "read_sms_code" to "读取短信验证码",
            "get_logcat" to "读取系统日志",
            "search_media" to "搜索媒体文件",
            "search_audio" to "搜索音频",
            "search_recordings" to "搜索录音",
            "search_files" to "搜索文件",
            "search_calendar_events" to "搜索日程",
            "search_contacts" to "搜索联系人",
            "search_call_history" to "搜索通话记录",
            "search_messages" to "搜索短信",
            "search_downloads" to "搜索下载内容",
            "search_coloros_notes" to "搜索便签",
            "search_coloros_recordings" to "搜索录音机",
            "search_recording_summaries" to "搜索录音摘要",
            "search_coloros_memories" to "搜索小布记忆",
            "search_saved_places" to "搜索收藏地点",
            "search_personal_orders" to "搜索个人订单",
            "search_qq_chat_images" to "搜索 QQ 聊天图片",
            "search_wechat_chat_images" to "搜索微信聊天图片",
            "set_setting" to "修改系统设置",
            "set_device_state" to "修改设备状态",
            "app_state_control" to "管理应用状态",
        )
    }
}
