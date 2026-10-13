package com.openautolink.app.carplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.openautolink.carplaylab.media.ReceiverTelemetry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class CarPlayVideoReadinessCoordinatorTest {
    @Test fun realTelemetryReattachAndSuccessorAttemptNeedNewRender() {
        val state = AtomicReference(CarPlayUiState())
        val ended = CountDownLatch(1)
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            state.set(it)
            if (it.selected && !it.busy) ended.countDown()
        }
        val surface = Surface(SurfaceTexture(0))
        val nextSurface = Surface(SurfaceTexture(1))
        val first = Runtime(1)
        val second = Runtime(2)
        try {
            coordinator.attachSurface(surface)
            assertTrue(coordinator.connect(true) { first })
            assertTrue(state.get().waitingForVideo)
            assertTrue(first.running.await(3, TimeUnit.SECONDS))
            coordinator.refreshTelemetry()
            assertTrue(state.get().waitingForVideo)
            first.telemetry.frameRendered(1, first.presentationGeneration)
            coordinator.refreshTelemetry()
            assertFalse(state.get().waitingForVideo)
            coordinator.attachSurface(nextSurface)
            assertTrue("reattach clears sampled predecessor readiness immediately", state.get().waitingForVideo)
            coordinator.refreshTelemetry()
            assertTrue(state.get().waitingForVideo)
            first.telemetry.frameRendered(2, first.presentationGeneration - 1)
            coordinator.refreshTelemetry()
            assertTrue("old presentation render cannot hide wait", state.get().waitingForVideo)
            coordinator.detachSurface(surface)
            first.telemetry.frameRendered(3, first.presentationGeneration)
            coordinator.refreshTelemetry()
            assertFalse("fresh real render hides wait", state.get().waitingForVideo)
            coordinator.stop()
            assertFalse(state.get().waitingForVideo)
            first.release.countDown()
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            assertTrue(coordinator.connect(true) { second })
            assertTrue("new attempt clears previous rendered evidence", state.get().waitingForVideo)
            assertTrue(second.running.await(3, TimeUnit.SECONDS))
            first.telemetry.frameRendered(4, first.presentationGeneration)
            coordinator.refreshTelemetry()
            assertTrue("old attempt is not sampled", state.get().waitingForVideo)
            second.telemetry.frameRendered(5, second.presentationGeneration)
            coordinator.refreshTelemetry()
            assertFalse(state.get().waitingForVideo)
        } finally {
            first.release.countDown(); second.release.countDown(); coordinator.close()
            surface.release(); nextSurface.release()
        }
    }

    @Test fun stopHidesWaitBeforeBlockedRetirementCompletes() {
        val state = AtomicReference(CarPlayUiState())
        val runtime = Runtime(3)
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) { state.set(it) }
        try {
            assertTrue(coordinator.connect(true) { runtime })
            assertTrue(runtime.running.await(3, TimeUnit.SECONDS))
            assertTrue(state.get().waitingForVideo)
            coordinator.stop()
            assertTrue("worker has not retired", state.get().busy)
            assertFalse("revocation hides spinner immediately", state.get().waitingForVideo)
            coordinator.refreshTelemetry()
            assertFalse(state.get().waitingForVideo)
        } finally { runtime.release.countDown(); coordinator.close() }
    }

    @Test fun dismissedFailureCannotResumeWaitDuringBlockedRetirement() {
        val state = AtomicReference(CarPlayUiState())
        val failed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            state.set(it)
            if (it.failure != null) failed.countDown()
        }
        try {
            coordinator.connect(true) { object : CarPlayRuntime {
                override fun run() { throw CarPlayConnectionFailure(CarPlayFailureKind.USB_SETUP, false) }
                override fun cancel() {}
                override fun close() { check(release.await(5, TimeUnit.SECONDS)) }
            } }
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertFalse(state.get().waitingForVideo)
            coordinator.dismissFailure()
            assertTrue("failed worker still closing", state.get().busy)
            assertFalse("dismissal cannot restart a failed attempt's indicator", state.get().waitingForVideo)
        } finally { release.countDown(); coordinator.close() }
    }

    private class Runtime(generation: Long) : CarPlayRuntime {
        val telemetry = ReceiverTelemetry(generation)
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        var presentationGeneration = 0L
        private var surface: Surface? = null
        override fun attachSurface(surface: Surface) {
            if (this.surface === surface) return
            this.surface = surface
            telemetry.presentationChanged(++presentationGeneration)
        }
        override fun detachSurface(surface: Surface) {
            if (this.surface !== surface) return
            this.surface = null
            telemetry.presentationChanged(++presentationGeneration)
        }
        override fun telemetrySnapshot() = telemetry.snapshot(10)
        override fun run() { running.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        override fun cancel() {}
        override fun close() { check(release.await(5, TimeUnit.SECONDS)) }
    }
}
