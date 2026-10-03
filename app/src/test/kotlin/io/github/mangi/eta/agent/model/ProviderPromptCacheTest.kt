package io.github.mangi.eta.agent.model

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拒绝记忆的 TTL 语义：按 host 与字段隔离、到期自动允许重新探测，
 * 不再像旧实现那样一次误判就关闭到进程结束。
 */
class ProviderPromptCacheTest {
    private val originalClock = ProviderPromptCache.clock
    private var now = 1_000_000L

    @After
    fun restoreClock() {
        ProviderPromptCache.clock = originalClock
    }

    @Test
    fun rejectedFieldsAreIsolatedPerHostAndExpireAfterTtl() {
        ProviderPromptCache.clock = { now }
        val keyHost = "https://ttl-keys.example.com:8443/v1"
        val otherHost = "https://ttl-other.example.com:8443/v1"

        assertTrue(ProviderPromptCache.openAiPromptCacheKeyAllowed(keyHost))
        ProviderPromptCache.markOpenAiPromptCacheKeyRejected(keyHost)
        assertFalse(ProviderPromptCache.openAiPromptCacheKeyAllowed(keyHost))
        // 字段之间互不牵连，host:port 之间互不污染。
        assertTrue(ProviderPromptCache.anthropicCacheControlAllowed(keyHost))
        assertTrue(ProviderPromptCache.openAiPromptCacheKeyAllowed(otherHost))

        // TTL 到期后自动允许重新探测一次，而不是进程内永久关闭。
        now += 31 * 60 * 1000L
        assertTrue(ProviderPromptCache.openAiPromptCacheKeyAllowed(keyHost))
    }

    @Test
    fun reasoningContentRejectionIsRememberedWithinTtl() {
        ProviderPromptCache.clock = { now }
        val host = "https://ttl-reasoning.example.com:8443/v1"

        assertFalse(ProviderPromptCache.isReasoningContentRejected(host))
        ProviderPromptCache.markReasoningContentRejected(host)
        assertTrue(ProviderPromptCache.isReasoningContentRejected(host))

        now += 31 * 60 * 1000L
        assertFalse(ProviderPromptCache.isReasoningContentRejected(host))
    }

    @Test
    fun reasoningSummaryRejectionIsRememberedWithinTtl() {
        ProviderPromptCache.clock = { now }
        val host = "https://ttl-summary.example.com:8443/v1"

        assertFalse(ProviderPromptCache.isReasoningSummaryRejected(host))
        ProviderPromptCache.markReasoningSummaryRejected(host)
        assertTrue(ProviderPromptCache.isReasoningSummaryRejected(host))

        now += 31 * 60 * 1000L
        assertFalse(ProviderPromptCache.isReasoningSummaryRejected(host))
    }
}
