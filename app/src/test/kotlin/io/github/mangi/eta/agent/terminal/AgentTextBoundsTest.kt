package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTextBoundsTest {
    @Test
    fun nonPositiveLimitReturnsEmptyString() {
        assertEquals("", "abc".truncateByCodePoints(0))
        assertEquals("", "abc".truncateByCodePoints(-3))
    }

    @Test
    fun textWithinLimitIsReturnedUnchanged() {
        assertEquals("abc", "abc".truncateByCodePoints(3))
        assertEquals("abc", "abc".truncateByCodePoints(99))
        assertEquals("", "".truncateByCodePoints(5))
    }

    @Test
    fun asciiTextCutsAtExactLimit() {
        assertEquals("abc", "abcdef".truncateByCodePoints(3))
    }

    @Test
    fun surrogatePairIsNeverSplit() {
        // "😀" 占两个 UTF-16 单元：limit=3 落在代理对中间时回退为 "ab"。
        val text = "ab😀cd"
        assertEquals("ab", text.truncateByCodePoints(3))
        // limit=4 恰好包含完整代理对。
        assertEquals("ab😀", text.truncateByCodePoints(4))
    }

    @Test
    fun emojiAtStartFallsBackInsteadOfLoneSurrogate() {
        assertEquals("", "😀".truncateByCodePoints(1))
        assertEquals("😀", "😀".truncateByCodePoints(2))
    }

    @Test
    fun everyLimitProducesValidUtf16WhenInputIsValid() {
        val text = "a😀b😀c"
        for (limit in 0..text.length) {
            val cut = text.truncateByCodePoints(limit)
            assertTrue("limit=$limit 结果超出上限", cut.length <= limit)
            var index = 0
            while (index < cut.length) {
                val unit = cut[index]
                if (Character.isHighSurrogate(unit)) {
                    val next = cut.getOrNull(index + 1)
                    assertTrue(
                        "limit=$limit 切出孤立高代理项",
                        next != null && Character.isLowSurrogate(next),
                    )
                    index += 2
                } else {
                    index += 1
                }
            }
        }
    }
}
