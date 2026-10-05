package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * grep 逐文件扫描警告识别：只有权限/IO 类警告才算“搜索已执行”，
 * 选项错误、目录不存在、正则被拒等仍应视为真正失败。
 */
class GrepScanWarningsTest {
    @Test
    fun permissionAndIoWarningsAreRecognized() {
        val stderr = """
            grep: ./a/b/c.zip: Permission denied
            grep: ./Android/data/x/y.ab: Bad file descriptor
            grep: ./tmp/gone.txt: No such file or directory
        """.trimIndent()
        assertTrue(GrepScanWarnings.isScanWarningOnly(stderr))
        assertEquals(3, GrepScanWarnings.lines(stderr).size)
    }

    @Test
    fun optionErrorIsNotAScanWarning() {
        val stderr = "grep: Unknown option 'include' (see \"grep --help\")"
        assertFalse(GrepScanWarnings.isScanWarningOnly(stderr))
    }

    @Test
    fun cdFailureIsNotAScanWarning() {
        val stderr = "sh: cd: /workspace/nope: No such file or directory"
        assertFalse(GrepScanWarnings.isScanWarningOnly(stderr))
    }

    @Test
    fun mixedOutputIsNotScanWarningOnly() {
        val stderr = """
            grep: ./a: Permission denied
            grep: Unknown option 'z'
        """.trimIndent()
        assertFalse(GrepScanWarnings.isScanWarningOnly(stderr))
    }

    @Test
    fun emptyStderrIsNotScanWarningOnly() {
        assertFalse(GrepScanWarnings.isScanWarningOnly(""))
        assertFalse(GrepScanWarnings.isScanWarningOnly("   \n  "))
    }

    @Test
    fun warningLineIsExactMatchOnly() {
        assertTrue(GrepScanWarnings.isWarningLine("grep: ./x: Permission denied"))
        assertFalse(GrepScanWarnings.isWarningLine("prefix grep: ./x: Permission denied and more"))
    }
}
