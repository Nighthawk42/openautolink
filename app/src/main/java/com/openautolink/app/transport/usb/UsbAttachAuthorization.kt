package com.openautolink.app.transport.usb

import android.hardware.usb.UsbDevice

/** Mirrors usb_device_filter.xml, including Android's device-OR-interface matching.
 * An ordinary app cannot query whether the OS resolver is open. Automatic paths therefore
 * NEVER request permission for these devices: they observe actual platform authorization.
 */
internal object UsbAttachAuthorization {
    private data class Identity(val name: String, val id: Int, val vendor: Int, val product: Int)
    private fun identity(device: UsbDevice) = Identity(device.deviceName, device.deviceId,
        device.vendorId, device.productId)
    private val explicitRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<Identity>()

    /** Process-local, single-use request authority from the actual picker, never an Intent. */
    fun explicitRetry(device: UsbDevice) { explicitRequests.add(identity(device)) }
    fun canRequest(device: UsbDevice): Boolean = !matches(device) || identity(device) in explicitRequests
    fun takeRequest(device: UsbDevice): Boolean {
        val explicit = explicitRequests.remove(identity(device))
        return explicit || !matches(device)
    }
    fun forget(deviceName: String) { explicitRequests.removeIf { it.name == deviceName } }

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
