package io.github.mangi.eta.agent.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolBatchExecutorTest {
    @Test
    fun safeSegmentRunsInParallelAndResultsStayInInputOrder() {
        val executor = AgentToolBatchExecutor(maxParallel = 4)
        val allStarted = CountDownLatch(4)
        val release = CountDownLatch(1)
        val running = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)

        val results = executor.execute(
            items = listOf(0, 1, 2, 3),
            parallelSafe = { true },
            execute = { value ->
                val now = running.incrementAndGet()
                maxRunning.accumulateAndGet(now, ::maxOf)
                allStarted.countDown()
                assertTrue("并行段必须四个任务同时启动", allStarted.await(2, TimeUnit.SECONDS))
                release.await(2, TimeUnit.SECONDS)
                running.decrementAndGet()
                value
            },
        )

        release.countDown()
        assertEquals(listOf(0, 1, 2, 3), results)
        assertEquals(4, maxRunning.get())
    }

    @Test
    fun serialItemsActAsBarriersWhileSafeSegmentsRemainOrdered() {
        val executor = AgentToolBatchExecutor(maxParallel = 4)
        val running = AtomicInteger(0)
        val serialWasConcurrent = java.util.concurrent.atomic.AtomicBoolean(false)
        val items = listOf(
            Task(0, safe = true),
            Task(1, safe = false),
            Task(2, safe = true),
            Task(3, safe = true),
        )

        val results = executor.execute(
            items = items,
            parallelSafe = { it.safe },
            execute = { task ->
                val now = running.incrementAndGet()
                if (!task.safe && now > 1) serialWasConcurrent.set(true)
                Thread.sleep(20)
                running.decrementAndGet()
                task.value
            },
        )

        assertEquals(listOf(0, 1, 2, 3), results)
        assertTrue("串行工具不得与同批其他工具并发", !serialWasConcurrent.get())
    }

    private data class Task(val value: Int, val safe: Boolean)
}
