package com.openautolink.app.ui.projection

/** The USB chooser must never cover the overlays used to leave USB mode. */
internal object UsbPickerVisibility {
    fun shouldShow(transport: String, settingsOpen: Boolean, diagnosticsOpen: Boolean): Boolean =
        transport == "usb" && !settingsOpen && !diagnosticsOpen
}
