package com.openautolink.app.transport.usb

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UsbManifestAuthorizationTest {
    @Test fun appleUsbMuxHasNarrowAlwaysUseFilter() {
        val file = listOf(File("src/main/res/xml/usb_device_filter.xml"), File("app/src/main/res/xml/usb_device_filter.xml")).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("usb-device")
        val filters = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        assertTrue("Apple USBMUX must be OS matched to offer Always use", filters.any {
            it.getAttribute("vendor-id") == "1452" && it.getAttribute("class") == "255" &&
                it.getAttribute("subclass") == "254" && it.getAttribute("protocol") == "2"
        })
        assertEquals("Only AOA and Apple USBMUX are allowed", 3, filters.size)
        assertTrue(filters.all { it.hasAttribute("vendor-id") })
    }
}
