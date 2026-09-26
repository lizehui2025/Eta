package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubagentWritePathsTest {
    private val mounts = listOf("root" to "/data/local/chroot-distro/ubuntu/root")
    private val linuxWorkspaceRoot = "/data/local/tmp/eta"

    @Test
    fun sharedMountViewMapsBackToAndroidPath() {
        assertEquals(
            "/data/local/chroot-distro/ubuntu/root/Eta/app/Main.kt",
            AgentSubagentWritePaths.canonical("/workspace/mounts/root/Eta/app/Main.kt", mounts, linuxWorkspaceRoot),
        )
    }

    @Test
    fun mappedMountRootItselfResolvesToMountTarget() {
        assertEquals(
            "/data/local/chroot-distro/ubuntu/root",
            AgentSubagentWritePaths.canonical("/workspace/mounts/root", mounts, linuxWorkspaceRoot),
        )
    }

    @Test
    fun unmappedMountKeepsNormalizedPath() {
        assertEquals(
            "/workspace/mounts/other/file.kt",
            AgentSubagentWritePaths.canonical("/workspace/mounts/other/file.kt", mounts, linuxWorkspaceRoot),
        )
    }

    @Test
    fun duplicateSlashesAndTrailingSlashAreNormalized() {
        assertEquals("/a/b", AgentSubagentWritePaths.canonical("//a//b//", emptyList(), linuxWorkspaceRoot))
    }

    @Test
    fun containsRespectsDirectoryBoundary() {
        assertTrue(AgentSubagentWritePaths.contains("/repo/app", "/repo/app/src/Main.kt"))
        assertTrue(AgentSubagentWritePaths.contains("/repo/app", "/repo/app"))
        assertFalse(AgentSubagentWritePaths.contains("/repo/app", "/repo/application/Main.kt"))
        assertFalse(AgentSubagentWritePaths.contains("", "/repo/app"))
    }

    @Test
    fun overlapsDetectsNestedDirectories() {
        assertTrue(AgentSubagentWritePaths.overlaps("/repo/app", "/repo/app/src"))
        assertTrue(AgentSubagentWritePaths.overlaps("/repo/app/src", "/repo/app"))
        assertTrue(AgentSubagentWritePaths.overlaps("/repo/app/a.kt", "/repo/app/a.kt"))
        assertFalse(AgentSubagentWritePaths.overlaps("/repo/app", "/repo/lib"))
    }

    @Test
    fun registryMutuallyExcludesAndSupportsOwnerReentry() {
        val registry = SubagentWriteRegistry()
        assertNull(registry.tryAcquire("/repo/a.kt", "0"))
        assertEquals("0", registry.tryAcquire("/repo/a.kt", "1"))
        assertNull(registry.tryAcquire("/repo/a.kt", "0"))
        registry.release("/repo/a.kt", "1")
        assertEquals("0", registry.tryAcquire("/repo/a.kt", "1"))
        registry.release("/repo/a.kt", "0")
        assertNull(registry.tryAcquire("/repo/a.kt", "1"))
    }

    @Test
    fun linuxWorkspaceViewMapsToBackendWorkspaceRoot() {
        assertEquals(
            "$linuxWorkspaceRoot/src/Main.kt",
            AgentSubagentWritePaths.canonical("/workspace/src/Main.kt", emptyList(), linuxWorkspaceRoot),
        )
        // 相对别名与宿主别名归一到同一 canonical：声明 `/workspace/x` 与实际写到同一文件时判定一致。
        assertEquals(
            "$linuxWorkspaceRoot/src/Main.kt",
            AgentSubagentWritePaths.canonical("workspace/src/Main.kt", emptyList(), linuxWorkspaceRoot),
        )
        assertEquals(
            "$linuxWorkspaceRoot/src/Main.kt",
            AgentSubagentWritePaths.canonical("/data/local/tmp/eta/src/Main.kt", emptyList(), linuxWorkspaceRoot),
        )
        val declared = AgentSubagentWritePaths.canonical("/workspace/src", emptyList(), linuxWorkspaceRoot)
        assertTrue(
            AgentSubagentWritePaths.contains(
                declared,
                AgentSubagentWritePaths.canonical("/data/local/tmp/eta/src/Main.kt", emptyList(), linuxWorkspaceRoot),
            ),
        )
        assertFalse(
            AgentSubagentWritePaths.contains(
                declared,
                AgentSubagentWritePaths.canonical("/workspace/other/Main.kt", emptyList(), linuxWorkspaceRoot),
            ),
        )
    }

    @Test
    fun ordinaryWorkspaceViewMapsToPrivateWorkspaceRoot() {
        val privateRoot = "/data/user/0/app/files/terminal-user/workspace"
        assertEquals(
            "$privateRoot/a.kt",
            AgentSubagentWritePaths.canonical("/workspace/a.kt", emptyList(), privateRoot),
        )
    }
}
