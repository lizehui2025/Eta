package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileEditTest {
    @Test
    fun uniqueReplacementApplies() {
        val outcome = AgentFileEdit.apply("fun main() {\n    println(1)\n}\n", "println(1)", "println(2)", false)
        val applied = outcome as AgentFileEdit.Outcome.Applied
        assertEquals(1, applied.replacements)
        assertTrue(applied.content.contains("println(2)"))
        assertTrue(!applied.content.contains("println(1)"))
    }

    @Test
    fun missingTextIsRejectedWithNoMatch() {
        val outcome = AgentFileEdit.apply("abc", "xyz", "123", false)
        assertEquals("NO_MATCH", (outcome as AgentFileEdit.Outcome.Rejected).code)
    }

    @Test
    fun multipleMatchesRequireReplaceAll() {
        val rejected = AgentFileEdit.apply("a b a", "a", "c", false)
        assertEquals("MULTI_MATCH", (rejected as AgentFileEdit.Outcome.Rejected).code)
        val applied = AgentFileEdit.apply("a b a", "a", "c", true) as AgentFileEdit.Outcome.Applied
        assertEquals(2, applied.replacements)
        assertEquals("c b c", applied.content)
    }

    @Test
    fun emptyOldStringIsInvalid() {
        val outcome = AgentFileEdit.apply("abc", "", "x", false)
        assertEquals("INVALID_ARGUMENT", (outcome as AgentFileEdit.Outcome.Rejected).code)
    }

    @Test
    fun identicalOldAndNewIsInvalid() {
        val outcome = AgentFileEdit.apply("abc", "abc", "abc", false)
        assertEquals("INVALID_ARGUMENT", (outcome as AgentFileEdit.Outcome.Rejected).code)
    }

    @Test
    fun replacementWorksAcrossLines() {
        val outcome = AgentFileEdit.apply("line1\nline2\nline3\n", "line1\nline2", "line1\nchanged", false)
        val applied = outcome as AgentFileEdit.Outcome.Applied
        assertEquals("line1\nchanged\nline3\n", applied.content)
    }
}
