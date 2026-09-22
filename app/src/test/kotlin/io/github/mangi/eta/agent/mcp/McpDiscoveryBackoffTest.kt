package io.github.mangi.eta.agent.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDiscoveryBackoffTest {
    @Test
    fun failedServerIsSuppressedForAWindowThenRetried() {
        val now = 1_000_000L
        assertFalse(McpDiscoveryBackoff.isSuppressed("server-a", now))

        McpDiscoveryBackoff.recordFailure("server-a", now)
        assertTrue(McpDiscoveryBackoff.isSuppressed("server-a", now + 1_000))
        // 抑制有界：窗口过后必须重新尝试，否则坏服务器会被永久跳过。
        assertFalse(McpDiscoveryBackoff.isSuppressed("server-a", now + 300_000))
        // 抑制按服务器隔离。
        assertFalse(McpDiscoveryBackoff.isSuppressed("server-b", now + 1_000))
    }

    @Test
    fun manualRefreshClearsSuppressionImmediately() {
        val now = 2_000_000L
        McpDiscoveryBackoff.recordFailure("server-a", now)
        assertTrue(McpDiscoveryBackoff.isSuppressed("server-a", now + 1_000))

        McpDiscoveryBackoff.clear("server-a")
        assertFalse(McpDiscoveryBackoff.isSuppressed("server-a", now + 1_000))
    }
}
