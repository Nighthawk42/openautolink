package com.openautolink.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvLearningWireSafetyContractTest {
    @Test
    fun `outgoing VEM chokepoint enforces policy and logs requested ready effective and reason`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()
        val chokepoint = source.substringAfter("private fun sendEnergyModelWithTuning(")
            .substringBefore("private fun rejectCurrentEnergyModel(")

        assertSafeShape(chokepoint)
        val unsafePreGate = chokepoint.replace(
            "val activation = EvLearningActivationPolicy.evaluate(",
            "session.sendEnergyModel(batteryWh * 1.1f, capacityWh, rangeM, chargeW)\n        val activation = EvLearningActivationPolicy.evaluate(",
        )
        assertThrows(AssertionError::class.java) { assertSafeShape(unsafePreGate) }

        assertTrue(chokepoint.contains("EvLearningActivationPolicy.evaluate("))
        assertTrue(chokepoint.contains("if (!activation.wireTuningAllowed)"))
        assertTrue(chokepoint.contains("session.sendEnergyModel(batteryWh, capacityWh, rangeM, chargeW)"))
        assertTrue(chokepoint.contains("requested=${'$'}{activation.requestedMode}"))
        assertTrue(chokepoint.contains("learnerReady=${'$'}{activation.learnerReady}"))
        assertTrue(chokepoint.contains("wireEffective=${'$'}{activation.wireEffectiveMode}"))
        assertTrue(chokepoint.contains("safetyHolds=${'$'}{activation.safetyHolds"))
        assertTrue(chokepoint.contains("if (logSafetyInfo)"))

        val telemetry = source.substringAfter("private fun forwardVehicleData(")
            .substringBefore("vd.speedKmh?.let")
        assertTrue(telemetry.contains("\"requested\" to activation.requestedMode"))
        assertTrue(telemetry.contains("\"learnerReady\" to activation.learnerReady"))
        assertTrue(telemetry.contains("\"wireEffective\" to activation.wireEffectiveMode"))
        assertTrue(telemetry.contains("\"safetyHolds\" to activation.safetyHolds.toList()"))
        assertTrue(telemetry.contains("\"droppedCommands\" to runtime?.droppedCommands"))
    }

    private fun assertSafeShape(body: String) {
        val sends = Regex("session\\.sendEnergyModel\\(").findAll(body).toList()
        assertTrue("wire chokepoint must contain a send", sends.isNotEmpty())
        val policy = body.indexOf("EvLearningActivationPolicy.evaluate(")
        val denyGate = body.indexOf("if (!activation.wireTuningAllowed)")
        val send = body.indexOf("session.sendEnergyModel(batteryWh, capacityWh, rangeM, chargeW)")
        assertEquals("the first send must preserve raw inputs", sends.first().range.first, send)
        assertTrue("policy must precede deny gate", policy >= 0 && denyGate > policy)
        assertTrue("deny gate must precede the first raw-value send", send > denyGate)
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