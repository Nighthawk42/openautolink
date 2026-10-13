package com.openautolink.app.ui.projection

import com.openautolink.app.transport.usb.UsbConnectionState
import com.openautolink.app.transport.usb.UsbDeviceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPickerVisibilityTest {
    @Test fun recoveryRequiresEnabledCarPlayPendingSelectionAndAnAttachedAppleCandidate() {
        val apple = UsbDeviceCandidate("/dev/bus/usb/001/002", 0x05ac, 0x12a8, "Phone", false)
        val android = apple.copy(vendorId = 0x18d1)
        for (transport in listOf("wpp", "tcp")) {
            for (state in UsbConnectionState.entries) {
                assertEquals(state == UsbConnectionState.AWAITING_USER_SELECTION,
                    UsbPickerVisibility.shouldShow(transport, false, false, true, state, listOf(apple)))
            }
            assertFalse(UsbPickerVisibility.shouldShow(transport, false, false, false,
                UsbConnectionState.AWAITING_USER_SELECTION, listOf(apple)))
            assertFalse(UsbPickerVisibility.shouldShow(transport, false, false, true,
                UsbConnectionState.AWAITING_USER_SELECTION, emptyList()))
            assertFalse(UsbPickerVisibility.shouldShow(transport, false, false, true,
                UsbConnectionState.AWAITING_USER_SELECTION, listOf(android)))
        }
    }

    @Test fun usbPickerIsVisibleOnlyOnIdleProjectionSurface() {
        assertTrue(UsbPickerVisibility.shouldShow("usb", settingsOpen = false, diagnosticsOpen = false))
        assertFalse(UsbPickerVisibility.shouldShow("usb", settingsOpen = true, diagnosticsOpen = false))
        assertFalse(UsbPickerVisibility.shouldShow("usb", settingsOpen = false, diagnosticsOpen = true))
        assertFalse(UsbPickerVisibility.shouldShow("usb", settingsOpen = true, diagnosticsOpen = true))
        assertFalse(UsbPickerVisibility.shouldShow("wpp", settingsOpen = false, diagnosticsOpen = false))
    }
}
