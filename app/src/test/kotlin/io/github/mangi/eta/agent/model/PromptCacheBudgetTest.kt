package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptCacheBudgetTest {
    @Test
    fun consumesBreakpointsAndDropsAfterCapacity() {
        val budget = PromptCacheBreakpoints.of(2)

        assertEquals("ephemeral", budget.take()?.getString("type"))
        assertEquals("ephemeral", budget.take()?.getString("type"))
        assertNull(budget.take())
        assertEquals(1, budget.dropped)
    }

    @Test
    fun usesOneHourBucketOnlyForLongTtl() {
        assertNull(PromptCacheBreakpoints.of(1).take(3599)?.optString("ttl")?.takeIf { it.isNotBlank() })
        assertEquals("1h", PromptCacheBreakpoints.of(1).take(3600)?.getString("ttl"))
    }
}
