package io.github.mangi.eta.ui.components

/** Height reported by the thinking body during its expand/collapse transition. */
internal fun resolveThinkingSlotHeight(
    progress: Float,
    measuredHeightPx: Int,
): Int = (measuredHeightPx.coerceAtLeast(0) * progress.coerceIn(0f, 1f)).toInt()

/** Returns true when a new user turn should clear retained collapse slots. */
internal fun resetThinkingCollapseSlots(
    previousResetKey: Long,
    currentResetKey: Long,
): Boolean = currentResetKey != 0L && currentResetKey != previousResetKey

internal fun thinkingBodyHeightDp(
    viewportHeightDp: Float,
    headerAndPaddingDp: Float = 56f,
): Float = (viewportHeightDp * 0.5f - headerAndPaddingDp).coerceAtLeast(48f)
