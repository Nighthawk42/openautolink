package com.openautolink.app.transport.usb

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsbOsAuthorizationTest {
    private val usb = mockk<UsbManager>(relaxed = true)
    private val context = spyk(RuntimeEnvironment.getApplication()) {
        every { getSystemService(Context.USB_SERVICE) } returns usb
    }
    private val scope = TestScope()
    private fun device(name: String = "/dev/bus/usb/001/002", id: Int = 2) = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns name
        every { deviceId } returns id
        every { vendorId } returns 0x18d1
        every { productId } returns 0x2d00
    }
    private val accessory = device()
    private val transportReady = java.util.concurrent.CountDownLatch(1)
    private val manager = UsbConnectionManager(context, scope, { transportReady.countDown() })
    private fun call(method: String, device: UsbDevice = accessory) {
        manager.javaClass.getDeclaredMethod(method, UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(manager, device)
    }
    private fun start() {
        every { usb.deviceList } returns hashMapOf(accessory.deviceName to accessory)
        manager.start()
    }
    @After fun cleanup() { manager.stop(); unmockkAll() }
    private val capability = slot<PendingIntent>()
    private fun grant(target: UsbDevice = accessory) {
        every { usb.hasPermission(target) } returns true
        capability.captured.send(context, 0, android.content.Intent()
            .putExtra(UsbManager.EXTRA_DEVICE, target)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    @Test fun matchedColdStartAndRawAttachRequestOnceWithoutWaitingForOsGrant() {
        every { usb.requestPermission(any<UsbDevice>(), capture(capability)) } just Runs
        start()
        call("handleDeviceAttached")
        scope.runCurrent()
        verify(exactly = 1) { usb.requestPermission(accessory, any<PendingIntent>()) }
        assertEquals(UsbConnectionState.PERMISSION_REQUESTED, UsbConnectionManager.connectionState.value)
        grant()
        verify(timeout = 1_000, exactly = 1) { usb.openDevice(accessory) }
    }
    @Test fun actualGrantBeforeStartupBypassesAppPermissionRequest() {
        every { usb.hasPermission(accessory) } returns true
        start(); scope.runCurrent()
        verify(timeout = 1_000, exactly = 1) { usb.openDevice(accessory) }
        call("handleDeviceAttached")
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
    }
    @Test fun managerRestartRequestsAgainUnlessPlatformPermissionActuallyExists() {
        every { usb.requestPermission(any<UsbDevice>(), capture(capability)) } just Runs
        start(); scope.runCurrent(); manager.stop()
        val restarted = UsbConnectionManager(context, scope, {})
        try {
            restarted.start(); scope.runCurrent()
            verify(exactly = 2) { usb.requestPermission(accessory, any<PendingIntent>()) }
            grant()
            verify(timeout = 1_000, exactly = 1) { usb.openDevice(accessory) }
        } finally { restarted.stop() }
    }
    @Test fun aoaReplacementWithoutOsGrantRequestsPermissionUnderSameOwner() {
        every { usb.requestPermission(any<UsbDevice>(), capture(capability)) } just Runs
        val input = mockk<android.hardware.usb.UsbEndpoint>(relaxed = true) {
            every { type } returns android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK
            every { direction } returns android.hardware.usb.UsbConstants.USB_DIR_IN
        }
        val output = mockk<android.hardware.usb.UsbEndpoint>(relaxed = true) {
            every { type } returns android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK
            every { direction } returns android.hardware.usb.UsbConstants.USB_DIR_OUT
        }
        val iface = mockk<android.hardware.usb.UsbInterface>(relaxed = true) {
            every { endpointCount } returns 2
            every { getEndpoint(0) } returns input
            every { getEndpoint(1) } returns output
        }
        every { accessory.interfaceCount } returns 1
        every { accessory.getInterface(0) } returns iface
        val connection = mockk<android.hardware.usb.UsbDeviceConnection>(relaxed = true) {
            every { claimInterface(iface, true) } returns true
        }
        every { usb.openDevice(accessory) } returns connection
        every { usb.deviceList } returns hashMapOf()
        manager.start()
        val ownership = manager.javaClass.getDeclaredField("dispatchOwnership")
            .apply { isAccessible = true }.get(manager) as UsbDispatchOwnership
        val original = ownership.select("/dev/bus/usb/001/001", 0x22d9)!!
        assertTrue(ownership.beginReenumeration(original))
        every { usb.deviceList } returns hashMapOf(accessory.deviceName to accessory)
        call("handleDeviceAttached")
        scope.runCurrent()
        assertSame(original, ownership.current())
        assertEquals(accessory.deviceName, original.deviceName)
        verify(exactly = 1) { usb.requestPermission(accessory, any<PendingIntent>()) }
        assertEquals(UsbConnectionState.PERMISSION_REQUESTED, UsbConnectionManager.connectionState.value)
        call("handleDeviceAttached")
        verify(exactly = 1) { usb.requestPermission(accessory, any<PendingIntent>()) }
        assertSame("Permission suppression must not send the selected replacement back to the picker", original, ownership.current())
        grant()
        assertTrue(transportReady.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertSame("Grant must continue the existing startup, not acquire another picker owner", original, ownership.current())
        assertEquals(UsbConnectionState.CONNECTED, UsbConnectionManager.connectionState.value)
        call("handleDeviceAttached")
        assertEquals("Duplicate attach must not replace the connected state", UsbConnectionState.CONNECTED, UsbConnectionManager.connectionState.value)
        verify(exactly = 1) { usb.openDevice(accessory) }
        verify(exactly = 1) { usb.requestPermission(accessory, any<PendingIntent>()) }
    }
    @Test fun unownedCandidateCannotOverrideAnotherPhonesConnectingState() {
        assertUnownedWaitCannotOverrideSelectedPhone(whileConnecting = true)
    }
    @Test fun unownedCandidateCannotOverrideAnotherPhonesConnectedState() {
        assertUnownedWaitCannotOverrideSelectedPhone(whileConnecting = false)
    }
    @Test fun postAdmissionRescanCannotOverrideAnotherPhonesConnectingState() {
        assertUnownedWaitCannotOverrideSelectedPhone(whileConnecting = true, pauseScan = true)
    }
    @Test fun postAdmissionRescanCannotOverrideAnotherPhonesConnectedState() {
        assertUnownedWaitCannotOverrideSelectedPhone(whileConnecting = false, pauseScan = true)
    }
    @Test fun rescanFromReleasedAdmissionCannotOfferInTheNewGeneration() {
        assertUnownedWaitCannotOverrideSelectedPhone(whileConnecting = false, pauseScan = true, releaseBeforeResume = true)
    }
    private fun assertUnownedWaitCannotOverrideSelectedPhone(
        whileConnecting: Boolean, pauseScan: Boolean = false, releaseBeforeResume: Boolean = false,
    ) {
        val selected = device("/dev/bus/usb/002/003", id = 3)
        val epIn = mockk<android.hardware.usb.UsbEndpoint>(relaxed = true) {
            every { type } returns android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK
            every { direction } returns android.hardware.usb.UsbConstants.USB_DIR_IN
        }
        val epOut = mockk<android.hardware.usb.UsbEndpoint>(relaxed = true) {
            every { type } returns android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK
            every { direction } returns android.hardware.usb.UsbConstants.USB_DIR_OUT
        }
        val iface = mockk<android.hardware.usb.UsbInterface>(relaxed = true) {
            every { endpointCount } returns 2
            every { getEndpoint(0) } returns epIn
            every { getEndpoint(1) } returns epOut
        }
        every { selected.interfaceCount } returns 1
        every { selected.getInterface(0) } returns iface
        val connection = mockk<android.hardware.usb.UsbDeviceConnection>(relaxed = true) {
            every { claimInterface(iface, true) } returns true
        }
        val opening = java.util.concurrent.CountDownLatch(1)
        val allowOpen = java.util.concurrent.CountDownLatch(if (whileConnecting) 1 else 0)
        val ready = java.util.concurrent.CountDownLatch(1)
        every { usb.openDevice(selected) } answers {
            opening.countDown()
            check(allowOpen.await(10, java.util.concurrent.TimeUnit.SECONDS))
            connection
        }
        var offered: Long? = null
        val productionManager = UsbConnectionManager(context, scope, { ready.countDown() },
            onCandidate = { generation, _ -> offered = generation })
        val scanPaused = java.util.concurrent.CountDownLatch(1)
        val resumeScan = java.util.concurrent.CountDownLatch(1)
        val scanner = java.util.concurrent.Executors.newSingleThreadExecutor()
        var scan: java.util.concurrent.Future<*>? = null
        try {
            every { usb.deviceList } returns if (pauseScan) hashMapOf() else hashMapOf(accessory.deviceName to accessory)
            productionManager.start(); scope.runCurrent()
            if (!pauseScan) {
                assertNotNull(offered) // A offers consent without an OS wait or dispatch owner.
                productionManager.stay(offered!!) // Decline A before selecting B's independent consent.
            }
            val attached = hashMapOf(accessory.deviceName to accessory, selected.deviceName to selected)
            if (pauseScan) {
                // Pause the actual production rescan AFTER its initial unowned check,
                // at enumeration. Only the scanner thread blocks; B can be admitted.
                val scannerThread = java.util.concurrent.atomic.AtomicReference<Thread>()
                val firstEnumeration = java.util.concurrent.atomic.AtomicBoolean(true)
                every { usb.deviceList } answers {
                    if (Thread.currentThread() === scannerThread.get() && firstEnumeration.compareAndSet(true, false)) {
                        scanPaused.countDown()
                        check(resumeScan.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        hashMapOf(accessory.deviceName to accessory)
                    } else attached
                }
                scan = scanner.submit {
                    scannerThread.set(Thread.currentThread())
                    productionManager.javaClass.getDeclaredMethod("scanExistingDevices")
                        .apply { isAccessible = true }.invoke(productionManager)
                }
                assertTrue(scanPaused.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } else every { usb.deviceList } returns attached
            every { usb.hasPermission(selected) } returns true
            UsbConnectionManager.selectDevice(selected.deviceName)
            assertNotNull(offered)
            assertTrue(productionManager.accept(offered!!))
            assertTrue(opening.await(5, java.util.concurrent.TimeUnit.SECONDS))
            if (!whileConnecting) assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val admittedState = if (whileConnecting) UsbConnectionState.CONNECTING else UsbConnectionState.CONNECTED
            assertEquals(admittedState, UsbConnectionManager.connectionState.value)
            if (releaseBeforeResume) productionManager.failedAdmission(offered!!)
            val expectedState = if (releaseBeforeResume) UsbConnectionState.IDLE else admittedState
            assertEquals(expectedState, UsbConnectionManager.connectionState.value)
            val expectedStatus = UsbConnectionManager.status.value
            resumeScan.countDown()
            scan?.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("Rescan must not immediately publish over B", expectedState, UsbConnectionManager.connectionState.value)
            assertEquals(expectedStatus, UsbConnectionManager.status.value)
            scope.advanceTimeBy(120_001); scope.runCurrent()
            assertEquals(expectedState, UsbConnectionManager.connectionState.value)
            assertEquals(expectedStatus, UsbConnectionManager.status.value)
            verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
            verify(exactly = 0) { usb.openDevice(accessory) }
        } finally {
            resumeScan.countDown()
            scanner.shutdownNow()
            allowOpen.countDown()
            ready.await(5, java.util.concurrent.TimeUnit.SECONDS)
            productionManager.stop()
        }
    }
    @Test fun detachCancelsPendingRequestAndCannotAuthorizeReusedName() {
        start(); scope.runCurrent()
        call("handleDeviceDetached")
        val replacement = device(id = 3)
        every { usb.deviceList } returns hashMapOf(replacement.deviceName to replacement)
        every { usb.hasPermission(accessory) } returns true
        scope.advanceTimeBy(200); scope.runCurrent()
        verify(exactly = 0) { usb.openDevice(any()) }
        verify(exactly = 1) { usb.requestPermission(accessory, any<PendingIntent>()) }
    }
    @Test fun stopCancelsPendingRequestWithoutRepromptOrOpen() {
        start(); scope.runCurrent(); manager.stop()
        every { usb.hasPermission(accessory) } returns true
        scope.advanceTimeBy(120_001); scope.runCurrent()
        verify(exactly = 0) { usb.openDevice(any()) }
        verify(exactly = 1) { usb.requestPermission(accessory, any<PendingIntent>()) }
    }
}
