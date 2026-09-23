package com.openautolink.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvContributionConsentContractTest {
    @Test fun `automatic EV contribution has independent default-off preference and explicit UI copy`() {
        val prefs = projectFile("app/src/main/java/com/openautolink/app/data/AppPreferences.kt").readText()
        val vm = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt").readText()
        val screen = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt").readText()
        assertTrue(prefs.contains("EV_CONTRIBUTION_CONSENT"))
        assertTrue(prefs.contains("DEFAULT_EV_CONTRIBUTION_CONSENT = false"))
        assertTrue(vm.contains("updateEvContributionConsent"))
        assertTrue(screen.contains("Automatic compact EV contribution"))
        assertTrue(screen.contains("no destinations, coordinates, VIN, device identifiers, or general logs"))
        assertTrue(screen.contains("deletePendingEvContributions"))
        assertTrue(screen.contains("evContributionStatus"))
        assertTrue(screen.contains("consentInvalidReason"))
        assertTrue(screen.contains("retainedCount"))
        assertTrue(screen.contains("lastUploadOutcome"))
        assertTrue(screen.contains("evictedCount"))
        assertTrue(screen.contains("quarantinedCount"))
        assertTrue(screen.contains("lastDeleteResult"))
        assertTrue(screen.contains("uploadStalled"))
        assertTrue(screen.contains("never the mutable device label"))
        assertFalse(screen.substringAfter("Automatic compact EV contribution").substringBefore("Log Upload (maintainer)").contains("logUploadEnabled"))
    }

    @Test fun `process runtime and vehicle path initialize shadow learner and compact contribution independently`() {
        val app = projectFile("app/src/main/java/com/openautolink/app/OalApplication.kt").readText()
        val session = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt").readText()
        val runtime = projectFile("app/src/main/java/com/openautolink/app/input/ProcessVehicleDataRuntime.kt").readText()
        val aasdk = projectFile("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt").readText()
        assertTrue(app.contains("EvContributionService.initialize(this)"))
        assertTrue(app.contains("ProcessVehicleDataRuntime.initialize(this, learnedEstimator)"))
        assertTrue(runtime.contains("EvContributionService.onVehicle(data)"))
        assertTrue(aasdk.contains("EvContributionService.onForecast(receivedForecast)"))
        assertTrue(runtime.contains("learn = estimator::onVehicleTick"))
        assertTrue(runtime.contains("IgnitionMonitor.acceptProcessVehicleData(data)"))
        assertFalse(app.contains("IgnitionMonitor.start(this)"))
        assertTrue(session.contains("ProcessVehicleDataRuntime.attachSessionConsumer(::forwardVehicleData)"))
        assertFalse(session.contains("_vehicleDataForwarder?.stop()"))
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
