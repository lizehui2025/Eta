package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveSchedulerTest {
    @Test
    fun idleRuntimeWithoutAlwaysOnDoesNotScheduleWakeups() {
        assertFalse(
            KeepAliveScheduler.shouldSchedule(
                activeRun = false,
                pendingResults = false,
                alwaysOn = false,
            ),
        )
    }

    @Test
    fun ActiveRunPendingResultOrAlwaysOnKeepsRuntimeAlive() {
        assertTrue(KeepAliveScheduler.shouldSchedule(true, false, false))
        assertTrue(KeepAliveScheduler.shouldSchedule(false, true, false))
        assertTrue(KeepAliveScheduler.shouldSchedule(false, false, true))
    }
}
