package com.openautolink.app.input

import org.junit.Assert.*
import org.junit.Test

class VhalRetryStateTest {
    @Test fun `six transient failures remain desired and service ready recovers exactly once`() {
        val retry = VhalRetryState(maxDelayMs = 30_000)
        val generations = mutableListOf<Long>()
        generations += retry.start()!!
        repeat(6) { failure ->
            val failed = generations.last()
            val delay = retry.failedAttemptCleaned(failed)!!
            assertTrue(delay in 1_000L..30_000L)
            if (failure < 5) generations += retry.retryTimerFired(failed)!!
        }
        val recovered = retry.serviceReady()!!
        assertNull("ready signal is one-shot while start is owned", retry.serviceReady())
        retry.started(recovered)
        assertTrue(retry.active)
        assertNull(retry.retryTimerFired(generations.last()))
    }

    @Test fun `stop permanently cancels retry and stale failure cannot tear down replacement`() {
        val retry = VhalRetryState()
        val old = retry.start()!!
        retry.failedAttemptCleaned(old)
        val replacement = retry.serviceReady()!!
        retry.started(replacement)
        assertFalse(retry.serviceLost(old))
        assertTrue(retry.active)
        retry.stop()
        assertNull(retry.serviceReady())
        assertNull(retry.retryTimerFired(replacement))
    }

    @Test fun `startup requires a meaningful safety or energy subscription`() {
        assertFalse(VhalSubscriptionReadiness.mayActivate(emptySet()))
        assertFalse(VhalSubscriptionReadiness.mayActivate(setOf("NIGHT_MODE")))
        assertTrue(VhalSubscriptionReadiness.mayActivate(setOf("GEAR_SELECTION")))
        assertTrue(VhalSubscriptionReadiness.mayActivate(setOf("IGNITION_STATE")))
        assertTrue(VhalSubscriptionReadiness.mayActivate(setOf("EV_BATTERY_LEVEL")))
    }
}