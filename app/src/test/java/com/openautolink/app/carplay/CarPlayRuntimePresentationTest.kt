package com.openautolink.app.carplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.ProjectionReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class CarPlayRuntimePresentationTest(private val usb: Boolean, private val replaceBeforePublish: Boolean) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "usb={0},replacement={1}")
        fun transports() = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true))
    }
    private fun runtimes(surface: Surface): List<CarPlayRuntime> = listOf(if (usb)
        OalUsbCarPlayRuntime(mockk<Context>(), mockk<UsbDevice>(relaxed = true), CarPlayDisplayPreferences(), surface, {})
    else
        OalCarPlayRuntime(mockk<Context>(), CarPlayRuntimeConfig("", "", "", false, "", "", 0,
            CarPlayDisplayPreferences()), surface, {}),
    )
    private fun active(runtime: CarPlayRuntime) {
        val method = runtime.javaClass.declaredMethods.single { it.name == "active" }.apply { isAccessible = true }
        try { method.invoke(runtime) } catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun installReceiver(runtime: CarPlayRuntime, create: () -> ProjectionReceiver) {
        val method = runtime.javaClass.declaredMethods.singleOrNull { it.name == "ownReceiver" }
            ?: runtime.javaClass.declaredMethods.single { it.name == "own" }
        method.isAccessible = true
        // Fallback reproduces the old production own { ProjectionReceiver(...).also { receiver=it } }.
        val factory = if (method.name == "ownReceiver") create else {
            { create().also { receiver -> runtime.javaClass.getDeclaredField("receiver")
                .apply { isAccessible = true }.set(runtime, receiver) } }
        }
        try { method.invoke(runtime, factory) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun desired(runtime: CarPlayRuntime): Surface? {
        val field = runtime.javaClass.declaredFields.singleOrNull { it.name == "requestedPresentation" }
        if (field != null) {
            val receipt = (field.apply { isAccessible = true }.get(runtime) as java.util.concurrent.atomic.AtomicReference<*>).get()
            if (receipt == null || receipt is Surface) return receipt as Surface?
            return receipt.javaClass.getDeclaredField("surface").apply { isAccessible = true }.get(receipt) as Surface?
        }
        // Before presentation/session separation, the immutable bootstrap Surface is the target.
        return runtime.javaClass.getDeclaredField("surface").apply { isAccessible = true }.get(runtime) as Surface?
    }

    @Test fun queuedReplacementCannotRepublishPredecessorReadiness() {
        val first = mockk<Surface>()
        val replacement = if (replaceBeforePublish) first else mockk<Surface>()
        val runtime = runtimes(first).single()
        val receiver = mockk<ProjectionReceiver>(relaxed = true)
        val telemetry = com.openautolink.carplaylab.media.ReceiverTelemetry(91)
        val generation = java.util.concurrent.atomic.AtomicLong(1)
        telemetry.presentationChanged(generation.get())
        every { receiver.telemetrySnapshot() } answers { telemetry.snapshot(100) }
        every { receiver.attachSurface(any()) } answers { telemetry.presentationChanged(generation.incrementAndGet()) }
        every { receiver.detachSurface(any()) } answers { telemetry.presentationChanged(generation.incrementAndGet()) }
        every { receiver.awaitClosed(10_000) } returns true
        val presentationWorker = runtime.javaClass.getDeclaredField("presentationWorker")
            .apply { isAccessible = true }.get(runtime) as java.util.concurrent.ExecutorService
        val blocked = CountDownLatch(1)
        val reconcile = CountDownLatch(1)
        val running = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val state = java.util.concurrent.atomic.AtomicReference(CarPlayUiState())
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) { state.set(it) }
        try {
            installReceiver(runtime) { receiver }
            coordinator.attachSurface(first)
            assertTrue(coordinator.connect(true) { object : CarPlayRuntime by runtime {
                override fun run() { running.countDown(); check(finish.await(10, TimeUnit.SECONDS)) }
                override fun close() { runtime.close(); finish.countDown() }
            } })
            assertTrue(running.await(3, TimeUnit.SECONDS))
            telemetry.frameRendered(1, generation.get())
            coordinator.refreshTelemetry()
            assertFalse(state.get().waitingForVideo)
            val blocker = presentationWorker.submit {
                blocked.countDown(); check(reconcile.await(5, TimeUnit.SECONDS))
            }
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            val predecessor = generation.get()
            if (replaceBeforePublish) coordinator.detachSurface(first)
            coordinator.attachSurface(replacement)
            assertTrue("request immediately clears sampled readiness", state.get().waitingForVideo)
            coordinator.refreshTelemetry()
            assertTrue("queued production reconciliation must fence predecessor rendered proof", state.get().waitingForVideo)
            assertEquals("receiver has not applied the request", predecessor, generation.get())
            assertEquals("lifetime observations remain available", 1L, state.get().evidence.telemetry?.renderedFrames)
            reconcile.countDown()
            blocker.get(3, TimeUnit.SECONDS)
            presentationWorker.submit {}.get(3, TimeUnit.SECONDS)
            coordinator.refreshTelemetry()
            assertTrue("applied receipt alone is not a real render", state.get().waitingForVideo)
            telemetry.frameRendered(2, predecessor)
            coordinator.refreshTelemetry()
            assertTrue("late predecessor callback cannot certify replacement", state.get().waitingForVideo)
            telemetry.frameRendered(3, generation.get())
            coordinator.refreshTelemetry()
            assertFalse("replacement actual render hides indicator", state.get().waitingForVideo)
            coordinator.stop()
            assertFalse(state.get().waitingForVideo)
        } finally {
            reconcile.countDown(); finish.countDown(); coordinator.close(); runtime.close()
        }
    }

    @Test fun onlyExactCurrentPresentationCanBeDetachedWithoutRevokingTransport() {
        val first = mockk<Surface>()
        val replacement = mockk<Surface>()
        runtimes(first).forEach { runtime ->
            try {
                runtime.attachSurface(replacement)
                assertSame("Replacement must become the desired presentation", replacement, desired(runtime))
                runtime.detachSurface(first)
                assertSame("Stale destroy must not clear the replacement", replacement, desired(runtime))
                runtime.detachSurface(replacement)
                assertNull(desired(runtime))
                active(runtime)
            } finally { runtime.close() }
        }
    }

    @Test fun detachDuringReceiverConstructionReturnsImmediatelyAndPublicationReconcilesNull() {
        val surface = mockk<Surface>()
        val replacement = mockk<Surface>()
        runtimes(surface).forEach { runtime ->
            val receiver = mockk<ProjectionReceiver>(relaxed = true)
            every { receiver.awaitClosed(10_000) } returns true
            val creating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbacks = Executors.newFixedThreadPool(2)
            try {
                val install = callbacks.submit { installReceiver(runtime) {
                    creating.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); receiver
                } }
                assertTrue(creating.await(5, TimeUnit.SECONDS))
                callbacks.submit {
                    if (replaceBeforePublish) runtime.attachSurface(replacement)
                    runtime.detachSurface(surface)
                }.get(1, TimeUnit.SECONDS)
                release.countDown()
                install.get(5, TimeUnit.SECONDS)
                if (replaceBeforePublish) verify(timeout = 3000, exactly = 1) { receiver.attachSurface(replacement) }
                else verify(timeout = 3000, exactly = 1) { receiver.detachSurface(surface) }
                verify(exactly = 0) { receiver.close() }
                active(runtime)
            } finally {
                release.countDown(); callbacks.shutdown(); callbacks.awaitTermination(5, TimeUnit.SECONDS); runtime.close()
            }
        }
    }

    @Test fun surfaceChangesAfterPublicationDoNotWaitForResourceFactoriesOrRetireSession() {
        val first = mockk<Surface>()
        val replacement = mockk<Surface>()
        runtimes(first).forEach { runtime ->
            val receiver = mockk<ProjectionReceiver>(relaxed = true)
            every { receiver.awaitClosed(10_000) } returns true
            val creating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbacks = Executors.newFixedThreadPool(2)
            try {
                installReceiver(runtime) { receiver }
                val creatingResource = callbacks.submit {
                    val own = runtime.javaClass.declaredMethods.single { it.name == "own" }.apply { isAccessible = true }
                    own.invoke(runtime, { creating.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); AutoCloseable {} })
                }
                assertTrue(creating.await(5, TimeUnit.SECONDS))
                callbacks.submit { runtime.attachSurface(replacement); runtime.detachSurface(first) }.get(1, TimeUnit.SECONDS)
                release.countDown()
                creatingResource.get(5, TimeUnit.SECONDS)
                verify(timeout = 3000, exactly = 1) { receiver.attachSurface(replacement) }
                runtime.detachSurface(replacement)
                verify(timeout = 3000, exactly = 1) { receiver.detachSurface(replacement) }
                verify(exactly = 0) { receiver.detachSurface(first) }
                verify(exactly = 0) { receiver.close() }
                active(runtime)
                runtime.cancel()
                runtime.attachSurface(first)
                verify(exactly = 0) { receiver.attachSurface(first) }
            } finally {
                release.countDown(); callbacks.shutdown(); callbacks.awaitTermination(5, TimeUnit.SECONDS); runtime.close()
            }
        }
    }

    @Test fun coalescedDestroyAndRecreateOfSameSurfaceStillReattachesDecoderTarget() {
        val surface = mockk<Surface>()
        runtimes(surface).forEach { runtime ->
            val receiver = mockk<ProjectionReceiver>(relaxed = true)
            every { receiver.awaitClosed(10_000) } returns true
            val creating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbacks = Executors.newFixedThreadPool(2)
            try {
                installReceiver(runtime) { receiver }
                val creatingResource = callbacks.submit {
                    val own = runtime.javaClass.declaredMethods.single { it.name == "own" }.apply { isAccessible = true }
                    own.invoke(runtime, { creating.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); AutoCloseable {} })
                }
                assertTrue(creating.await(5, TimeUnit.SECONDS))
                callbacks.submit { runtime.detachSurface(surface); runtime.attachSurface(surface) }.get(1, TimeUnit.SECONDS)
                release.countDown()
                creatingResource.get(5, TimeUnit.SECONDS)
                verify(timeout = 3000, exactly = 1) { receiver.attachSurface(surface) }
                verify(exactly = 1) { receiver.detachSurface(surface) }
                active(runtime)
                verify(exactly = 0) { receiver.close() }
            } finally {
                release.countDown(); callbacks.shutdown(); callbacks.awaitTermination(5, TimeUnit.SECONDS); runtime.close()
            }
        }
    }

    @Test fun canceledReceiverConstructionNeverPublishesPresentationAndRetainsExactOwnerForClose() {
        val first = mockk<Surface>()
        val replacement = mockk<Surface>()
        runtimes(first).forEach { runtime ->
            val receiver = mockk<ProjectionReceiver>(relaxed = true)
            every { receiver.awaitClosed(10_000) } returns true
            val creating = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbacks = Executors.newSingleThreadExecutor()
            try {
                val install = callbacks.submit { installReceiver(runtime) {
                    creating.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); receiver
                } }
                assertTrue(creating.await(5, TimeUnit.SECONDS))
                runtime.attachSurface(replacement)
                runtime.cancel()
                release.countDown()
                try { install.get(5, TimeUnit.SECONDS); fail("Canceled receiver installation must be rejected") }
                catch (failure: java.util.concurrent.ExecutionException) { assertTrue(failure.cause is IllegalStateException) }
                verify(exactly = 0) { receiver.attachSurface(any()) }
                verify(exactly = 0) { receiver.detachSurface(any()) }
                runtime.close()
                runtime.close()
                verify(exactly = 1) { receiver.close() }
                verify(exactly = 1) { receiver.awaitClosed(10_000) }
            } finally {
                release.countDown(); callbacks.shutdown(); callbacks.awaitTermination(5, TimeUnit.SECONDS); runtime.close()
            }
        }
    }

    @Test fun losingInitialSurfaceDoesNotRevokeEitherTransport() {
        val surface = mockk<Surface>()
        every { surface.isValid } returns true
        val runtimes = runtimes(surface)
        try {
            runtimes.forEach(::active)
            every { surface.isValid } returns false
            runtimes.forEach(::active)
            runtimes.forEach { runtime ->
                runtime.cancel()
                try { active(runtime); fail("Explicit cancellation must still revoke the attempt") }
                catch (_: IllegalStateException) {}
            }
        } finally { runtimes.forEach { it.close() } }
    }
}
