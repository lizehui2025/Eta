package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.terminal.TerminalDeletePolicy
import io.github.mangi.eta.agent.terminal.TerminalEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * file_ops delete 的保护判定与层级决策（实测 P2-8）：
 * 拒绝场景（根/系统路径、工作区根、非空目录、特殊文件）与正常删除计划
 * （文件、空目录、recursive 非空目录）。
 */
class TerminalDeletePolicyTest {
    private val workspaceRoot = "/data/local/tmp/eta"

    @Test
    fun fixedProtectedPathsAreRejected() {
        listOf("/", "/data", "/data/data", "/data/local/tmp", "/system", "/vendor", "/storage/emulated/0").forEach { path ->
            val issue = TerminalDeletePolicy.protectionIssue(path, listOf(workspaceRoot))
            assertTrue("应拒绝删除受保护路径 $path", issue != null)
        }
    }

    @Test
    fun workspaceRootIsRejectedButChildrenAreAllowed() {
        assertTrue(TerminalDeletePolicy.protectionIssue(workspaceRoot, listOf(workspaceRoot)) != null)
        // 尾部斜杠与空白不影响判定。
        assertTrue(TerminalDeletePolicy.protectionIssue("$workspaceRoot/", listOf(workspaceRoot)) != null)
        assertNull(TerminalDeletePolicy.protectionIssue("$workspaceRoot/probe.md", listOf(workspaceRoot)))
        assertNull(TerminalDeletePolicy.protectionIssue("$workspaceRoot/sub/dir", listOf(workspaceRoot)))
    }

    @Test
    fun nonEmptyDirectoryRequiresRecursiveFlag() {
        val reject = TerminalDeletePolicy.decide(
            kind = TerminalEntryKind.DIRECTORY,
            directEntries = 3,
            totalEntries = 7,
            recursive = false,
            normalizedPath = "$workspaceRoot/dir",
            workspaceRoots = listOf(workspaceRoot),
        )
        assertEquals("DIRECTORY_NOT_EMPTY", (reject as TerminalDeletePolicy.Decision.Reject).code)

        val delete = TerminalDeletePolicy.decide(
            kind = TerminalEntryKind.DIRECTORY,
            directEntries = 3,
            totalEntries = 7,
            recursive = true,
            normalizedPath = "$workspaceRoot/dir",
            workspaceRoots = listOf(workspaceRoot),
        ) as TerminalDeletePolicy.Decision.Delete
        assertTrue(delete.recursive)
        // 目录自身计入：7 个后代 + 1。
        assertEquals(8, delete.deletedEntries)
    }

    @Test
    fun emptyDirectoryAndFileAreDeletedWithoutRecursive() {
        val empty = TerminalDeletePolicy.decide(
            kind = TerminalEntryKind.DIRECTORY,
            directEntries = 0,
            totalEntries = 0,
            recursive = false,
            normalizedPath = "$workspaceRoot/empty",
            workspaceRoots = listOf(workspaceRoot),
        ) as TerminalDeletePolicy.Decision.Delete
        assertFalse(empty.recursive)
        assertEquals(1, empty.deletedEntries)

        val file = TerminalDeletePolicy.decide(
            kind = TerminalEntryKind.FILE,
            directEntries = 1,
            totalEntries = 1,
            recursive = false,
            normalizedPath = "$workspaceRoot/a.txt",
            workspaceRoots = listOf(workspaceRoot),
        ) as TerminalDeletePolicy.Decision.Delete
        assertEquals(TerminalEntryKind.FILE, file.kind)
        assertEquals(1, file.deletedEntries)
    }

    @Test
    fun protectionBeatsRecursiveDecisionOnWorkspaceRoot() {
        val decision = TerminalDeletePolicy.decide(
            kind = TerminalEntryKind.DIRECTORY,
            directEntries = 5,
            totalEntries = 9,
            recursive = true,
            normalizedPath = workspaceRoot,
            workspaceRoots = listOf(workspaceRoot),
        )
        assertEquals("PROTECTED_PATH", (decision as TerminalDeletePolicy.Decision.Reject).code)
    }

    @Test
    fun specialFileIsRejected() {
        val decision = TerminalDeletePolicy.decide(
            kind = TerminalEntryKind.OTHER,
            directEntries = 0,
            totalEntries = 0,
            recursive = false,
            normalizedPath = "$workspaceRoot/fifo",
            workspaceRoots = listOf(workspaceRoot),
        )
        assertEquals("NOT_REGULAR_FILE", (decision as TerminalDeletePolicy.Decision.Reject).code)
    }
}
