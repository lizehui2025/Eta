package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工作过程动效的纯函数：短语轮换、彩蛋步点与类别选择。
 * 这些行为是确定性实现，因此可以用精确断言锁定（UI 侧只按 testTag 断言存在性）。
 */
class WorkProcessMotionTest {
    @Test
    fun phraseRotationWalksThePoolInOrderAndWrapsAround() {
        val pool = listOf("a", "b", "c")
        assertEquals("a", pickWorkingPhrase(pool, emptyList(), 0))
        assertEquals("b", pickWorkingPhrase(pool, emptyList(), 1))
        assertEquals("c", pickWorkingPhrase(pool, emptyList(), 2))
        assertEquals("a", pickWorkingPhrase(pool, emptyList(), 3))
    }

    @Test
    fun funPhraseReplacesEveryFiftiethStepAndRotatesThroughThePool() {
        val pool = listOf("work")
        val funPool = listOf("fun-1", "fun-2")
        assertEquals("work", pickWorkingPhrase(pool, funPool, 49))
        assertEquals("fun-1", pickWorkingPhrase(pool, funPool, 50))
        assertEquals("work", pickWorkingPhrase(pool, funPool, 51))
        assertEquals("fun-2", pickWorkingPhrase(pool, funPool, 100))
        assertEquals("fun-1", pickWorkingPhrase(pool, funPool, 150))
    }

    @Test
    fun emptyPoolsAndNonPositiveStepsStaySafe() {
        assertEquals("", pickWorkingPhrase(emptyList(), listOf("fun"), 3))
        assertEquals("a", pickWorkingPhrase(listOf("a"), emptyList(), 0))
        assertEquals("a", pickWorkingPhrase(listOf("a"), emptyList(), -5))
    }

    @Test
    fun phraseCategoryFollowsTheMostRecentItemKind() {
        assertEquals(WorkingPhraseCategory.Thinking, workingPhraseCategory(null))
        assertEquals(WorkingPhraseCategory.Terminal, workingPhraseCategory("terminal"))
        assertEquals(WorkingPhraseCategory.Terminal, workingPhraseCategory("run_command"))
        assertEquals(WorkingPhraseCategory.Tool, workingPhraseCategory("web_search"))
    }
}
