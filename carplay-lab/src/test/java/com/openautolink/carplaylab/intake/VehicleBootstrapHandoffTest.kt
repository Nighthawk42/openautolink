package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class VehicleBootstrapHandoffTest {
    @Test fun bootstrapIdentificationExportsItsExactFrozenOfferBeforeRuntimeExistsOnlyOnce() {
        val lines=mutableListOf<String>()
        val caps=OalCarDataCapabilities(location=true,currentEnergy=true)
        val bootstrap=OalCarDataBootstrap(caps,{0},{lines+=it})
        val base=Iap2Messages.buildRaw(0x1D01) {u16List(6,emptyList());u16List(7,emptyList())}
        repeat(1000) {bootstrap.identification(base)}
        assertEquals(listOf("carplay-car-data-offer location=true range=false current=true maximum=false"),lines)
        bootstrap.close()
    }
    @Test fun lateFactoryOwnerAfterBootstrapCancellationIsFencedAndStillReturnedForRetirement() {
        val caps=OalCarDataCapabilities()
        val bootstrap=OalCarDataBootstrap(caps,{0},{})
        var revokes=0;var closes=0;var authenticates=0
        val target=object : RuntimeCarDataLink {
            override fun identification(base: Iap2Frame)=base
            override fun authenticate() {authenticates++}
            override fun handle(frame: Iap2Frame)=false
            override fun tick(encryptedEventReady: Boolean) {}
            override fun pollTimeout(maximumMs: Long)=maximumMs
            override fun revoke() {revokes++}
            override fun close() {closes++}
        }
        bootstrap.close()
        val wrapped=runCatching {bootstrap.wrap(target)}.getOrNull()
        assertNotNull("constructed late owner cannot be lost on a throwing wrap",wrapped)
        assertEquals(1,revokes)
        wrapped!!.authenticate();assertEquals(0,authenticates)
        wrapped.close();assertEquals(1,closes)
    }
    @Test fun cancellationFenceNeverWaitsForBootstrapReplayTransportWrite() {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val fenced=java.util.concurrent.CountDownLatch(1)
        val caps=OalCarDataCapabilities(currentEnergy=true)
        val bootstrap=OalCarDataBootstrap(caps,{0},{})
        val reporter=OalIap2CarDataReporter(caps,{0},{null},{OalEvSnapshot(currentChargeWh=0)}, {},
            {entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS))},{})
        val target=object : RuntimeCarDataLink {
            override fun identification(base: Iap2Frame)=bootstrap.identification(base)
            override fun authenticate()=reporter.authenticate()
            override fun handle(frame: Iap2Frame)=reporter.handle(frame)
            override fun tick(encryptedEventReady: Boolean)=reporter.tick()
            override fun pollTimeout(maximumMs: Long)=reporter.pollTimeout(maximumMs)
            override fun revoke() {fenced.countDown()} // OS-free nonblocking exact runtime fence.
            override fun close()=reporter.close()
        }
        bootstrap.handle(Iap2Messages.buildRaw(0xA100) {void(21)})
        val wrapped=bootstrap.wrap(target)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val replay=pool.submit {wrapped.authenticate()}
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            val revoke=pool.submit {wrapped.revoke()}
            assertTrue("revoke cannot wait on the replay/send monitor",fenced.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            revoke.get(1,java.util.concurrent.TimeUnit.SECONDS)
            release.countDown(); replay.get(1,java.util.concurrent.TimeUnit.SECONDS)
            wrapped.close()
        } finally {release.countDown();pool.shutdownNow();bootstrap.close();reporter.close()}
    }
    @Test fun boundedExactBootstrapPreservesLatestSubscriptionStopAndFrozenOfferUntilAuthenticatedReplay() {
        var now=0L
        val receipts=mutableListOf<String>()
        val caps=OalCarDataCapabilities(location=true,range=true,currentEnergy=true)
        val bootstrap=OalCarDataBootstrap(caps,{now},{receipts+=it})
        fun defer(frame: Iap2Frame) = bootstrap.handle(frame)
        val sent=mutableListOf<Iap2Frame>()
        val reporter=OalIap2CarDataReporter(caps,{now},{null},{OalEvSnapshot(100,0,60000)},{},{sent+=it},{receipts+=it})
        val target=object : RuntimeCarDataLink {
            override fun identification(base: Iap2Frame)=OalCarDataIdentification.apply(base,caps)
            override fun authenticate()=reporter.authenticate()
            override fun handle(frame: Iap2Frame)=reporter.handle(frame)
            override fun tick(encryptedEventReady: Boolean)=reporter.tick()
            override fun pollTimeout(maximumMs: Long)=reporter.pollTimeout(maximumMs)
            override fun revoke()=reporter.close()
            override fun close()=reporter.close()
        }
        assertFalse(defer(Iap2Frame(0x1234,byteArrayOf())))
        val request=Iap2Messages.buildRaw(0xA100) {void(21)}
        assertTrue(defer(request))
        request.payload.fill(99) // Deferred frames must not alias a caller's mutable bytes.
        repeat(1000) {defer(Iap2Messages.buildRaw(0xFFFA) {void(1)})}
        defer(Iap2Frame(0xFFFC,byteArrayOf(1)))
        assertTrue(sent.isEmpty())
        val wrapped=bootstrap.wrap(target)
        val base=Iap2Messages.buildRaw(0x1D01) {u16List(6,emptyList());u16List(7,emptyList())}
        assertArrayEquals(OalCarDataIdentification.apply(base,caps).encodedFrame(),wrapped.identification(base).encodedFrame())
        wrapped.authenticate()
        assertEquals(1,sent.size)
        assertEquals(listOf(22),Iap2BodyReader.of(sent.single()).list().map {it.id})
        assertEquals(0L,Iap2BodyReader.of(sent.single()).u32(22))
        assertEquals(30000L,wrapped.pollTimeout(60000)) // location Stop survived the bootstrap gap.
        assertTrue(receipts.count {it.startsWith("carplay-car-data-bootstrap ")}<=4)
        wrapped.revoke(); now=30000; wrapped.tick(false); assertEquals(1,sent.size)
        wrapped.close(); bootstrap.close()
        defer(Iap2Messages.buildRaw(0xA100) {void(3)}); assertEquals(1,sent.size)
    }
}
