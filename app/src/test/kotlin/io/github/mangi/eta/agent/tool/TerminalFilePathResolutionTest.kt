package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.terminal.TerminalFilePathResolution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * file 类工具的相对路径基准（实测 P2-2）：
 * 相对路径必须与绝对 /workspace 使用同一个翻译根，不允许“绝对路径走宿主工作区、
 * 相对路径落私有工作区”的双轨；非 root/PRoot 模式下两者同样落在私有工作区。
 */
class TerminalFilePathResolutionTest {
    private val hostWorkspaceRoot = "/data/local/tmp/eta"
    private val privateWorkspaceRoot = "/data/data/io.github.mangi.eta/files/terminal-user/workspace"
    private val userStorage = "/storage/emulated/0"

    @Test
    fun relativePathUsesTheSameRootAsAbsoluteWorkspace() {
        // 相对路径以工作区根为基准。
        assertEquals("$hostWorkspaceRoot/probe.md", resolve("probe.md"))
        assertEquals("$hostWorkspaceRoot/sub/dir/a.txt", resolve("sub/dir/a.txt"))
        // 已翻译的绝对路径（来自 /workspace/...）保持原样，与相对路径落在同一根下。
        assertEquals("$hostWorkspaceRoot/probe.md", resolve("$hostWorkspaceRoot/probe.md"))
    }

    @Test
    fun dotRelativePathIsBasedAtWorkspaceRoot() {
        // "." 先拼到工作区根；调用方的 File(...).canonicalPath 会把它归一为根目录本身。
        assertEquals("$hostWorkspaceRoot/.", resolve("."))
    }

    @Test
    fun tildeMapsToUserStorage() {
        assertEquals(userStorage, resolve("~"))
        assertEquals("$userStorage/Download/a.txt", resolve("~/Download/a.txt"))
    }

    @Test
    fun privateWorkspaceRootIsUsedWhenProvided() {
        // 免 Root / PRoot 模式：翻译根与相对基准同为私有工作区，模式判定保持一致。
        assertEquals("$privateWorkspaceRoot/probe.md", resolve("probe.md", workspaceRoot = privateWorkspaceRoot))
        assertEquals(
            "$privateWorkspaceRoot/probe.md",
            resolve("$privateWorkspaceRoot/probe.md", workspaceRoot = privateWorkspaceRoot),
        )
    }

    private fun resolve(translated: String, workspaceRoot: String = hostWorkspaceRoot): String =
        TerminalFilePathResolution.resolve(translated, workspaceRoot, userStorage)
}
