package com.openautolink.app.carplay

import android.view.Surface
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Reflection keeps RED behavioral on the old contract, not an unresolved-method compiler error. */
class CarPlayPresentationLifecycleTest {
    private fun present(owner: Any, name: String, surface: Surface) {
        owner.javaClass.methods.singleOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(Surface::class.java)) }
            ?.apply { isAccessible = true }?.invoke(owner, surface)
    }
    private fun CountDownLatch.waitFor() = assertTrue("Latch timed out", await(5, TimeUnit.SECONDS))
    private class Runtime : CarPlayRuntime {
        val events = Collections.synchronizedList(mutableListOf<Pair<String, Surface>>())
        val started = CountDownLatch(1)
        val end = CountDownLatch(1)
        var cancellations = 0
        override fun attachSurface(surface: Surface) { events.add("attach" to surface) }
        override fun detachSurface(surface: Surface) { events.add("detach" to surface) }
        override fun run() { started.countDown(); assertTrue(end.await(5, TimeUnit.SECONDS)) }
        override fun cancel() { cancellations++; end.countDown() }
        override fun close() { end.countDown() }
    }
    @Test fun detachWhileFactoryIsBlockedInstallsWithoutRevivingOriginalSurface() {
        val surface = mockk<Surface>()
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = Runtime()
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {}
        try {
            present(coordinator, "attachSurface", surface)
            coordinator.connect(true) { creating.countDown(); release.waitFor(); runtime }
            creating.waitFor()
            present(coordinator, "detachSurface", surface)
            release.countDown()
            runtime.started.waitFor()
            assertEquals(listOf("detach" to surface), runtime.events.toList())
            assertEquals(0, runtime.cancellations)
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun replacementWhileFactoryIsBlockedAttachesOnlyLatestSurface() {
        val first = mockk<Surface>()
        val latest = mockk<Surface>()
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = Runtime()
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {}
        try {
            present(coordinator, "attachSurface", first)
            coordinator.connect(true) { creating.countDown(); release.waitFor(); runtime }
            creating.waitFor()
            present(coordinator, "attachSurface", latest)
            present(coordinator, "detachSurface", first)
            release.countDown()
            runtime.started.waitFor()
            assertEquals(listOf("detach" to first, "attach" to latest), runtime.events.toList())
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun canceledLateFactoryCannotReceiveSurfaceUpdatesForSuccessor() {
        val first = mockk<Surface>()
        val latest = mockk<Surface>()
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val runtime = Runtime()
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) { if (!it.busy && it.selected) retired.countDown() }
        try {
            present(coordinator, "attachSurface", first)
            coordinator.connect(true) { creating.countDown(); release.waitFor(); runtime }
            creating.waitFor()
            coordinator.stop()
            present(coordinator, "attachSurface", latest)
            release.countDown()
            retired.waitFor()
            assertTrue(runtime.events.isEmpty())
            assertEquals(1L, runtime.started.count)
            val successor = Runtime()
            coordinator.connect(true) { successor }
            successor.started.waitFor()
            present(coordinator, "detachSurface", first)
            assertEquals(listOf("attach" to latest), successor.events.toList())
            assertTrue(runtime.events.isEmpty())
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun replacementAndStaleDestroyKeepTheSameSessionRunning() {
        val first = mockk<Surface>()
        val second = mockk<Surface>()
        val runtime = Runtime()
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {}
        try {
            present(coordinator, "attachSurface", first)
            assertTrue(coordinator.connect(true) { runtime })
            runtime.started.waitFor()
            present(coordinator, "attachSurface", second)
            present(coordinator, "detachSurface", first)
            present(coordinator, "detachSurface", second)
            assertEquals(listOf("attach" to first, "attach" to second, "detach" to second), runtime.events.toList())
            assertEquals(0, runtime.cancellations)
            assertEquals(1L, runtime.end.count)
        } finally { coordinator.close() }
    }
}
