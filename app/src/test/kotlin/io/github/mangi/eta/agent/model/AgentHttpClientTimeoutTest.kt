package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentHttpClientTimeoutTest {
    @Test
    fun modelClientUsesPerPurposeHardCallDeadline() {
        assertEquals(
            AgentHttpClient.CHAT_CALL_TIMEOUT_MS,
            AgentHttpClient.modelClientFor(ProviderRequestPurpose.CHAT).callTimeoutMillis.toLong(),
        )
        assertEquals(
            AgentHttpClient.COMPACTION_CALL_TIMEOUT_MS,
            AgentHttpClient.modelClientFor(ProviderRequestPurpose.COMPACTION).callTimeoutMillis.toLong(),
        )
        assertEquals(
            AgentHttpClient.REWRITE_CALL_TIMEOUT_MS,
            AgentHttpClient.modelClientFor(ProviderRequestPurpose.REPLY_REWRITE).callTimeoutMillis.toLong(),
        )
    }
}
