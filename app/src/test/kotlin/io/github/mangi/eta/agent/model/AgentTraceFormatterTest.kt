package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceFormatterTest {
    private val formatter = AgentTraceFormatter()

    @Test
    fun sensitiveToolArgumentsAreSummarizedWithoutRawValues() {
        val cases = listOf(
            RedactionCase(
                toolName = "terminal",
                argumentsJson =
                    """{"action":"open_and_exec","identity":"root","command":"echo bearer-secret"}""",
                expectedParts = listOf("终端", "单次执行", "Android", "root"),
                sensitiveParts = listOf("echo bearer-secret", "bearer-secret"),
            ),
            RedactionCase(
                toolName = "run_command",
                argumentsJson = """{"command":"cat /data/local/tmp/private-token"}""",
                expectedParts = listOf("执行命令", "Android", "root"),
                sensitiveParts = listOf("cat ", "/data/local/tmp/private-token", "private-token"),
            ),
            RedactionCase(
                toolName = "write_file",
                argumentsJson =
                    """{"path":"/data/local/tmp/secret.txt","content":"api-key-value"}""",
                expectedParts = listOf("写入文件", "secret.txt", "字符"),
                sensitiveParts = listOf("/data/local/tmp/secret.txt", "api-key-value"),
            ),
            RedactionCase(
                toolName = "input_text",
                argumentsJson = """{"text":"one-time-password-123456"}""",
                expectedParts = listOf("输入文本", "字符"),
                sensitiveParts = listOf("one-time-password-123456", "123456"),
            ),
            RedactionCase(
                toolName = "read_file",
                argumentsJson = """{"path":"/data/user/0/example/private.xml"}""",
                expectedParts = listOf("读取文件", "private.xml"),
                sensitiveParts = listOf("/data/user/0/example/private.xml"),
            ),
            RedactionCase(
                toolName = "list_directory",
                argumentsJson = """{"path":"/storage/emulated/0/Private"}""",
                expectedParts = listOf("列出目录"),
                sensitiveParts = listOf("/storage/emulated/0/Private"),
            ),
            RedactionCase(
                toolName = "memory_get",
                argumentsJson = """{"query":"private relationship"}""",
                expectedParts = listOf("检索记忆"),
                sensitiveParts = listOf("private relationship"),
            ),
            RedactionCase(
                toolName = "memory_write",
                argumentsJson = """{"mode":"append","revision":"secret-revision","content":"private memory"}""",
                expectedParts = listOf("更新记忆", "追加", "行", "字节"),
                sensitiveParts = listOf("secret-revision", "private memory"),
            ),
        )

        cases.forEach { case ->
            val summary = formatter.summarizeArguments(
                AgentModelClient.ToolCall(
                    id = "call-test",
                    name = case.toolName,
                    argumentsJson = case.argumentsJson,
                )
            )

            case.expectedParts.forEach { expected ->
                assertTrue(
                    "${case.toolName} summary must contain '$expected': $summary",
                    summary.contains(expected),
                )
            }
            case.sensitiveParts.forEach { sensitive ->
                assertFalse(
                    "${case.toolName} summary leaked '$sensitive': $summary",
                    summary.contains(sensitive),
                )
            }
        }
    }

    @Test
    fun terminalCommandsAreExposedOnlyThroughDisplayField() {
        val terminal = AgentModelClient.ToolCall(
            id = "terminal-call",
            name = "terminal",
            argumentsJson =
                """{"action":"open_and_exec","environment":"linux","command":"git status --short"}""",
        )
        val runCommand = AgentModelClient.ToolCall(
            id = "run-command-call",
            name = "run_command",
            argumentsJson = """{"command":"pm list packages | head"}""",
        )

        assertEquals("git status --short", formatter.displayCommand(terminal))
        assertEquals("pm list packages | head", formatter.displayCommand(runCommand))
        assertEquals("终端 · 单次执行 · Linux · root", formatter.summarizeArguments(terminal))
        assertFalse(formatter.summarizeArguments(terminal).contains("git status"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("observe", "observe_screen", "{}")
            )
        )
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall(
                    "oversized",
                    "run_command",
                    """{"command":"${"x".repeat(4_001)}"}""",
                )
            )
        )
    }

    @Test
    fun terminalSummaryPreservesExplicitEnvironmentNames() {
        listOf("android" to "Android", "alpine" to "Alpine", "debian" to "Debian")
            .forEach { (environment, label) ->
                val summary = formatter.summarizeArguments(
                    AgentModelClient.ToolCall(
                        id = "terminal-environment",
                        name = "terminal",
                        argumentsJson =
                            """{"action":"open_and_exec","environment":"$environment"}""",
                    ),
                )

                assertEquals("终端 · 单次执行 · $label · root", summary)
            }
    }

    @Test
    fun displayedTerminalCommandsRedactCommonCredentialForms() {
        val command = """export API_KEY='sk-secret'; curl -H 'Authorization: Bearer token-value' --password hunter2 https://example.com?access_token=query-secret"""
        val displayed = formatter.displayCommand(
            AgentModelClient.ToolCall(
                id = "secret-command",
                name = "run_command",
                argumentsJson = JSONObject().put("command", command).toString(),
            )
        )!!

        assertTrue(displayed.contains("API_KEY=<已隐藏>"))
        assertTrue(displayed.contains("Authorization: <已隐藏>"))
        assertTrue(displayed.contains("--password <已隐藏>"))
        assertTrue(displayed.contains("access_token=<已隐藏>"))
        assertFalse(displayed.contains("sk-secret"))
        assertFalse(displayed.contains("token-value"))
        assertFalse(displayed.contains("hunter2"))
        assertFalse(displayed.contains("query-secret"))
    }

    @Test
    fun malformedSensitiveArgumentsUseSafeFallback() {
        val summary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "call-test",
                name = "terminal",
                argumentsJson = "{secret-command",
            )
        )

        assertTrue(summary.contains("终端"))
        assertFalse(summary.contains("secret-command"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("call-test", "terminal", "{secret-command")
            )
        )
    }

    @Test
    fun screenObservationSummaryUsesTreeFirstDefaults() {
        val defaultSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-default",
                name = "observe_screen",
                argumentsJson = "{}",
            ),
        )
        val screenshotSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-image",
                name = "observe_screen",
                argumentsJson = """{"include_screenshot":true}""",
            ),
        )

        assertTrue(defaultSummary.contains("观察屏幕"))
        assertTrue(defaultSummary.contains("含界面树"))
        assertFalse(defaultSummary.contains("含截图"))
        assertTrue(screenshotSummary.contains("含截图"))
        assertTrue(screenshotSummary.contains("含界面树"))
    }

    @Test
    fun browserResultSummaryIsHumanReadable() {
        val success = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"navigate","page":{"url":"https://example.com/path?token=secret-query","title":"Example Domain"}}""",
            ),
        )

        assertTrue(success.contains("已打开"))
        assertTrue(success.contains("example.com"))
        assertTrue(success.contains("《Example Domain》"))
        assertFalse(success.contains("ok="))
        assertFalse(success.contains("token=secret-query"))

        val failure = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"USER_CONTROL_ACTIVE","action":"click"}""",
            ),
        )

        assertTrue(failure.startsWith("失败"))
        assertTrue(failure.contains("code=USER_CONTROL_ACTIVE"))

        val readable = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"get_readable","text_chars":23000,"truncated":true,"page":{"url":"https://example.com"}}""",
            ),
        )

        assertTrue(readable.contains("已提取正文"))
        assertTrue(readable.contains("约 2.3 万字"))
        assertTrue(readable.contains("已截断"))
    }

    @Test
    fun resultSuccessComesFromOkFlagNotSummaryText() {
        val ok = AgentModelClient.ToolResult(content = """{"ok":true}""")
        val failed = AgentModelClient.ToolResult(content = """{"ok":false,"code":"X"}""")
        val noFlag = AgentModelClient.ToolResult(content = """{"data":1}""")
        val malformed = AgentModelClient.ToolResult(content = "{broken")

        assertTrue(formatter.isSuccessResult(ok))
        assertFalse(formatter.isSuccessResult(failed))
        assertTrue(formatter.isSuccessResult(noFlag))
        assertTrue(formatter.isSuccessResult(malformed))
        assertEquals("完成", formatter.summarizeResult("tap", ok))
        assertEquals("失败 · code=X", formatter.summarizeResult("tap", failed))
    }

    @Test
    fun memoryResultSummaryContainsOnlyStatusLineAndByteMetadata() {
        val summary = formatter.summarizeResult(
            "memory_get",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"bytes":321,"line_count":9,"content":"private memory"}""",
                sensitive = true,
            ),
        )

        assertTrue(summary.contains("已读取记忆"))
        assertTrue(summary.contains("9 行"))
        assertTrue(summary.contains("321 字节"))
        assertFalse(summary.contains("ok="))
        assertFalse(summary.contains("private memory"))
    }

    @Test
    fun searchQueryIsShownInSummary() {
        val searchApps = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-apps",
                name = "search_apps",
                argumentsJson = """{"query":"抖音"}""",
            ),
        )
        assertEquals("搜索应用 · 抖音", searchApps)

        val searchFiles = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-files",
                name = "search_files",
                argumentsJson = """{"query":"周报"}""",
            ),
        )
        assertEquals("搜索文件 · 周报", searchFiles)

        // 关键词单行化并截断，避免撑爆折叠行标题
        val longQuery = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-long",
                name = "search_apps",
                argumentsJson = """{"query":"${"很长的关键词".repeat(10)}\n第二行"}""",
            ),
        )
        assertFalse(longQuery.contains("\n"))
        assertTrue(longQuery.length <= "搜索应用 · ".length + 30 + 3)

        // 非搜索类设备工具不追加 query
        val deviceStatus = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "device-status",
                name = "device_status",
                argumentsJson = """{"query":"不应出现"}""",
            ),
        )
        assertEquals("查看设备状态", deviceStatus)
    }

    @Test
    fun searchAppsResultListsAppNames() {
        val summary = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """
                    {"ok":true,"tool":"search_apps","query":"抖音","apps":[
                      {"app_name":"抖音","package_name":"com.ss.android.ugc.aweme","is_system_app":false},
                      {"app_name":"抖音极速版","package_name":"com.ss.android.ugc.aweme.lite","is_system_app":false},
                      {"app_name":"抖音商城","package_name":"com.ss.android.ugc.livelite","is_system_app":false},
                      {"app_name":"抖音火山版","package_name":"com.ss.android.ugc.live","is_system_app":false}
                    ]}
                """.trimIndent(),
            ),
        )

        assertTrue(summary.contains("已找到 4 个应用"))
        assertTrue(summary.contains("抖音、抖音极速版、抖音商城"))
        assertTrue(summary.contains("等"))
        assertFalse(summary.contains("com.ss.android"))

        val empty = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"search_apps","query":"不存在的应用","apps":[]}""",
            ),
        )
        assertEquals("未找到匹配应用", empty)
    }

    @Test
    fun launchAppResultShowsAppName() {
        val summary = formatter.summarizeResult(
            "launch_app",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"launch_app","app_name":"抖音","package_name":"com.ss.android.ugc.aweme"}""",
            ),
        )

        assertEquals("已打开 · 抖音", summary)
    }

    @Test
    fun todoListSummaryShowsProgressAndItems() {
        val args = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "todo-1",
                name = "todo_write",
                argumentsJson = """
                    {"todos":[
                      {"content":"梳理现状","status":"completed"},
                      {"content":"实现提醒","status":"in_progress"},
                      {"content":"回归验证","status":"pending"}
                    ]}
                """.trimIndent(),
            ),
        )
        assertTrue(args.contains("任务清单"))
        assertTrue(args.contains("已完成 1/3"))
        assertTrue(args.contains("进行中：实现提醒"))

        val result = formatter.summarizeResult(
            "todo_write",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"todo_write","total":3,"completed":1,"items":[
                  {"content":"梳理现状","status":"completed"},
                  {"content":"实现提醒","status":"in_progress"},
                  {"content":"回归验证","status":"pending"}
                ]}""",
            ),
        )
        assertTrue(result.startsWith("进度 1/3"))
        assertTrue(result.contains("✓ 梳理现状"))
        assertTrue(result.contains("◐ 实现提醒"))
        assertTrue(result.contains("○ 回归验证"))
    }

    @Test
    fun terminalResultShowsExitCodeAndOutputPreview() {
        val success = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"run_command","exit_code":0,"stdout":"hello\nworld","stderr":"","timed_out":false}""",
            ),
        )
        assertTrue(success.startsWith("执行完成"))
        assertTrue(success.contains("hello\nworld"))

        val failure = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"terminal","action":"exec","exit_code":1,"stdout":"","stderr":"Permission denied"}""",
            ),
        )
        assertTrue(failure.startsWith("失败 · 退出码 1"))
        assertTrue(failure.contains("Permission denied"))

        val timedOut = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"run_command","exit_code":-2,"timed_out":true,"stdout":""}""",
            ),
        )
        assertEquals("失败 · 执行超时", timedOut)

        // 协议错误仍保留 code= 标记
        val coded = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"JOB_NOT_FOUND"}""",
            ),
        )
        assertEquals("失败 · code=JOB_NOT_FOUND", coded)

        // 带中文原因的错误：原因面向用户，code= 留给日志提取
        val codedWithMessage = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TERMINAL_TOOLS_DISABLED","message":"请先启用终端/文件工具"}""",
            ),
        )
        assertEquals("失败 · 请先启用终端/文件工具 · code=TERMINAL_TOOLS_DISABLED", codedWithMessage)

        // 无输出的会话动作
        val closed = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"terminal","action":"close","closed_session":true}""",
            ),
        )
        assertEquals("终端 · 关闭终端", closed)
    }

    @Test
    fun failureSummaryShowsHumanReasonAndKeepsCodeMarker() {
        val summary = formatter.summarizeResult(
            "wait_for_text",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TIMEOUT","message":"等待文本超时：抖音"}""",
            ),
        )
        assertEquals("失败 · 等待文本超时：抖音 · code=TIMEOUT", summary)

        val codeOnly = formatter.summarizeResult(
            "tap_element",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"INVALID_NODE_INDEX"}""",
            ),
        )
        assertEquals("失败 · code=INVALID_NODE_INDEX", codeOnly)
    }

    @Test
    fun fileReadDetailShowsNameAndLineRange() {
        val call = AgentModelClient.ToolCall(
            id = "call-read",
            name = "read_file",
            argumentsJson =
                """{"path":"/data/user/0/example/Main.kt","offset_bytes":0,"max_bytes":4096}""",
        )
        val content = (1..88).joinToString("\n") { "line-$it" }
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("tool", "read_file")
                .put("path", "/data/user/0/example/Main.kt")
                .put("offset_bytes", 0)
                .put("bytes_read", 4096)
                .put("truncated", false)
                .put("content", content)
                .toString(),
        )

        val detail = formatter.summarizeDetail("read_file", call.argumentsJson, result)

        assertTrue(detail.contains("文件：Main.kt"))
        assertTrue(detail.contains("第 1–88 行"))
        assertTrue(detail.contains("4096 字节"))
        assertFalse(detail.contains("/data/user/0"))

        val offsetRead = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("tool", "read_file")
                .put("path", "/data/x/a.txt")
                .put("offset_bytes", 4096)
                .put("bytes_read", 10)
                .put("truncated", true)
                .put("content", "a\nb")
                .toString(),
        )
        val offsetDetail = formatter.summarizeDetail("read_file", call.argumentsJson, offsetRead)
        assertTrue(offsetDetail.contains("自字节 4096 起"))
        assertTrue(offsetDetail.contains("已截断"))
    }

    @Test
    fun fileWriteDetailShowsDiffOrPreview() {
        val call = AgentModelClient.ToolCall(
            id = "call-write",
            name = "write_file",
            argumentsJson =
                """{"path":"/data/x/Config.kt","content":"val a = 1\nval b = 2"}""",
        )
        val withDiff = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("tool", "write_file")
                .put("path", "/data/x/Config.kt")
                .put("mode", "overwrite")
                .put("bytes_written", 20)
                .put("diff", "第 1 行起：旧 2 行 → 新 2 行\n- val a = 0\n+ val a = 1")
                .toString(),
        )
        val detail = formatter.summarizeDetail("write_file", call.argumentsJson, withDiff)
        assertTrue(detail.contains("文件：Config.kt"))
        assertTrue(detail.contains("旧内容对比"))
        assertTrue(detail.contains("- val a = 0"))

        val newFile = AgentModelClient.ToolResult(
            content = """{"ok":true,"tool":"write_file","mode":"overwrite","bytes_written":20}""",
        )
        val previewDetail = formatter.summarizeDetail("write_file", call.argumentsJson, newFile)
        assertTrue(previewDetail.contains("新内容预览"))
        assertTrue(previewDetail.contains("val a = 1"))
        assertTrue(previewDetail.contains("覆盖写入"))
    }

    @Test
    fun fileEditDetailShowsReplacementSnippet() {
        val call = AgentModelClient.ToolCall(
            id = "call-edit",
            name = "edit_file",
            argumentsJson = JSONObject()
                .put("path", "/repo/app/Main.kt")
                .put("old_string", "val a = 0")
                .put("new_string", "val a = 1\nval b = 2")
                .toString(),
        )
        val result = AgentModelClient.ToolResult(
            content = """{"ok":true,"tool":"edit_file","replacements":1,"bytes_written":30}""",
        )

        val detail = formatter.summarizeDetail("edit_file", call.argumentsJson, result)

        assertTrue(detail.contains("文件：Main.kt"))
        assertTrue(detail.contains("替换 1 处"))
        assertTrue(detail.contains("- val a = 0"))
        assertTrue(detail.contains("+ val a = 1"))
    }

    @Test
    fun terminalDetailShowsLongerBoundedOutput() {
        val longStdout = (1..30).joinToString("\n") { "line-$it" }
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("tool", "run_command")
                .put("exit_code", 0)
                .put("stdout", longStdout)
                .put("stderr", "warn: careful")
                .put("stdout_truncated", true)
                .toString(),
        )

        val detail = formatter.summarizeDetail("run_command", "{}", result)

        assertTrue(detail.contains("退出码 0"))
        assertTrue(detail.contains("输出（stdout）"))
        assertTrue(detail.contains("line-14"))
        assertFalse(detail.contains("line-15"))
        assertTrue(detail.contains("输出已截断"))
        assertTrue(detail.contains("错误输出（stderr）"))
        assertTrue(detail.contains("warn: careful"))
    }

    @Test
    fun terminalResultPreviewIsBounded() {
        val longOutput = (1..10).joinToString("\n") { "line-$it-${"x".repeat(100)}" }
        val summary = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "run_command")
                    .put("exit_code", 0)
                    .put("stdout", longOutput)
                    .put("stdout_truncated", true)
                    .toString(),
            ),
        )

        val previewLines = summary.lines()
        // 状态行 + 最多 3 行预览 + 省略标记
        assertTrue(previewLines.size <= 5)
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length < longOutput.length)
    }

    @Test
    fun listDirectoryResultAndDetailShowCountsAndPaging() {
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("tool", "list_directory")
                .put("path", "/data/x/repo")
                .put("exit_code", 0)
                .put("total", 5)
                .put("offset", 0)
                .put("count", 2)
                .put("truncated", true)
                .put("entries_text", "d app\n- settings.gradle")
                .put("stderr", "")
                .toString(),
        )

        val summary = formatter.summarizeResult("list_directory", result)
        assertTrue(summary.contains("共 5 项"))
        assertTrue(summary.contains("显示 2 项"))
        assertTrue(summary.contains("已截断"))

        val detail = formatter.summarizeDetail("list_directory", """{"path":"/data/x/repo"}""", result)
        assertTrue(detail.contains("共 5 项 · 本页 2 项"))
        assertTrue(detail.contains("offset=2"))
        assertTrue(detail.contains("d app"))
        assertFalse(detail.contains("/data/x/repo"))

        val empty = AgentModelClient.ToolResult(
            content = """{"ok":true,"tool":"list_directory","total":0,"offset":0,"count":0,"truncated":false,"entries_text":""}""",
        )
        assertEquals("空目录", formatter.summarizeResult("list_directory", empty))
    }

    @Test
    fun subagentResultShowsInterruptionAndFilteredTools() {
        val results = org.json.JSONArray()
            .put(
                JSONObject()
                    .put("label", "调研 A")
                    .put("ok", true)
                    .put("duration_ms", 1200)
                    .put("content", "done")
                    .put("changed_files", org.json.JSONArray()),
            )
            .put(
                JSONObject()
                    .put("label", "改码 B")
                    .put("ok", false)
                    .put("code", "SUBAGENT_INTERRUPTED")
                    .put("duration_ms", 180000)
                    .put("content", "子代理被取消或中断")
                    .put("verify_hint", "先读 changed_files 核实现状")
                    .put("still_running", true)
                    .put("changed_files", org.json.JSONArray().put("/repo/a.kt")),
            )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("mode", "research")
                .put("total", 2)
                .put("succeeded", 1)
                .put("interrupted", 1)
                .put("fanout_elapsed_ms", 181000)
                .put("results", results)
                .put("filtered_tools", org.json.JSONArray().put("terminal"))
                .toString(),
        )

        val summary = formatter.summarizeResult("spawn_agents", result)
        assertTrue(summary.contains("1/2 成功"))
        assertTrue(summary.contains("未完成 1"))
        assertTrue(summary.contains("已过滤 1 个工具"))

        val detail = formatter.summarizeDetail("spawn_agents", """{"tasks":[{"label":"x"}]}""", result)
        assertTrue(detail.contains("共 2 项 · 成功 1 项 · 未完成 1 项"))
        assertTrue(detail.contains("被模式过滤的工具"))
        assertTrue(detail.contains("terminal"))
        assertTrue(detail.contains("✓ 调研 A"))
        assertTrue(detail.contains("✗ 改码 B"))
        assertTrue(detail.contains("先读 changed_files 核实"))
        assertTrue(detail.contains("线程仍在收尾"))
    }

    @Test
    fun codeSearchDetailShowsHitsAndGlobHint() {
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("tool", "search_code")
                .put("path", "/repo")
                .put("pattern", "Foo")
                .put("glob", "")
                .put("count", 3)
                .put("truncated", true)
                .put(
                    "results",
                    org.json.JSONArray()
                        .put("/repo/a.kt:1:class Foo")
                        .put("/repo/b.kt:7:fun foo()"),
                )
                .toString(),
        )

        val summary = formatter.summarizeResult("search_code", result)
        assertTrue(summary.contains("命中 3 条"))
        assertTrue(summary.contains("已截断"))

        val detail = formatter.summarizeDetail("search_code", """{"pattern":"Foo"}""", result)
        assertTrue(detail.contains("命中 3 条"))
        assertTrue(detail.contains("glob"))
        assertTrue(detail.contains("/repo/a.kt:1:class Foo"))
    }

    private data class RedactionCase(
        val toolName: String,
        val argumentsJson: String,
        val expectedParts: List<String>,
        val sensitiveParts: List<String>,
    )
}
