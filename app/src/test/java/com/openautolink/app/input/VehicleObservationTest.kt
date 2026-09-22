package com.openautolink.app.input

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.app.diagnostics.EvFreshParkGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class VehicleObservationTest {
    interface Callback { fun onChangeEvent(value: Any) }
    @Test fun retiredProxyCannotOverwriteNewRegistration() {
        val f = forwarder()
        val proxy = f.javaClass.getDeclaredMethod("createCallbackProxy", Class::class.java)
            .apply { isAccessible = true }.invoke(f, Callback::class.java) as Callback
        f.javaClass.getDeclaredMethod("cleanup").apply { isAccessible = true }.invoke(f)
        @Suppress("UNCHECKED_CAST")
        val tracked = f.javaClass.getDeclaredField("trackedPropertyIds").apply { isAccessible = true }.get(f) as MutableSet<Int>
        tracked.add(batteryId)
        event(f, PropertyValue(batteryId, 49000f, 300L, 0))
        proxy.onChangeEvent(PropertyValue(batteryId, 50000f, 100L, 0))
        assertEquals(49000f, snapshot(f).evBatteryLevelWh)
        assertEquals(300L, snapshot(f).evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
    }
    @Test fun nullAvailableValueDoesNotRefreshNumericProvenance() {
        val f = forwarder()
        event(f, PropertyValue(batteryId, 50000f, 100L, 0))
        event(f, PropertyValue(batteryId, null, 200L, 0))
        val m = snapshot(f).evObservationMetadata.getValue("EV_BATTERY_LEVEL")
        assertEquals(50000f, snapshot(f).evBatteryLevelWh)
        org.junit.Assert.assertFalse(m.status == 0 && m.timestampElapsedNanos == 200L)
    }

    private val batteryId = 0x11600309

    class PropertyValue(private val id: Int, private val value: Any?, private val timestamp: Long, private val status: Int) {
        fun getPropertyId() = id
        fun getValue() = value
        fun getTimestamp() = timestamp
        fun getStatus() = status
    }

    private fun forwarder(): VehicleDataForwarderImpl {
        val result = VehicleDataForwarderImpl(io.mockk.mockk(relaxed = true), {})
        val tracked = result.javaClass.getDeclaredField("trackedPropertyIds").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (tracked.get(result) as MutableSet<Int>).addAll(listOf(batteryId, 0x11600106))
        return result
    }

    private fun event(forwarder: VehicleDataForwarderImpl, value: Any) {
        forwarder.javaClass.getDeclaredMethod("handleChangeEvent", Any::class.java)
            .apply { isAccessible = true }.invoke(forwarder, value)
    }

    private fun snapshot(forwarder: VehicleDataForwarderImpl): ControlMessage.VehicleData =
        forwarder.javaClass.getDeclaredMethod("buildVehicleData").apply { isAccessible = true }
            .invoke(forwarder) as ControlMessage.VehicleData

    @Test fun nonavailableStatusIsRecordedWithoutDiscardingExistingForwardedValue() {
        val forwarder = forwarder()
        event(forwarder, PropertyValue(batteryId, 12345f, 9876543210L, 2))
        val data = snapshot(forwarder)
        assertEquals(12345f, data.evBatteryLevelWh)
        val metadata = data.evObservationMetadata["EV_BATTERY_LEVEL"]
        assertNotNull("Observed VHAL values need metadata even when status is not available", metadata)
        assertEquals(9876543210L, metadata!!.timestampElapsedNanos)
        assertEquals(2, metadata.status)
        assertEquals("vhal", metadata.source)
        assertEquals(0L, metadata.receivedElapsedMs) // Android JVM stub elapsedRealtime
    }
    class MissingMetadataValue {
        fun getPropertyId() = 0x11600309
        fun getValue() = 42f
    }

    class BrokenMetadataValue {
        fun getPropertyId() = 0x11600309
        fun getValue() = 43f
        fun getTimestamp(): Long = error("unsupported")
        fun getStatus(): Int = error("unsupported")
    }

    @Test fun missingMetadataDoesNotBlockForwardingAndIsExplicitlyUnknown() {
        val forwarder = forwarder()
        event(forwarder, MissingMetadataValue())
        val data = snapshot(forwarder)
        assertEquals(42f, data.evBatteryLevelWh)
        assertEquals(null, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
        assertEquals(null, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").status)
    }

    @Test fun throwingMetadataDoesNotBlockForwarding() {
        val forwarder = forwarder()
        event(forwarder, BrokenMetadataValue())
        val data = snapshot(forwarder)
        assertEquals(43f, data.evBatteryLevelWh)
        assertEquals(null, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
        assertEquals(null, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").status)
    }

    @Test fun cleanupClearsMetadataIncludingPublishedSnapshotAndLateEvents() {
        val forwarder = forwarder()
        event(forwarder, PropertyValue(batteryId, 12f, 100L, 0))
        forwarder.javaClass.getDeclaredMethod("cleanup").apply { isAccessible = true }.invoke(forwarder)
        assertEquals(emptyMap<String, Any>(), snapshot(forwarder).evObservationMetadata)
        assertEquals(emptyMap<String, Any>(), forwarder.latestVehicleData.value.evObservationMetadata)
        event(forwarder, PropertyValue(batteryId, 13f, 200L, 0))
        assertEquals(emptyMap<String, Any>(), snapshot(forwarder).evObservationMetadata)
        val tracked = forwarder.javaClass.getDeclaredField("trackedPropertyIds").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (tracked.get(forwarder) as MutableSet<Int>).add(batteryId)
        event(forwarder, PropertyValue(batteryId, 14f, 300L, 0))
        assertEquals(300L, snapshot(forwarder).evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
    }

    @Test fun cachedSnapshotsRetainReceiptAndCannotBeMutated() {
        io.mockk.mockkStatic(android.os.SystemClock::class)
        try {
            io.mockk.every { android.os.SystemClock.elapsedRealtime() } returns 123L
            val forwarder = forwarder()
            event(forwarder, PropertyValue(batteryId, 12f, 100L, 0))
            event(forwarder, PropertyValue(0x11600106, 100f, 100L, 0))
            val first = snapshot(forwarder)
            io.mockk.every { android.os.SystemClock.elapsedRealtime() } returns 999L
            val cached = snapshot(forwarder)
            assertEquals(123L, cached.evObservationMetadata.getValue("EV_BATTERY_LEVEL").receivedElapsedMs)
            assertEquals(100L, cached.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
            event(forwarder, PropertyValue(batteryId, 13f, 200L, 0))
            assertEquals(100L, first.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
            assertEquals(999L, snapshot(forwarder).evObservationMetadata.getValue("EV_BATTERY_LEVEL").receivedElapsedMs)
            try {
                (cached.evObservationMetadata as MutableMap).clear()
                org.junit.Assert.fail("Observation snapshots must be immutable")
            } catch (_: UnsupportedOperationException) { }
        } finally { io.mockk.unmockkStatic(android.os.SystemClock::class) }
    }

    @Test fun concurrentSnapshotsKeepValueAndTimestampTogether() {
        val forwarder = forwarder()
        val writer = Thread {
            repeat(500) { event(forwarder, PropertyValue(batteryId, it.toFloat(), it.toLong(), 0)) }
        }
        writer.start()
        try {
            repeat(500) {
                val data = snapshot(forwarder)
                data.evBatteryLevelWh?.let {
                    assertEquals(it.toLong(), data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
                }
            }
        } finally { writer.join() }
    }

    @Test fun concurrentHistorySnapshotsKeepMotorPowerAndObservationTogether() {
        val forwarder = forwarder()
        val holderField = forwarder.javaClass.getDeclaredField("latestMotorPowerSnapshot")
            .apply { isAccessible = true }
        val holderClass = forwarder.javaClass.declaredClasses
            .single { it.simpleName == "MotorPowerSnapshot" }
        val constructor = holderClass.declaredConstructors.single().apply { isAccessible = true }
        val writer = Thread {
            repeat(2_000) { sequence ->
                holderField.set(
                    forwarder,
                    constructor.newInstance(
                        sequence.toFloat(),
                        VehiclePropertyObservation(sequence.toLong(), sequence.toLong(), 0, "test"),
                    ),
                )
            }
        }
        writer.start()
        try {
            repeat(2_000) {
                val data = snapshot(forwarder)
                data.evMotorPowerW?.let { power ->
                    val observation = data.evObservationMetadata.getValue("EV_MOTOR_POWER")
                    assertEquals(power.toLong(), observation.timestampElapsedNanos)
                }
            }
        } finally { writer.join() }

        val source = java.io.File("src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt").readText()
        assertTrue(source.contains("@Volatile private var latestMotorPowerSnapshot"))
        org.junit.Assert.assertFalse(source.contains("latestMotorPowerObservation"))
        org.junit.Assert.assertFalse(source.contains("latestMotorPowerW:"))
    }

    @Test fun nullValueRetainsPreviousNumericValueButRecordsUnavailableObservation() {
        val forwarder = forwarder()
        event(forwarder, PropertyValue(batteryId, 12f, 100L, 0))
        event(forwarder, PropertyValue(batteryId, null, 200L, 1))
        val data = snapshot(forwarder)
        assertEquals(12f, data.evBatteryLevelWh)
        assertEquals(1, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").status)
        assertEquals(null, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
    }

    @Test fun unknownPropertyDoesNotAddObservation() {
        val forwarder = forwarder()
        event(forwarder, PropertyValue(-1, 12f, 100L, 0))
        assertEquals(emptyMap<String, Any>(), snapshot(forwarder).evObservationMetadata)
    }

    @Test fun standardTelemetryKeysAreCapturedWithoutInventingAbsentFields() {
        val properties = mapOf(
            "PERF_VEHICLE_SPEED" to 0x11600207,
            "EV_BATTERY_LEVEL" to batteryId,
            "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
            "RANGE_REMAINING" to 0x11600308,
            "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 0x1160030C,
            "ENV_OUTSIDE_TEMPERATURE" to 0x11600703,
            "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030D,
            "EV_BATTERY_AVERAGE_TEMPERATURE" to 0x1160030E,
            "EV_CHARGE_PORT_CONNECTED" to 0x1120030B,
            "EV_CHARGE_STATE" to 0x11400F41,
            "IGNITION_STATE" to 0x11400409,
            "GEAR_SELECTION" to 0x11400400,
        )
        val forwarder = forwarder()
        assertEquals(emptyMap<String, Any>(), snapshot(forwarder).evObservationMetadata)
        val tracked = forwarder.javaClass.getDeclaredField("trackedPropertyIds").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (tracked.get(forwarder) as MutableSet<Int>).addAll(properties.values)
        properties.values.forEach { event(forwarder, PropertyValue(it, 0f, 100L, 0)) }
        assertEquals(properties.keys, snapshot(forwarder).evObservationMetadata.keys)
    }

    @Test fun vehicleDataDefaultsToEmptyDiagnosticMetadata() {
        val getter = ControlMessage.VehicleData::class.java.methods
            .firstOrNull { it.name == "getEvObservationMetadata" }
        assertNotNull("VehicleData must carry diagnostic-only observation metadata", getter)
        assertEquals(emptyMap<String, Any>(), getter!!.invoke(ControlMessage.VehicleData()))
    }

    @Test fun propertyStatusConcurrentWritesClearsAndReadsExposeImmutableSnapshots() {
        val forwarder = forwarder()
        val field = forwarder.javaClass.getDeclaredField("_propertyStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statuses = field.get(forwarder) as MutableMap<String, String>
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            runCatching {
                repeat(10_000) { i ->
                    statuses["p${i % 32}"] = i.toString()
                    if (i % 17 == 0) statuses.clear()
                }
            }.onFailure(failure::set)
        }
        writer.start()
        repeat(10_000) {
            val snapshot = forwarder.propertyStatus
            snapshot.entries.forEach { entry -> assertTrue(entry.key.startsWith("p")) }
            try {
                (snapshot as MutableMap)["bad"] = "bad"
                org.junit.Assert.fail("propertyStatus snapshots must be immutable")
            } catch (_: UnsupportedOperationException) { }
        }
        writer.join()
        failure.get()?.let { throw it }
    }

    @Test fun productionForwarderSafetyMetadataRevokesOnUnavailableAndReconnect() {
        io.mockk.mockkStatic(android.os.SystemClock::class)
        try {
            var now = 1_000L
            io.mockk.every { android.os.SystemClock.elapsedRealtime() } answers { now }
            val forwarder = forwarder()
            val tracked = forwarder.javaClass.getDeclaredField("trackedPropertyIds").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (tracked.get(forwarder) as MutableSet<Int>).addAll(listOf(0x11400400, 0x11400409))
            event(forwarder, PropertyValue(0x11400400, 4, 1_000_000_000L, 0))
            event(forwarder, PropertyValue(0x11400409, 2, 1_000_000_000L, 0))
            val gate = EvFreshParkGate()
            assertTrue(gate.observe(snapshot(forwarder), now))

            now = 1_100L
            event(forwarder, PropertyValue(0x11400400, null, 1_100_000_000L, 1))
            event(forwarder, PropertyValue(0x11400409, null, 1_100_000_000L, 1))
            event(forwarder, PropertyValue(batteryId, 42f, 1_100_000_000L, 0))
            assertEquals(4, snapshot(forwarder).gearRaw) // value cache remains, authority does not
            assertFalse(gate.observe(snapshot(forwarder), now))

            forwarder.javaClass.getDeclaredMethod("cleanup").apply { isAccessible = true }.invoke(forwarder)
            assertFalse(gate.observe(forwarder.latestVehicleData.value, now))
        } finally {
            io.mockk.unmockkStatic(android.os.SystemClock::class)
        }
    }
}
