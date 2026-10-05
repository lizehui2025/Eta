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

    @Test
    fun crlfFileAcceptsLfOldString() {
        val outcome = AgentFileEdit.apply(
            "line1\r\nline2\r\nline3\r\n",
            "line1\nline2",
            "line1\nchanged",
            false,
        )
        val applied = outcome as AgentFileEdit.Outcome.Applied
        // 行尾按文件实际风格保留 CRLF。
        assertEquals("line1\r\nchanged\r\nline3\r\n", applied.content)
    }

    @Test
    fun lfFileAcceptsCrlfOldString() {
        val outcome = AgentFileEdit.apply("a\nb\nc\n", "a\r\nb", "a\r\nB", false)
        val applied = outcome as AgentFileEdit.Outcome.Applied
        assertEquals("a\nB\nc\n", applied.content)
    }

    @Test
    fun noMatchCarriesApproximateLocationContext() {
        // 常见失败：old_string 跨行且后续行与文件不一致；上下文按首行定位到文件实际位置。
        val content = "fun main() {\n    println(1)\n}\n"
        val rejected = AgentFileEdit.apply(
            content,
            "fun main() {\n    println(2)",
            "fun main() {\n    println(3)",
            false,
        ) as AgentFileEdit.Outcome.Rejected
        assertEquals("NO_MATCH", rejected.code)
        val context = rejected.context
        assertTrue("应有近似位置上下文，实际=$context", context != null)
        assertTrue("上下文应带实际行号与内容：$context", context!!.contains("1: fun main() {"))
        assertTrue("上下文应带实际行号与内容：$context", context.contains("2:     println(1)"))
    }

    @Test
    fun noMatchContextLocatesLineWithLongerActualContent() {
        val content = "fun f() {\n    val y = computeValue(1) // note\n}\n"
        val rejected = AgentFileEdit.apply(
            content,
            "val y = computeValue(1)\n  return y",
            "val y = computeValue(2)\n  return y",
            false,
        ) as AgentFileEdit.Outcome.Rejected
        val context = rejected.context
        assertTrue("应定位到以 old_string 首行为前缀的行，实际=$context", context != null)
        assertTrue(context!!.contains("2:     val y = computeValue(1) // note"))
    }

    @Test
    fun noMatchWithoutLocatableLineHasNoContext() {
        val rejected = AgentFileEdit.apply("abc\n", "xyz", "123", false) as AgentFileEdit.Outcome.Rejected
        assertEquals("NO_MATCH", rejected.code)
        assertEquals(null, rejected.context)
    }

    @Test
    fun multiMatchMessageListsLineNumbers() {
        val rejected = AgentFileEdit.apply("a\na\na\n", "a", "b", false) as AgentFileEdit.Outcome.Rejected
        assertEquals("MULTI_MATCH", rejected.code)
        assertTrue("应列出匹配行号：${rejected.message}", rejected.message.contains("第 1 行"))
        assertTrue("应列出匹配行号：${rejected.message}", rejected.message.contains("第 3 行"))
    }
}
