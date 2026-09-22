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
        assertFalse(screen.substringAfter("Automatic compact EV contribution").substringBefore("Log Upload (maintainer)").contains("logUploadEnabled"))
    }

    @Test fun `process runtime and vehicle path initialize shadow learner and compact contribution independently`() {
        val app = projectFile("app/src/main/java/com/openautolink/app/OalApplication.kt").readText()
        val session = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt").readText()
        val aasdk = projectFile("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt").readText()
        assertTrue(app.contains("EvContributionService.initialize(this)"))
        assertTrue(session.contains("EvContributionService.onVehicle("))
        assertTrue(aasdk.contains("EvContributionService.onForecast(receivedForecast)"))
        assertTrue(session.contains("evLearnedEstimator?.onVehicleTick(vd, now)"))
        assertTrue(session.indexOf("evLearnedEstimator?.onVehicleTick(vd, now)") < session.indexOf("sendEnergyModelWithTuning("))
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
