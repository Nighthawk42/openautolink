package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Supplemental wiring guards; coordinator/runtime behavioral tests prove ownership races. */
class CarPlayPresentationWiringTest {
    @Test fun activityAndSurfaceLossArePresentationOnly() {
        val activity = File("src/main/java/com/openautolink/app/MainActivity.kt").readText()
        val stop = activity.substringAfter("override fun onStop()").substringBefore("override fun onTopResumedActivityChanged")
        assertFalse("Activity backgrounding must not stop CarPlay", stop.contains("stopCarPlay("))
        val vm = File("src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt").readText()
        val callbacks = vm.substringAfter("fun onCarPlaySurfaceAvailable(").substringBefore("fun onCarPlayTouch(")
        assertTrue(callbacks.contains("carPlaySurface.value = surface"))
        assertTrue(callbacks.contains("carPlayCoordinator.attachSurface(surface)"))
        assertTrue(callbacks.contains("carPlaySurface.value !== surface"))
        assertTrue(callbacks.contains("carPlayViewport.value = null"))
        assertTrue(callbacks.contains("carPlayCoordinator.detachSurface(surface)"))
        assertFalse(callbacks.contains("carPlayCoordinator.stop()"))
        val factory = vm.substringAfter("private fun createProjectionCarPlayRuntime(").substringBefore("fun reconnectCarPlay()")
        assertTrue("Factory retains initial valid geometry qualification", factory.contains("carPlaySurface.first { it?.isValid == true }"))
        assertTrue("Factory must not leak captured bootstrap presentation into coordinator install", factory.contains("runtime.detachSurface(surface)"))
    }
}
