package com.openautolink.app.input

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MotorPowerObservationContractTest {
    @Test
    fun `history motor cache uses the coherent source timestamp instead of poll receipt`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt",
        ).readText()
        assertTrue(source.contains("latestMotorPowerSample(context)"))
        assertTrue(source.contains("timestampElapsedNanos = sample.sourceElapsedNanos"))
        assertTrue(source.contains("receivedElapsedMs = SystemClock.elapsedRealtime()"))
        assertTrue(source.contains("source = \"history-provider-coherent\""))
        assertTrue(source.contains("EvLearnedRateEstimator.MOTOR_POWER_OBSERVATION to it"))
    }

    @Test
    fun `history timestamp milliseconds convert to elapsed nanoseconds exactly`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/data/GmHistoryProviderRepository.kt",
        ).readText()
        assertTrue(source.contains("Math.multiplyExact(sourceElapsedMs, 1_000_000L)"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("project file not found: $path")
    }
}