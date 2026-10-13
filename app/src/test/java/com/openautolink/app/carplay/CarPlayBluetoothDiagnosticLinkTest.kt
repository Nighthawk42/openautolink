package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.RuntimeMetadataLink
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayBluetoothDiagnosticLinkTest {
    @Test fun bothProductionRuntimeFactoriesAttachPassiveBluetoothAndOriginalCommandReceipts() {
        for (name in listOf("OalCarPlayRuntime.kt","OalUsbCarPlayRuntime.kt")) {
            val source = java.io.File("src/main/java/com/openautolink/app/carplay/$name").readText()
            assertTrue("$name must attach Bluetooth evidence to the exact runtime metadata link",source.contains("CarPlayBluetoothDiagnosticLink("))
            assertTrue("$name must attach the process-owned passive observer",source.contains("subscribeEvidence"))
            assertTrue("$name must export original media callback receipts",source.contains("receipt = receipt"))
        }
    }

    @Test fun unauthenticatedEvidenceIsDroppedAndAuthenticatedExportHasFiniteBudget() {
        val delegate = mockk<RuntimeMetadataLink>(relaxed=true)
        val receipts = mutableListOf<String>()
        var callback: ((String) -> Unit)? = null
        val link = CarPlayBluetoothDiagnosticLink(delegate,
            { listener -> callback=listener;AutoCloseable {} }, { receipts.add(it) })
        val event = "carplay-bt event=hfp-audio state=connected previous=connecting scope=device owner=unknown"
        repeat(10) { callback!!.invoke(event) }
        assertTrue(receipts.isEmpty())
        link.authenticate()
        repeat(520) { callback!!.invoke(event) }
        assertEquals(512,receipts.size)
        link.revoke();callback!!.invoke(event)
        assertEquals(512,receipts.size)
        link.close()
    }

    @Test fun subscriptionFailureDoesNotDiscardRetryableMetadataClose() {
        val delegate = mockk<RuntimeMetadataLink>(relaxed=true)
        val link = CarPlayBluetoothDiagnosticLink(delegate,{ null },{})
        every { delegate.close() } throws IllegalStateException("synthetic-close") andThen Unit
        assertThrows(IllegalStateException::class.java) { link.close() }
        link.close()
        verify(exactly=2) { delegate.close() }
    }

    @Test fun exactLinkSubscriptionEndsAtRevokeAndCannotExportLateBluetoothEvents() {
        val delegate = mockk<RuntimeMetadataLink>(relaxed=true)
        val receipts = mutableListOf<String>()
        var callback: ((String) -> Unit)? = null
        var detached = 0
        val clazz = runCatching { Class.forName("com.openautolink.app.carplay.CarPlayBluetoothDiagnosticLink") }.getOrNull()
        assertNotNull("Bluetooth diagnostics must be wired to the real metadata link retirement",clazz)
        val link = clazz!!.declaredConstructors.single().apply { isAccessible=true }.newInstance(
            delegate, { listener: (String) -> Unit -> callback=listener;AutoCloseable { detached++ } },
            { line: String -> receipts.add(line) },
        ) as RuntimeMetadataLink
        link.authenticate()
        callback!!.invoke("carplay-bt event=hfp-voice state=started previous=unknown scope=device owner=unknown")
        assertEquals(1,receipts.size)
        link.revoke()
        callback!!.invoke("carplay-bt event=hfp-voice state=stopped previous=unknown scope=device owner=unknown")
        assertEquals(1,receipts.size)
        assertEquals(1,detached)
        link.close();assertEquals(1,detached)
        verify { delegate.authenticate();delegate.revoke();delegate.close() }
    }
}
