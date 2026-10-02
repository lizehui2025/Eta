package io.github.mangi.eta.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 折叠面板（思考块、工具详情、子代理步骤）展开/收起时共用的高度过渡。
 *
 * 历史背景：展开以前用 spring 尺寸动画，而当时正文是未限高的 Markdown，动画每一帧都要
 * 重新测量上千行内容，展开因此严重掉帧；性能优化时被降级成纯淡入淡出，代价是高度瞬间跳变，
 * 点击反馈变得突兀。现在正文已限高，思考全文分段布局，尺寸动画重新变得廉价，
 * 但把时长压到 180ms 以内：既有连续的展开感，也不会长时间拖动列表重排。
 */
internal const val PANEL_EXPAND_MILLIS = 180
internal const val PANEL_COLLAPSE_MILLIS = 160

private val panelExpandSpring = spring<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 360f,
)

private val panelCollapseSpring = spring<IntSize>(
    dampingRatio = 0.94f,
    stiffness = 420f,
)

internal fun panelExpandEnter(): EnterTransition =
    fadeIn(tween(PANEL_EXPAND_MILLIS, easing = FastOutSlowInEasing)) +
        expandVertically(panelExpandSpring)

internal fun panelCollapseExit(): ExitTransition =
    fadeOut(tween(PANEL_COLLAPSE_MILLIS, easing = FastOutSlowInEasing)) +
        shrinkVertically(panelCollapseSpring)

@Composable
internal fun rememberThinkingExpansionProgress(expanded: Boolean): Float {
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (expanded) PANEL_EXPAND_MILLIS else PANEL_COLLAPSE_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        label = "thinking_collapse_progress",
    )
    return progress
}

@Composable
internal fun ThinkingCollapseBody(
    expanded: Boolean,
    progress: Float,
    measuredHeightPx: Int,
    onHeightMeasured: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!expanded && progress <= 0f) return
    Layout(
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        content = { Column(Modifier.fillMaxWidth()) { content() } },
    ) { measurables, constraints ->
        // Measure at the expanded height. Only the height reported to the card shrinks;
        // forcing the child height inside a shrinking Box can retain its old bounds.
        val body = measurables.single().measure(constraints.copy(minHeight = 0))
        if (expanded && body.height > 0 && body.height != measuredHeightPx) {
            onHeightMeasured(body.height)
        }
        val height = resolveThinkingSlotHeight(progress, body.height)
        layout(body.width, height) { body.placeRelative(0, 0) }
    }
}

/**
 * 折叠指示箭头：展开时旋转 90°，而不是在 ChevronRight / ExpandMore 之间瞬间切换图标。
 * 旋转只更新绘制层，不触发重新布局，因此不会给滚动和展开重新引入开销。
 */
@Composable
internal fun ExpandChevron(
    expanded: Boolean,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 360f,
        ),
        label = "expand_chevron_rotation",
    )
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { rotationZ = rotation },
        tint = tint,
    )
}
