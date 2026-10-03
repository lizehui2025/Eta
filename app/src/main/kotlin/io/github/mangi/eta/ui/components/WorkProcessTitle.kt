package io.github.mangi.eta.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/**
 * 工作过程与工具行的标题（对齐 VS Code Copilot Chat）：
 * - 块标题优先取模型思考首行的加粗短句（VS Code 的 header 提取规则），否则取最近一次工具动作；
 * - 工具行完成态用过去式动词（「已运行 · ls -la」），运行中保持显示名；
 * - 终端以真实命令为首选目标（VS Code 的 "Ran `ls`"），其余工具沿用参数摘要。
 */

/** 工具行标题里的动作动词；未收录的工具保持显示名不变。 */
internal enum class ToolActionVerb { Read, Write, Edit, Search, Run, Browse, View, Open }

/** 工作过程标题：模型思考首行的字面短句，或最近一次可识别的工具动作。 */
internal sealed interface WorkProcessTitle {
    data class Literal(val text: String) : WorkProcessTitle

    data class Action(val verb: ToolActionVerb, val tool: ToolActivityMessageUi) : WorkProcessTitle
}

/** 终端类工具：标题目标优先取真实命令，而不是「终端 · 执行 · Android · root」的环境串。 */
private val TerminalToolNames = setOf("terminal", "run_command")

/** 目标文本上限：单行标题会被省略号截断，这里只防止超长命令拖慢排版。 */
private const val ToolTitleTargetMaxChars = 160

/** 思考首行的加粗短句；超过 48 字的加粗段多半是正文强调，不当作标题。 */
private val BoldFirstLine = Regex("^\\s*\\*\\*([^*\\n]{1,48})\\*\\*")

/** 工具名（含 file_ops 的 operation）→ 动作动词；未收录返回 null，调用方回退显示名。 */
internal fun toolActionVerb(toolName: String, argumentsSummary: String = ""): ToolActionVerb? =
    when (toolName) {
        // file_ops 是聚合工具，operation 决定动词；argumentsSummary 形如「文件操作 · write」。
        "file_ops" -> when (argumentsSummary.substringAfter(" · ", "").trim()) {
            "write" -> ToolActionVerb.Write
            "edit" -> ToolActionVerb.Edit
            "search" -> ToolActionVerb.Search
            "read", "list" -> ToolActionVerb.Read
            else -> null
        }
        "read_file", "read_image", "list_directory" -> ToolActionVerb.Read
        "write_file" -> ToolActionVerb.Write
        "edit_file", "replace_text" -> ToolActionVerb.Edit
        "web_search", "search_code", "search_apps" -> ToolActionVerb.Search
        "terminal", "run_command" -> ToolActionVerb.Run
        "browser_use" -> ToolActionVerb.Browse
        "observe_screen" -> ToolActionVerb.View
        "launch_app", "open_uri", "app_action" -> ToolActionVerb.Open
        else -> null
    }

/** 思考首行的加粗短句；无标题时返回 null。 */
internal fun boldFirstLine(text: String): String? =
    BoldFirstLine.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 工作过程标题：思考首行加粗短句优先，其次最近一次可识别的工具动作；
 * 两者都没有时返回 null，调用方回退到步骤数文案。
 */
internal fun workProcessTitle(messages: List<AgentChatMessageUi>): WorkProcessTitle? {
    messages.firstNotNullOfOrNull { message ->
        (message as? ThinkingMessageUi)?.let { boldFirstLine(it.content) }
    }?.let { return WorkProcessTitle.Literal(it) }
    messages.asReversed().firstNotNullOfOrNull { message ->
        val tool = message as? ToolActivityMessageUi ?: return@firstNotNullOfOrNull null
        toolActionVerb(tool.toolName, tool.argumentsSummary)
            ?.let { verb -> WorkProcessTitle.Action(verb, tool) }
    }?.let { return it }
    return null
}

/** 标题目标：终端优先真实命令首行；其余工具沿用参数摘要。 */
internal fun toolTitleTarget(toolName: String, command: String?, argumentsSummary: String): String {
    if (toolName in TerminalToolNames) {
        command?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it.take(ToolTitleTargetMaxChars) }
    }
    return argumentsSummary
}

@StringRes
internal fun toolDoneVerbResource(verb: ToolActionVerb): Int = when (verb) {
    ToolActionVerb.Read -> R.string.work_tool_done_read
    ToolActionVerb.Write -> R.string.work_tool_done_write
    ToolActionVerb.Edit -> R.string.work_tool_done_edit
    ToolActionVerb.Search -> R.string.work_tool_done_search
    ToolActionVerb.Run -> R.string.work_tool_done_run
    ToolActionVerb.Browse -> R.string.work_tool_done_browse
    ToolActionVerb.View -> R.string.work_tool_done_view
    ToolActionVerb.Open -> R.string.work_tool_done_open
}

/**
 * 工具行的展示标题：完成态用过去式动词（如「已运行 · ls -la」），
 * 运行中或未收录动词时沿用「显示名 · 摘要」。
 */
@Composable
internal fun toolActionTitle(tool: ToolActivityMessageUi): String = toolActionTitle(
    toolName = tool.toolName,
    command = tool.command,
    argumentsSummary = tool.argumentsSummary,
    status = tool.status,
)

@Composable
internal fun toolActionTitle(
    toolName: String,
    command: String?,
    argumentsSummary: String,
    status: ToolActivityStatusUi,
): String {
    val displayName = toolDisplayName(toolName)
    val verb = toolActionVerb(toolName, argumentsSummary)
        ?.takeIf { status != ToolActivityStatusUi.Running }
    val label = verb?.let { stringResource(toolDoneVerbResource(it)) } ?: displayName
    val raw = toolTitleTarget(toolName, command, argumentsSummary)
    var target = if (raw.startsWith(displayName)) raw.removePrefix(displayName) else raw
    target = target.trim().trimStart('·').trim()
    // 聚合工具的 operation 与动词同义（write → 已写入），标题里不再重复这一段。
    if (verb != null && toolName == "file_ops") {
        target = target.substringAfter(" · ", "").trim()
    }
    return if (target.isBlank()) label else "$label · $target"
}

/** 块标题的展示文本：字面短句直接使用，工具动作复用工具行标题（含过去式动词）。 */
@Composable
internal fun workProcessTitleText(title: WorkProcessTitle): String = when (title) {
    is WorkProcessTitle.Literal -> title.text
    is WorkProcessTitle.Action -> toolActionTitle(title.tool)
}
