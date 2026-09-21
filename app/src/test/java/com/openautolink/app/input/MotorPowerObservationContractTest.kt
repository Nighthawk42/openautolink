package com.openautolink.app.input

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MotorPowerObservationContractTest {
    @Test
    fun `history motor cache carries explicit identity status and receipt time into vehicle data`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt",
        ).readText()
        assertTrue(source.contains("latestMotorPowerObservation = VehiclePropertyObservation("))
        assertTrue(source.contains("receivedElapsedMs = SystemClock.elapsedRealtime()"))
        assertTrue(source.contains("status = 0"))
        assertTrue(source.contains("source = \"history-provider\""))
        assertTrue(source.contains("EvLearnedRateEstimator.MOTOR_POWER_OBSERVATION to it"))
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