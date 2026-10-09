package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalRuntime 的 per-run 会话工作区契约的纯 JVM 单测（不使用 Robolectric）。
 *
 * 覆盖三部分：
 *  1. 常量 SESSIONS_DIR / MAX_RETAINED_SESSION_WORKSPACES 的取值；
 *  2. sessionWorkspacePath / safeRunId 的拼接与净化（含路径穿越、绝对路径、空白、超长、空串）；
 *  3. pruneSessionWorkspaces 的保留/删除集合（含 keep=8、keep=1、mtime 相同、activeName 不在 entries 中等）。
 */
class TerminalSessionWorkspaceTest {

    private val sessionsDir = TerminalRuntime.SESSIONS_DIR

    private fun entryList(vararg pairs: Pair<String, Long>): List<Pair<String, Long>> = pairs.toList()

    private fun names(entries: List<Pair<String, Long>>): List<String> = entries.map { it.first }

    // ------------------------------------------------------------------ 常量

    @Test
    fun `sessions dir constant keeps the documented value sessions`() {
        assertEquals(
            "SESSIONS_DIR 必须为 \"sessions\"",
            "sessions",
            TerminalRuntime.SESSIONS_DIR
        )
    }

    @Test
    fun `max retained session workspaces constant keeps the documented value eight`() {
        assertEquals(
            "MAX_RETAINED_SESSION_WORKSPACES 必须为 8",
            8,
            TerminalRuntime.MAX_RETAINED_SESSION_WORKSPACES
        )
    }

    // -------------------------------------------------------------- 路径拼接

    @Test
    fun `session workspace path joins root with sessions dir and safe run id`() {
        assertEquals(
            "普通 root 应拼成 <root>/sessions/<safeRunId>",
            "/ws/sessions/run-1",
            TerminalRuntime.sessionWorkspacePath("/ws", "run-1")
        )
    }

    @Test
    fun `session workspace path normalizes trailing slash of root`() {
        assertEquals(
            "root 末尾斜杠必须被去除，结果不能出现双斜杠",
            "/ws/sessions/run-1",
            TerminalRuntime.sessionWorkspacePath("/ws/", "run-1")
        )
    }

    @Test
    fun `session workspace path applies safe run id sanitizing`() {
        assertEquals(
            "路径中的 runId 必须经过 safeRunId 处理，\"../../etc\" 应净化为 \"etc\"",
            "/ws/sessions/etc",
            TerminalRuntime.sessionWorkspacePath("/ws", "../../etc")
        )
    }

    @Test
    fun `session workspace path never escapes root for hostile run ids`() {
        val hostileIds = listOf("/abs", "a b", "a\u0000b", "..", "../..", ".", "", "   ", "a/b")
        for (rawId in hostileIds) {
            val path = TerminalRuntime.sessionWorkspacePath("/ws", rawId)
            assertTrue(
                "路径必须以 /ws/sessions/ 开头，实际为 \"$path\"（runId=\"$rawId\"）",
                path.startsWith("/ws/$sessionsDir/")
            )
            assertFalse(
                "路径不得包含 \"..\" 片段，实际为 \"$path\"（runId=\"$rawId\"）",
                path.contains("..")
            )
            assertEquals(
                "路径不得出现连续斜杠，实际为 \"$path\"（runId=\"$rawId\"）",
                path,
                path.replace("//", "/")
            )
            assertEquals(
                "路径末尾必须是净化后的 runId，实际为 \"$path\"（runId=\"$rawId\"）",
                TerminalRuntime.safeRunId(rawId),
                path.substringAfterLast('/')
            )
        }
    }

    @Test
    fun `session workspace path does not throw for empty or slash only root`() {
        for (root in listOf("", "/")) {
            val path = TerminalRuntime.sessionWorkspacePath(root, "run-1")
            assertTrue(
                "root=\"$root\" 时结果应包含 sessions，实际为 \"$path\"",
                path.contains(sessionsDir)
            )
            assertTrue(
                "root=\"$root\" 时结果应保留净化后的 runId，实际为 \"$path\"",
                path.endsWith("run-1")
            )
        }
    }

    // ---------------------------------------------------------------- runId 净化

    @Test
    fun `safe run id strips path traversal segments`() {
        assertEquals(
            "\"../../etc\" 必须净化为 \"etc\"",
            "etc",
            TerminalRuntime.safeRunId("../../etc")
        )
        assertEquals(
            "\"..\" 被完全剥离后为空，必须回退为 \"run\"",
            "run",
            TerminalRuntime.safeRunId("..")
        )
        assertEquals(
            "\"../../\" 被完全剥离后为空，必须回退为 \"run\"",
            "run",
            TerminalRuntime.safeRunId("../../")
        )
    }

    @Test
    fun `safe run id strips leading slash of absolute path`() {
        assertEquals(
            "\"/abs\" 必须以 \"abs\" 返回",
            "abs",
            TerminalRuntime.safeRunId("/abs")
        )
        assertEquals(
            "\"/etc/passwd\" 必须以 \"etcpasswd\" 返回",
            "etcpasswd",
            TerminalRuntime.safeRunId("/etc/passwd")
        )
    }

    @Test
    fun `safe run id removes whitespace and control characters`() {
        assertEquals(
            "\"a b\" 的空格必须被移除",
            "ab",
            TerminalRuntime.safeRunId("a b")
        )
        assertEquals(
            "\"a\\u0000b\" 的 NUL 必须被移除",
            "ab",
            TerminalRuntime.safeRunId("a\u0000b")
        )
        assertEquals(
            "\"a\\tb\\nc\" 的制表符与换行必须被移除",
            "abc",
            TerminalRuntime.safeRunId("a\tb\nc")
        )
        assertEquals(
            "\"   \" 被完全剥离后必须回退为 \"run\"",
            "run",
            TerminalRuntime.safeRunId("   ")
        )
    }

    @Test
    fun `safe run id falls back to run for empty or fully stripped input`() {
        assertEquals("空串必须返回 \"run\"", "run", TerminalRuntime.safeRunId(""))
        assertEquals("全部为非法字符时必须返回 \"run\"", "run", TerminalRuntime.safeRunId("***"))
        assertEquals("全部为非法字符时必须返回 \"run\"", "run", TerminalRuntime.safeRunId("///"))
        assertEquals("仅控制字符时必须返回 \"run\"", "run", TerminalRuntime.safeRunId("\u0000"))
    }

    @Test
    fun `safe run id keeps allowed characters untouched`() {
        val allowed = "Run_01-abcZY"
        assertEquals(
            "仅含 [A-Za-z0-9_-] 的 id 必须原样返回",
            allowed,
            TerminalRuntime.safeRunId(allowed)
        )
        assertEquals(
            "点号也必须被剥离，\"a.b\" 应变为 \"ab\"",
            "ab",
            TerminalRuntime.safeRunId("a.b")
        )
    }

    @Test
    fun `safe run id truncates identifiers longer than sixty four characters`() {
        val tooLong = "a".repeat(100)
        val safe = TerminalRuntime.safeRunId(tooLong)
        assertEquals("超过 64 字符必须截断到 64，实际长度为 ${safe.length}", 64, safe.length)
        assertEquals("截断必须保留前 64 个字符", tooLong.take(64), safe)

        val exactly64 = "b".repeat(64)
        assertEquals("恰好 64 字符不应被截断", exactly64, TerminalRuntime.safeRunId(exactly64))
    }

    @Test
    fun `safe run id never yields slash dot backslash or whitespace`() {
        val hostile = listOf(
            "../../etc/passwd",
            "/abs",
            "a b",
            "a\u0000b",
            "..",
            ".",
            ".hidden",
            "a/b\\c",
            "  spaced  ",
            "\u0000",
            "a\u2028b"
        )
        for (raw in hostile) {
            val safe = TerminalRuntime.safeRunId(raw)
            assertTrue(
                "safeRunId(\"$raw\") 不得为空，实际为 \"$safe\"",
                safe.isNotEmpty()
            )
            assertFalse(
                "safeRunId(\"$raw\") 不得包含 '/'，实际为 \"$safe\"",
                safe.contains('/')
            )
            assertFalse(
                "safeRunId(\"$raw\") 不得包含 '\\'，实际为 \"$safe\"",
                safe.contains('\\')
            )
            assertFalse(
                "safeRunId(\"$raw\") 不得包含 '.'，实际为 \"$safe\"",
                safe.contains('.')
            )
            assertTrue(
                "safeRunId(\"$raw\") 不得包含空白字符，实际为 \"$safe\"",
                safe.none { it.isWhitespace() }
            )
            assertTrue(
                "safeRunId(\"$raw\") 只能包含 [A-Za-z0-9_-]，实际为 \"$safe\"",
                safe.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
            )
        }
    }

    // ------------------------------------------------------------------ prune

    @Test
    fun `prune deletes nothing when entries fit within keep budget`() {
        val entries = entryList(
            "sessions/run-a" to 100L,
            "sessions/run-b" to 200L,
            "sessions/run-c" to 300L
        )
        val pruned = TerminalRuntime.pruneSessionWorkspaces(
            entries,
            TerminalRuntime.MAX_RETAINED_SESSION_WORKSPACES,
            "sessions/run-c"
        )
        assertTrue(
            "条目数不超过 keep 上限时不应删除任何目录，实际删除 $pruned",
            pruned.isEmpty()
        )
    }

    @Test
    fun `prune with keep of eight keeps active plus seven newest entries`() {
        val entries = (1..12).map { "sessions/run-$it" to it.toLong() }
        val active = "sessions/run-1"
        val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, 8, active)
        assertEquals(
            "keep=8 时应保留 active 与最新 7 个（run-6..run-12），删除 run-2..run-5，实际删除 $pruned",
            setOf("sessions/run-2", "sessions/run-3", "sessions/run-4", "sessions/run-5"),
            pruned.toSet()
        )
        assertFalse("active 目录永不能被删除，实际删除 $pruned", pruned.contains(active))
        assertEquals(
            "删除结果不得含重复项，实际为 $pruned",
            pruned.size,
            pruned.toSet().size
        )
    }

    @Test
    fun `prune with keep of one retains only the active workspace`() {
        val entries = entryList(
            "sessions/old" to 10L,
            "sessions/active" to 20L,
            "sessions/newer" to 30L,
            "sessions/newest" to 40L
        )
        val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, 1, "sessions/active")
        assertEquals(
            "keep=1 时除 active 外全部应删除，实际删除 $pruned",
            setOf("sessions/old", "sessions/newer", "sessions/newest"),
            pruned.toSet()
        )
        assertFalse("keep=1 时 active 也必须保留，实际删除 $pruned", pruned.contains("sessions/active"))
    }

    @Test
    fun `prune keeps active workspace even when it is the oldest entry`() {
        val entries = entryList(
            "sessions/active" to 1L,
            "sessions/b" to 50L,
            "sessions/c" to 60L,
            "sessions/d" to 70L
        )
        val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, 2, "sessions/active")
        assertEquals(
            "keep=2 时应保留 active（即使它最旧）与 mtime 最新的 1 个，删除 b、c，实际删除 $pruned",
            setOf("sessions/b", "sessions/c"),
            pruned.toSet()
        )
        assertFalse("active 不能被删除，实际删除 $pruned", pruned.contains("sessions/active"))
    }

    @Test
    fun `prune removes all entries when active name is not part of entries`() {
        val entries = entryList(
            "sessions/a" to 1L,
            "sessions/b" to 2L
        )
        val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, 1, "sessions/current")
        assertEquals(
            "active 不在 entries 中时，除 active 名额外的条目全部删除，实际删除 $pruned",
            setOf("sessions/a", "sessions/b"),
            pruned.toSet()
        )
    }

    @Test
    fun `prune deletes nothing when keep covers active plus every other entry`() {
        val entries = entryList(
            "sessions/a" to 1L,
            "sessions/active" to 2L,
            "sessions/c" to 3L
        )
        val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, 8, "sessions/active")
        assertTrue(
            "keep 大于等于条目总数时不应删除任何目录，实际删除 $pruned",
            pruned.isEmpty()
        )
    }

    @Test
    fun `prune keeps active workspace when active is missing from entries and keep equals entry count`() {
        val entries = entryList(
            "sessions/a" to 1L,
            "sessions/b" to 2L,
            "sessions/c" to 3L
        )
        val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, 3, "sessions/current")
        assertTrue(
            "keep=3 且 active 不在 entries 中时应保留 3 个，实际删除 $pruned",
            pruned.isEmpty()
        )
    }

    @Test
    fun `prune is deterministic with equal mtimes and keeps the expected count`() {
        val entries = entryList(
            "sessions/a" to 100L,
            "sessions/b" to 100L,
            "sessions/c" to 100L,
            "sessions/d" to 100L,
            "sessions/e" to 100L
        )
        val first = TerminalRuntime.pruneSessionWorkspaces(entries, 3, "sessions/a")
        val second = TerminalRuntime.pruneSessionWorkspaces(entries, 3, "sessions/a")
        assertEquals(
            "mtime 相同时两次调用的结果必须一致，第一次 $first，第二次 $second",
            first,
            second
        )
        assertEquals(
            "keep=3 且 mtime 相同时，应删除除 active 与 2 个保留项之外的 2 个条目，实际删除 $first",
            2,
            first.size
        )
        assertFalse("mtime 相同时 active 仍不得出现在删除列表，实际删除 $first", first.contains("sessions/a"))
        assertTrue(
            "删除结果必须来自 entries，实际删除 $first",
            names(entries).containsAll(first)
        )
    }

    @Test
    fun `prune with non positive keep never returns the active workspace`() {
        val entries = entryList(
            "sessions/a" to 1L,
            "sessions/active" to 2L,
            "sessions/c" to 3L
        )
        for (keep in listOf(0, -1, -100)) {
            val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, keep, "sessions/active")
            assertFalse(
                "keep=$keep 时不得把 active 列入删除，实际删除 $pruned",
                pruned.contains("sessions/active")
            )
            assertTrue(
                "keep=$keep 时只能返回 entries 中的目录，实际删除 $pruned",
                names(entries).containsAll(pruned)
            )
            assertEquals(
                "keep=$keep 时删除结果不得含重复项，实际为 $pruned",
                pruned.size,
                pruned.toSet().size
            )
        }
    }

    @Test
    fun `prune returns empty list for empty input`() {
        val pruned = TerminalRuntime.pruneSessionWorkspaces(emptyList(), 1, "sessions/active")
        assertTrue("空输入必须返回空列表，实际为 $pruned", pruned.isEmpty())
    }

    @Test
    fun `prune returns empty list for a single entry`() {
        val activeOnly = entryList("sessions/active" to 5L)
        val prunedActiveOnly = TerminalRuntime.pruneSessionWorkspaces(activeOnly, 1, "sessions/active")
        assertTrue(
            "只有 active 一个条目时必须返回空列表，实际为 $prunedActiveOnly",
            prunedActiveOnly.isEmpty()
        )

        val singleOther = entryList("sessions/only" to 5L)
        val prunedSingleOther = TerminalRuntime.pruneSessionWorkspaces(singleOther, 8, "sessions/active")
        assertTrue(
            "只有 1 个条目时必须返回空列表，实际为 $prunedSingleOther",
            prunedSingleOther.isEmpty()
        )
    }

    @Test
    fun `prune only ever returns names taken from the given entries`() {
        val entries = (1..10).map { "sessions/run-$it" to it.toLong() }
        val pruned = TerminalRuntime.pruneSessionWorkspaces(entries, 4, "sessions/run-5")
        assertEquals(
            "keep=4 时应删除最旧的 6 个条目，实际删除 $pruned",
            setOf(
                "sessions/run-1",
                "sessions/run-2",
                "sessions/run-3",
                "sessions/run-4",
                "sessions/run-6",
                "sessions/run-7"
            ),
            pruned.toSet()
        )
        assertTrue(
            "删除结果必须全部来自 entries，实际删除 $pruned",
            names(entries).containsAll(pruned)
        )
    }
}
