package com.openautolink.app.ui.projection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPickerVisibilityTest {
    @Test fun usbPickerIsVisibleOnlyOnIdleProjectionSurface() {
        assertTrue(UsbPickerVisibility.shouldShow("usb", settingsOpen = false, diagnosticsOpen = false))
        assertFalse(UsbPickerVisibility.shouldShow("usb", settingsOpen = true, diagnosticsOpen = false))
        assertFalse(UsbPickerVisibility.shouldShow("usb", settingsOpen = false, diagnosticsOpen = true))
        assertFalse(UsbPickerVisibility.shouldShow("usb", settingsOpen = true, diagnosticsOpen = true))
        assertFalse(UsbPickerVisibility.shouldShow("wpp", settingsOpen = false, diagnosticsOpen = false))
    }
}
