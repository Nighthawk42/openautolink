package com.openautolink.app.ui.projection

import com.openautolink.app.transport.usb.UsbConnectionState
import com.openautolink.app.transport.usb.UsbDeviceCandidate

/** Recovery is independent of saved AA transport; visibility never authorizes permission. */
internal object UsbPickerVisibility {
    fun shouldShow(
        transport: String, settingsOpen: Boolean, diagnosticsOpen: Boolean,
        carPlayEnabled: Boolean = false,
        usbState: UsbConnectionState = UsbConnectionState.IDLE,
        devices: List<UsbDeviceCandidate> = emptyList(),
    ): Boolean {
        val pendingCarPlayAuthorization = carPlayEnabled &&
            usbState == UsbConnectionState.AWAITING_USER_SELECTION && devices.any { it.vendorId == 0x05ac }
        return (transport == "usb" || pendingCarPlayAuthorization) && !settingsOpen && !diagnosticsOpen
    }
}
