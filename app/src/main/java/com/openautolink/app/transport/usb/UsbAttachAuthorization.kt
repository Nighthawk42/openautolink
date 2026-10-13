package com.openautolink.app.transport.usb

import android.hardware.usb.UsbDevice

/** Mirrors usb_device_filter.xml, including Android's device-OR-interface matching.
 * Matching offers OS attach/default UI; it is NEVER evidence of permission, nor
 * an indication that Android's resolver is active. Always check hasPermission.
 */
internal object UsbAttachAuthorization {
    fun matches(device: UsbDevice): Boolean {
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) return true
        if (device.vendorId != 0x05ac) return false
        fun mux(cls: Int, sub: Int, proto: Int) = cls == 255 && sub == 254 && proto == 2
        if (mux(device.deviceClass, device.deviceSubclass, device.deviceProtocol)) return true
        return (0 until device.interfaceCount).any {
            val iface = device.getInterface(it)
            mux(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)
        }
    }

    fun exact(expected: UsbDevice, actual: UsbDevice?): Boolean = actual != null &&
        expected.deviceName == actual.deviceName && expected.deviceId == actual.deviceId &&
        expected.vendorId == actual.vendorId && expected.productId == actual.productId
}
