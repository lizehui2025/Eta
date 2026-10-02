package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact, domain-oriented schemas exposed to the model.
 *
 * The old fine-grained schemas remain available to the execution adapter so existing
 * transcripts and checkpoints can still be replayed, but new model requests only see these
 * canonical tools.
 */
internal object AgentCanonicalToolCatalog {
    fun appendTo(
        tools: JSONArray,
        browserTools: Boolean,
        terminalTools: Boolean,
        deviceDirectTools: Boolean,
        githubDiscovery: Boolean,
        githubInstall: Boolean,
        memoryTools: Boolean,
        memoryWritable: Boolean,
        subagentTools: Boolean,
        planTools: Boolean,
    ) {
        tools.put(observeScreen())
        AgentWebSearchToolCatalog.appendTo(tools)
        tools.put(uiAction())
        tools.put(appAction())
        tools.put(deviceInfo())
        if (deviceDirectTools) tools.put(deviceControl())
        tools.put(clipboard())
        if (browserTools) appendNamed(tools, { source -> AgentBrowserToolCatalog.appendTo(source) })
        if (terminalTools) {
            appendNamed(tools, { source -> AgentTerminalToolCatalog.appendTo(source) }, "terminal")
            tools.put(readImage())
            tools.put(fileOps())
        }
        if (githubDiscovery || githubInstall) {
            tools.put(skill())
            tools.put(skillGithub())
        } else {
            tools.put(skill())
        }
        if (memoryTools) tools.put(memory(memoryWritable))
        if (planTools) AgentPlanToolCatalog.appendTo(tools)
        if (subagentTools) AgentSubagentToolCatalog.appendTo(tools)
        AgentInteractionToolCatalog.appendTo(tools)
    }

    private fun appendNamed(
        target: JSONArray,
        append: (JSONArray) -> Unit,
        name: String? = null,
    ) {
        val source = JSONArray()
        append(source)
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val itemName = item.optJSONObject("function")?.optString("name")
            if (name == null || itemName == name) {
                // Reused catalogs predate the canonical closed-object contract. Clone the
                // function parameters before publishing so legacy schemas remain untouched.
                item.optJSONObject("function")
                    ?.optJSONObject("parameters")
                    ?.takeIf { it.optString("type") == "object" }
                    ?.put("additionalProperties", false)
                target.put(item)
            }
        }
    }

    private fun observeScreen(): JSONObject = AgentToolSchema.function(
        "observe_screen",
        "观察当前手机屏幕，默认只返回前台应用、屏幕尺寸、observation_id 与可见 UI 节点，不附截图。" +
            "节点为空、目标无法唯一识别或任务依赖视觉内容时设置 include_screenshot=true；补截图时保持 include_ui_tree=true，" +
            "禁止把新截图与旧节点混用。后续 ui_action 使用节点时必须携带本次返回的 observation_id。",
        objectSchema(
            "include_screenshot" to bool("是否附加原图，默认 false").put("default", false),
            "include_ui_tree" to bool("是否返回 UI 节点，默认 true").put("default", true),
            "max_nodes" to integer("最多返回的 UI 节点数", 1, 120).put("default", 60),
        ),
    )

    private fun uiAction(): JSONObject = AgentToolSchema.function(
        "ui_action",
        "执行一次前台界面操作。先 observe_screen 获取证据；节点操作必须复用同一个 observation_id。action 决定其余字段的含义。",
        objectSchema(
            "action" to enum("动作", "tap", "long_press", "swipe", "scroll", "input", "clear", "key", "wait", "open_system_panel"),
            "x" to integerValue("坐标 X"),
            "y" to integerValue("坐标 Y"),
            "x1" to integerValue("起点 X"),
            "y1" to integerValue("起点 Y"),
            "x2" to integerValue("终点 X"),
            "y2" to integerValue("终点 Y"),
            "index" to integerValue("observe_screen 返回的节点 index"),
            "observation_id" to string("对应的观察快照 ID", 200),
            "coordinate_space" to enum("坐标系", "screenshot", "screen"),
            "direction" to enum("滚动方向", "up", "down", "left", "right"),
            "duration_ms" to integer("持续时间（毫秒）", 1, 3_000),
            "text" to string("输入或等待的文本", 20_000),
            "mode" to enum("输入模式", "append", "replace", "paste"),
            "button" to string("系统按键名称", 64),
            "condition" to enum("等待条件", "duration", "text", "package"),
            "timeout_ms" to integer("等待超时（毫秒）", 1, 180_000),
            "include_desc" to bool("等待文本时是否匹配内容描述"),
            "match" to enum("文本匹配方式", "contains", "equals", "regex"),
            "package_name" to string("等待的包名", 255),
            "panel" to string("系统面板名称", 64),
        ),
        required = arrayOf("action"),
    )

    private fun appAction(): JSONObject = AgentToolSchema.function(
        "app_action",
        "搜索应用、启动应用或把 URI 交给外部应用处理。",
        objectSchema(
            "action" to enum("应用动作", "search", "launch", "open_uri"),
            "query" to string("应用搜索关键词", 255),
            "include_system" to bool("搜索时是否包含系统应用"),
            "limit" to integer("最多返回数量", 1, 20),
            "package_name" to string("精确包名", 255),
            "app_name" to string("应用显示名", 255),
            "uri" to string("要交给系统处理的 URI", 4_000),
        ),
        required = arrayOf("action"),
    )

    private fun deviceInfo(): JSONObject = AgentToolSchema.function(
        "device_info",
        "读取设备和运行环境信息。operation=top_memory 或 top_storage 可能需要 Root；location 受定位授权和敏感读取开关控制。",
        objectSchema(
            "operation" to enum("读取类型", "context", "status", "network", "environment", "top_memory", "top_storage"),
            "limit" to integer("最多返回数量", 1, 30),
        ),
        required = arrayOf("operation"),
    )

    private fun deviceControl(): JSONObject = AgentToolSchema.function(
        "device_control",
        "执行低风险的直接设备控制：创建闹钟或计时器、控制媒体和设置音量。",
        objectSchema(
            "operation" to enum("控制类型", "alarm", "timer", "media", "volume"),
            "hour" to integer("小时", 0, 23),
            "minute" to integer("分钟", 0, 59),
            "duration_seconds" to integer("计时秒数", 1, 86_400),
            "label" to string("标签", 100),
            "repeat_days" to stringArray("重复星期", "mon", "tue", "wed", "thu", "fri", "sat", "sun"),
            "vibrate" to bool("是否振动"),
            "media_action" to enum("媒体动作", "play", "pause", "play_pause", "next", "previous", "stop"),
            "stream" to enum("音量通道", "media", "alarm", "ring", "notification"),
            "percent" to integer("音量百分比", 0, 100),
        ),
        required = arrayOf("operation"),
    )

    private fun clipboard(): JSONObject = AgentToolSchema.function(
        "clipboard",
        "读取或写入系统剪贴板，或在确认输入焦点后粘贴文本。剪贴板内容按敏感数据处理。",
        objectSchema(
            "operation" to enum("剪贴板动作", "get", "set", "paste"),
            "text" to string("要写入或粘贴的文本", 20_000),
        ),
        required = arrayOf("operation"),
    )

    private fun fileOps(): JSONObject = AgentToolSchema.function(
        "file_ops",
        "在当前授权范围内读取、写入、编辑、搜索文件或列出目录。使用 Linux/Android 终端时仍遵循运行环境说明。",
        objectSchema(
            "operation" to enum("文件操作", "read", "write", "edit", "search", "list"),
            "path" to string("文件或目录路径", 4_000),
            "query" to string("搜索文本或正则", 2_000),
            "pattern" to string("代码搜索正则（兼容底层搜索器）", 2_000),
            "max_results" to integer("最多搜索结果", 1, 500),
            "content" to string("写入内容", 500_000),
            "old_string" to string("要替换的原文", 100_000),
            "new_string" to string("替换后的文本", 100_000),
            "append" to bool("写入时是否追加"),
            "replace_all" to bool("编辑时是否替换全部匹配"),
            "offset_bytes" to integerValue("读取偏移字节数"),
            "max_bytes" to integerValue("最多读取字节数"),
            "limit" to integer("每页最大条目数", 1, 200),
            "offset" to integerValue("目录分页偏移"),
            "glob" to string("目录文件名过滤", 1_000),
            "recursive" to bool("是否递归列目录"),
            "show_hidden" to bool("是否显示隐藏文件"),
        ),
        required = arrayOf("operation"),
    )

    private fun readImage(): JSONObject = AgentToolSchema.function(
        "read_image",
        "读取当前应用有权访问的图片并交给视觉模型分析。",
        objectSchema("path" to string("图片路径或已授权 URI", 4_000)),
        required = arrayOf("path"),
    )

    private fun skill(): JSONObject = AgentToolSchema.function(
        "skill",
        "读取本地已启用 Skill。此工具只处理本地读取和精选目录浏览。",
        objectSchema(
            "operation" to enum("技能操作", "list", "read", "resource", "curated"),
            "query" to string("筛选关键词", 500),
            "skill_id" to string("Skill id、名称或 SKILL.md 路径", 500),
            "relative_path" to string("Skill 内相对资源路径", 1_000),
            "max_chars" to integer("最多返回字符数", 512, 64_000),
            "limit" to integer("最多返回数量", 1, 200),
        ),
        required = arrayOf("operation"),
    )

    private fun skillGithub(): JSONObject = AgentToolSchema.function(
        "skill_github",
        "检查或安装公共 GitHub 仓库中的 Skill。安装前必须先检查并使用检查结果中的固定 ref/path。",
        objectSchema(
            "operation" to enum("GitHub 技能操作", "inspect", "install"),
            "repository" to string("公共仓库或 GitHub URL", 500),
            "ref" to string("分支、标签或 commit", 200),
            "path" to string("仓库内相对路径", 1_000),
            "paths" to stringArray("要安装的 Skill 路径", maxItems = 20),
            "replace_existing" to bool("是否按冲突结果精确替换"),
            "expected_replacement_id" to string("冲突结果中的精确 Skill id", 500),
        ),
        required = arrayOf("operation", "repository"),
    )

    private fun memory(writable: Boolean): JSONObject {
        val operations = if (writable) arrayOf("get", "write") else arrayOf("get")
        return AgentToolSchema.function(
            "memory",
            "读取或原子更新长期记忆。不要保存凭据、验证码或临时请求。编码模式只公开读取。",
            objectSchema(
                "operation" to enum("记忆操作", *operations),
                "query" to string("检索关键词", 500),
                "start_line" to integerValue("起始行"),
                "max_chars" to integer("最多返回字符数", AgentMemoryStore.MIN_READ_CHARS, AgentMemoryStore.MAX_READ_CHARS),
                "mode" to enum("写入方式", "replace_range", "append", "clear"),
                "revision" to string("当前记忆 revision", 64),
                "end_line" to integerValue("结束行"),
                "content" to string("写入内容", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS),
            ),
            required = arrayOf("operation"),
        )
    }

    private fun objectSchema(vararg entries: Pair<String, JSONObject>, required: Array<String> = emptyArray()): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().also { props -> entries.forEach { (name, value) -> props.put(name, value) } })
            // Reject hallucinated fields before dispatch. Canonical operations intentionally use
            // a closed contract so a typo cannot silently select a legacy default.
            .put("additionalProperties", false)
            .also { schema -> if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList())) }

    private fun function(
        name: String,
        description: String,
        parameters: JSONObject,
        required: Array<String>,
    ): JSONObject = AgentToolSchema.function(
        name = name,
        description = description,
        parameters = parameters.put("required", JSONArray(required.toList())),
    )

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject().put("type", "string").put("description", description).also { maxLength?.let { value -> it.put("maxLength", value) } }

    private fun integerValue(description: String): JSONObject = JSONObject().put("type", "integer").put("description", description)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        integerValue(description).put("minimum", minimum).put("maximum", maximum)

    private fun bool(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)

    private fun enum(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String, maxItems: Int = values.size.coerceAtLeast(1)): JSONObject =
        JSONObject().put("type", "array").put("items", if (values.isEmpty()) string(description) else enum(description, *values))
            .put("maxItems", maxItems).put("uniqueItems", true).put("description", description)
}
