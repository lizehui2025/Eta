package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 像素 spinner 帧函数：关键帧四段（上升 / 停留 / 下落 / 隐藏）、长/短变体、
 * 逐点延迟与 steps(6, jump-none) 量化。
 */
class PixelSpinnerTest {
    private fun dot(
        delayMillis: Int = 0,
        keyframes: PixelDotKeyframes = PixelDotKeyframes.Normal,
    ) = PixelDotSpec(delayMillis = delayMillis, keyframes = keyframes)

    @Test
    fun dotRisesHoldsFallsThenHidesWithinOneCycle() {
        // 上升段：仍在下方上方且未全亮。
        val rising = pixelDotFrame(dot(), 0.05f)
        assertTrue("上升段应仍在上方：offset=${rising.offsetInDots}", rising.offsetInDots < 0f)
        assertTrue("上升段应未全亮：alpha=${rising.alpha}", rising.alpha in 0.01f..0.99f)
        // 停留段：完全可见且就位。
        val hold = pixelDotFrame(dot(), 0.3f)
        assertEquals(1f, hold.alpha, 0.0001f)
        assertEquals(0f, hold.offsetInDots, 0.0001f)
        // 下落段：向下位移并淡出。
        val falling = pixelDotFrame(dot(), 0.62f)
        assertTrue("下落段应向下位移：offset=${falling.offsetInDots}", falling.offsetInDots > 0f)
        assertTrue("下落段应开始淡出：alpha=${falling.alpha}", falling.alpha < 1f)
        // 隐藏段：完全不可见。
        assertEquals(0f, pixelDotFrame(dot(), 0.9f).alpha, 0.0001f)
    }

    @Test
    fun keyframeVariantsShiftFallingStart() {
        // 0.6 相位：长停留变体仍就位（hold 到 0.6429），短停留变体已在下落（hold 到 0.50）。
        val longDot = pixelDotFrame(dot(keyframes = PixelDotKeyframes.Long), 0.6f)
        assertEquals(1f, longDot.alpha, 0.0001f)
        val shortDot = pixelDotFrame(dot(keyframes = PixelDotKeyframes.Short), 0.55f)
        assertTrue(shortDot.alpha < 1f)
        assertTrue(shortDot.offsetInDots > 0f)
    }

    @Test
    fun dotDelayShiftsItsLocalPhase() {
        val delayed = dot(delayMillis = 520)
        // 520ms / 1820ms：全局相位正好走到该点相位起点，点尚未出现。
        assertEquals(0f, pixelDotFrame(delayed, 520f / 1820f).alpha, 0.0001f)
        // 全局相位再前移一点，该点进入上升段。
        val afterStart = pixelDotFrame(delayed, 520f / 1820f + 0.05f)
        assertTrue(afterStart.alpha > 0.01f)
        assertTrue(afterStart.offsetInDots < 0f)
    }

    @Test
    fun riseProgressIsQuantizedToSixSteps() {
        val samples = (0..40).map { index ->
            pixelDotFrame(dot(), index / 40f * 0.0934f).alpha
        }
        val distinct = samples.map { (it * 1000).toInt() }.distinct()
        assertTrue("上升段应量化到最多 6 档，实际=${distinct.size}", distinct.size <= 6)
    }

    @Test
    fun phaseWrapsAcrossCycles() {
        val base = pixelDotFrame(dot(delayMillis = 130), 0.4f)
        val wrapped = pixelDotFrame(dot(delayMillis = 130), 1.4f)
        assertEquals(base.alpha, wrapped.alpha, 0.0001f)
        assertEquals(base.offsetInDots, wrapped.offsetInDots, 0.0001f)
    }

    @Test
    fun dotTableMatchesReferenceDelaysAndVariants() {
        assertEquals(6, PixelSpinnerDots.size)
        assertEquals(listOf(520, 650, 260, 390, 0, 130), PixelSpinnerDots.map { it.delayMillis })
        assertEquals(
            listOf(
                PixelDotKeyframes.Normal,
                PixelDotKeyframes.Normal,
                PixelDotKeyframes.Normal,
                PixelDotKeyframes.Normal,
                PixelDotKeyframes.Long,
                PixelDotKeyframes.Short,
            ),
            PixelSpinnerDots.map { it.keyframes },
        )
    }
}
