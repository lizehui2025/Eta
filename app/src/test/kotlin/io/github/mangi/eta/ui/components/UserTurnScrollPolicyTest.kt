package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTurnScrollPolicyTest {
    @Test
    fun submissionPinsTheNewUserTurn() {
        val next = resolveUserTurnScrollTransition(
            UserTurnScrollState(),
            UserTurnScrollEvent.Submitted("user-1"),
        )
        assertEquals(UserTurnScrollPhase.Pinned, next.phase)
        assertEquals("user-1", next.anchorMessageId)
    }

    @Test
    fun shortContentHasEnoughReserveToPlaceUserAtTop() {
        assertEquals(900, resolveUserTurnReservePx(1000, 100))
        assertEquals(0, resolveUserTurnReservePx(1000, 1000))
    }

    @Test
    fun unmeasuredUserMessageStillGetsInitialTopSpace() {
        assertEquals(1000, resolveUserTurnReservePx(1000, null))
        assertEquals(0, resolveUserTurnReservePx(0, null))
    }

    @Test
    fun inputInsetIsExcludedButTailReserveDoesNotReduceVisibleArea() {
        assertEquals(800, resolveUserTurnViewportEndPx(1000, 200))
        assertEquals(500, resolveUserTurnReservePx(800, 300))
        assertEquals(0, resolveUserTurnReservePx(800, 850))
    }

    @Test
    fun submissionWaitsForNewUserInsteadOfPinningPreviousTurn() {
        val pending = resolveUserTurnScrollTransition(
            UserTurnScrollState(),
            UserTurnScrollEvent.Submitted(previousUserMessageId = "old-user"),
        )
        assertTrue(!isUserTurnAnchorReady(pending, "old-user"))
        assertTrue(!isUserTurnAnchorReady(pending, null))
        assertTrue(isUserTurnAnchorReady(pending, "new-user"))
        val edited = resolveUserTurnScrollTransition(
            pending,
            UserTurnScrollEvent.Submitted("old-user", "old-user"),
        )
        assertTrue(isUserTurnAnchorReady(edited, "old-user"))
    }

    @Test
    fun overflowEnablesSmoothFollowingWithoutChangingAnchor() {
        val pinned = UserTurnScrollState(UserTurnScrollPhase.Pinned, "user-1")
        val next = resolveUserTurnScrollTransition(pinned, UserTurnScrollEvent.ContentOverflow)
        assertEquals(UserTurnScrollPhase.FollowingOverflow, next.phase)
        assertEquals("user-1", next.anchorMessageId)
    }

    @Test
    fun userDragTakesControlAndReturningToBottomOnlyResumesFollowing() {
        val pinned = UserTurnScrollState(UserTurnScrollPhase.Pinned, "user-1")
        val controlled = resolveUserTurnScrollTransition(
            pinned,
            UserTurnScrollEvent.UserDragged(atBottom = false),
        )
        assertEquals(UserTurnScrollPhase.UserControlled, controlled.phase)
        val resumed = resolveUserTurnScrollTransition(
            controlled,
            UserTurnScrollEvent.ReturnedToBottom,
        )
        assertEquals(UserTurnScrollPhase.FollowingOverflow, resumed.phase)
        assertTrue(resumed.anchorMessageId == "user-1")
    }

    @Test
    fun microDragAtBottomDoesNotResumeFollowing() {
        val following = UserTurnScrollState(UserTurnScrollPhase.FollowingOverflow, "user-1")
        // 从未离开底部（轻触/微扫）：松手后仍由用户接管，不重新武装跟底。
        val controlled = resolveUserTurnScrollTransition(
            following,
            UserTurnScrollEvent.UserDragged(atBottom = true),
        )
        assertEquals(UserTurnScrollPhase.UserControlled, controlled.phase)
        assertEquals(
            UserTurnScrollPhase.UserControlled,
            resolveUserTurnScrollTransition(controlled, UserTurnScrollEvent.ReturnedToBottom).phase,
        )
        // 离开过底部再回来：恢复跟底。
        val leftBottom = resolveUserTurnScrollTransition(
            controlled,
            UserTurnScrollEvent.UserDragged(atBottom = false),
        )
        assertEquals(
            UserTurnScrollPhase.FollowingOverflow,
            resolveUserTurnScrollTransition(leftBottom, UserTurnScrollEvent.ReturnedToBottom).phase,
        )
    }

    @Test
    fun resetReturnsToIdle() {
        assertEquals(
            UserTurnScrollPhase.Idle,
            resolveUserTurnScrollTransition(
                UserTurnScrollState(UserTurnScrollPhase.FollowingOverflow, "user-1"),
                UserTurnScrollEvent.Reset,
            ).phase,
        )
    }

    @Test
    fun panelToggleCannotResumeFollowUntilUserDrags() {
        val pinned = UserTurnScrollState(UserTurnScrollPhase.FollowingOverflow, "user-1")
        val paused = resolveUserTurnScrollTransition(pinned, UserTurnScrollEvent.PanelToggled)
        assertEquals(UserTurnScrollPhase.UserControlled, paused.phase)
        assertEquals(paused, resolveUserTurnScrollTransition(paused, UserTurnScrollEvent.ReturnedToBottom))
        val dragged = resolveUserTurnScrollTransition(
            paused,
            UserTurnScrollEvent.UserDragged(atBottom = false),
        )
        assertEquals(
            UserTurnScrollPhase.FollowingOverflow,
            resolveUserTurnScrollTransition(dragged, UserTurnScrollEvent.ReturnedToBottom).phase,
        )
    }

    @Test
    fun completionAlignsOnlyWhileUserHasNotTakenControl() {
        assertTrue(shouldAlignTurnToBottom(UserTurnScrollPhase.Pinned, false))
        assertTrue(shouldAlignTurnToBottom(UserTurnScrollPhase.FollowingOverflow, false))
        assertTrue(!shouldAlignTurnToBottom(UserTurnScrollPhase.UserControlled, false))
        assertTrue(!shouldAlignTurnToBottom(UserTurnScrollPhase.FollowingOverflow, true))
    }
}
