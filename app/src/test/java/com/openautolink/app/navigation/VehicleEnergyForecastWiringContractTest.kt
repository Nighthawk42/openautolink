package com.openautolink.app.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleEnergyForecastWiringContractTest {
    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative).readText()
    }

    @Test
    fun `typed GAL forecast reaches a process scoped Kotlin state flow`() {
        val callback = projectFile("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSessionCallback.kt")
        val session = projectFile("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt")
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val header = projectFile("app/src/main/cpp/jni_session.h")
        val manager = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt")

        assertTrue(callback.contains("fun onVehicleEnergyForecast("))
        assertTrue(header.contains("jmethodID onVehicleEnergyForecast"))
        assertTrue(native.contains("GetMethodID(cbClass, \"onVehicleEnergyForecast\""))
        assertTrue(native.contains("CallVoidMethod(callbackRef_, cbMethods_.onVehicleEnergyForecast"))
        assertTrue(session.contains("val vehicleEnergyForecast: StateFlow<VehicleEnergyForecast?>"))
        assertTrue(session.contains("override fun onVehicleEnergyForecast("))
        assertTrue(manager.contains("val vehicleEnergyForecast: StateFlow<VehicleEnergyForecast?>"))
        assertTrue(manager.contains("session.vehicleEnergyForecast.collect"))
    }

    @Test
    fun `route and session termination clear stale forecast state`() {
        val session = projectFile("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt")
        val manager = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt")

        // Match the session member, not the nested generation-fencing adapter.
        val stopStart = session.indexOf("\n    override fun onSessionStopped(reason: String)")
        val stopEnd = session.indexOf("\n    override fun onVideoFrame", stopStart)
        assertTrue(stopStart >= 0 && stopEnd > stopStart)
        assertTrue(session.substring(stopStart, stopEnd).contains("_vehicleEnergyForecast.value = null"))

        val navStart = session.indexOf("\n    override fun onNavigationStatus(status: Int)")
        val navEnd = session.indexOf("\n    override fun onNavigationTurn", navStart)
        assertTrue(navStart >= 0 && navEnd > navStart)
        assertTrue(session.substring(navStart, navEnd).contains("_vehicleEnergyForecast.value = null"))
        assertTrue(manager.contains("_vehicleEnergyForecast.value = null"))
        assertTrue(manager.contains("delay(VehicleEnergyForecastPolicy.MAX_AGE_MS)"))
        assertTrue(manager.contains("forecastExpiryJob?.cancel()"))

        val managerStopStart = manager.indexOf("fun stop()")
        val managerStopEnd = manager.indexOf("fun reconnect(", managerStopStart)
        assertTrue(managerStopStart >= 0 && managerStopEnd > managerStopStart)
        val managerStop = manager.substring(managerStopStart, managerStopEnd)
        assertTrue(managerStop.contains("sessionCollectors?.cancel()"))
        assertTrue(
            managerStop.indexOf("sessionCollectors?.cancel()") <
                managerStop.indexOf("_vehicleEnergyForecast.value = null"),
        )
    }

    @Test
    fun `both cluster paths publish Maps arrival SOC through TravelEstimate trip text`() {
        for (path in listOf(
            "app/src/main/java/com/openautolink/app/cluster/ClusterMainSession.kt",
            "app/src/main/java/com/openautolink/app/cluster/OalClusterSession.kt",
        )) {
            val source = projectFile(path)
            assertTrue("$path must consume the shared forecast", source.contains("ClusterNavigationState.vehicleEnergyForecast"))
            assertTrue("$path must use the VEM capacity snapshot", source.contains("ClusterNavigationState.batteryCapacityWh"))
            assertTrue("$path must publish trip text", source.contains("setTripText"))
        }
    }

    @Test
    fun `projection omits floating arrival badge while cluster and diagnostics retain forecast`() {
        val screen = projectFile(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt",
        )
        val viewModel = projectFile(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt",
        )
        val diagnostics = projectFile(
            "app/src/main/java/com/openautolink/app/ui/diagnostics/DiagnosticsViewModel.kt",
        )
        val cluster = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterMainSession.kt",
        )

        assertFalse(screen.contains("mapsArrivalBatteryBadge"))
        assertFalse(screen.contains("arrivalBatteryText"))
        assertFalse(viewModel.contains("arrivalBatteryText"))
        assertTrue(diagnostics.contains("mapsArrivalBatteryPct"))
        assertTrue(cluster.contains("setTripText"))
    }

    @Test
    fun `VHAL charge power is converted from milliwatts and never becomes max capability`() {
        val forwarder = projectFile("app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt")
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val start = native.indexOf("void JniSession::sendEnergyModelSensor")
        val end = native.indexOf("void JniSession::sendAccelerometerSensor", start)
        assertTrue(start >= 0 && end > start)
        val sender = native.substring(start, end)

        assertTrue(forwarder.contains("EvEnergyValuePolicy.instantaneousMilliwattsToWatts"))
        assertTrue(sender.contains("maxChargeWOverride > 0 ? maxChargeWOverride : 150000"))
        assertFalse(sender.contains("chargeRateW > 0"))
        assertTrue(sender.contains("auxWhPerKmOverride >= 0.0f ? auxWhPerKmOverride : 0.0f"))
        assertTrue(sender.contains("aeroCoefOverride >= 0.0f ? aeroCoefOverride : 0.0f"))
    }
}
