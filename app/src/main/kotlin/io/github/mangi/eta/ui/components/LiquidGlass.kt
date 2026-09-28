package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LiquidGlassCornerRadius = 24.dp
private val LiquidGlassBorderWidth = 0.5.dp
private val LiquidGlassHighlightHeight = 40.dp

@Composable
internal fun Modifier.liquidGlassSurface(
    cornerRadius: Dp = LiquidGlassCornerRadius,
    alpha: Float = 0.72f,
): Modifier {
    val surfaceColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = alpha)
    val borderColor = Color.White.copy(alpha = 0.22f)
    return this
        .squircleSurface(color = surfaceColor, cornerRadius = cornerRadius)
        .squircleBorder(
            width = LiquidGlassBorderWidth,
            color = borderColor,
            cornerRadius = cornerRadius,
        )
}

@Composable
internal fun Modifier.liquidGlassHighlight(
    cornerRadius: Dp = LiquidGlassCornerRadius,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    val highlightBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.18f),
            Color.White.copy(alpha = 0.04f),
            Color.Transparent,
        ),
    )
    return this
        .clip(shape)
        .background(highlightBrush)
}

@Composable
internal fun LiquidGlassDivider(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LiquidGlassCornerRadius,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(cornerRadius))
    )
}
