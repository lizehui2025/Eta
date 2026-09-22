package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessTerminalAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ordinaryRunCommandUsesAppIdentityWithoutGrant() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val result = JSONObject(controller.runCommand("printf hello", temporary.root.path, 5))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("user", result.getString("identity"))
            assertEquals("hello", result.getString("stdout"))
        } finally { controller.closeAll() }
    }

    @Test fun explicitRootCommandIsRejectedWithoutGrant() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val result = JSONObject(controller.terminalOpenAndExec("id", "/", 5000, "root", false))
            assertFalse(result.getBoolean("ok"))
            assertEquals("ROOT_REQUIRED", result.getString("code"))
        } finally { controller.closeAll() }
    }

    @Test fun ordinaryFileReadCannotFollowWorkspaceLinkOutsideAllowedRoots() {
        val workspace = File(TerminalRuntime.userWorkspacePath).apply { mkdirs() }
        val link = File(workspace, "test-link-${System.nanoTime()}")
        val outside = temporary.newFile("outside").apply { writeText("private") }
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val result = JSONObject(UserFileAccess.read(link.path, 0, 100))
            assertFalse(result.getBoolean("ok"))
            assertEquals("INVALID_PATH", result.getString("code"))
        } finally { Files.deleteIfExists(link.toPath()) }
    }

    @Test fun ordinaryReadsReportOffsetsForTheReturnedChunk() {
        val file = File(TerminalRuntime.userWorkspacePath, "test-read-${System.nanoTime()}")
        try {
            file.parentFile!!.mkdirs()
            file.writeText("a".repeat(20_000))
            val first = JSONObject(UserFileAccess.read(file.path, 0, 200_000))
            assertTrue(first.getBoolean("truncated"))
            assertEquals(first.getString("content").length, first.getInt("bytes_read"))
            val second = JSONObject(UserFileAccess.read(file.path, first.getInt("bytes_read"), 200_000))
            assertFalse(second.getBoolean("truncated"))
            assertEquals(20_000, first.getString("content").length + second.getString("content").length)
        } finally { file.delete() }
    }

    @Test fun ordinaryListSupportsPaginationAndGlobFiltering() {
        val dir = File(TerminalRuntime.userWorkspacePath, "test-list-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "a.kt").writeText("x")
            File(dir, "b.md").writeText("y")
            File(dir, ".hidden").writeText("z")
            val first = JSONObject(UserFileAccess.list(dir.path, false, 1, 0, "", false))
            assertTrue(first.toString(), first.getBoolean("ok"))
            assertEquals(2, first.getInt("total"))
            assertEquals(1, first.getInt("count"))
            assertEquals(0, first.getInt("offset"))
            assertTrue(first.getBoolean("truncated"))
            val second = JSONObject(UserFileAccess.list(dir.path, false, 1, 1, "", false))
            assertTrue(second.getBoolean("ok"))
            assertFalse(second.getBoolean("truncated"))
            assertEquals(2, second.getInt("total"))
            val filtered = JSONObject(UserFileAccess.list(dir.path, false, 200, 0, "*.kt", false))
            assertTrue(filtered.getBoolean("ok"))
            assertEquals(1, filtered.getInt("total"))
            assertTrue(filtered.getString("entries_text"), filtered.getString("entries_text").contains("a.kt"))
            val withHidden = JSONObject(UserFileAccess.list(dir.path, true, 200, 0, "", false))
            assertTrue(withHidden.getBoolean("ok"))
            assertEquals(3, withHidden.getInt("total"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun ordinaryListSupportsRecursiveRelativePaths() {
        val dir = File(TerminalRuntime.userWorkspacePath, "test-list-r-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "top.txt").writeText("t")
            val sub = File(dir, "sub").apply { mkdirs() }
            File(sub, "nested.kt").writeText("n")
            val flat = JSONObject(UserFileAccess.list(dir.path, false, 200, 0, "", false))
            assertEquals(2, flat.getInt("total"))
            val recursive = JSONObject(UserFileAccess.list(dir.path, false, 200, 0, "", true))
            assertTrue(recursive.toString(), recursive.getBoolean("ok"))
            assertEquals(3, recursive.getInt("total"))
            assertTrue(
                recursive.getString("entries_text"),
                recursive.getString("entries_text").contains("sub/nested.kt")
            )
        } finally { dir.deleteRecursively() }
    }

    @Test fun ordinarySearchRejectsBadPatternSkipsBinaryAndReturnsAbsolutePaths() {
        val dir = File(TerminalRuntime.userWorkspacePath, "test-search-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "ok.txt").writeText("hello world")
            val binary = File(dir, "bin.dat")
            binary.writeBytes("hello".toByteArray() + byteArrayOf(0) + "hello".toByteArray())
            val bad = JSONObject(UserFileAccess.search(dir.path, "[unclosed", "", 200))
            assertFalse(bad.getBoolean("ok"))
            assertEquals("INVALID_PATTERN", bad.getString("code"))
            val good = JSONObject(UserFileAccess.search(dir.path, "hello", "", 200))
            assertTrue(good.toString(), good.getBoolean("ok"))
            assertEquals(1, good.getInt("count"))
            val entry = good.getJSONArray("results").getString(0)
            assertTrue(entry, entry.startsWith("/") && entry.contains("ok.txt"))
        } finally { dir.deleteRecursively() }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
