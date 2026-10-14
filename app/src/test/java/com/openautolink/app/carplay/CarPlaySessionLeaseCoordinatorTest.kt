package com.openautolink.app.carplay

import android.app.Application
import android.content.Intent
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CarPlaySessionLeaseCoordinatorTest {
    private fun coordinator(source: CarPlaySessionLeaseSource, onState: (CarPlayUiState) -> Unit = {}) =
        CarPlayCoordinator(CarPlayOwnership(), {}, source, onState)
    private fun CountDownLatch.waitFor() = assertTrue("Latch timed out", await(5, TimeUnit.SECONDS))
    private fun eventually(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(check())
    }
    private inner class Runtime : CarPlayRuntime {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        override fun run() { started.countDown(); finish.waitFor() }
        override fun cancel() { finish.countDown() }
        override fun close() { closing.countDown(); allowClose.waitFor() }
    }
    @Test fun canceledLateFactoryRetainsExactLeaseUntilItsRuntimeCloses() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = Runtime()
        val creating = CountDownLatch(1)
        val allowFactory = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val owner = coordinator(CarPlaySessionLeases.forContext(app)) { if (it.selected && !it.busy) retired.countDown() }
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        try {
            owner.connect(true) { creating.countDown(); allowFactory.waitFor(); runtime }
            service.onStartCommand(shadowOf(app).nextStartedService, 0, 1)
            creating.waitFor()
            owner.stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertFalse(owner.connect(true) { error("Successor before exact retirement") })
            allowFactory.countDown()
            runtime.closing.waitFor()
            assertEquals("Canceled late runtime must never run", 1L, runtime.started.count)
            assertFalse(shadowOf(service).isStoppedBySelf)
            runtime.allowClose.countDown()
            retired.waitFor()
            eventually { shadowOf(service).isStoppedBySelf }
        } finally { allowFactory.countDown(); runtime.allowClose.countDown(); owner.close(); controller.destroy() }
    }

    @Test fun failedCloseKeepsForegroundLeaseUntilSuccessfulStopRetry() {
        val app = RuntimeEnvironment.getApplication()
        val failed = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val allowClose = java.util.concurrent.atomic.AtomicBoolean()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val owner = coordinator(CarPlaySessionLeases.forContext(app)) {
            if (it.status.contains("retirement failed")) failed.countDown()
            if (it.selected && !it.busy) retired.countDown()
        }
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        try {
            owner.connect(true) { object : CarPlayRuntime {
                override fun run() { started.countDown(); finish.waitFor() }
                override fun cancel() { finish.countDown() }
                override fun close() { check(allowClose.get()) { "Close not acknowledged" } }
            } }
            service.onStartCommand(shadowOf(app).nextStartedService, 0, 1)
            started.waitFor()
            owner.stop(); failed.waitFor()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertFalse(owner.connect(true) { error("Unretired predecessor") })
            allowClose.set(true)
            owner.stop(); retired.waitFor()
            eventually { shadowOf(service).isStoppedBySelf }
        } finally { allowClose.set(true); owner.close(); controller.destroy() }
    }

    @org.robolectric.annotation.Implements(android.app.Service::class)
    class RejectForegroundShadow : org.robolectric.shadows.ShadowService() {
        @org.robolectric.annotation.Implementation(minSdk = 29)
        override fun startForeground(id: Int, notification: android.app.Notification, foregroundServiceType: Int) {
            throw SecurityException("Platform promotion rejected")
        }
    }

    @Test
    @Config(shadows = [RejectForegroundShadow::class])
    fun actualForegroundPromotionFailureCannotBootstrapTransport() {
        val app = RuntimeEnvironment.getApplication()
        val retired = CountDownLatch(1)
        val bootstraps = AtomicInteger()
        val owner = coordinator(CarPlaySessionLeases.forContext(app)) { if (it.selected && !it.busy) retired.countDown() }
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        try {
            owner.connect(true) { bootstraps.incrementAndGet(); error("Unprotected transport") }
            service.onStartCommand(shadowOf(app).nextStartedService, 0, 1)
            retired.waitFor()
            assertEquals("startForeground failure must revoke bootstrap", 0, bootstraps.get())
            assertNull(shadowOf(service).lastForegroundNotification)
        } finally { owner.close(); controller.destroy() }
    }

    @Test fun actualOsStartRejectionCannotBootstrapTransport() {
        val app = RuntimeEnvironment.getApplication()
        val deniedContext = object : android.content.ContextWrapper(app) {
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                throw android.app.ForegroundServiceStartNotAllowedException("Background start rejected")
            }
        }
        val retired = CountDownLatch(1)
        val bootstraps = AtomicInteger()
        val owner = coordinator(CarPlaySessionLeases(deniedContext)) { if (it.selected && !it.busy) retired.countDown() }
        try {
            owner.connect(true) { bootstraps.incrementAndGet(); error("Unprotected transport") }
            retired.waitFor()
            assertEquals("OS start failure must revoke bootstrap", 0, bootstraps.get())
            assertNull(shadowOf(app).nextStartedService)
        } finally { owner.close() }
    }

    @Test fun acquisitionExceptionFailsClosedEvenWhenUiCallbackThrows() {
        val bootstraps = AtomicInteger()
        val acquired = AtomicInteger()
        val owner = coordinator(CarPlaySessionLeaseSource {
            acquired.incrementAndGet()
            throw SecurityException("Admission denied")
        }) { throw IllegalStateException("UI is not a protection boundary") }
        try {
            assertFalse(owner.connect(true) { bootstraps.incrementAndGet(); error("Unprotected bootstrap") })
            assertEquals(1, acquired.get())
            assertEquals(0, bootstraps.get())
        } finally { owner.close() }
    }

    @Test fun productionCoordinatorWaitsForForegroundAckAndKeepsLeaseUntilCloseAck() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = Runtime()
        val factory = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val owner = coordinator(CarPlaySessionLeases.forContext(app)) { if (it.selected && !it.busy) retired.countDown() }
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        try {
            assertTrue(owner.connect(true) { factory.countDown(); runtime })
            val start = shadowOf(app).nextStartedService
            assertNotNull("Exact attempt must acquire protection during admission", start)
            assertFalse("Bootstrap must await actual foreground promotion", factory.await(100, TimeUnit.MILLISECONDS))
            service.onStartCommand(start, 0, 1)
            runtime.started.waitFor()
            val surface = io.mockk.mockk<android.view.Surface>()
            owner.attachSurface(surface)
            owner.detachSurface(surface)
            assertEquals("Presentation detach cannot retire transport", 1L, runtime.finish.count)
            assertNotNull("Foreground priority survives Surface loss", shadowOf(service).lastForegroundNotification)
            owner.stop()
            runtime.closing.waitFor()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Close in progress must retain foreground priority", shadowOf(service).isStoppedBySelf)
            runtime.allowClose.countDown()
            retired.waitFor()
            eventually { shadowOf(service).isStoppedBySelf }
        } finally { runtime.allowClose.countDown(); owner.close(); controller.destroy() }
    }
}
