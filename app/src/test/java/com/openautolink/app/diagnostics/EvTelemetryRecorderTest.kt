package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation

class EvTelemetryRecorderTest {
    @Test fun consentStopRejectsNewCallbacksAndRestartBeginsNewDriveEpoch() {
        val dir = Files.createTempDirectory("ev-consent").toFile()
        var now = 1000L
        val recorder = EvTelemetryRecorder({ now }, { now }, "boot")
        try {
            recorder.session("one")
            recorder.enable(dir)
            recorder.event("one", "before_stop")
            recorder.disable()
            assertTrue(recorder.flushForUpload())
            val stoppedSize = dir.listFiles()!!.sumOf { it.length() }
            recorder.event("one", "forbidden_after_stop")
            recorder.nativeModel("one", "vem", "VEM session=1 forbidden_after_stop")
            recorder.vehicle("one", ControlMessage.VehicleData(evBatteryLevelWh = 12345f))
            assertTrue(recorder.flushForUpload())
            assertEquals(stoppedSize, dir.listFiles()!!.sumOf { it.length() })
            now = 2000
            recorder.enable(dir)
            recorder.event("one", "after_restart")
            assertTrue(recorder.flushForUpload())
            val records = dir.listFiles()!!.flatMap { it.readLines() }.map {
                kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
            val starts = records.filter { it["type"].toString() == "\"consent_start\"" }
            assertEquals(2, starts.size)
            assertNotEquals(starts[0]["drive"], starts[1]["drive"])
            assertFalse(records.toString().contains("forbidden_after_stop"))
        } finally { recorder.disable(); recorder.flushForUpload(); dir.deleteRecursively() }
    }

    @Test fun unknownPropertyStatusIsNotPromotedToAvailableForDistance() {
        val dir = Files.createTempDirectory("ev-unknown-status").toFile()
        var now = 1000L
        val recorder = EvTelemetryRecorder({ now }, { now }, "boot")
        try {
            recorder.session("one")
            recorder.enable(dir)
            fun vehicle() = ControlMessage.VehicleData(speedKmh = 36f,
                evObservationMetadata = mapOf("PERF_VEHICLE_SPEED" to VehiclePropertyObservation(now * 1000000, now, null)))
            recorder.vehicle("one", vehicle())
            now = 2000
            recorder.vehicle("one", vehicle())
            assertTrue(recorder.flushForUpload())
            val text = dir.listFiles()!!.joinToString { it.readText() }
            assertFalse(text.contains("\"integratedDistanceM\":10.0"))
            assertTrue(text.contains("\"status\":null"))
        } finally { recorder.disable(); recorder.flushForUpload(); dir.deleteRecursively() }
    }

    @Test fun replayedOldForecastDoesNotBecomeFreshAtCollectionTime() {
        val dir = Files.createTempDirectory("ev-old-forecast").toFile()
        val recorder = EvTelemetryRecorder({ 20000 }, { 20000 }, "boot")
        try {
            recorder.session("one")
            recorder.enable(dir)
            recorder.navigation("one", ControlMessage.NavState(null, null, null, null,
                destination = "private stop", destDistanceMeters = 10, timeToArrivalSeconds = 1))
            recorder.forecast("one", com.openautolink.app.navigation.VehicleEnergyForecast(
                com.openautolink.app.navigation.EnergyAtDistance(10, 40000, 1), forecastQuality = 2, receivedAtElapsedMs = 1000))
            recorder.vehicle("one", ControlMessage.VehicleData(speedKmh = 0f, gearRaw = 4,
                evBatteryLevelWh = 40000f, evObservationMetadata =
                    listOf("PERF_VEHICLE_SPEED", "EV_BATTERY_LEVEL", "GEAR_SELECTION").associateWith {
                        VehiclePropertyObservation(20000000000, 20000, 0) }))
            assertTrue(recorder.flushForUpload())
            assertFalse(dir.listFiles()!!.joinToString { it.readText() }.contains("\"arrivalCandidate\":true"))
        } finally { recorder.disable(); recorder.flushForUpload(); dir.deleteRecursively() }
    }

    @Test fun disabledCaptureStillReportsPendingStorageFailureAtUpload() {
        val unavailable = Files.createTempFile("ev-not-directory", ".tmp").toFile()
        val recorder = EvTelemetryRecorder({ 1000 }, { 1000 }, "boot")
        try {
            recorder.enable(unavailable)
            recorder.disable()
            assertFalse(recorder.flushForUpload())
        } finally { unavailable.delete() }
    }

    @Test fun consentSessionOwnershipAndUploadParkSnapshotAreExplicit() {
        val dir = Files.createTempDirectory("ev-recorder").toFile()
        var time = 1000L
        val recorder = EvTelemetryRecorder({ time }, { time }, "testboot")
        try {
            recorder.session("one")
            recorder.vehicle("one", ControlMessage.VehicleData(evBatteryLevelWh = 50000f))
            assertTrue(dir.listFiles()!!.isEmpty())
            recorder.enable(dir)
            recorder.vehicle("one", ControlMessage.VehicleData(speedKmh = 0f, gearRaw = 4,
                evBatteryLevelWh = 50000f, evObservationMetadata = mapOf(
                    "PERF_VEHICLE_SPEED" to VehiclePropertyObservation(1000000000, 1000, 0),
                    "EV_BATTERY_LEVEL" to VehiclePropertyObservation(1000000000, 1000, 0))))
            recorder.nativeModel("one", "vem", "VEM_DIAG secret forbidden")
            recorder.nativeModel("one", "nav", "RAW destination private")
            recorder.session("two")
            recorder.nativeModel("one", "vem", "VEM session=1 stale_callback")
            recorder.gap("one", "stale_callback")
            assertTrue(recorder.flushForUpload())
            recorder.disable()
            val text = dir.listFiles()!!.joinToString { it.readText() }
            assertTrue(text.contains("upload_snapshot"))
            assertTrue(text.contains("consent_start"))
            assertFalse(text.contains("private"))
            assertFalse(text.contains("stale_callback"))
        } finally { recorder.disable(); dir.deleteRecursively() }
    }
}
