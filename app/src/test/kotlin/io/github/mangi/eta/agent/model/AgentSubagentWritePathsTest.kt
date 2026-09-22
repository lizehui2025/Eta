package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubagentWritePathsTest {
    private val mounts = listOf("root" to "/data/local/chroot-distro/ubuntu/root")

    @Test
    fun sharedMountViewMapsBackToAndroidPath() {
        assertEquals(
            "/data/local/chroot-distro/ubuntu/root/Eta/app/Main.kt",
            AgentSubagentWritePaths.canonical("/workspace/mounts/root/Eta/app/Main.kt", mounts),
        )
    }

    @Test
    fun mappedMountRootItselfResolvesToMountTarget() {
        assertEquals(
            "/data/local/chroot-distro/ubuntu/root",
            AgentSubagentWritePaths.canonical("/workspace/mounts/root", mounts),
        )
    }

    @Test
    fun unmappedMountKeepsNormalizedPath() {
        assertEquals(
            "/workspace/mounts/other/file.kt",
            AgentSubagentWritePaths.canonical("/workspace/mounts/other/file.kt", mounts),
        )
    }

    @Test
    fun duplicateSlashesAndTrailingSlashAreNormalized() {
        assertEquals("/a/b", AgentSubagentWritePaths.canonical("//a//b//", emptyList()))
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
}
