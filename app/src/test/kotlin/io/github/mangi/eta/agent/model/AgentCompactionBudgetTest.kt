package io.github.mangi.eta.agent.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budget for a whole compaction: the only thing bounding an unresponsive wait, so this verifies the
 * limits actually fire, that parallel chunks cannot collectively overshoot, and that an over-budget
 * failure is not amplified by the retry layer.
 */
class AgentCompactionBudgetTest {
    @Test
    fun callLimitStopsFurtherCalls() {
        val budget = AgentCompactionBudget(maxCalls = 2)

        assertNull(budget.tryAcquireCall())
        assertNull(budget.tryAcquireCall())

        val reason = budget.tryAcquireCall()
        assertNotNull(reason)
        assertTrue(reason!!.contains("2 次摘要调用上限"))
        // A rejected attempt consumes no slot.
        assertEquals(2, budget.callsUsed)
    }

    @Test
    fun wallClockLimitStopsFurtherCalls() {
        var now = 0L
        val budget = AgentCompactionBudget(maxWallClockMs = 1_000, clock = { now })

        assertNull(budget.tryAcquireCall())
        now = 999
        assertNull(budget.tryAcquireCall())

        now = 1_000
        val reason = budget.tryAcquireCall()
        assertNotNull(reason)
        assertTrue(reason!!.contains("1 秒"))
        assertEquals(2, budget.callsUsed)
    }

    @Test
    fun parallelAcquirersNeverExceedTheCallLimit() {
        val budget = AgentCompactionBudget(maxCalls = 8)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val granted = AtomicInteger(0)
        try {
            val futures = (0 until 32).map {
                pool.submit {
                    start.await()
                    if (budget.tryAcquireCall() == null) granted.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // A hard limit even for concurrent chunks: grants equal the limit exactly, no per-chunk overshoot.
        assertEquals(8, granted.get())
        assertEquals(8, budget.callsUsed)
    }

    @Test
    fun exceedingTheBudgetFailsWithoutRetry() {
        val budget = AgentCompactionBudget(maxCalls = 1)
        assertNull(budget.tryAcquireCall())

        val failure = runCatching { budget.acquireOrThrow() }.exceptionOrNull()
        assertTrue("超预算必须抛出压缩同族的失败", failure is AgentModelFailure)
        val modelFailure = failure as AgentModelFailure
        assertEquals(AgentCompactionBudget.CODE_BUDGET_EXCEEDED, modelFailure.code)
        // Compaction sits on the user's waiting path: the failure must stay non-retryable, or
        // retries would extend the wait.
        assertFalse(modelFailure.retryable)
        assertEquals(1, budget.callsUsed)
    }
}
