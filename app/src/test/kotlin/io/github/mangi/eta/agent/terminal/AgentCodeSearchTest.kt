package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCodeSearchTest {
    @Test
    fun globsMatchFileNamesAndEmptyGlobsAllowEverything() {
        val globs = AgentCodeSearch.compileGlobs("*.kt, README.md")
        assertTrue(AgentCodeSearch.matchesGlobs(globs, "Main.kt"))
        assertTrue(AgentCodeSearch.matchesGlobs(globs, "README.md"))
        assertFalse(AgentCodeSearch.matchesGlobs(globs, "Main.java"))
        assertTrue(AgentCodeSearch.matchesGlobs(emptyList(), "anything.bin"))
    }

    @Test
    fun questionMarkMatchesExactlyOneCharacter() {
        val globs = AgentCodeSearch.compileGlobs("a?c")
        assertTrue(AgentCodeSearch.matchesGlobs(globs, "abc"))
        assertFalse(AgentCodeSearch.matchesGlobs(globs, "abbc"))
    }

    @Test
    fun entryFormatsPathLineTextWithCapsAndTrimsTrailingSpace() {
        val entry = AgentCodeSearch.entry("a/b.kt", 12, "value = 1   ")
        assertEquals("a/b.kt:12:value = 1", entry)
        val long = "x".repeat(600)
        assertEquals(
            "a/b.kt:12:" + "x".repeat(AgentCodeSearch.MAX_LINE_CHARS),
            AgentCodeSearch.entry("a/b.kt", 12, long),
        )
    }

    @Test
    fun grepLineParsesPathLineAndText() {
        val triple = AgentCodeSearch.parseGrepLine("./src/Main.kt:42:val x = 1")
        assertEquals("./src/Main.kt", triple!!.first)
        assertEquals(42, triple.second)
        assertEquals("val x = 1", triple.third)
    }

    @Test
    fun grepLineKeepsColonsInsidePath() {
        val triple = AgentCodeSearch.parseGrepLine("dir/a:b.kt:7:hi")
        assertEquals("dir/a:b.kt", triple!!.first)
        assertEquals(7, triple.second)
        assertEquals("hi", triple.third)
    }

    @Test
    fun malformedGrepLineIsRejected() {
        assertNull(AgentCodeSearch.parseGrepLine("no-number:abc:text"))
        assertNull(AgentCodeSearch.parseGrepLine(""))
    }
}
