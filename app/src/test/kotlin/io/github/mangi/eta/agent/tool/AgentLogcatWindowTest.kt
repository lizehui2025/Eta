package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * get_logcat 的扫描窗口：query 必须在扩大后的窗口内匹配，且回传 scanned_lines/matched 统计，
 * 避免把“浅窗口内 0 命中”误判为“系统无相关日志”。
 * 另外覆盖过滤下推（设备端 grep + 统计标记行）后的返回体解析。
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

    // ---- 过滤下推后的返回体解析 ----

    @Test
    fun parsePrefilteredSeparatesMarkersFromCandidateLines() {
        val raw = listOf(
            "10-08 12:00:00.000 Other hit-1",
            "__eta_logcat_scan__ 2000",
            "10-08 12:00:01.000 Other hit-2",
            "__eta_logcat_filter__ 127",
        )

        val parsed = AgentLogcatWindow.parsePrefiltered(raw)

        assertEquals(listOf("10-08 12:00:00.000 Other hit-1", "10-08 12:00:01.000 Other hit-2"), parsed.lines)
        assertEquals(2_000, parsed.scannedLines)
        assertTrue(parsed.filterFailed)
    }

    @Test
    fun parsePrefilteredWithoutMarkersKeepsEveryLine() {
        val parsed = AgentLogcatWindow.parsePrefiltered(listOf("line-1", "line-2"))

        assertEquals(listOf("line-1", "line-2"), parsed.lines)
        assertEquals(null, parsed.scannedLines)
        assertFalse(parsed.filterFailed)
    }

    @Test
    fun parsePrefilteredIgnoresUnparsableScanMarker() {
        val parsed = AgentLogcatWindow.parsePrefiltered(
            listOf("__eta_logcat_scan__ 不是数字", "__eta_logcat_scan__ 6"),
        )

        assertTrue(parsed.lines.isEmpty())
        assertEquals(6, parsed.scannedLines)
        assertFalse(parsed.filterFailed)
    }

    @Test
    fun parsePrefilteredFeedsSecondClientSideFilter() {
        // 设备端只回命中行时，客户端第二道过滤仍按同一 query 复查（大小写不敏感）。
        val parsed = AgentLogcatWindow.parsePrefiltered(
            listOf("10-08 12:00:00.000 Other Accessibility line", "__eta_logcat_scan__ 120"),
        )
        val scan = AgentLogcatWindow.scan(parsed.lines.joinToString("\n"), "accessibility", maxLines = 20)

        assertEquals(120, parsed.scannedLines)
        assertEquals(1, scan.matched)
        assertTrue(scan.lines[0].contains("Accessibility"))
    }
}
