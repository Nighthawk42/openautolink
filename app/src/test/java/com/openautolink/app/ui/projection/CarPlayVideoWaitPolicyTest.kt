package com.openautolink.app.ui.projection

import com.openautolink.app.carplay.*
import com.openautolink.carplaylab.media.ReceiverTelemetrySnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarPlayVideoWaitPolicyTest {
    @Test fun activeSelectedAttemptWaitsWithoutRenderedProof() {
        assertTrue(CarPlayUiState(selected = true, busy = true).waitingForVideo)
        assertFalse(CarPlayUiState().waitingForVideo)
        assertTrue(CarPlayUiState(selected = true, busy = true, evidence = CarPlayEvidence(
            runtimeAuthenticated = true, videoRendered = true,
            telemetry = ReceiverTelemetrySnapshot(1, decoderName = "decoder", renderedFrames = 50),
        )).waitingForVideo) // Lifetime/log evidence cannot certify the current presentation.
        assertFalse(CarPlayUiState(selected = true, busy = true, evidence = CarPlayEvidence(
            telemetry = ReceiverTelemetrySnapshot(1, presentationGeneration = 2, presentationRendered = true),
        )).waitingForVideo)
    }

    @Test fun compactIndicatorIsWiredAndSuppressedBehindRecoveryUi() {
        val policy = runCatching { Class.forName("com.openautolink.app.ui.projection.CarPlayVideoWaitPolicy") }.getOrNull()
        assertNotNull("UI needs an explicit noninteractive visibility policy", policy)
        val instance = policy!!.getField("INSTANCE").get(null)
        val show = policy.getMethod("shouldShow", CarPlayUiState::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val waiting = CarPlayUiState(selected = true, busy = true)
        assertEquals(true, show.invoke(instance, waiting, true, false))
        assertEquals(false, show.invoke(instance, waiting, false, false))
        assertEquals(false, show.invoke(instance, waiting, true, true))
        assertEquals(false, show.invoke(instance, CarPlayUiState(), true, false))
        val screen = File("src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt").readText()
        assertTrue(screen.contains("CarPlayVideoWaitPolicy.shouldShow("))
        assertTrue(screen.contains("carPlayVideoWaitIndicator"))
        val badge = screen.substringAfter("private fun CarPlayVideoWaitIndicator(").substringBefore("/**")
        assertTrue(badge.contains("Waiting for video"))
        assertFalse(badge.contains("pointerInteropFilter"))
        assertFalse(badge.contains("clickable"))
        assertFalse(badge.contains("fillMaxSize"))
    }

    @Test fun usbSwitchFailureObscuresCompactVideoWaitIndicator() {
        val screen = File("src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt").readText()
        val visibilityCall = screen.substringAfter("if (CarPlayVideoWaitPolicy.shouldShow(").substringBefore("))")
        assertTrue("USB switch failure modal must join the indicator's obscured inputs",
            visibilityCall.contains("usbSwitchFailure != null"))
        val waiting = CarPlayUiState(selected = true, busy = true)
        val usbSwitchFailure: String? = "USB switch paused"
        assertFalse(CarPlayVideoWaitPolicy.shouldShow(waiting, enabled = true, obscured = usbSwitchFailure != null))
        assertTrue("dismissing recovery UI restores the active startup indicator",
            CarPlayVideoWaitPolicy.shouldShow(waiting, enabled = true, obscured = false))
    }

    @Test fun inactiveStoppedOrFailedAttemptNeverWaits() {
        assertFalse(CarPlayUiState(selected = true, busy = false).waitingForVideo)
        assertFalse(CarPlayUiState(selected = true, busy = true, videoWaitActive = false).waitingForVideo)
        assertFalse(CarPlayUiState(selected = true, busy = true, failure = CarPlayFailureUi(
            CarPlayFailureKind.USB_SETUP, false, "Setup failed",
        )).waitingForVideo)
    }
}
