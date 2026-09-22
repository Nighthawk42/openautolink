package com.openautolink.app.input

/** Generation-owned retry policy: attempts are unlimited; only delay is capped. */
class VhalRetryState(
    private val maxDelayMs: Long = 30_000L,
) {
    var active: Boolean = false
        private set
    private var desired = false
    private var startOwned = false
    private var generation = 0L
    private var failures = 0

    @Synchronized fun request() { desired = true }

    @Synchronized fun start(): Long? {
        desired = true
        if (active || startOwned) return null
        startOwned = true
        return ++generation
    }

    @Synchronized fun failedAttemptCleaned(failedGeneration: Long): Long? {
        if (!desired || !startOwned || generation != failedGeneration) return null
        startOwned = false
        active = false
        val shift = failures.coerceAtMost(20)
        failures++
        return (1_000L * (1L shl shift)).coerceAtMost(maxDelayMs)
    }

    @Synchronized fun retryTimerFired(failedGeneration: Long): Long? {
        if (!desired || active || startOwned || generation != failedGeneration) return null
        startOwned = true
        return ++generation
    }

    @Synchronized fun serviceReady(): Long? {
        if (!desired || active || startOwned) return null
        failures = 0
        startOwned = true
        return ++generation
    }

    @Synchronized fun started(startGeneration: Long): Boolean {
        if (!desired || !startOwned || generation != startGeneration) return false
        startOwned = false
        active = true
        failures = 0
        return true
    }

    @Synchronized fun serviceLost(serviceGeneration: Long): Boolean {
        if (!desired || !active || generation != serviceGeneration) return false
        active = false
        startOwned = true
        return true
    }

    @Synchronized fun stop() {
        desired = false
        active = false
        startOwned = false
        failures = 0
        generation++
    }
}