package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCollapsePolicyTest {
    @Test
    fun cardBodyHeightFollowsExpansionProgress() {
        val measured = 420
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val body = resolveThinkingSlotHeight(progress, measured)
            assertTrue(body in 0..measured)
        }
        assertEquals(0, resolveThinkingSlotHeight(0f, measured))
        assertEquals(measured, resolveThinkingSlotHeight(1f, measured))
    }

    @Test
    fun newTurnClearsOldCollapseSpace() {
        assertFalse(resetThinkingCollapseSlots(1L, 1L))
        assertTrue(resetThinkingCollapseSlots(1L, 2L))
    }

    @Test
    fun thinkingUsesHalfViewport() {
        assertEquals(344f, thinkingBodyHeightDp(800f))
        assertEquals(48f, thinkingBodyHeightDp(80f))
    }
}
