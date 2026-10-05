package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

/**
 * 指示色对比度保障（纯函数，便于单测）。
 *
 * 背景是浅色（默认白色主题）时，主题里的 pastel 强调色（浅蓝/浅灰）作为 2dp
 * 小方块几乎不可见；深色主题同理存在过暗的强调色。这里按 WCAG 相对亮度计算
 * 对比度，不足 [MIN_INDICATOR_CONTRAST] 时沿色相向黑或白混合，只加深/提亮、
 * 不换色相，保证运行指示在两种主题下都清晰可辨。
 */

/** 图形元素（非文本）的最低对比度：WCAG 2.1 非文本对比度要求。 */
internal const val MIN_INDICATOR_CONTRAST = 3.0f

private const val BLEND_STEP = 0.05f
private const val MAX_BLEND = 0.85f

/**
 * 返回在 [background] 上满足最低对比度的 [color]；已达标时原样返回（不做任何改动）。
 * 半透明色先按背景合成后判定，混合结果保留原 alpha（动画仍可乘透明度）。
 */
internal fun ensureReadableTint(
    color: Color,
    background: Color,
    minContrast: Float = MIN_INDICATOR_CONTRAST,
): Color {
    if (color == Color.Unspecified || background == Color.Unspecified) return color
    if (contrastRatio(color, background) >= minContrast) return color
    // 浅背景向黑混合、深背景向白混合，沿同一色相加深/提亮。
    val target = if (relativeLuminance(background) > 0.5f) Color.Black else Color.White
    var step = 0f
    var candidate = color
    // lerp 会同时向 target 的 alpha=1 插值；判定必须固定回原 alpha，
    // 否则半透明色会“靠变不透明”达标，而最终返回的仍是原 alpha。
    while (step < MAX_BLEND &&
        contrastRatio(candidate.copy(alpha = color.alpha), background) < minContrast
    ) {
        step += BLEND_STEP
        candidate = lerp(color, target, step)
    }
    return candidate.copy(alpha = color.alpha)
}

/** WCAG 对比度；前景半透明时先与背景合成再计算。 */
internal fun contrastRatio(foreground: Color, background: Color): Float {
    val fg = relativeLuminance(
        if (foreground.alpha < 1f) foreground.compositeOver(background) else foreground,
    )
    val bg = relativeLuminance(background)
    return (maxOf(fg, bg) + 0.05f) / (minOf(fg, bg) + 0.05f)
}

/** WCAG 相对亮度（sRGB 线性化）。 */
internal fun relativeLuminance(color: Color): Float {
    fun linear(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * linear(color.red) +
        0.7152f * linear(color.green) +
        0.0722f * linear(color.blue)
}
