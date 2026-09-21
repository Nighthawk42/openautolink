package com.openautolink.app.diagnostics

import com.openautolink.app.transport.aasdk.AasdkSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext

class EvTelemetryNativeReceiptTest {
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
