package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EvTelemetryIntegrationTest {
    @Test fun nativeMirrorUsesCallbackOwnerInsteadOfGlobalDiagnosticStream() {
        val session = source("transport/aasdk/AasdkSession.kt")
        assertTrue(session.contains("nativeModel(evTelemetryToken, tag, message)"))
        assertFalse(source("diagnostics/DiagnosticLog.kt").contains("EvTelemetryRecorder.instance.nativeModel"))
    }

    @Test fun realRecorderOutputFlushesIntoBoundedArchiveWithoutPrivateRouteText() {
        val dir = java.nio.file.Files.createTempDirectory("ev-chain").toFile()
        var time = 1000L
        val recorder = EvTelemetryRecorder({ time }, { 1800000000000L + time }, "testboot")
        try {
            recorder.session("testsession")
            recorder.enable(dir)
            fun vehicle() = com.openautolink.app.transport.ControlMessage.VehicleData(
                speedKmh = 0f, gearRaw = 4, evBatteryLevelWh = 39000f,
                evBatteryCapacityWh = 70000f, evCurrentBatteryCapacityWh = 68000f,
                evObservationMetadata = listOf("PERF_VEHICLE_SPEED", "EV_BATTERY_LEVEL", "GEAR_SELECTION")
                    .associateWith { com.openautolink.app.transport.VehiclePropertyObservation(time * 1000000, time, 0) })
            recorder.vehicle("testsession", vehicle(), mapOf("requested" to mapOf("mode" to "learned"),
                "mode" to "default", "learnerState" to "inactive_not_initialized"))
            recorder.navigation("testsession", com.openautolink.app.transport.ControlMessage.NavState(
                maneuver = null, distanceMeters = null, road = "PRIVATE_ROAD", etaSeconds = null,
                destination = "PRIVATE_DESTINATION", destDistanceMeters = 50, timeToArrivalSeconds = 10))
            recorder.forecast("testsession", com.openautolink.app.navigation.VehicleEnergyForecast(
                com.openautolink.app.navigation.EnergyAtDistance(50, 40000, 10), forecastQuality = 2, receivedAtElapsedMs = time))
            time = 2000
            recorder.forecast("testsession", com.openautolink.app.navigation.VehicleEnergyForecast(
                com.openautolink.app.navigation.EnergyAtDistance(25, 39000, 5), forecastQuality = 2, receivedAtElapsedMs = time))
            recorder.vehicle("testsession", vehicle())
            recorder.nativeModel("testsession", "vem", "VEM session=1 sample=1 schema=legacy-local payload=VehicleEnergyModel bytes=1 chunk=1/1 hex=00")
            assertTrue(recorder.flushForUpload())
            recorder.disable()
            assertTrue(recorder.flushForUpload())
            val files = dir.listFiles()!!.toList()
            val plan = LogUploadArchive.select(files, System.currentTimeMillis())
            LogUploadArchive.create(plan, dir).use { archive ->
                val text = java.util.zip.ZipFile(archive.file).use { zip ->
                    zip.entries().asSequence().joinToString("\n") { zip.getInputStream(it).bufferedReader().readText() }
                }
                assertTrue(text.contains("\"arrivalCandidate\":true"))
                assertTrue(text.contains("\"initialArrivalWh\":40000"))
                assertTrue(text.contains("\"latestArrivalWh\":39000"))
                assertTrue(text.contains("\"snapshotIsCached\":true"))
                assertTrue(text.contains("\"learnerState\":\"inactive_not_initialized\""))
                assertTrue(text.contains("native_model"))
                assertTrue(text.contains("ev_flush_confirmed=true"))
                assertFalse(text.contains("PRIVATE_"))
                val fixture = File("build/ev-telemetry-integration.zip")
                fixture.parentFile!!.mkdirs()
                archive.file.copyTo(fixture, overwrite = true)
            }
        } finally { recorder.disable(); recorder.flushForUpload(); dir.deleteRecursively() }
    }

    private fun source(path: String): String = File("src/main/java/com/openautolink/app/$path").readText()
    @Test fun captureHooksExistWithoutActivatingEstimatorObserver() {
        val manager = source("session/SessionManager.kt")
        assertTrue(manager.contains("EvTelemetryRecorder.instance.vehicle("))
        assertTrue(manager.contains("EvTelemetryRecorder.instance.forecast("))
        assertTrue(manager.contains("EvTelemetryRecorder.instance.navigation("))
        assertEquals(2, Regex("observeEvTuningPrefs\\(").findAll(manager).count()) // definition + existing dormant caller
        val vm = source("ui/projection/ProjectionViewModel.kt")
        assertTrue(vm.contains("EvTelemetryRecorder.instance.enable("))
        assertTrue(vm.contains("EvTelemetryRecorder.instance.disable()"))
    }
}
