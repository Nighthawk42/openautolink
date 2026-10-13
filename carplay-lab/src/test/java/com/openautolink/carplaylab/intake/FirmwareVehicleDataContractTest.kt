package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

/** Synthetic sender shapes recovered from representative 24A446; not owner phone captures. */
class FirmwareVehicleDataContractTest {
    @Test fun firmwareVoidEvSelectorsSubscribeToGroupsNotUpdateScalarIds() {
        var now=0L
        val sent=mutableListOf<Iap2Frame>()
        val link=OalIap2CarDataReporter(OalCarDataCapabilities(range=true,currentEnergy=true,maximumEnergy=true),
            {now},{null},{OalEvSnapshot(123,0,60000)}, {}, {sent+=it}, {})
        link.authenticate()
        link.handle(Iap2Messages.buildRaw(0xA100) {void(3);void(11);void(21)})
        assertEquals("selected firmware request must produce an update",1,sent.size)
        assertEquals(listOf(3,11,22,23),Iap2BodyReader.of(sent.single()).list().map {it.id})
        assertEquals(2,Iap2BodyReader.of(sent.single()).raw(3).size)
        assertEquals(4,Iap2BodyReader.of(sent.single()).raw(22).size)
        assertEquals(0L,Iap2BodyReader.of(sent.single()).u32(22))
        now=30000; link.handle(Iap2Messages.buildRaw(0xA100) {void(11)})
        assertEquals(listOf(11),Iap2BodyReader.of(sent.last()).list().map {it.id})
        now=60000; link.handle(Iap2Messages.buildRaw(0xA100) {void(21)})
        assertEquals(listOf(22,23),Iap2BodyReader.of(sent.last()).list().map {it.id})
        val count=sent.size
        link.handle(Iap2Frame(0xA102,byteArrayOf(1)))
        now=90000; link.tick(); assertEquals(count,sent.size)
        link.close(); link.authenticate(); link.handle(Iap2Messages.buildRaw(0xA100) {void(21)})
        assertEquals(count,sent.size)
    }
    private var now = 0L
    @Test fun sentenceOnlyIntervalDoesNotInventGlobalMinimumAndMaliciousRequestsRevoke() {
        val valid=Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0x8001,250)}
        val link=reporter(); link.authenticate(); link.handle(valid)
        assertEquals(250L,link.pollTimeout(60000))
        val invalid=listOf(
            Iap2Frame(0xFFFA,ByteArray(129)),
            Iap2Frame(0xFFFA,byteArrayOf(0,3,0)),
            Iap2Messages.buildRaw(0xFFFA) {void(1);void(1)},
            Iap2Messages.buildRaw(0xFFFA) {u16(0,250);void(1)},
            Iap2Messages.buildRaw(0xFFFA) {u8(1,1)},
            Iap2Messages.buildRaw(0xFFFA) {void(1);u16(0x8001,250)},
            Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0x8002,250)},
            Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0,0)},
            Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0,60001)},
            Iap2Messages.buildRaw(0xFFFA) {void(1);u32(0,0xffffffffL)},
            Iap2Messages.buildRaw(0xFFFA) {void(1);void(99)},
        )
        invalid.forEach { bad ->
            now+=1000; link.handle(valid); val count=sent.size
            link.handle(bad); now+=1000; link.tick()
            assertEquals(count,sent.size); assertEquals(60000L,link.pollTimeout(60000))
        }
    }
    private val sent = mutableListOf<Iap2Frame>()
    private val reads = mutableListOf<Set<Int>>()
    private fun reporter() = OalIap2CarDataReporter(OalCarDataCapabilities(location=true), {now}, {
        reads += it
        val cache = OalNmeaCache {now}
        cache.accept(OalNmeaCacheTest.gga); cache.accept(OalNmeaCacheTest.rmc)
        cache.latest(it)
    }, {null}, {}, {sent += it}, {})

    @Test fun firmwareGlobalU32AndIndependentSentenceIntervalsDriveActualReporter() {
        val link = reporter(); link.authenticate()
        link.handle(Iap2Messages.buildRaw(0xFFFA) {
            u32(0,250); void(1); void(2); u32(0x8001,250); u32(0x8002,1000)
        })
        assertEquals(listOf(setOf(1,2)),reads)
        assertEquals(250L,link.pollTimeout(60_000))
        now=249; link.tick(); assertEquals(1,sent.size)
        now=250; link.tick(); assertEquals(setOf(1),reads.last())
        assertEquals(OalNmeaCacheTest.gga+"\r\n",Iap2BodyReader.of(sent.last()).string(0))
        now=500; link.tick(); now=750; link.tick(); now=1000; link.tick()
        assertEquals(setOf(1,2),reads.last())
        link.handle(Iap2Frame(0xFFFC,byteArrayOf(1))); now=2000; link.tick()
        assertEquals(5,sent.size)
    }
}
