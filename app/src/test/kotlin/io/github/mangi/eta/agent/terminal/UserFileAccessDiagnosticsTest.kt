package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 免 Root 文件访问的失败诊断：目录/不存在/不是普通文件分别给出可修正的结论，
 * 缺失路径附父目录条目建议，编辑失败附近似位置上下文。
 */
class UserFileAccessDiagnosticsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun workspaceDir(): File = File(TerminalRuntime.userWorkspacePath)

    @Test
    fun missingFileReportsNotFoundWithSiblingSuggestion() {
        val dir = File(workspaceDir(), "diag-missing-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "real.txt").writeText("x")
            val result = JSONObject(UserFileAccess.read(File(dir, "typo.txt").path, 0, 100))
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("NOT_FOUND", result.getString("code"))
            assertTrue(
                "应列出父目录条目：${result.getString("message")}",
                result.getString("message").contains("real.txt"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readingDirectoryReportsIsDirectory() {
        val dir = File(workspaceDir(), "diag-dir-${System.nanoTime()}").apply { mkdirs() }
        try {
            val result = JSONObject(UserFileAccess.read(dir.path, 0, 100))
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("IS_DIRECTORY", result.getString("code"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun editMissingFileReportsNotFound() {
        val dir = File(workspaceDir(), "diag-edit-${System.nanoTime()}").apply { mkdirs() }
        try {
            val result = JSONObject(UserFileAccess.edit(File(dir, "gone.txt").path, "a", "b", false))
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("NOT_FOUND", result.getString("code"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun editNoMatchCarriesApproximateLocationContext() {
        val dir = File(workspaceDir(), "diag-ctx-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(dir, "code.txt")
            file.writeText("fun main() {\n    println(1)\n}\n")
            val result = JSONObject(
                UserFileAccess.edit(file.path, "fun main() {\n    println(2)", "x", false),
            )
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("NO_MATCH", result.getString("code"))
            assertTrue(
                "应附带近似位置上下文：$result",
                result.getString("context").contains("2:     println(1)"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun listMissingDirectoryReportsMissingDirectory() {
        val result = JSONObject(
            UserFileAccess.list(File(workspaceDir(), "diag-none-${System.nanoTime()}").path, false, 50, 0, "", false),
        )
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals("MISSING_DIRECTORY", result.getString("code"))
    }

    @Test
    fun searchMissingRootReportsMissingDirectory() {
        val result = JSONObject(
            UserFileAccess.search(
                File(workspaceDir(), "diag-search-none-${System.nanoTime()}").path,
                "pattern",
                "",
                10,
            ),
        )
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals("MISSING_DIRECTORY", result.getString("code"))
    }
}
