package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class VehicleRequestDiagnosticsTest {
    @Test fun evReceiptCannotBorrowIntervalsFromAnUnrelatedLocationSubscription() {
        var now=0L
        val lines=mutableListOf<String>()
        val link=OalIap2CarDataReporter(OalCarDataCapabilities(location=true,currentEnergy=true),{now},{null},{null},{},{},{lines+=it})
        link.authenticate();link.handle(Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0,250)})
        now=1000;link.handle(Iap2Messages.buildRaw(0xA100) {void(21)})
        assertTrue(lines.any {it.startsWith("carplay-car-data-request id=41216 ") && it.endsWith("ggaMs=0 rmcMs=0")})
    }
    @Test fun admittedRequestWithFailedSourceActivationIsNotMisclassifiedAsParserRejection() {
        val lines=mutableListOf<String>()
        val link=OalIap2CarDataReporter(OalCarDataCapabilities(location=true),{0},{null},{null},
            {if(it) throw IllegalStateException("private-source-failure")},{},{lines+=it})
        link.authenticate();link.handle(Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0,250)})
        assertTrue(lines.any {it.startsWith("carplay-car-data-request ") && it.contains("result=accepted ")})
        assertTrue(lines.any {it.contains("reason=source_absent")})
        assertFalse(lines.any {it.contains("reason=unsupported_request") || it.contains("private")})
    }
    @Test fun actualParserDistinguishesRejectionBoundariesWithoutPayloadValuesAndBoundsFlood() {
        var now=0L
        val lines=mutableListOf<String>()
        val link=OalIap2CarDataReporter(OalCarDataCapabilities(location=true,range=true,currentEnergy=true),
            {now},{null},{null},{},{},{lines+=it})
        link.authenticate()
        val cases=listOf(
            "oversized" to Iap2Frame(0xFFFA,ByteArray(129)),
            "malformed" to Iap2Frame(0xFFFA,byteArrayOf(0,3,0)),
            "duplicate" to Iap2Messages.buildRaw(0xFFFA) {void(1);void(1)},
            "width" to Iap2Messages.buildRaw(0xFFFA) {u16(0,250);void(1)},
            "width" to Iap2Messages.buildRaw(0xFFFA) {u8(1,1)},
            "interval_bounds" to Iap2Messages.buildRaw(0xFFFA) {u32(0,0);void(1)},
            "orphan_interval" to Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0x8002,250)},
            "unsupported_id" to Iap2Messages.buildRaw(0xFFFA) {void(99)},
            "no_supported_groups" to Iap2Messages.buildRaw(0xFFFA) {void(4)},
            "duplicate" to Iap2Messages.buildRaw(0xA100) {void(21);void(21)},
            "width" to Iap2Messages.buildRaw(0xA100) {u32(21,12345678)},
            "unsupported_id" to Iap2Messages.buildRaw(0xA100) {void(22)},
            "oversized" to Iap2Frame(0xA100,ByteArray(129)),
        )
        cases.forEach { (reason,frame) ->
            now+=1000; lines.clear(); link.handle(frame)
            assertTrue("missing precise $reason diagnostic",lines.any {it.startsWith("carplay-car-data-request ") && it.contains("result=$reason ")})
            assertFalse(lines.any {it.contains("12345678")})
        }
        now+=1000; lines.clear()
        val start=Iap2Messages.buildRaw(0xFFFA) {u32(0,250);void(1);void(2);u32(0x8002,1000)}
        repeat(1000) {link.handle(start)}
        assertEquals(1,lines.count {it.startsWith("carplay-car-data-request ")})
        assertTrue(lines.any {it.contains("groups=3 ggaMs=250 rmcMs=1000")})
        assertTrue(lines.size<=4)
    }
}
