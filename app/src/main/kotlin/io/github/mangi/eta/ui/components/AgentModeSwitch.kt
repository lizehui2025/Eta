package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏左上角的聊天/编码模式切换（两段式胶囊，点选切换）。
 *
 * - 聊天模式：正常读取并保存持久记忆；
 * - 编码模式：记忆只读、不主动保存（工具表不暴露 memory_write，写入在执行期被复查拦截）。
 *
 * 模式在 run 开始时快照，决定该次运行的提示词与工具表；切换只影响之后的 run，
 * 而写入许可在执行期还会动态复查，中途切到编码模式会立即阻止进行中的 run 继续保存。
 */
@Composable
internal fun AgentModeSwitch(
    mode: AgentMode,
    onModeChange: (AgentMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.secondaryContainer,
                cornerRadius = 15.dp,
            )
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentModeSegment(
            label = stringResource(R.string.agent_mode_chat),
            active = mode == AgentMode.CHAT,
            onClick = { onModeChange(AgentMode.CHAT) },
        )
        AgentModeSegment(
            label = stringResource(R.string.agent_mode_coding),
            active = mode == AgentMode.CODING,
            onClick = { onModeChange(AgentMode.CODING) },
        )
    }
}

@Composable
private fun AgentModeSegment(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .squircleSurface(
                color = if (active) MiuixTheme.colorScheme.primary else Color.Transparent,
                cornerRadius = 13.dp,
            )
            .clickable(enabled = !active, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (active) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            maxLines = 1,
        )
    }
}
