package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "terminal",
                    description = "Manage terminal sessions on the current device. environment=android runs Android system commands and root operations; environment=linux runs the Alpine or Debian environment selected by the user. Apktool build is unavailable until an ARM64 AAPT2 runtime is installed. Use open_and_exec for one-shot commands. Use open to create a persistent shell session and exec with session_id for multi-step work. Use async=true without session_id for long-running independent commands, then read_async_result with job_id to stream output chunks. Use daemon_start for services that must keep running after the Agent run (listening ports, web panels, watchers): the process detaches from any command shell, logs to a file, and survives until daemon_stop or device reboot. Manage daemons with daemon_list, daemon_logs and daemon_stop by task_id; daemon_list defaults to 10 entries (max 50), lists running tasks first, pages with offset/limit and truncates command text to 120 chars. Use close to stop jobs or close sessions.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("open")
                                                .put("exec")
                                                .put("open_and_exec")
                                                .put("read_async_result")
                                                .put("close")
                                                .put("daemon_start")
                                                .put("daemon_list")
                                                .put("daemon_logs")
                                                .put("daemon_stop")
                                        )
                                        .put("description", "open creates a session. exec runs command in a session or cwd. open_and_exec runs a one-shot command. read_async_result reads async output by job_id. close closes a session_id or job_id. daemon_start launches a detached long-lived service and returns task_id. daemon_list lists daemon tasks with liveness: running tasks first, commands truncated to 120 chars, default limit 10 (max 50); page with limit/offset (the response returns offset, next_offset and has_more); filter with running_only or state; exited records stay listed (stale_count) until daemon_stop removes them. daemon_logs tails a task log. daemon_stop terminates and removes a task.")
                                )
                                .put(
                                    "identity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("user").put("root"))
                                        .put("description", "宿主执行身份。Android 默认使用当前可用身份；Linux 根据已选择的后端使用 user 或 root。PRoot 内模拟 root 不授予 Android 特权。")
                                )
                                .put(
                                    "environment",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("android").put("linux"))
                                        .put("description", "android uses the native Android shell with BusyBox applets when available, for device data and system/root operations. linux uses the Alpine or Debian environment selected in Eta settings, for finding code, running scripts and processing data. Defaults to Linux when a Linux environment is installed, otherwise android.")
                                )
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Android shell command to execute. Required for exec/open_and_exec.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory. Defaults to workspace for android and /workspace for linux. Relative paths use the environment default. ~/ means /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Command timeout in milliseconds. Default 30000, max 180000.")
                                )
                                .put(
                                    "merge_stderr",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether stderr should be appended to stdout in command responses.")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Session id returned by action=open. Use with exec or close.")
                                )
                                .put(
                                    "job_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Async job id returned when async=true. Use with read_async_result or close.")
                                )
                                .put(
                                    "task_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Daemon task id returned by daemon_start. Use with daemon_logs or daemon_stop.")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For daemon_list only: maximum tasks to return. Default 10, max 50. Running tasks are shown first.")
                                )
                                .put(
                                    "offset",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For daemon_list only: skip the first N matched tasks after sorting (paging, 0-based, default 0). Combine with limit; the response returns offset, next_offset and has_more.")
                                )
                                .put(
                                    "running_only",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "For daemon_list only: return only running tasks. Default false.")
                                )
                                .put(
                                    "state",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("running").put("exited").put("all"))
                                        .put("description", "For daemon_list only: filter tasks by state (running/exited/all). Default all; takes precedence over running_only.")
                                )
                                .put(
                                    "async",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Start command in a separate background shell and return immediately with job_id. Do not combine with session_id. Use read_async_result to stream output.")
                                )
                                .put(
                                    "offset_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, read stdout from this character offset. Default 0.")
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, maximum stdout characters to return. Default 8000, max 16000.")
                                )
                                .put(
                                    "close_if_done",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "For read_async_result, remove the async job when it has completed.")
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "run_command",
                    description = "在 Android 设备上用非交互 Root Shell 执行命令。适合系统信息、包管理、路径与文件系统检查、Linux 命令流水线。每次调用都是新 shell；不要运行交互式或长期驻留命令；读写或修改文件内容请优先用 read_file/write_file/edit_file/search_code，不要用 cat/sed/grep/perl 等命令替代；目录浏览优先用 list_directory（支持翻页/过滤/递归），内容定位优先用 search_code，共享文档检索用 search_files，不要用 ls/find/grep 手工重复实现。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要执行的 shell 命令，可使用管道和重定向。")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "工作目录，默认 workspace 终端工作区。相对路径也按该目录解析；用户存储可用 ~/ 表示 /storage/emulated/0。")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "超时秒数，1 到 180，默认 30。")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "read_file",
                    description = "读取 Android 文件内容。适合读取配置、日志、小文本文件；大文件用 offset_bytes/max_bytes 分段读取。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "offset_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "从第几个字节开始，默认 0。")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多读取字节数，默认 65536；有 Root 时上限 262144，无 Root 时上限 16000。")
                                )
                                .put("find", autoFindSchemaDescription())
                                .put("no_fail", autoFindNoFailSchemaDescription())
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "write_file",
                    description = "写入 Android 文件。可覆盖或追加；会自动创建父目录；覆盖采用临时文件原子替换。已有文件的局部修改优先使用 edit_file。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("content", JSONObject().put("type", "string"))
                                .put(
                                    "append",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true 追加，false 覆盖，默认 false。")
                                )
                                .put("find", autoFindSchemaDescription())
                                .put("no_fail", autoFindNoFailSchemaDescription())
                        )
                        .put("required", JSONArray().put("path").put("content"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "list_directory",
                    description = "列出 Android 目录内容（按名排序，目录前缀 d、文件前缀 -，不含 . 和 ..）。默认 workspace 终端工作区；支持 limit/offset 翻页、glob 按文件名过滤（逗号分隔，支持 * 与 ?）、recursive 递归列出子目录。返回 total/count/offset/truncated 与 entries_text；按名找文件用它，按内容定位用 search_code，找手机文档/下载用 search_files，不要用 ls/find 手工分页。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("show_hidden", JSONObject().put("type", "boolean"))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "每页最多返回 1 到 200 行，默认 80。")
                                )
                                .put(
                                    "offset",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "按排序跳过前 N 行，默认 0，用于翻页。")
                                )
                                .put(
                                    "glob",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选文件名过滤，如 *.kt 或 *.kt,*.md。")
                                )
                                .put(
                                    "recursive",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true 递归列出子目录（相对路径），默认 false 只列一层。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "edit_file",
                    description = "对已有文本文件做精确替换：old_string 必须与文件内容完全一致且默认要求唯一匹配；命中多处时设置 replace_all=true 或补充上下文。写入采用临时文件原子替换，适合代码与配置的小改动，避免整文件重写。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "old_string",
                                    JSONObject().put("type", "string")
                                        .put("description", "要被替换的原文，包含空白与缩进。")
                                )
                                .put(
                                    "new_string",
                                    JSONObject().put("type", "string")
                                        .put("description", "替换后的新文本；允许空串以删除内容。")
                                )
                                .put(
                                    "replace_all",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "true 时替换全部匹配，默认 false 且要求唯一匹配。")
                                )
                                .put("find", autoFindSchemaDescription())
                                .put("no_fail", autoFindNoFailSchemaDescription())
                        )
                        .put("required", JSONArray().put("path").put("old_string").put("new_string"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_code",
                    description = "在目录内按扩展正则搜索文本并返回绝对路径 path:line:text 列表；默认跳过 .git、build、node_modules、.gradle、.idea，可用 glob 限定文件名（逗号分隔，支持 * 与 ?），自动跳过二进制文件。单次最多扫描 3000 个文件、最多返回 200 条；正则非法返回 INVALID_PATTERN。适合定位实现、用法与调用点；按名找文件用 list_directory，找手机文档用 search_files。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "pattern",
                                    JSONObject().put("type", "string")
                                        .put("description", "扩展正则表达式，最长 200 字符。")
                                )
                                .put(
                                    "path",
                                    JSONObject().put("type", "string")
                                        .put("description", "搜索根目录，默认使用终端工作区。")
                                )
                                .put(
                                    "glob",
                                    JSONObject().put("type", "string")
                                        .put("description", "可选文件名过滤，如 *.kt 或 *.kt,*.md。")
                                )
                                .put(
                                    "max_results",
                                    JSONObject().put("type", "integer")
                                        .put("description", "最多返回 1 到 200 条，默认 200。")
                                )
                        )
                        .put("required", JSONArray().put("pattern"))
                )
            )
    }

}

/** 读写工具共用的 `find` 参数声明（默认关闭的自动查找，不是 search 工具）。 */
private fun autoFindSchemaDescription(): JSONObject =
    JSONObject()
        .put("type", "boolean")
        .put(
            "description",
            "可选、默认关闭：目标文件不存在时在工作区按文件名自动查找；唯一匹配直接采用，" +
                "多个匹配返回候选列表（不自动采用）。"
        )

/** 读写工具共用的 `no_fail` 参数声明：只返回候选、不自动采用，优先级高于 find。 */
private fun autoFindNoFailSchemaDescription(): JSONObject =
    JSONObject()
        .put("type", "boolean")
        .put(
            "description",
            "可选、默认关闭：目标不存在时只返回候选列表供下一步选择，不自动采用任何候选；" +
                "优先级高于 find。"
        )
