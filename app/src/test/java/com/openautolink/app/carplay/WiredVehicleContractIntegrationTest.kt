package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.*
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class WiredVehicleContractIntegrationTest {
    @Test fun bootstrapOfferStaysCoherentAcrossLateRuntimeCreationAndActualReceiptsReachArchive() {
        var now=1000L
        val observation=VehiclePropertyObservation(1_000_000_000,1000,0,registrationGeneration=1,subscriptionActive=true)
        val source=MutableStateFlow(ControlMessage.VehicleData(fuelTypes=listOf(10),rangeKm=123f,
            evBatteryLevelWh=0f,evCurrentBatteryCapacityWh=60000f,evBatteryCapacityWh=90000f,
            vhalRegistrationGeneration=1,evObservationMetadata=listOf("RANGE_REMAINING","EV_BATTERY_LEVEL","EV_CURRENT_BATTERY_CAPACITY").associateWith {observation}))
        val clock: ()->Long={now}
        val vehicle=OalCarPlayVehicleSnapshotAdapter({source},clock)
        val initial=vehicle.snapshot()!!
        val caps=OalCarDataCapabilities(false,initial.rangeKm!=null,initial.currentChargeWh!=null,initial.maxChargeWh!=null)
        val receipts=mutableListOf<String>(); val sent=mutableListOf<Iap2Frame>()
        val logs: (String)->Unit={receipts+=it}; val send: (Iap2Frame)->Unit={sent+=it}; val night: (Boolean)->Boolean={false}
        val bootstrap=OalCarDataBootstrap(caps,clock,logs)
        val base=Iap2Messages.buildRaw(0x1D01) {u16List(6,emptyList());u16List(7,emptyList())}
        val advertised=bootstrap.identification(base).encodedFrame()
        bootstrap.handle(Iap2Messages.buildRaw(0xA100) {void(3);void(11);void(21)})
        now=60000 // Runtime creation cannot independently re-sample and shrink an already sent offer.
        val gnss=OalCarPlayGnssSnapshotAdapter(clock) {object : GnssForwarder {
            override val isActive=false
            override fun start() {fail("unadvertised GPS cannot activate")}
            override fun stop() {}
        }}
        val appearance=CarPlayAppearanceBridge(source,Dispatchers.IO,clock,5000)
        // Compile-safe RED uses the old real constructor until the frozen contract argument exists.
        val constructor=OalCarPlayIntakeLink::class.java.declaredConstructors.firstOrNull {
            it.parameterCount==9 && it.parameterTypes.last()==OalCarDataCapabilities::class.java
        }
        val target=if(constructor==null) OalCarPlayIntakeLink(false,vehicle,gnss,clock,appearance,night,send,logs)
            else constructor.newInstance(false,vehicle,gnss,clock,appearance,night,send,logs,caps) as OalCarPlayIntakeLink
        val exact=bootstrap.wrap(target)
        val folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"vehicle-chain").toFile()
        try {
            assertArrayEquals(advertised,exact.identification(base).encodedFrame())
            source.value=source.value.copy(evObservationMetadata=source.value.evObservationMetadata.mapValues {
                it.value.copy(timestampElapsedNanos=60_000_000_000,receivedElapsedMs=60000)
            })
            exact.authenticate()
            assertEquals("reporter must use the same contract as USB identification",1,sent.size)
            val update=Iap2BodyReader.of(sent.single())
            assertEquals(listOf(3,11,22,23),update.list().map {it.id})
            assertEquals(2,update.raw(3).size); assertEquals(4,update.raw(22).size)
            assertEquals(0L,update.u32(22)); assertEquals(60000L,update.u32(23))
            val exported=mutableListOf<String>()
            val reporter=CarPlayAudioDiagnosticReporter(1,{1_000_000_000L},exported::add)
            receipts.forEach {line ->
                assertNotNull(line,CarPlayAudioDiagnostics.sanitize(1,line)); reporter.record(line)
                for(suffix in listOf(" lat=123"," VIN=private"," key=private","\nprivate","\u0000"))
                    assertNull(CarPlayAudioDiagnostics.sanitize(1,line+suffix))
            }
            assertTrue(exported.any {it.contains("reason=transmitted count=1 fields=30")})
            assertTrue(exported.any {it.contains("result=accepted")})
            val contents=exported.joinToString("\n",postfix="\n")
            val file=java.io.File(folder,"oal_vehicle_chain.log").apply {writeText(contents)}
            val plan=com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(file),file.lastModified())
            com.openautolink.app.diagnostics.LogUploadArchive.create(plan,folder).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertEquals(contents,zip.getInputStream(zip.getEntry(file.name)).bufferedReader().readText())
                }
            }
        } finally {exact.close();bootstrap.close();folder.deleteRecursively()}
    }
}
