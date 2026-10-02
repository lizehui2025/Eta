package io.github.mangi.eta.ui.components

import kotlin.math.roundToInt

/** Scroll phase for the user turn currently being produced. */
internal enum class UserTurnScrollPhase {
    Idle,
    Pinned,
    FollowingOverflow,
    UserControlled,
}

internal data class UserTurnScrollState(
    val phase: UserTurnScrollPhase = UserTurnScrollPhase.Idle,
    val anchorMessageId: String? = null,
    val resumeAfterDrag: Boolean = false,
    val previousUserMessageId: String? = null,
)

internal sealed interface UserTurnScrollEvent {
    data class Submitted(
        val anchorMessageId: String? = null,
        val previousUserMessageId: String? = null,
    ) : UserTurnScrollEvent
    data object ContentOverflow : UserTurnScrollEvent
    data class UserDragged(val atBottom: Boolean) : UserTurnScrollEvent
    data object PanelToggled : UserTurnScrollEvent
    data object ReturnedToBottom : UserTurnScrollEvent
    data object Reset : UserTurnScrollEvent
}

internal fun resolveUserTurnScrollTransition(
    state: UserTurnScrollState,
    event: UserTurnScrollEvent,
): UserTurnScrollState = when (event) {
    is UserTurnScrollEvent.Submitted -> UserTurnScrollState(
        phase = UserTurnScrollPhase.Pinned,
        anchorMessageId = event.anchorMessageId,
        previousUserMessageId = event.previousUserMessageId,
    )

    UserTurnScrollEvent.ContentOverflow -> when (state.phase) {
        UserTurnScrollPhase.Pinned -> state.copy(phase = UserTurnScrollPhase.FollowingOverflow)
        else -> state
    }

    is UserTurnScrollEvent.UserDragged -> state.copy(
        phase = UserTurnScrollPhase.UserControlled,
        resumeAfterDrag = true,
    )

    UserTurnScrollEvent.PanelToggled -> state.copy(
        phase = UserTurnScrollPhase.UserControlled,
        resumeAfterDrag = false,
    )

    UserTurnScrollEvent.ReturnedToBottom -> if (
        state.phase == UserTurnScrollPhase.UserControlled && state.resumeAfterDrag
    ) state.copy(phase = UserTurnScrollPhase.FollowingOverflow, resumeAfterDrag = false)
    else state

    UserTurnScrollEvent.Reset -> UserTurnScrollState()
}

/**
 * Tail space needed to make the anchored user message reachable at the top without filling the
 * whole screen. Once the response itself fills the viewport this naturally reaches zero.
 */
internal fun resolveUserTurnReservePx(
    viewportHeightPx: Int,
    contentAfterAnchorPx: Int?,
    maxFraction: Float = 1f,
): Int {
    if (viewportHeightPx <= 0) return 0
    // Before the new item is measured, reserve enough space for its first top alignment.
    val needed = (viewportHeightPx - (contentAfterAnchorPx ?: 0)).coerceAtLeast(0)
    val maximum = (viewportHeightPx * maxFraction.coerceIn(0f, 1f)).roundToInt()
    return needed.coerceAtMost(maximum)
}

internal fun isUserTurnAnchorReady(
    state: UserTurnScrollState,
    latestUserMessageId: String?,
): Boolean = state.phase == UserTurnScrollPhase.Pinned && latestUserMessageId != null &&
    if (state.anchorMessageId != null) latestUserMessageId == state.anchorMessageId
    else latestUserMessageId != state.previousUserMessageId

internal fun resolveUserTurnViewportEndPx(
    viewportEndOffsetPx: Int,
    inputAndBottomPaddingPx: Int,
): Int = (viewportEndOffsetPx - inputAndBottomPaddingPx).coerceAtLeast(0)

internal fun shouldAlignTurnToBottom(
    phase: UserTurnScrollPhase,
    isUserDragging: Boolean,
): Boolean = phase != UserTurnScrollPhase.UserControlled && !isUserDragging
