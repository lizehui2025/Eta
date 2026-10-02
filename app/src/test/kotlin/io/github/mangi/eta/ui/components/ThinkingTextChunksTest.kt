package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingTextChunksTest {
    @Test
    fun keepsAllThinkingTextIncludingBlankLinesAndUnicode() {
        val content = "开头\n\n" + "长段落😀".repeat(800) + "\n结尾\n"
        val chunks = thinkingTextChunks(content, targetChars = 40)
        assertEquals(content, chunks.joinToString("\n"))
        assertEquals(true, chunks.size > 2)
    }

    @Test
    fun noNewlineStaysOnOneVisualLine() {
        val content = "x".repeat(8_000)
        assertEquals(listOf(content), thinkingTextChunks(content, targetChars = 40))
    }

    @Test
    fun keepsAdjacentBlankLinesAcrossChunkBoundaries() {
        val content = "a\n\nb\n\n\nc"
        assertEquals(content, thinkingTextChunks(content, targetChars = 1).joinToString("\n"))
    }

    @Test
    fun accumulatorRetainsCompletedChunksAcrossDeltas() {
        val accumulator = ThinkingTextChunkAccumulator(targetChars = 4)
        val first = accumulator.update("abcd")
        val second = accumulator.update("abcdef")
        assertEquals(listOf("abcd"), first)
        assertEquals(listOf("abcd", "ef"), second)
    }
}
