package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentFilePathMapperTest {
    private val mounts = listOf("root" to "/data/local/chroot-distro/ubuntu/root")
    private val workspace = "/data/user/0/io.github.mangi.eta.subagents/files/terminal-user/workspace"
    private val hostWorkspace = "/data/local/tmp/eta"

    @Test
    fun sharedMountViewMapsToAndroidSource() {
        assertEquals(
            "/data/local/chroot-distro/ubuntu/root/Eta/app/Main.kt",
            AgentFilePathMapper.toAndroidPath("/workspace/mounts/root/Eta/app/Main.kt", mounts, workspace),
        )
    }

    @Test
    fun mountRootItselfMapsToSource() {
        assertEquals(
            "/data/local/chroot-distro/ubuntu/root",
            AgentFilePathMapper.toAndroidPath("/workspace/mounts/root", mounts, workspace),
        )
    }

    @Test
    fun unmappedMountKeepsOriginal() {
        assertEquals(
            "/workspace/mounts/other/file.kt",
            AgentFilePathMapper.toAndroidPath("/workspace/mounts/other/file.kt", mounts, workspace),
        )
    }

    @Test
    fun workspaceRootMapsToUserWorkspace() {
        assertEquals(
            workspace,
            AgentFilePathMapper.toAndroidPath("/workspace", mounts, workspace),
        )
    }

    @Test
    fun workspaceSubPathMapsToUserWorkspace() {
        assertEquals(
            "$workspace/imports/abc/doc.pdf",
            AgentFilePathMapper.toAndroidPath("/workspace/imports/abc/doc.pdf", mounts, workspace),
        )
    }

    @Test
    fun daemonDirMapsToHostDaemonDir() {
        assertEquals(
            "$workspace/daemon/dm_1234.log",
            AgentFilePathMapper.toAndroidPath("/workspace/daemon/dm_1234.log", mounts, workspace),
        )
    }

    @Test
    fun chrootBackendWorkspaceMapsToHostWorkspace() {
        assertEquals(
            "$hostWorkspace/und.txt",
            AgentFilePathMapper.toAndroidPath("/workspace/und.txt", mounts, hostWorkspace),
        )
        assertEquals(hostWorkspace, AgentFilePathMapper.toAndroidPath("/workspace", mounts, hostWorkspace))
        assertEquals(
            "$hostWorkspace/src/Main.kt",
            AgentFilePathMapper.toAndroidPath("workspace/src/Main.kt", mounts, hostWorkspace),
        )
        assertEquals(
            "$hostWorkspace/daemon/dm_1234.log",
            AgentFilePathMapper.toAndroidPath("/workspace/daemon/dm_1234.log", mounts, hostWorkspace),
        )
    }

    @Test
    fun unrelatedPathsPassThrough() {
        assertEquals(
            "/storage/emulated/0/Download/a.txt",
            AgentFilePathMapper.toAndroidPath("/storage/emulated/0/Download/a.txt", mounts, workspace),
        )
        assertEquals(
            "~/docs",
            AgentFilePathMapper.toAndroidPath("~/docs", mounts, workspace),
        )
        assertEquals(
            "relative/path",
            AgentFilePathMapper.toAndroidPath("relative/path", mounts, workspace),
        )
        assertEquals("", AgentFilePathMapper.toAndroidPath("  ", mounts, workspace))
    }

    @Test
    fun translationRootFollowsBackendAndReadiness() {
        // chroot / proot / 无配置三态：解析函数决定 `/workspace` 的翻译根。
        assertEquals(
            hostWorkspace,
            TerminalRuntime.resolveLinuxWorkspaceRoot(
                backend = LinuxExecutionBackend.CHROOT,
                environmentReady = true,
                rootGranted = true,
                userWorkspace = workspace,
            ),
        )
        assertEquals(
            workspace,
            TerminalRuntime.resolveLinuxWorkspaceRoot(
                backend = LinuxExecutionBackend.PROOT,
                environmentReady = true,
                rootGranted = true,
                userWorkspace = workspace,
            ),
        )
        assertEquals(
            workspace,
            TerminalRuntime.resolveLinuxWorkspaceRoot(
                backend = LinuxExecutionBackend.CHROOT,
                environmentReady = false,
                rootGranted = true,
                userWorkspace = workspace,
            ),
        )
        assertEquals(
            workspace,
            TerminalRuntime.resolveLinuxWorkspaceRoot(
                backend = LinuxExecutionBackend.CHROOT,
                environmentReady = true,
                rootGranted = false,
                userWorkspace = workspace,
            ),
        )
    }
}
