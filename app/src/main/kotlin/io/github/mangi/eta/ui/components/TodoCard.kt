package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 任务清单卡片：todo_write 工具行的专用渲染。
 *
 * 头部展示"任务清单 + 已完成 N/M + 比例进度条"，默认展开逐项状态
 * （✓ 完成 / ● 进行中 / ○ 待办），让执行到哪一步一目了然；点击头部折叠。
 *
 * 数据来自 todo_write 的工具结果摘要（AgentTraceFormatter.summarizeTodoResult 的输出格式）：
 * ```
 * 进度 2/4
 * ✓ 已完成项
 * ◐ 进行中项
 * ○ 待办项
 * ```
 * 解析失败返回 null，调用方回退到普通工具行渲染（格式变化时天然降级）。
 */

/** TodoCard 的测试标记。 */
internal const val TodoCardTag = "todo-card"

/** 头部行（可点击折叠）的测试标记。 */
internal const val TodoCardHeaderTag = "todo-card-header"

/** 清单条目状态；与结果摘要里的 ✓/◐/○ 标记一一对应。 */
internal enum class TodoEntryStatusUi { Completed, InProgress, Pending }

internal data class TodoEntryUi(val content: String, val status: TodoEntryStatusUi)

internal data class TodoListUi(
    val total: Int,
    val completed: Int,
    val entries: List<TodoEntryUi>,
)

private val TodoProgressPattern = Regex("^进度\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$")
private val TodoEntryPattern = Regex("^([✓◐○])\\s+(.+)$")

/** 解析工具结果摘要；格式不符（null/空/无进度行/无条目）时返回 null。 */
internal fun parseTodoListDetail(detail: String?): TodoListUi? {
    val lines = detail
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toList()
        ?: return null
    val progress = lines.firstOrNull()?.let(TodoProgressPattern::matchEntire) ?: return null
    val completed = progress.groupValues[1].toIntOrNull() ?: return null
    val total = progress.groupValues[2].toIntOrNull() ?: return null
    if (total <= 0) return null
    val entries = lines.drop(1).mapNotNull { line ->
        val match = TodoEntryPattern.matchEntire(line) ?: return@mapNotNull null
        val status = when (match.groupValues[1]) {
            "✓" -> TodoEntryStatusUi.Completed
            "◐" -> TodoEntryStatusUi.InProgress
            else -> TodoEntryStatusUi.Pending
        }
        TodoEntryUi(content = match.groupValues[2].trim(), status = status)
    }
    if (entries.isEmpty()) return null
    return TodoListUi(total = total, completed = completed, entries = entries)
}

@Composable
internal fun TodoCard(
    message: ToolActivityMessageUi,
    todo: TodoListUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // 默认展开：最新一张卡片直接呈现"执行到哪一步"；用户可点击折叠历史卡片。
    var expanded by rememberSaveable(message.id) { mutableStateOf(true) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 0.dp else 20.dp,
                vertical = if (compact) 0.dp else 2.dp,
            )
            .testTag(TodoCardTag),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { expanded = !expanded }
                .testTag(TodoCardHeaderTag)
                .padding(horizontal = 2.dp, vertical = if (compact) 4.dp else 5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Checklist,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 14.dp else 15.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(if (compact) 6.dp else 7.dp))
            Text(
                text = stringResource(R.string.tool_todo_write),
                style = if (compact) MiuixTheme.textStyles.footnote1 else MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${todo.completed}/${todo.total}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.weight(1f))
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.work_collapse else R.string.work_expand,
                ),
                modifier = Modifier.size(13.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
            )
        }
        TodoProgressBar(
            completed = todo.completed,
            total = todo.total,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = panelExpandEnter(),
            exit = panelCollapseExit(),
        ) {
            Column(modifier = Modifier.padding(top = 5.dp, bottom = 2.dp)) {
                todo.entries.forEachIndexed { index, entry ->
                    TodoEntryRow(index = index + 1, entry = entry)
                }
            }
        }
    }
}

/** 比例进度条：completed/total 填充，代表清单完成比例。 */
@Composable
private fun TodoProgressBar(completed: Int, total: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.24f)),
    ) {
        if (total > 0 && completed > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((completed.toFloat() / total).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun TodoEntryRow(index: Int, entry: TodoEntryUi) {
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val pendingColor = secondary.copy(alpha = 0.55f)
    val inProgress = entry.status == TodoEntryStatusUi.InProgress
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 3.dp),
    ) {
        // 序号让“执行到第几步”直接可读。
        Text(
            text = index.toString(),
            style = MiuixTheme.textStyles.footnote2,
            color = if (inProgress) primary else secondary.copy(alpha = 0.7f),
            textAlign = TextAlign.End,
            modifier = Modifier
                .padding(top = 1.dp)
                .width(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(13.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (entry.status) {
                TodoEntryStatusUi.Completed -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = StatusSuccess,
                )
                TodoEntryStatusUi.InProgress -> Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(primary),
                )
                TodoEntryStatusUi.Pending -> Box(
                    modifier = Modifier
                        .size(9.dp)
                        .border(1.dp, pendingColor, CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = entry.content,
            style = MiuixTheme.textStyles.footnote1,
            color = when (entry.status) {
                TodoEntryStatusUi.InProgress -> MiuixTheme.colorScheme.onSurface
                TodoEntryStatusUi.Completed -> secondary
                TodoEntryStatusUi.Pending -> secondary.copy(alpha = 0.85f)
            },
            modifier = Modifier.weight(1f),
        )
        // 进行中的条目明确标注文字，避免只有圆点看不出状态。
        if (inProgress) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.todo_in_progress),
                style = MiuixTheme.textStyles.footnote2,
                color = primary,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}
