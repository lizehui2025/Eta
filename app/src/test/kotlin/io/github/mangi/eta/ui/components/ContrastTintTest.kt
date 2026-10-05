package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指示色对比度保障：低对比度被提升到达标、达标色原样返回、alpha 保留。
 */
class ContrastTintTest {
    private val white = Color(0xFFFFFFFF)
    private val black = Color(0xFF000000)

    // 浅色主题下常见的 pastel 强调色：白底对比度远低于 3:1。
    private val pastelBlue = Color(0xFFBBDCFF)

    @Test
    fun lowContrastTintIsStrengthenedOnLightBackground() {
        assertTrue(
            "前提：浅蓝在白底对比度应不足 3:1，实际 ${contrastRatio(pastelBlue, white)}",
            contrastRatio(pastelBlue, white) < MIN_INDICATOR_CONTRAST,
        )
        val fixed = ensureReadableTint(pastelBlue, white)
        assertTrue(
            "修正后对比度应达标，实际 ${contrastRatio(fixed, white)}",
            contrastRatio(fixed, white) >= MIN_INDICATOR_CONTRAST,
        )
        // 只加深、不换色相：混合方向是黑，蓝色分量应仍占主导。
        assertTrue("应保持蓝色主导：fixed=$fixed", fixed.blue > fixed.red && fixed.blue > fixed.green)
    }

    @Test
    fun alreadyReadableTintIsReturnedUnchanged() {
        val strong = Color(0xFF1A73E8)
        assertTrue(contrastRatio(strong, white) >= MIN_INDICATOR_CONTRAST)
        assertEquals(strong, ensureReadableTint(strong, white))
    }

    @Test
    fun darkBackgroundStrengthensTowardWhite() {
        val darkBackground = Color(0xFF1C1C1E)
        val dimColors = Color(0xFF3A3A3E)
        assertTrue(contrastRatio(dimColors, darkBackground) < MIN_INDICATOR_CONTRAST)
        val fixed = ensureReadableTint(dimColors, darkBackground)
        assertTrue(contrastRatio(fixed, darkBackground) >= MIN_INDICATOR_CONTRAST)
        assertTrue(
            "深背景应朝白提亮：before=${relativeLuminance(dimColors)} after=${relativeLuminance(fixed)}",
            relativeLuminance(fixed) > relativeLuminance(dimColors),
        )
    }

    @Test
    fun alphaIsPreservedForAnimation() {
        val semiTransparent = pastelBlue.copy(alpha = 0.5f)
        val fixed = ensureReadableTint(semiTransparent, white)
        // Color 的 alpha 为 8 位量化，按原值（而非 0.5f 字面量）比较。
        assertEquals(semiTransparent.alpha, fixed.alpha, 0.0001f)
        assertTrue(contrastRatio(fixed, white) >= MIN_INDICATOR_CONTRAST)
    }

    @Test
    fun unspecifiedColorsPassThrough() {
        assertEquals(Color.Unspecified, ensureReadableTint(Color.Unspecified, white))
        assertEquals(pastelBlue, ensureReadableTint(pastelBlue, Color.Unspecified))
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        // 黑白为 21:1；#767676 与白的经典参考值为 4.54:1。
        assertEquals(21f, contrastRatio(black, white), 0.01f)
        assertEquals(4.54f, contrastRatio(Color(0xFF767676), white), 0.05f)
    }
}
