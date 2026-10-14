package com.openautolink.carplaylab.media

import org.junit.Assert.*
import org.junit.Test

class ReceiverVideoReadinessTest {
    @Test fun onlyActualRenderMakesCurrentPresentationReady() {
        val change = ReceiverTelemetry::class.java.methods.find { it.name == "presentationChanged" }
        assertNotNull("numeric telemetry must expose current presentation readiness", change)
        val render = ReceiverTelemetry::class.java.methods.find { it.name == "frameRendered" && it.parameterCount == 2 }
        assertNotNull("render must be presentation-generation qualified", render)
        val ready = ReceiverTelemetrySnapshot::class.java.methods.find { it.name == "getPresentationRendered" }
        assertNotNull(ready)
        val telemetry = ReceiverTelemetry(1)
        change!!.invoke(telemetry, 1L)
        telemetry.decoderConfigured("decoder")
        telemetry.outputFormat(1280, 720, 1280, 720)
        assertEquals(false, ready!!.invoke(telemetry.snapshot(0)))
        render!!.invoke(telemetry, 1L, 1L)
        assertEquals(true, ready.invoke(telemetry.snapshot(1)))
    }

    @Test fun reattachKeepsLifetimeFramesButRequiresCurrentGenerationRender() {
        val telemetry = ReceiverTelemetry(4)
        telemetry.presentationChanged(0)
        telemetry.frameRendered(1, 0)
        telemetry.presentationChanged(1)
        assertEquals(1L, telemetry.snapshot(1).renderedFrames)
        assertFalse(telemetry.snapshot(1).presentationRendered)
        telemetry.frameRendered(2, 0)
        telemetry.presentationChanged(0)
        assertFalse(telemetry.snapshot(2).presentationRendered)
        telemetry.frameRendered(3, 1)
        assertTrue(telemetry.snapshot(3).presentationRendered)
        telemetry.presentationChanged(1) // Duplicate/stale Surface callbacks do not reset readiness.
        assertTrue(telemetry.snapshot(3).presentationRendered)
    }

    @Test fun retiredOrNewReceiverCannotInheritReadiness() {
        val old = ReceiverTelemetry(1)
        old.presentationChanged(0)
        old.frameRendered(1, 0)
        old.retire()
        old.presentationChanged(1)
        old.frameRendered(2, 1)
        assertFalse(old.snapshot(2).presentationRendered)
        val fresh = ReceiverTelemetry(2)
        fresh.presentationChanged(0)
        assertFalse(fresh.snapshot(2).presentationRendered)
        fresh.frameRendered(3, 0)
        assertTrue(fresh.snapshot(3).presentationRendered)
    }
}
