package com.openautolink.app.diagnostics

import com.openautolink.app.transport.aasdk.AasdkSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext

class EvTelemetryNativeReceiptTest {
    @Test fun nativeActiveBoundaryRotatesOnceAndDuplicatePreservesEpoch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AasdkSession(scope, io.mockk.mockk(relaxed = true))
        val callback = session.javaClass.getDeclaredMethod("telemetryCallback").apply { isAccessible = true }
            .invoke(session) as com.openautolink.app.transport.aasdk.AasdkSessionCallback
        val recorder = EvTelemetryRecorder.instance
        val dir = Files.createTempDirectory("ev-active-boundary").toFile()
        try {
            recorder.session(session.evTelemetryToken); recorder.enable(dir)
            callback.onNavigationStatus(1)
            callback.onNavigationStatus(1)
            callback.onNavigationStatus(3)
            assertTrue(recorder.flushForUpload())
            val records = dir.listFiles()!!.flatMap { it.readLines() }
            val active = records.filter { it.contains("\"type\":\"route_active\"") }
            assertEquals(2, active.size)
            val epochs = active.map {
                (kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject)["routeEpochId"].toString()
            }
            assertEquals(1, epochs.distinct().size)
            val reroute = records.single { it.contains("\"type\":\"reroute\"") }
            assertFalse(reroute.contains(epochs.first()))
            assertTrue(records.any {
                it.contains("\"type\":\"navigation_status\"") &&
                    it.contains("\"status\":3") && it.contains("\"statusName\":\"REROUTING\"")
            })
        } finally { scope.cancel(); recorder.disable(); recorder.flushForUpload(); dir.deleteRecursively() }
    }

    @Test fun nativeReceiptNormalizesOnlyExplicitPositiveDestinationNumbers() {
        val source = java.io.File("src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt").readText()
        val first = source.indexOf("override fun onNavigationFullState(")
        val start = source.indexOf("override fun onNavigationFullState(", first + 1)
        val callback = source.substring(start, source.indexOf("override fun onVehicleEnergyForecast(", start))
        assertTrue(callback.contains("destDistanceMeters.takeIf { it > 0 }"))
        assertTrue(callback.contains("timeToArrivalSeconds.takeIf { it > 0 }"))
        assertFalse(callback.contains("takeIf { it >= 0 }"))
    }

    @Test fun jniUsesNegativeSentinelsForAbsentNavigationNumbers() {
        val source = java.io.File("src/main/cpp/jni_channel_handlers.cpp").readText()
        val state = source.substring(source.indexOf("void JniNavStatusHandler::onNavigationState"),
            source.indexOf("void JniNavStatusHandler::onCurrentPosition"))
        val position = source.substring(source.indexOf("void JniNavStatusHandler::onCurrentPosition"))
        assertTrue(state.contains("nullptr, 0,\n        -1, -1"))
        assertTrue(state.contains("\"\", destination, \"\", -1, -1"))
        assertTrue(position.contains("int distanceMeters = -1;"))
        assertTrue(position.contains("int etaSeconds = -1;"))
        assertTrue(position.contains("long long timeToArrivalSeconds = -1;"))
        assertTrue(position.contains("int destDistanceMeters = -1;"))
    }

    @Test fun currentNativeAdapterClearsForecastWhenNavigationEnds() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AasdkSession(scope, io.mockk.mockk(relaxed = true))
        val callback = session.javaClass.getDeclaredMethod("telemetryCallback").apply { isAccessible = true }
            .invoke(session) as com.openautolink.app.transport.aasdk.AasdkSessionCallback
        try {
            callback.onVehicleEnergyForecast(10, 40000, 1, -1, -1, -1, 2, -1, -1, -1)
            assertEquals(40000, session.vehicleEnergyForecast.value?.energyAtNextStop?.arrivalBatteryEnergyWh)
            callback.onNavigationStatus(0)
            assertNull(session.vehicleEnergyForecast.value)
        } finally { scope.cancel() }
    }

    @Test fun actualRetiredNativeAdapterCannotCaptureIntoReplacement() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val s = AasdkSession(scope, io.mockk.mockk(relaxed = true))
        val r = EvTelemetryRecorder.instance
        val dir = Files.createTempDirectory("ev-native-generation").toFile()
        fun callback() = s.javaClass.getDeclaredMethod("telemetryCallback").apply { isAccessible = true }
            .invoke(s) as com.openautolink.app.transport.aasdk.AasdkSessionCallback
        try {
            r.session(s.evTelemetryToken); r.enable(dir)
            val retired = callback()
            s.beginEvTelemetryGeneration(); r.session(s.evTelemetryToken)
            retired.onNativeLog(1, "vem", "VEM session=1 retired_generation")
            retired.onVehicleEnergyForecast(10, 40000, 1, -1, -1, -1, 2, -1, -1, -1)
            callback().onNativeLog(1, "vem", "VEM session=2 current_generation")
            assertTrue(r.flushForUpload())
            val text = dir.listFiles()!!.joinToString { it.readText() }
            assertFalse(text.contains("retired_generation"))
            assertFalse(text.contains("\"type\":\"forecast\""))
            assertTrue(text.contains("current_generation"))
        } finally { scope.cancel(); r.disable(); r.flushForUpload(); dir.deleteRecursively() }
    }

    @Test fun navigationIsCapturedAtReceiptNotAfterQueuedDeliveryInNewConsent() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = AasdkSession(scope, io.mockk.mockk(relaxed = true))
        val r = EvTelemetryRecorder.instance
        val dir = Files.createTempDirectory("ev-native-receipt").toFile()
        try {
            r.session(session.evTelemetryToken); r.enable(dir)
            session.onNavigationFullState(null, null, null, 10, 1, null, null, null, null,
                -1, null, "private stop", null, 1L, 10, null, null)
            assertTrue(r.flushForUpload())
            assertTrue("Receipt must be recorded before queued collector work", dir.listFiles()!!.any { it.readText().contains("navigation") })
            r.disable(); r.flushForUpload(); r.enable(dir); r.flushForUpload()
            fun navigationCount() = dir.listFiles()!!.flatMap { it.readLines() }.count { it.contains("\"type\":\"navigation\"") }
            val before = navigationCount()
            val pending = queued.toList(); queued.clear(); pending.forEach { it.run() }
            // A barrier only; avoid adding an upload snapshot in the size assertion.
            r.disable(); r.flushForUpload()
            assertEquals(before, navigationCount())
            assertFalse(dir.listFiles()!!.joinToString { it.readText() }.contains("private stop"))
        } finally { scope.cancel(); r.disable(); r.flushForUpload(); dir.deleteRecursively() }
    }
}
