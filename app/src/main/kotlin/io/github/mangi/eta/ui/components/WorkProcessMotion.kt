package io.github.mangi.eta.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工作过程的动效原语（对齐 VS Code Copilot Chat）：
 * 运行中短语轮换、尾部标签扫光、工具行不确定进度条。
 *
 * 统一约束：动画值一律只在绘制阶段读取（draw 阶段 State 读取只触发重绘），
 * 不因每帧动画触发重组；系统关闭动画时不创建任何无限动画。
 */

/** 尾部工作提示行的测试标记（布局测试与 theme 无关，不依赖具体短语文案）。 */
internal const val WorkProcessTailTag = "work-process-tail"

/** 尾部提示的文案类别，对齐 VS Code 的 thinking / terminal / tool 三组短语池。 */
internal enum class WorkingPhraseCategory { Thinking, Tool, Terminal }

/** 短语按步号顺序轮换；每 [WorkingFunPhraseInterval] 步插入一条彩蛋文案。 */
internal const val WorkingFunPhraseInterval = 50

/** 最近条目决定短语类别：末端是工具时按工具族选择，思考或正文则回到思考池。 */
internal fun workingPhraseCategory(lastToolName: String?): WorkingPhraseCategory =
    when (lastToolName) {
        null -> WorkingPhraseCategory.Thinking
        "terminal", "run_command" -> WorkingPhraseCategory.Terminal
        else -> WorkingPhraseCategory.Tool
    }

/**
 * 确定性轮换：`step % pool.size` 顺序取用（可单测、无随机抖动）；
 * 每 [WorkingFunPhraseInterval] 步用彩蛋池替换一次。池为空时返回空串。
 */
internal fun pickWorkingPhrase(pool: List<String>, funPool: List<String>, step: Int): String {
    if (pool.isEmpty()) return ""
    if (step <= 0) return pool[0]
    if (step % WorkingFunPhraseInterval == 0 && funPool.isNotEmpty()) {
        return funPool[(step / WorkingFunPhraseInterval - 1) % funPool.size]
    }
    return pool[step % pool.size]
}

/** 三类短语池 + 彩蛋池；文案与 VS Code 默认池一一对应，中文按当前语言本地化。 */
@Composable
internal fun rememberWorkingPhrase(category: WorkingPhraseCategory, step: Int): String {
    val thinking = listOf(
        stringResource(R.string.work_phrase_thinking_1),
        stringResource(R.string.work_phrase_thinking_2),
        stringResource(R.string.work_phrase_thinking_3),
        stringResource(R.string.work_phrase_thinking_4),
        stringResource(R.string.work_phrase_thinking_5),
        stringResource(R.string.work_phrase_thinking_6),
    )
    val tool = listOf(
        stringResource(R.string.work_phrase_tool_1),
        stringResource(R.string.work_phrase_tool_2),
        stringResource(R.string.work_phrase_tool_3),
        stringResource(R.string.work_phrase_tool_4),
        stringResource(R.string.work_phrase_tool_5),
    )
    val terminal = listOf(
        stringResource(R.string.work_phrase_terminal_1),
        stringResource(R.string.work_phrase_terminal_2),
        stringResource(R.string.work_phrase_terminal_3),
    )
    val funPool = listOf(
        stringResource(R.string.work_phrase_fun_1),
        stringResource(R.string.work_phrase_fun_2),
        stringResource(R.string.work_phrase_fun_3),
    )
    val pool = when (category) {
        WorkingPhraseCategory.Thinking -> thinking
        WorkingPhraseCategory.Tool -> tool
        WorkingPhraseCategory.Terminal -> terminal
    }
    return pickWorkingPhrase(pool, funPool, step)
}

/** 系统关闭动画（动画缩放为 0）时不再创建无限动画；判定与 StartupSplash 一致。 */
@Composable
internal fun rememberAnimationsEnabled(): Boolean = remember {
    val scale = runCatching { ValueAnimator.getDurationScale() }.getOrNull()
    ValueAnimator.areAnimatorsEnabled() && (scale == null || (scale.isFinite() && scale > 0f))
}

/** 扫光条宽度：固定长度，短标签也能看到完整扫过。 */
private val WorkShimmerSweep = 84.dp

/** 一次扫光的周期；比脉冲稍快，接近 VS Code 的 shimmer 节奏。 */
private const val WorkShimmerPeriodMillis = 1_400

/**
 * 尾部工作提示的扫光：高光带从左向右扫过标签文字。
 *
 * 离屏层保证 [BlendMode.SrcAtop] 只作用于标签自身的字形像素，
 * 不会在父布局背景上刷出一道色带（与窗口渐隐同源的教训）。
 */
@Composable
internal fun Modifier.workingShimmer(
    enabled: Boolean,
    highlight: Color = MiuixTheme.colorScheme.onSurface,
): Modifier {
    if (!enabled || !rememberAnimationsEnabled()) return this
    val transition = rememberInfiniteTransition(label = "work_shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(WorkShimmerPeriodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "work_shimmer_progress",
    )
    val sweepPx = with(LocalDensity.current) { WorkShimmerSweep.toPx() }
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val startX = -sweepPx + progress * (size.width + sweepPx * 2f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        highlight.copy(alpha = 0.85f),
                        Color.Transparent,
                    ),
                    startX = startX,
                    endX = startX + sweepPx,
                ),
                size = size,
                blendMode = BlendMode.SrcAtop,
            )
        }
}

private val ToolRunProgressHeight = 2.dp

/** 高光段占轨道的比例：过长会失去"推进中"的节奏感。 */
private const val ToolRunProgressSegmentFraction = 0.4f

private const val ToolRunProgressPeriodMillis = 1_200

/**
 * 工具运行中的不确定进度条：高光段循环扫过静态轨道。
 * 动画值只在绘制阶段读取；系统关闭动画时退化为静态轨道。
 */
@Composable
internal fun ToolRunProgressBar(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    trackColor: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
) {
    val bar = Modifier
        .fillMaxWidth()
        .height(ToolRunProgressHeight)
        .clip(CircleShape)
        .background(trackColor)
    if (!rememberAnimationsEnabled()) {
        Box(modifier.then(bar))
        return
    }
    val transition = rememberInfiniteTransition(label = "tool_run_progress")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ToolRunProgressPeriodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tool_run_progress_sweep",
    )
    Box(
        modifier = modifier
            .then(bar)
            .drawWithContent {
                drawContent()
                val segment = size.width * ToolRunProgressSegmentFraction
                val startX = -segment + progress * (size.width + segment)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, color, Color.Transparent),
                        startX = startX,
                        endX = startX + segment,
                    ),
                    size = size,
                )
            },
    )
}
