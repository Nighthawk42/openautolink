package com.openautolink.app.carplay

import android.app.Application
import android.content.Intent
import android.content.pm.ServiceInfo
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
class CarPlaySessionServiceTest {
    @Test fun delayedServiceCallbackCannotAcknowledgeAnAttemptBeforeItsOsAdmission() {
        val app = RuntimeEnvironment.getApplication()
        val first = CarPlaySessionLeases.forContext(app).acquire()
        val start = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(start, 0, 1)
        first.awaitReady()
        val deniedContext = object : android.content.ContextWrapper(app) {
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                throw SecurityException("OS admission denied")
            }
        }
        val acquired = java.util.concurrent.atomic.AtomicReference<CarPlaySessionLease>()
        val acquireThread = Thread { acquired.set(CarPlaySessionLeases(deniedContext).acquire()) }
        acquireThread.start(); acquireThread.join(2000)
        assertFalse(acquireThread.isAlive)
        val second = acquired.get()
        val success = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val waiter = Thread {
            try { second.awaitReady(); success.countDown() } catch (_: Exception) {} finally { completed.countDown() }
        }.apply { isDaemon = true }
        try {
            waiter.start()
            // Predecessor's delayed delivery must not admit a not-yet-dispatched successor.
            service.onStartCommand(start, 0, 2)
            assertFalse("Unadmitted successor cannot bootstrap", success.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L, success.count)
        } finally {
            second.release(); first.release()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test fun retiredQueuedAcquisitionCannotIssueALateOsStart() {
        val app = RuntimeEnvironment.getApplication()
        val acquired = java.util.concurrent.atomic.AtomicReference<CarPlaySessionLease>()
        val thread = Thread { acquired.set(CarPlaySessionLeases.forContext(app).acquire()) }
        thread.start(); thread.join(2000)
        assertFalse(thread.isAlive)
        acquired.get().release()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull("Retired token must fence queued start dispatch", shadowOf(app).nextStartedService)
    }

    @Test fun queuedRetirementAndDelayedPredecessorStartCannotStopSuccessorLease() {
        val app = RuntimeEnvironment.getApplication()
        val first = CarPlaySessionLeases.forContext(app).acquire()
        val oldStart = shadowOf(app).nextStartedService
        val releaseThread = Thread { first.release() }
        releaseThread.start(); releaseThread.join(2000)
        assertFalse(releaseThread.isAlive)
        val second = CarPlaySessionLeases.forContext(app).acquire()
        val newStart = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(oldStart, 0, 1)
            second.awaitReady()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            first.release()
            assertFalse("Stale exact release must not stop successor", shadowOf(service).isStoppedBySelf)
            service.onStartCommand(newStart, 0, 2)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            second.release()
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(2, shadowOf(service).stopSelfResultId)
            assertTrue(shadowOf(service).isForegroundStopped)
        } finally { second.release(); controller.destroy() }
    }

    @Test fun staleServiceInstanceCallbacksCannotRetireSuccessorService() {
        val app = RuntimeEnvironment.getApplication()
        val first = CarPlaySessionLeases.forContext(app).acquire()
        val oldStart = shadowOf(app).nextStartedService
        val old = Robolectric.buildService(CarPlaySessionService::class.java).create()
        old.get().onStartCommand(oldStart, 0, 1)
        first.awaitReady(); first.release()
        val successor = CarPlaySessionLeases.forContext(app).acquire()
        val nextStart = shadowOf(app).nextStartedService
        val next = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = next.get()
        try {
            service.onStartCommand(nextStart, 0, 1)
            successor.awaitReady()
            old.get().onStartCommand(oldStart, 0, 2)
            old.destroy()
            first.release()
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            successor.release()
            assertTrue("Exact new service must still be registered", shadowOf(service).isStoppedBySelf)
        } finally { successor.release(); next.destroy() }
    }

    @Test fun exactLeaseStartsConnectedDeviceForegroundServiceBeforeReady() {
        val app = RuntimeEnvironment.getApplication()
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        val controller = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val service = controller.get()
        try {
            val intent = shadowOf(app).nextStartedService
            assertNotNull("Acquisition must request a foreground service", intent)
            assertEquals(CarPlaySessionService::class.java.name, intent.component!!.className)
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertNotNull("Service must promote before acknowledging lease", shadowOf(service).lastForegroundNotification)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
            lease.awaitReady()
        } finally { lease.release(); controller.destroy() }
    }
}
