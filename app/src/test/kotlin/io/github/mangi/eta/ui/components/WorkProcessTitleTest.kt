package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 工作过程标题的纯函数：动词识别、加粗首行提取、标题优先级与终端命令目标。 */
class WorkProcessTitleTest {
    private fun tool(
        name: String,
        summary: String = "",
        command: String? = null,
        status: ToolActivityStatusUi = ToolActivityStatusUi.Success,
    ) = ToolActivityMessageUi(
        id = "tool-$name",
        toolName = name,
        status = status,
        argumentsSummary = summary,
        command = command,
    )

    @Test
    fun verbMappingCoversCanonicalAggregatedToolsAndLegacyNames() {
        assertEquals(ToolActionVerb.Write, toolActionVerb("file_ops", "文件操作 · write"))
        assertEquals(ToolActionVerb.Edit, toolActionVerb("file_ops", "文件操作 · edit"))
        assertEquals(ToolActionVerb.Search, toolActionVerb("file_ops", "文件操作 · search"))
        assertEquals(ToolActionVerb.Read, toolActionVerb("file_ops", "文件操作 · read"))
        assertEquals(ToolActionVerb.Read, toolActionVerb("file_ops", "文件操作 · list"))
        assertNull(toolActionVerb("file_ops", "文件操作"))
        assertEquals(ToolActionVerb.Run, toolActionVerb("terminal"))
        assertEquals(ToolActionVerb.Run, toolActionVerb("run_command"))
        assertEquals(ToolActionVerb.Search, toolActionVerb("web_search"))
        assertEquals(ToolActionVerb.Browse, toolActionVerb("browser_use"))
        assertEquals(ToolActionVerb.View, toolActionVerb("observe_screen"))
        assertEquals(ToolActionVerb.Open, toolActionVerb("app_action"))
        assertEquals(ToolActionVerb.Read, toolActionVerb("read_file"))
        assertNull(toolActionVerb("custom_tool"))
    }

    @Test
    fun boldFirstLineOnlyAcceptsLeadingBoldSpan() {
        assertEquals("Analyzing the request", boldFirstLine("**Analyzing the request**\nmore text"))
        assertEquals("标题", boldFirstLine("  **标题**  "))
        assertNull(boldFirstLine("hello **world**"))
        assertNull(boldFirstLine(""))
        // 49 字的加粗段超过标题长度上限，不当作标题（多半是正文强调）。
        assertNull(boldFirstLine("**" + "x".repeat(49) + "**"))
    }

    @Test
    fun blockTitlePrefersBoldLineThenLatestMappedTool() {
        val withBold = listOf(
            ThinkingMessageUi("t1", "**Planning the refactor**\nsteps", isStreaming = false),
            tool("terminal", command = "ls -la"),
        )
        assertEquals(WorkProcessTitle.Literal("Planning the refactor"), workProcessTitle(withBold))

        val toolOnly = listOf(
            ThinkingMessageUi("t1", "no bold here", isStreaming = false),
            tool("read_file", summary = "读取文件 · a.md"),
            tool("custom_tool", summary = "whatever"),
        )
        val action = workProcessTitle(toolOnly) as WorkProcessTitle.Action
        assertEquals(ToolActionVerb.Read, action.verb)
        assertEquals("read_file", action.tool.toolName)

        assertNull(workProcessTitle(listOf(tool("custom_tool", summary = "x"))))
    }

    @Test
    fun toolTitleTargetPrefersTerminalCommandFirstLine() {
        assertEquals(
            "ls -la && git status",
            toolTitleTarget("terminal", "ls -la && git status\nsecond", "终端 · 执行"),
        )
        assertEquals("终端 · 执行", toolTitleTarget("terminal", null, "终端 · 执行"))
        assertEquals("读取文件 · a.md", toolTitleTarget("read_file", "ignored", "读取文件 · a.md"))
    }
}
