package com.openautolink.app.input

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VehicleObservationTest {
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

    @Test fun nullValueRetainsPreviousNumericValueButRecordsUnavailableObservation() {
        val forwarder = forwarder()
        event(forwarder, PropertyValue(batteryId, 12f, 100L, 0))
        event(forwarder, PropertyValue(batteryId, null, 200L, 1))
        val data = snapshot(forwarder)
        assertEquals(12f, data.evBatteryLevelWh)
        assertEquals(1, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").status)
        assertEquals(200L, data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
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
}
