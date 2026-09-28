package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object EtaColors {
    val SuccessLight = Color(0xFF00BD13)
    val SuccessDark = Color(0xFF00D415)
    val WarningLight = Color(0xFFFFB200)
    val WarningDark = Color(0xFFFFC833)
    val PreferenceBlue = Color(0xFF0080FF)
    val PreferenceOrange = Color(0xFFFF7700)
    val OverlayBubbleDark = Color(0xFF37393D)
    val OverlayBubbleLight = Color(0xFFE5E7EA)
    val PreferenceBackgroundFallback = Color(0xFFF0F1F2)
}

internal object EtaSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

internal object EtaRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

internal object EtaDimens {
    val CardCorner = EtaRadius.xxl
    val ButtonCorner = EtaRadius.xl
    val DialogCorner = EtaRadius.xl
    val InputCorner = EtaRadius.xl
    val ChipCorner = EtaRadius.md
    val IconSize = 24.dp
    val IconSizeSm = 16.dp
    val IconSizeXs = 12.dp
    val RowMinHeight = 52.dp
    val ButtonMinHeight = 44.dp
}

@Composable
internal fun statusSuccessColor(): Color =
    if (MiuixTheme.colorScheme.background.luminance() > 0.5f) {
        EtaColors.SuccessLight
    } else {
        EtaColors.SuccessDark
    }

@Composable
internal fun statusWarningColor(): Color =
    if (MiuixTheme.colorScheme.background.luminance() > 0.5f) {
        EtaColors.WarningLight
    } else {
        EtaColors.WarningDark
    }

@Composable
internal fun overlayBubbleColor(): Color =
    if (MiuixTheme.colorScheme.background.luminance() > 0.5f) {
        EtaColors.OverlayBubbleLight
    } else {
        EtaColors.OverlayBubbleDark
    }
