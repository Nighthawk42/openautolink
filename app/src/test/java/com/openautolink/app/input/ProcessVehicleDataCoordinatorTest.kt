package com.openautolink.app.input

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.diagnostics.EvContributionQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessVehicleDataCoordinatorTest {
    @Test
    fun `no projection observations advance real learner and retain contribution bytes`() = runTest {
        val estimator = EvLearnedRateEstimator.createForTest(
            object : EvLearnedRateEstimator.Store {
                override suspend fun load() = "{}"
                override suspend fun save(value: String) = Unit
            },
            this,
            EvLearnedRateEstimator.Config(persistDebounceMs = 60_000L),
        )
        val root = java.nio.file.Files.createTempDirectory("process-vhal").toFile()
        val queue = EvContributionQueue(root, 100_000, 8)
        var now = 1_000L
        val coordinator = ProcessVehicleDataCoordinator(
            learn = estimator::onVehicleTick,
            contribute = { data ->
                queue.append(
                    "drive",
                    1,
                    "{\"schema\":2,\"type\":\"vehicle\",\"batteryWh\":${data.evBatteryLevelWh?.toInt()}}",
                    "owner",
                )
            },
            elapsedRealtime = { now },
        )
        fun data(battery: Float) = ControlMessage.VehicleData(
            evBatteryLevelWh = battery,
            evBatteryCapacityWh = 60_000f,
            speedKmh = 36f,
            carMake = "Test", carModel = "EV", carYear = "2024",
        )

        coordinator.onRawBatch(data(50_000f))
        now = 6_000L
        coordinator.onRawBatch(data(49_990f))
        estimator.awaitIdle()

        assertTrue(estimator.activeSnapshot.value.whPerKm > 0f)
        assertEquals(1, queue.storageUsage().units)
        assertNull(coordinator.sessionConsumer())
        estimator.stop()
    }

    @Test
    fun `raw observations reach learning and contribution without projection then reach projection when attached`() {
        val learned = mutableListOf<ControlMessage.VehicleData>()
        val contributed = mutableListOf<ControlMessage.VehicleData>()
        val projected = mutableListOf<ControlMessage.VehicleData>()
        val coordinator = ProcessVehicleDataCoordinator(
            learn = { data, _ -> learned += data },
            contribute = { contributed += it },
            elapsedRealtime = { 123L },
        )
        val offline = ControlMessage.VehicleData(speedKmh = 12f, evBatteryLevelWh = 50_000f)

        coordinator.onRawBatch(offline)

        assertEquals(listOf(offline), learned)
        assertEquals(listOf(offline), contributed)
        assertEquals(offline, coordinator.latestVehicleData.value)
        assertNull(coordinator.sessionConsumer())

        coordinator.attachSessionConsumer { projected += it }
        assertEquals(listOf(offline), projected)

        val online = offline.copy(speedKmh = 20f)
        coordinator.onRawBatch(online)

        assertEquals(listOf(offline, online), projected)
        assertEquals(2, learned.size)
        assertEquals(2, contributed.size)
    }

    @Test
    fun `stale attachment cannot detach replacement consumer`() {
        val first = mutableListOf<ControlMessage.VehicleData>()
        val second = mutableListOf<ControlMessage.VehicleData>()
        val coordinator = ProcessVehicleDataCoordinator({ _, _ -> }, {}, { 0L })
        val firstAttachment = coordinator.attachSessionConsumer { first += it }
        coordinator.attachSessionConsumer { second += it }

        coordinator.detachSessionConsumer(firstAttachment)
        val event = ControlMessage.VehicleData(speedKmh = 7f)
        coordinator.onRawBatch(event)

        assertTrue(first.isEmpty())
        assertEquals(listOf(event), second)
    }

    @Test
    fun `session detach never stops process source and later projection receives current stream`() {
        var starts = 0
        var stops = 0
        val owner = ProcessVehicleDataOwner(
            automotive = true,
            createForwarder = { sink ->
                FakeForwarder(onStart = { starts++ }, onStop = { stops++ }, sink = sink)
            },
            coordinator = ProcessVehicleDataCoordinator({ _, _ -> }, {}, { 0L }),
        )

        owner.startProcess()
        owner.attachSessionConsumer {}
        owner.detachSessionConsumer()
        owner.onSessionLifecycleBoundary()

        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    private class FakeForwarder(
        private val onStart: () -> Unit,
        private val onStop: () -> Unit,
        @Suppress("unused") private val sink: (ControlMessage.VehicleData) -> Unit,
    ) : VehicleDataForwarder {
        override var isActive: Boolean = false
        override val latestVehicleData = kotlinx.coroutines.flow.MutableStateFlow(ControlMessage.VehicleData())
        override val propertyStatus: Map<String, String> = emptyMap()
        override fun start() { isActive = true; onStart() }
        override fun stop() { isActive = false; onStop() }
    }
}
