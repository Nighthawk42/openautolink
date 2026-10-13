package com.openautolink.app.transport.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.mockk.*
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UsbAttachAuthorizationPolicyTest {
    private fun apple(name: String = "bus/2", id: Int = 2): UsbDevice = mockk(relaxed = true) {
        every { deviceName } returns name
        every { deviceId } returns id
        every { vendorId } returns 0x05ac
        every { productId } returns 0x12a8
        every { interfaceCount } returns 1
        every { getInterface(0) } returns mockk<UsbInterface> {
            every { interfaceClass } returns 255
            every { interfaceSubclass } returns 254
            every { interfaceProtocol } returns 2
        }
    }
    @After fun cleanup() { UsbAttachAuthorization.forget("bus/2"); UsbAttachAuthorization.forget("bus/3"); unmockkAll() }
    @Test fun narrowMatcherDoesNotClaimAllAppleOrAndroidOemDevices() {
        val mux = apple()
        assertTrue(UsbAttachAuthorization.matches(mux))
        every { mux.interfaceCount } returns 0
        assertFalse(UsbAttachAuthorization.matches(mux))
        every { mux.vendorId } returns 0x18d1
        every { mux.productId } returns 0x2d00
        assertTrue(UsbAttachAuthorization.matches(mux))
        every { mux.productId } returns 0x2d01
        assertTrue(UsbAttachAuthorization.matches(mux))
        every { mux.productId } returns 0x2d02
        assertFalse(UsbAttachAuthorization.matches(mux))
    }
    @Test fun requestAuthorityIsSingleUseAndDoesNotTransferToReplacementOrReusedName() {
        val selected = apple()
        UsbAttachAuthorization.explicitRetry(selected)
        assertFalse(UsbAttachAuthorization.canRequest(apple("bus/3", 3)))
        assertFalse(UsbAttachAuthorization.canRequest(apple(id = 3)))
        assertTrue(UsbAttachAuthorization.takeRequest(selected))
        assertFalse(UsbAttachAuthorization.takeRequest(selected))
    }
    @Test fun stopRevokesPickerAuthorityForAnUnadmittedCandidate() {
        val selected = apple()
        val usb = mockk<UsbManager>(relaxed = true) {
            every { deviceList } returns hashMapOf(selected.deviceName to selected)
        }
        val context = mockk<Context>(relaxed = true) {
            every { getSystemService(Context.USB_SERVICE) } returns usb
        }
        val manager = UsbConnectionManager(context, TestScope(), {}, onCandidate = { _, _ -> })
        manager.javaClass.getDeclaredField("isRunning").apply { isAccessible = true }.set(manager, true)
        manager.javaClass.getDeclaredMethod("onUserSelectedDevice", String::class.java)
            .apply { isAccessible = true }.invoke(manager, selected.deviceName)
        assertTrue(UsbAttachAuthorization.canRequest(selected))
        manager.stop()
        assertFalse("A restarted manager must not inherit a cancelled picker request", UsbAttachAuthorization.canRequest(selected))
    }
}
