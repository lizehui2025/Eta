package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动查找的纯逻辑：名称档位、候选排序、唯一采用门槛、列举解析、祖先探测与两轮查找。
 */
class WorkspaceAutoFindTest {

    @Test
    fun nameTierCoversExactCaseInsensitiveAndNormalized() {
        assertEquals(0, WorkspaceAutoFind.nameTier("Foo.kt", "Foo.kt"))
        assertEquals(1, WorkspaceAutoFind.nameTier("foo.KT", "Foo.kt"))
        assertEquals(2, WorkspaceAutoFind.nameTier("agent_loop.kt", "AgentLoop.kt"))
        assertEquals(2, WorkspaceAutoFind.nameTier("agent-loop.kt", "AgentLoop.kt"))
        assertNull(WorkspaceAutoFind.nameTier("Other.kt", "Foo.kt"))
        // 不做编辑距离等模糊匹配：只差一个字母的名字不应命中。
        assertNull(WorkspaceAutoFind.nameTier("Fop.kt", "Foo.kt"))
    }

    @Test
    fun rankOrdersByTierThenShorterPath() {
        val ranked = WorkspaceAutoFind.rankCandidates(
            listOf(
                "/ws/b/other_Foo.kt",
                "/ws/deep/nested/Foo.kt",
                "/ws/Foo.kt",
                "/ws/a/foo.kt",
            ),
            "Foo.kt",
        )
        // 不匹配的名字（other_Foo.kt）被剔除；档位优先，其次更短路径。
        assertEquals(
            listOf("/ws/Foo.kt", "/ws/deep/nested/Foo.kt", "/ws/a/foo.kt"),
            ranked,
        )
    }

    @Test
    fun chooseAdoptionRequiresUniqueMatchWithinTierCap() {
        // 唯一完全一致：可采用。
        assertEquals(
            "/ws/a/Foo.kt",
            WorkspaceAutoFind.chooseAdoption(listOf("/ws/a/Foo.kt", "/ws/b/Other.kt"), "Foo.kt", maxTier = 1),
        )
        // 同档多个：不采用，交由候选列表。
        assertNull(
            WorkspaceAutoFind.chooseAdoption(listOf("/ws/a/Foo.kt", "/ws/b/Foo.kt"), "Foo.kt", maxTier = 1),
        )
        // 只匹配到档位 2，写类工具（maxTier=1）不采用；读类（maxTier=2）可采用。
        val normalizedOnly = listOf("/ws/a/agent_loop.kt")
        assertNull(WorkspaceAutoFind.chooseAdoption(normalizedOnly, "AgentLoop.kt", maxTier = 1))
        assertEquals(
            "/ws/a/agent_loop.kt",
            WorkspaceAutoFind.chooseAdoption(normalizedOnly, "AgentLoop.kt", maxTier = 2),
        )
        // 无候选。
        assertNull(WorkspaceAutoFind.chooseAdoption(emptyList(), "Foo.kt", maxTier = 2))
    }

    @Test
    fun parseListingFilesKeepsOnlyFileEntriesAndJoinsRoot() {
        val text = "d sub\n- a.kt\n- sub/b.kt\n"
        assertEquals(
            listOf("/ws/a.kt", "/ws/sub/b.kt"),
            WorkspaceAutoFind.parseListingFiles("/ws", text),
        )
        assertTrue(WorkspaceAutoFind.parseListingFiles("/ws", "").isEmpty())
    }

    @Test
    fun nearestExistingAncestorWalksUpToExistingDirectory() {
        val existing = setOf("/", "/ws", "/ws/app")
        val resolved = WorkspaceAutoFind.nearestExistingAncestor(
            path = "/ws/app/src/main",
            dirExists = { it in existing },
        )
        assertEquals("/ws/app", resolved)
        assertNull(
            WorkspaceAutoFind.nearestExistingAncestor(
                path = "/ws/app/src",
                dirExists = { it == "/nope" },
            ),
        )
    }

    @Test
    fun findInUsesExactPassFirstThenExtensionFallback() {
        val requested: MutableList<String> = mutableListOf()
        val outcome = WorkspaceAutoFind.findIn("/ws", "Foo.kt") { root, glob ->
            requested += glob
            WorkspaceAutoFind.Listing(
                ok = true,
                root = root,
                entriesText = if (glob == "Foo.kt") "" else "- deep/Foo.kt\n- other/Bar.kt\n",
                truncated = false,
            )
        }
        assertEquals(listOf("Foo.kt", "*.kt"), requested)
        assertEquals(listOf("/ws/deep/Foo.kt"), outcome?.candidates)
    }

    @Test
    fun findInReturnsNullWhenListingUnavailable() {
        assertNull(WorkspaceAutoFind.findIn("/ws", "Foo.kt") { _, _ -> null })
    }

    @Test
    fun findInWithoutExtensionUsesBroadGlobFallback() {
        val seen: MutableList<String> = mutableListOf()
        val outcome = WorkspaceAutoFind.findIn("/ws", "Makefile") { root, glob ->
            seen += glob
            WorkspaceAutoFind.Listing(true, root, if (glob == "Makefile") "" else "- app/Makefile\n", false)
        }
        assertEquals(listOf("Makefile", "*"), seen)
        assertEquals(listOf("/ws/app/Makefile"), outcome?.candidates)
    }
}
