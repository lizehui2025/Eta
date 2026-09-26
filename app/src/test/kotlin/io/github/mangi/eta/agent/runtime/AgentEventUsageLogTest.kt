package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventUsageLogTest {
    @Test
    fun usageReceivedLogsCacheHitWhenInputAndCachedTokensAreUsable() {
        val line = AgentEvent.UsageReceived(
            round = 2,
            usage = AgentTokenUsage(inputTokens = 1_000, cachedTokens = 900),
        ).toLogLine()

        assertTrue(line.contains("cache_hit=90%"))
        assertTrue(line.contains("cache=900"))
    }

    @Test
    fun usageReceivedOmitsCacheHitWhenTokensAreMissing() {
        val missingBoth = AgentEvent.UsageReceived(
            round = 1,
            usage = AgentTokenUsage(),
        ).toLogLine()
        val missingInput = AgentEvent.UsageReceived(
            round = 1,
            usage = AgentTokenUsage(cachedTokens = 900),
        ).toLogLine()
        val missingCached = AgentEvent.UsageReceived(
            round = 1,
            usage = AgentTokenUsage(inputTokens = 1_000),
        ).toLogLine()

        assertFalse(missingBoth.contains("cache_hit="))
        assertFalse(missingInput.contains("cache_hit="))
        assertFalse(missingCached.contains("cache_hit="))
    }

    @Test
    fun usageReceivedOmitsCacheHitWhenInputIsZero() {
        val line = AgentEvent.UsageReceived(
            round = 1,
            usage = AgentTokenUsage(inputTokens = 0, cachedTokens = 0),
        ).toLogLine()

        assertFalse(line.contains("cache_hit="))
    }
}
