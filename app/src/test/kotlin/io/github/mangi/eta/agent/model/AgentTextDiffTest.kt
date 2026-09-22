package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTextDiffTest {
    @Test
    fun summarizeTrimsCommonPrefixAndSuffix() {
        val oldText = "a\nb\nc\nd\ne"
        val newText = "a\nb\nX\nY\ne"

        val summary = AgentTextDiff.summarize(oldText, newText)!!

        assertTrue(summary.contains("旧 2 行 → 新 2 行"))
        assertTrue(summary.contains("- c"))
        assertTrue(summary.contains("- d"))
        assertTrue(summary.contains("+ X"))
        assertTrue(summary.contains("+ Y"))
        assertTrue(summary.contains("开头 2 行"))
    }

    @Test
    fun summarizeHandlesAppendAndIdenticalText() {
        val appended = AgentTextDiff.summarize("a\nb", "a\nb\nc\nd")!!
        assertTrue(appended.contains("+ c"))
        assertTrue(appended.contains("+ d"))
        assertNull(AgentTextDiff.summarize("same", "same", 100))
    }

    @Test
    fun replacementShowsBothSidesAndBinaryFallsBack() {
        val replacement = AgentTextDiff.summarizeReplacement("old line", "new line")!!
        assertTrue(replacement.contains("- old line"))
        assertTrue(replacement.contains("+ new line"))

        assertEquals("内容无变化", AgentTextDiff.summarizeReplacement("x", "x"))
        assertNull(AgentTextDiff.summarizeReplacement("a\u0000b", "c"))
        assertNull(AgentTextDiff.summarize("a\u0000b", "c"))
    }

    @Test
    fun summarizeIsBounded() {
        val oldText = (1..200).joinToString("\n") { "old-$it-${"x".repeat(50)}" }
        val newText = (1..200).joinToString("\n") { "new-$it-${"x".repeat(50)}" }

        val summary = AgentTextDiff.summarize(oldText, newText, maxChars = 400)!!

        assertTrue(summary.length <= 402)
        assertTrue(summary.endsWith("…"))
    }
}
