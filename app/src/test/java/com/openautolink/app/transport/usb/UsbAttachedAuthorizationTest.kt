package com.openautolink.app.transport.usb

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE, application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class UsbAttachedAuthorizationTest {
    private val usb = RuntimeEnvironment.getApplication().getSystemService(UsbManager::class.java)
    private fun device(pid: Int = 0x2d00) = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns "/dev/bus/usb/001/002"
        every { deviceId } returns 2
        every { vendorId } returns 0x18d1
        every { productId } returns pid
    }
    @After fun cleanup() = unmockkAll()
    private fun launch(device: UsbDevice): Intent? {
        val controller = Robolectric.buildActivity(UsbAttachedActivity::class.java,
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
        val activity = controller.create().get()
        return shadowOf(activity).nextStartedActivity.also { controller.destroy() }
    }
    @Test fun exportedAttachIntentIsNotPermissionProof() {
        val phone = device()
        shadowOf(usb).addOrUpdateUsbDevice(phone, false)
        assertNull("Untrusted exported intent must not launch projection without OS permission", launch(phone))
    }
    @Test fun platformGrantWithExactCurrentDeviceHandsOffToMainActivity() {
        val phone = device()
        shadowOf(usb).addOrUpdateUsbDevice(phone, true)
        assertNotNull(launch(phone))
    }
    @Test fun grantedDifferentEnumerationCannotAuthorizeStaleIntent() {
        shadowOf(usb).addOrUpdateUsbDevice(device(0x2d01), true)
        assertNull(launch(device()))
    }
}
