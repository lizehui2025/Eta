package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 运行中指示：像素方块 spinner。
 *
 * 对齐 VS Code Copilot Chat 的 `monaco-pixel-spinner`（grid 变体）：2 列 × 3 行的
 * 小方块，每块自上而下"落格"（出现 → 停稳 → 下落消失）循环，六块错开延迟形成
 * 级联填充的观感，作为「会话 / 子代理 / 工具正在运行」的统一指示。
 *
 * 约束与全项目动画规范一致：动画值只在 draw 阶段读取（运行期间零重组）；
 * [active] 为 false 或系统关闭动画时回落为静态全亮网格（与 CSS 的
 * `prefers-reduced-motion` 回落一致）。
 */

/** PixelSpinner 的测试标记。 */
internal const val PixelSpinnerTag = "pixel-spinner"

/** 整轮级联周期（1820ms），与 monaco-pixel-spinner 一致。 */
private const val PixelSpinnerPeriodMillis = 1_820

/** 单点起落关键帧变体：停留与消失时点不同（对应 CSS 的 cycle / -long / -short）。 */
internal enum class PixelDotKeyframes(val holdEnd: Float, val fadeEnd: Float) {
    Normal(holdEnd = 0.5714f, fadeEnd = 0.6648f),
    Long(holdEnd = 0.6429f, fadeEnd = 0.7363f),
    Short(holdEnd = 0.50f, fadeEnd = 0.5934f),
}

/** 单点规格：出现延迟与关键帧变体；列表顺序即 2×3 网格的排布顺序。 */
internal data class PixelDotSpec(
    val delayMillis: Int,
    val keyframes: PixelDotKeyframes,
)

/** 六个点的规格，延迟取自 monaco-pixel-spinner 的逐点 animation-delay。 */
internal val PixelSpinnerDots: List<PixelDotSpec> = listOf(
    PixelDotSpec(delayMillis = 520, keyframes = PixelDotKeyframes.Normal),
    PixelDotSpec(delayMillis = 650, keyframes = PixelDotKeyframes.Normal),
    PixelDotSpec(delayMillis = 260, keyframes = PixelDotKeyframes.Normal),
    PixelDotSpec(delayMillis = 390, keyframes = PixelDotKeyframes.Normal),
    PixelDotSpec(delayMillis = 0, keyframes = PixelDotKeyframes.Long),
    PixelDotSpec(delayMillis = 130, keyframes = PixelDotKeyframes.Short),
)

/** 单点帧：透明度与纵向偏移（单位：点边长；-2 ≈ 上方两个点高，+3.5 ≈ 下方三个半点）。 */
internal data class PixelDotFrame(
    val alpha: Float,
    val offsetInDots: Float,
)

/** 上升段终点（9.34%）：此后点就位并保持完全可见。 */
private const val PixelDotEnterEnd = 0.0934f

/** CSS translateY(-4px)，按 2px 点折算。 */
private const val PixelDotRiseOffsetDots = -2f

/** CSS translateY(7px)，按 2px 点折算。 */
private const val PixelDotFallOffsetDots = 3.5f

/** 与 CSS `steps(6, jump-none)` 对应的量化档数。 */
private const val PixelDotSteps = 6

/**
 * 单点在全局相位（0..1）下的帧。
 *
 * 与 CSS 等价：每段进度用 `steps(6, jump-none)` 量化成 6 档（像素跳动感），
 * 位移与透明度共用同一量化进度，保证"落格"按整档移动。
 */
internal fun pixelDotFrame(spec: PixelDotSpec, globalPhase: Float): PixelDotFrame {
    val delay = spec.delayMillis.toFloat() / PixelSpinnerPeriodMillis
    val phase = positiveFraction(globalPhase - delay)
    return when {
        phase < PixelDotEnterEnd -> {
            val step = stepsQuantize(phase / PixelDotEnterEnd, PixelDotSteps)
            PixelDotFrame(
                alpha = step,
                offsetInDots = PixelDotRiseOffsetDots * (1f - step),
            )
        }
        phase < spec.keyframes.holdEnd -> PixelDotFrame(alpha = 1f, offsetInDots = 0f)
        phase < spec.keyframes.fadeEnd -> {
            val span = spec.keyframes.fadeEnd - spec.keyframes.holdEnd
            val step = stepsQuantize((phase - spec.keyframes.holdEnd) / span, PixelDotSteps)
            PixelDotFrame(
                alpha = 1f - step,
                offsetInDots = PixelDotFallOffsetDots * step,
            )
        }
        else -> PixelDotFrame(alpha = 0f, offsetInDots = PixelDotFallOffsetDots)
    }
}

/** CSS `steps(n, jump-none)`：把 [0,1] 进度量化成 n 档（含两端值）。 */
internal fun stepsQuantize(progress: Float, steps: Int): Float {
    if (steps <= 1) return 0f
    val lastStep = steps - 1
    val clamped = progress.coerceIn(0f, 1f)
    return floor(clamped * lastStep + 1e-6f).toInt().coerceAtMost(lastStep).toFloat() / lastStep
}

private fun positiveFraction(value: Float): Float {
    val wrapped = value % 1f
    return if (wrapped < 0f) wrapped + 1f else wrapped
}

/**
 * 像素方块 spinner。
 *
 * @param active 为 false 时不做动画（静态全亮网格），适合已完成但仍需占位的场景。
 * @param color 点颜色；默认取次要前景色，可传工具状态色保持既有配色语义。
 * @param size 容器边长；点尺寸按 `size / 8` 等比（16dp 容器 → 2dp 点，与参考实现一致）。
 */
@Composable
internal fun PixelSpinner(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    size: Dp = 16.dp,
) {
    val resolvedColor = if (color == Color.Unspecified) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        color
    }
    val animating = active && rememberAnimationsEnabled()
    val phase: () -> Float = if (animating) {
        val transition = rememberInfiniteTransition(label = "pixel_spinner")
        val progress = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PixelSpinnerPeriodMillis, easing = LinearEasing),
            ),
            label = "pixel_spinner_phase",
        )
        remember(progress) { { progress.value } }
    } else {
        remember { { 0f } }
    }
    Canvas(
        modifier = modifier
            .size(size)
            .testTag(PixelSpinnerTag),
    ) {
        drawPixelSpinnerDots(
            phase = if (animating) phase() else null,
            color = resolvedColor,
        )
    }
}

/** 绘制 6 点网格；[phase] 为 null 时画静态全亮网格（关闭动画的回落）。 */
private fun DrawScope.drawPixelSpinnerDots(phase: Float?, color: Color) {
    val dot = size.minDimension / 8f
    val gap = dot
    val gridWidth = dot * 2 + gap
    val gridHeight = dot * 3 + gap * 2
    val originX = (size.width - gridWidth) / 2f
    val originY = (size.height - gridHeight) / 2f
    for (index in PixelSpinnerDots.indices) {
        val column = index % 2
        val row = index / 2
        val frame = phase?.let { pixelDotFrame(PixelSpinnerDots[index], it) }
            ?: PixelDotFrame(alpha = 1f, offsetInDots = 0f)
        if (frame.alpha <= 0.01f) continue
        drawRect(
            color = color.copy(alpha = color.alpha * frame.alpha),
            topLeft = Offset(
                x = originX + column * (dot + gap),
                y = originY + row * (dot + gap) + frame.offsetInDots * dot,
            ),
            size = Size(dot, dot),
        )
    }
}
