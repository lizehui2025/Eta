package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * get_logcat 的扫描窗口：query 必须在扩大后的窗口内匹配，且回传 scanned_lines/matched 统计，
 * 避免把“浅窗口内 0 命中”误判为“系统无相关日志”。
 */
class AgentLogcatWindowTest {
    @Test
    fun queryScansBeyondReturnWindowAndReportsWindowSize() {
        val output = buildString {
            repeat(120) { index ->
                val tag = if (index == 3) "AccessibilityProtection" else "Other"
                append("10-08 12:00:00.000 $tag line-$index\n")
            }
        }

        val scan = AgentLogcatWindow.scan(output, "accessibility", maxLines = 20)

        assertEquals(120, scan.scannedLines)
        assertEquals(1, scan.matched)
        assertEquals(1, scan.lines.size)
        assertTrue(scan.lines[0].contains("line-3"))
    }

    @Test
    fun matchesBeyondReturnLimitKeepTheNewestLines() {
        val output = (1..5).joinToString("\n") { "t=$it hit" }

        val scan = AgentLogcatWindow.scan(output, "hit", maxLines = 2)

        assertEquals(5, scan.scannedLines)
        assertEquals(5, scan.matched)
        assertEquals(2, scan.lines.size)
        assertTrue(scan.lines[0].contains("t=4"))
        assertTrue(scan.lines[1].contains("t=5"))
    }

    @Test
    fun blankQueryKeepsScannedWindowAndCapsReturnedLines() {
        val output = (1..30).joinToString("\n") { "line-$it" }

        val scan = AgentLogcatWindow.scan(output, "", maxLines = 20)

        assertEquals(30, scan.scannedLines)
        assertEquals(30, scan.matched)
        assertEquals(20, scan.lines.size)
        assertTrue(scan.lines[0].contains("line-11"))
        assertTrue(scan.lines[19].contains("line-30"))
    }

    @Test
    fun emptyOutputReportsZeroWithoutMatches() {
        val scan = AgentLogcatWindow.scan("", "query", maxLines = 20)

        assertEquals(0, scan.scannedLines)
        assertEquals(0, scan.matched)
        assertTrue(scan.lines.isEmpty())
    }
}
