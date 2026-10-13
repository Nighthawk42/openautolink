package com.openautolink.app.carplay

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UsbCarPlayPermissionTest {
    private val usb = mockk<UsbManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val device = mockk<UsbDevice> {
        every { deviceName } returns "/dev/bus/usb/001/002"
        every { deviceId } returns 2
        every { vendorId } returns 0x05ac
        every { productId } returns 0x12a8
        every { deviceClass } returns 0
        every { deviceSubclass } returns 0
        every { deviceProtocol } returns 0
        every { interfaceCount } returns 0
        every { manufacturerName } returns "Apple"
        every { productName } returns "Phone"
    }
    private val capability = mockk<PendingIntent>(relaxed = true)
    private val receiver = slot<BroadcastReceiver>()
    private var generation = -1L
    private var granted = false
    @Before fun setup() {
        every { context.packageName } returns "com.openautolink.app"
        every { usb.deviceList } returns hashMapOf(device.deviceName to device)
        every { usb.hasPermission(device) } answers { granted }
        every { context.registerReceiver(capture(receiver), any<IntentFilter>(), any<Int>()) } returns null
        mockkStatic(PendingIntent::class)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra("generation", any<Long>()) } answers {
            generation = secondArg(); self as Intent
        }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns capability
    }
    @After fun cleanup() = unmockkAll()
    private fun reply(value: Boolean, token: Long = generation, target: UsbDevice = device, detach: Boolean = false) {
        val intent = mockk<Intent> {
            every { action } returns if (detach) UsbManager.ACTION_USB_DEVICE_DETACHED else "com.openautolink.app.CARPLAY_USB_PERMISSION.$generation"
            every { getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) } returns target
            every { getLongExtra("generation", -1L) } returns token
            every { getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) } returns value
        }
        receiver.captured.onReceive(context, intent)
    }
    private fun await(cancelled: () -> Boolean = { false }) = UsbCarPlayPermission.await(context, usb, device, 30, cancelled)
    private fun cleaned() {
        verify(exactly = 1) { context.unregisterReceiver(receiver.captured) }
        verify(exactly = 1) { capability.cancel() }
        verify(exactly = 1) { usb.requestPermission(device, capability) }
    }
    private fun osMatchedApple() {
        every { device.deviceClass } returns 0
        every { device.deviceSubclass } returns 0
        every { device.deviceProtocol } returns 0
        every { device.interfaceCount } returns 1
        val mux = mockk<UsbInterface> {
            every { interfaceClass } returns 255
            every { interfaceSubclass } returns 254
            every { interfaceProtocol } returns 2
        }
        every { device.getInterface(0) } returns mux
    }
    @Test fun osMatchedAppleRequestsOnceAndAcceptsConcurrentExternalGrant() {
        osMatchedApple()
        var checks = 0
        assertEquals(UsbCarPlayPermission.Result.GRANTED, await {
            if (++checks == 3) granted = true
            false
        })
        cleaned()
    }
    @Test fun matchedReplacementWithoutOsGrantRequestsFreshPermissionAndContinues() {
        osMatchedApple()
        // car0.1.521: initial explicit grant, then a new USBMUX enumeration with
        // no OS attach/default grant. The selected startup must not silently expire.
        granted = true
        assertEquals(UsbCarPlayPermission.Result.GRANTED, await())
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        granted = false
        every { device.deviceName } returns "/dev/bus/usb/001/003"
        every { device.deviceId } returns 3
        every { usb.deviceList } returns hashMapOf(device.deviceName to device)
        every { usb.requestPermission(device, capability) } answers { granted = true; reply(true) }
        assertEquals(UsbCarPlayPermission.Result.GRANTED, await())
        verify(exactly = 1) { usb.requestPermission(device, capability) }
        verify(exactly = 0) { usb.openDevice(any()) } // permission cannot transfer or open by itself
    }
    @Test fun matchedRequestStillCancelsAndRevokesCapability() {
        osMatchedApple()
        var checks = 0
        assertEquals(UsbCarPlayPermission.Result.CANCELLED, await { ++checks >= 3 })
        cleaned()
    }
    @Test fun pickerSelectionAuthorizesOneExactAppleAppRequest() {
        osMatchedApple()
        every { context.getSystemService(Context.USB_SERVICE) } returns usb
        var outcome: UsbCarPlayPermission.Result? = null
        val manager = com.openautolink.app.transport.usb.UsbConnectionManager(context,
            kotlinx.coroutines.test.TestScope(), {}, onIphoneSelected = { outcome = await(); true })
        manager.javaClass.getDeclaredField("isRunning").apply { isAccessible = true }.set(manager, true)
        every { usb.requestPermission(device, capability) } answers { granted = true; reply(true) }
        manager.javaClass.getDeclaredMethod("onUserSelectedDevice", String::class.java)
            .apply { isAccessible = true }.invoke(manager, device.deviceName)
        assertEquals(UsbCarPlayPermission.Result.GRANTED, outcome)
        verify(exactly = 1) { usb.requestPermission(device, capability) }
        manager.stop()
    }
    @Test fun grantRequiresExactDeviceGenerationAndActualPermission() {
        every { usb.requestPermission(device, capability) } answers {
            granted = true
            reply(true, generation - 1)
            reply(true, target = mockk<UsbDevice>(relaxed = true))
            reply(true)
        }
        assertEquals(UsbCarPlayPermission.Result.GRANTED, await())
        cleaned()
    }
    @Test fun denialIsTerminalEvenAfterLateGrant() {
        every { usb.requestPermission(device, capability) } answers { reply(false); granted = true; reply(true) }
        assertEquals(UsbCarPlayPermission.Result.DENIED, await())
        cleaned()
    }
    @Test fun broadcastGrantWithoutPlatformPermissionFailsClosed() {
        every { usb.requestPermission(device, capability) } answers { reply(true) }
        assertEquals(UsbCarPlayPermission.Result.DENIED, await())
        cleaned()
    }
    @Test fun missingResultExpiresWithoutReprompt() {
        assertEquals(UsbCarPlayPermission.Result.TIMED_OUT, await())
        cleaned()
    }
    @Test fun cancellationRevokesEvenAlreadyGrantedResult() {
        var cancelled = false
        every { usb.requestPermission(device, capability) } answers { granted = true; reply(true); cancelled = true }
        assertEquals(UsbCarPlayPermission.Result.CANCELLED, await { cancelled })
        cleaned()
    }
    @Test fun exactDetachWinsOverLaterGrant() {
        every { usb.requestPermission(device, capability) } answers { reply(false, detach = true); granted = true; reply(true) }
        assertEquals(UsbCarPlayPermission.Result.DETACHED, await())
        cleaned()
    }
    @Test fun alreadyGrantedDoesNotPromptButStillRequiresAttachment() {
        granted = true
        assertEquals(UsbCarPlayPermission.Result.GRANTED, await())
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        verify(exactly = 1) { context.unregisterReceiver(receiver.captured) }
    }
    @Test fun requestExceptionStillRevokesReceiverAndCapability() {
        every { usb.requestPermission(device, capability) } throws SecurityException("test")
        try { await(); fail("Expected platform rejection") } catch (_: SecurityException) { }
        cleaned()
    }
    @Test fun matchedCancellationBeforeRegistrationCannotRequest() {
        osMatchedApple()
        assertEquals(UsbCarPlayPermission.Result.CANCELLED, await { true })
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        verify(exactly = 0) { context.registerReceiver(any(), any<IntentFilter>(), any<Int>()) }
    }
    @Test fun cancellationBeforeStartDoesNotRegisterOrRequest() {
        assertEquals(UsbCarPlayPermission.Result.CANCELLED, await { true })
        verify(exactly = 0) { context.registerReceiver(any(), any<IntentFilter>(), any<Int>()) }
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
    }
}
