package com.openautolink.app.data

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [EvLearnedRateEstimator.applyTick] — the pure tick math.
 * Avoids DataStore / coroutines / context entirely.
 */
class EvLearnedRateEstimatorTest {

    private class FakeStore(
        private val loadGate: CompletableDeferred<Unit>? = null,
        var raw: String = "{}",
        var failuresRemaining: Int = 0,
    ) : EvLearnedRateEstimator.Store {
        val writes = mutableListOf<String>()
        var saveAttempts = 0
        override suspend fun load(): String {
            loadGate?.await()
            return raw
        }
        override suspend fun save(value: String) {
            saveAttempts++
            if (failuresRemaining-- > 0) error("injected save failure")
            raw = value
            writes += value
        }
    }

    private fun tick(
        prev: EvLearnedRateEstimator.VehicleState? = null,
        batteryWh: Int? = null,
        speedKmh: Float? = null,
        evChargeRateW: Float? = null,
        motorPowerW: Float? = null,
        nowMs: Long,
        motorObservation: VehiclePropertyObservation? = motorPowerW?.let {
            VehiclePropertyObservation(null, nowMs, 0, "test")
        },
        chargeState: Int? = null,
        chargePortConnected: Boolean? = null,
    ): Pair<EvLearnedRateEstimator.VehicleState, String> {
        val s = prev ?: EvLearnedRateEstimator.VehicleState()
        val vd = ControlMessage.VehicleData(
            evBatteryLevelWh = batteryWh?.toFloat(),
            speedKmh = speedKmh,
            evChargeRateW = evChargeRateW,
            evMotorPowerW = motorPowerW,
            evChargeState = chargeState,
            chargePortConnected = chargePortConnected,
            evObservationMetadata = motorObservation?.let {
                mapOf(EvLearnedRateEstimator.MOTOR_POWER_OBSERVATION to it)
            }.orEmpty(),
            carMake = "Test",
            carModel = "Model",
            carYear = "2024",
        )
        val status = EvLearnedRateEstimator.applyTick(s, vd, nowMs)
        return s to status
    }

    @Test
    fun subFiftyMetreTicksAccumulateIntoOneWindowWithoutDoubleCounting() {
        val state = EvLearnedRateEstimator.VehicleState()
        var batteryWh = 50_000
        var nowMs = 1_000L
        tick(state, batteryWh, 36f, nowMs = nowMs)

        // 20 half-second intervals at 36 km/h = exactly 100 m. The battery
        // changes by 2 Wh per interval, so the completed window is 400 Wh/km.
        repeat(20) {
            nowMs += 500L
            batteryWh -= 2
            tick(state, batteryWh, 36f, nowMs = nowMs)
        }

        assertEquals(0.1f, state.sampleKm, 0.0001f)
        assertEquals(400f, state.whPerKm, 0.1f)
    }

    @Test
    fun oneSecondVaryingSpeedTicksUseTrapezoidalDistance() {
        val state = EvLearnedRateEstimator.VehicleState()
        var battery = 50_000
        var now = 1_000L
        tick(state, battery, 0f, nowMs = now)
        val speeds = listOf(18f, 54f, 18f, 54f, 18f, 54f)
        var status = ""
        for (speed in speeds) {
            now += 1_000L
            battery -= 2
            status = tick(state, battery, speed, nowMs = now).second
        }
        assertTrue(status.startsWith("ok:bd"))
        assertEquals(0.0525f, state.sampleKm, 0.0001f)
        assertEquals(12f / 0.0525f, state.whPerKm, 0.1f)
    }

    @Test
    fun fullyCoveredMotorPowerIsIntegratedButPartialCoverageFallsBackToBatteryDelta() {
        fun runWindow(missingPowerAt: Int?): Pair<EvLearnedRateEstimator.VehicleState, String> {
            val state = EvLearnedRateEstimator.VehicleState()
            var battery = 50_000
            var now = 1_000L
            tick(state, battery, 36f, motorPowerW = 7_200f, nowMs = now)
            var status = ""
            repeat(5) { index ->
                now += 1_000L
                battery -= 2 // battery source = 200 Wh/km over the 50 m window
                status = tick(
                    state,
                    battery,
                    36f,
                    motorPowerW = if (index == missingPowerAt) null else 7_200f,
                    nowMs = now,
                ).second
            }
            return state to status
        }

        val (covered, coveredStatus) = runWindow(missingPowerAt = null)
        assertTrue(coveredStatus.startsWith("ok:gt"))
        assertEquals(200f, covered.whPerKm, 0.1f)

        val (partial, partialStatus) = runWindow(missingPowerAt = 3)
        assertTrue("partial motor coverage must not be spread over full distance", partialStatus.startsWith("ok:bd"))
        assertEquals(200f, partial.whPerKm, 0.1f)
    }

    @Test
    fun `motor power requires finite fresh available observation coverage`() {
        fun resultFor(power: Float, observation: VehiclePropertyObservation?): String {
            val state = EvLearnedRateEstimator.VehicleState()
            var now = 1_000L
            tick(state, 50_000, 36f, motorPowerW = 7_200f, nowMs = now)
            var status = ""
            repeat(5) { index ->
                now += 1_000L
                status = tick(
                    state,
                    50_000 - (index + 1) * 2,
                    36f,
                    motorPowerW = power,
                    nowMs = now,
                    motorObservation = observation,
                ).second
            }
            return status
        }

        assertTrue(resultFor(Float.NaN, VehiclePropertyObservation(null, 5_000L, 0)).startsWith("ok:bd"))
        assertTrue(resultFor(7_200f, VehiclePropertyObservation(null, 5_000L, 1)).startsWith("ok:bd"))
        assertTrue(resultFor(7_200f, VehiclePropertyObservation(null, -20_000L, 0)).startsWith("ok:bd"))
        assertTrue(resultFor(7_200f, null).startsWith("ok:bd"))
    }

    @Test
    fun `charging state and port transitions break the accumulation window`() {
        val state = EvLearnedRateEstimator.VehicleState()
        tick(state, 50_000, 36f, nowMs = 1_000L, chargeState = 1, chargePortConnected = false)
        tick(state, 49_996, 36f, nowMs = 3_000L, chargeState = 1, chargePortConnected = false)

        val boundary = tick(
            state,
            49_994,
            36f,
            nowMs = 4_000L,
            chargeState = 2,
            chargePortConnected = true,
        ).second
        assertEquals("skip:charging-boundary", boundary)

        tick(state, 49_990, 36f, nowMs = 6_000L, chargeState = 1, chargePortConnected = false)
        assertEquals(0f, state.sampleKm, 0.0001f)
    }

    @Test
    fun gapChargingAndRegenBreakWindowsInsteadOfBridgingThem() {
        val state = EvLearnedRateEstimator.VehicleState()
        tick(state, 50_000, 36f, nowMs = 1_000L)
        tick(state, 49_992, 36f, nowMs = 3_000L) // 20 m only
        tick(state, 49_990, 36f, evChargeRateW = 1_000f, nowMs = 4_000L)
        tick(state, 49_982, 36f, nowMs = 6_000L) // 20 m after charging
        assertEquals(0f, state.sampleKm, 0.0001f)

        val gapStatus = tick(state, 49_980, 36f, nowMs = 20 * 60 * 1000L).second
        assertTrue(gapStatus.startsWith("skip:gap"))
        val regenStatus = tick(state, 49_990, 36f, nowMs = 20 * 60 * 1000L + 5_000L).second
        assertTrue(regenStatus.startsWith("skip:regen"))
        assertEquals(0f, state.sampleKm, 0.0001f)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun initialLoadAndTicksAreSerializedAndIdentitySwitchBreaksTheWindow() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val estimator = EvLearnedRateEstimator.createForTest(FakeStore(loadGate), this)

        estimator.onVehicleTick(vehicle("A", 50_000, 36f), 1_000L)
        estimator.onVehicleTick(vehicle("A", 49_980, 36f), 6_000L)
        runCurrent()
        assertEquals(null, estimator.activeSnapshot.value.key)

        loadGate.complete(Unit)
        estimator.awaitIdle()
        assertEquals(0.05f, estimator.snapshotFor("Test|A|2024").sampleKm, 0.0001f)

        estimator.onVehicleTick(vehicle("B", 40_000, 36f), 7_000L)
        estimator.onVehicleTick(vehicle("A", 49_960, 36f), 12_000L)
        estimator.awaitIdle()
        assertEquals(
            "switching away and back must not bridge another vehicle",
            0.05f,
            estimator.snapshotFor("Test|A|2024").sampleKm,
            0.0001f,
        )
        estimator.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `bounded nonblocking admission reports dropped ticks`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val estimator = EvLearnedRateEstimator.createForTest(
            FakeStore(loadGate),
            this,
            EvLearnedRateEstimator.Config(commandCapacity = 2),
        )
        runCurrent()

        assertTrue(estimator.onVehicleTick(vehicle("A", 50_000, 36f), 1_000L))
        assertTrue(estimator.onVehicleTick(vehicle("B", 50_000, 36f), 1_000L))
        assertFalse(estimator.onVehicleTick(vehicle("C", 50_000, 36f), 1_000L))
        assertFalse(estimator.onVehicleTick(vehicle("D", 50_000, 36f), 1_000L))
        assertEquals(2L, estimator.runtimeState.value.droppedCommands)

        loadGate.complete(Unit)
        estimator.awaitIdle()
        estimator.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `incomplete identities obey deterministic entry bound`() = runTest {
        val estimator = EvLearnedRateEstimator.createForTest(
            FakeStore(),
            this,
            EvLearnedRateEstimator.Config(maxEntries = 2),
        )
        estimator.onVehicleTick(vehicle("A", 50_000, 36f), 1_000L)
        estimator.onVehicleTick(vehicle("B", 50_000, 36f), 2_000L)
        estimator.onVehicleTick(vehicle("C", 50_000, 36f), 3_000L)
        estimator.awaitIdle()

        assertEquals(2, estimator.runtimeState.value.stateEntryCount)
        assertEquals(null, estimator.snapshotFor("Test|A|2024").key)
        assertEquals("Test|B|2024", estimator.snapshotFor("Test|B|2024").key)
        assertEquals("Test|C|2024", estimator.snapshotFor("Test|C|2024").key)
        estimator.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stop closes admission drains accepted ticks then flushes`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val store = FakeStore(loadGate)
        val estimator = EvLearnedRateEstimator.createForTest(
            store,
            this,
            EvLearnedRateEstimator.Config(commandCapacity = 8),
        )
        runCurrent()
        assertTrue(estimator.onVehicleTick(vehicle("A", 50_000, 36f), 1_000L))
        assertTrue(estimator.onVehicleTick(vehicle("A", 49_980, 36f), 6_000L))

        val stopping = async { estimator.stop() }
        yield()
        assertFalse(estimator.runtimeState.value.accepting)
        assertFalse(estimator.onVehicleTick(vehicle("B", 40_000, 36f), 7_000L))
        loadGate.complete(Unit)

        val result = stopping.await()
        assertTrue(result.finalSaveSucceeded)
        assertTrue(Json.parseToJsonElement(store.raw).jsonObject.containsKey("Test|A|2024"))
        assertFalse(Json.parseToJsonElement(store.raw).jsonObject.containsKey("Test|B|2024"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `normal persistence retries are capped and dirty state survives for stop`() = runTest {
        val store = FakeStore(failuresRemaining = 100)
        val estimator = EvLearnedRateEstimator.createForTest(
            store,
            this,
            EvLearnedRateEstimator.Config(
                persistDebounceMs = 0L,
                persistMaxDelayMs = 60_000L,
                persistRetryMs = 1_000L,
                maxPersistAttempts = 2,
            ),
        )
        estimator.onVehicleTick(vehicle("A", 50_000, 36f), 1_000L)
        estimator.onVehicleTick(vehicle("A", 49_980, 36f), 6_000L)
        estimator.awaitIdle()
        runCurrent()
        estimator.awaitIdle()
        advanceTimeBy(1_000L)
        runCurrent()
        estimator.awaitIdle()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(2, store.saveAttempts)
        assertTrue(estimator.runtimeState.value.dirty)
        assertTrue(estimator.runtimeState.value.retryExhausted)
        assertEquals(2L, estimator.runtimeState.value.persistFailures)

        try {
            estimator.stop()
            fail("final save failure must be surfaced")
        } catch (expected: EvLearnedRateEstimator.FinalSaveException) {
            assertTrue(expected.message!!.contains("dirty learned state"))
        }
        assertEquals(3, store.saveAttempts)
    }

    private fun vehicle(model: String, batteryWh: Int, speedKmh: Float) =
        ControlMessage.VehicleData(
            evBatteryLevelWh = batteryWh.toFloat(),
            speedKmh = speedKmh,
            carMake = "Test",
            carModel = model,
            carYear = "2024",
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun continuousAcceptedSamplesPersistByMaximumDirtyDelay() = runTest {
        val store = FakeStore()
        val estimator = EvLearnedRateEstimator.createForTest(
            store,
            this,
            EvLearnedRateEstimator.Config(5_000L, 12_000L, 1_000L),
        )
        var battery = 50_000
        estimator.onVehicleTick(vehicle("A", battery, 36f), 1_000L)
        estimator.awaitIdle()

        repeat(4) { index ->
            advanceTimeBy(4_000L)
            battery -= 20
            estimator.onVehicleTick(vehicle("A", battery, 36f), 6_000L + index * 5_000L)
            estimator.awaitIdle()
        }

        assertTrue("max delay must force a checkpoint", store.writes.isNotEmpty())
        estimator.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resetAndStopFlushImmediatelyAndFailedWriteRetries() = runTest {
        val store = FakeStore(failuresRemaining = 1)
        val estimator = EvLearnedRateEstimator.createForTest(
            store,
            this,
            EvLearnedRateEstimator.Config(60_000L, 120_000L, 1_000L),
        )
        estimator.onVehicleTick(vehicle("A", 50_000, 36f), 1_000L)
        estimator.onVehicleTick(vehicle("A", 49_980, 36f), 6_000L)
        estimator.awaitIdle()
        estimator.reset("Test|A|2024")
        estimator.awaitIdle()
        assertTrue(store.writes.isEmpty())

        advanceTimeBy(1_000L)
        runCurrent()
        estimator.awaitIdle()
        assertEquals(1, store.writes.size)

        estimator.onVehicleTick(vehicle("B", 40_000, 36f), 7_000L)
        estimator.onVehicleTick(vehicle("B", 39_980, 36f), 12_000L)
        estimator.awaitIdle()
        estimator.stop()
        assertEquals(2, store.writes.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistedMapHasDeterministicEntryAndByteBounds() = runTest {
        val store = FakeStore(
            raw = """{
                "Test|A|2024":{"whPerKm":200.0,"sampleKm":2.0,"lastUpdateMs":100},
                "Test|B|2024":{"whPerKm":201.0,"sampleKm":2.0,"lastUpdateMs":101},
                "Test|C|2024":{"whPerKm":202.0,"sampleKm":2.0,"lastUpdateMs":102},
                "Test|D|2024":{"whPerKm":203.0,"sampleKm":2.0,"lastUpdateMs":103}
            }""".trimIndent(),
        )
        val estimator = EvLearnedRateEstimator.createForTest(
            store,
            this,
            EvLearnedRateEstimator.Config(maxEntries = 2, maxJsonBytes = 300),
        )
        estimator.awaitIdle()
        estimator.stop()

        val saved = Json.parseToJsonElement(store.raw).jsonObject
        assertEquals(2, saved.size)
        assertTrue(saved.containsKey("Test|C|2024"))
        assertTrue(saved.containsKey("Test|D|2024"))
        assertTrue(store.raw.toByteArray(Charsets.UTF_8).size <= 300)
    }

    @Test
    fun firstTickIsAlwaysInit() {
        val (s, status) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        assertEquals("init", status)
        assertEquals(0f, s.whPerKm, 0.001f)
        assertEquals(0f, s.sampleKm, 0.001f)
    }

    @Test
    fun consumptionTickAcceptedAndPopulatesEma() {
        // First tick seeds prev battery.
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        // Second tick: 10 minutes later → 10 km traveled at 60 km/h, 2000 Wh
        // consumed → 200 Wh/km. (Stays under the 15-min MAX_TICK_GAP_MS.)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 50_000 - 2_000,
            speedKmh = 60f,
            nowMs = 1_000 + 10 * 60 * 1000,
        )
        assertTrue("expected ok status, got '$status'", status.startsWith("ok:"))
        // EMA seeded by first sample → exactly the instantaneous value.
        assertEquals(200f, s2.whPerKm, 1f)
        assertEquals(10f, s2.sampleKm, 0.5f)
    }

    @Test
    fun chargingTickIsSkipped() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 50_500,
            speedKmh = 60f,
            evChargeRateW = 50_000f,  // charging
            nowMs = 1_000 + 60_000,
        )
        assertEquals("skip:charging", status)
        assertEquals(0f, s2.whPerKm, 0.001f)
        assertEquals(0f, s2.sampleKm, 0.001f)
    }

    @Test
    fun regenTickIsSkipped() {
        // First tick at low battery, second tick higher battery → net regen.
        val (s, _) = tick(batteryWh = 30_000, speedKmh = 50f, nowMs = 1_000)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 30_500,            // gained 500 Wh while moving
            speedKmh = 50f,
            nowMs = 1_000 + 60_000,
        )
        assertTrue("expected regen-skip, got '$status'", status.startsWith("skip:regen"))
        assertEquals(0f, s2.whPerKm, 0.001f)
        assertEquals(0f, s2.sampleKm, 0.001f)
    }

    @Test
    fun stationaryTickIsSkipped() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 0f, nowMs = 1_000)
        val (s2, status) = tick(
            prev = s,
            batteryWh = 49_950,
            speedKmh = 0f,
            nowMs = 1_000 + 60_000,
        )
        assertTrue("expected stationary accumulation, got '$status'", status.startsWith("accum:dKm"))
        assertEquals(0f, s2.whPerKm, 0.001f)
    }

    @Test
    fun longGapResetsTheTick() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        val gapMs = 30 * 60 * 1000L  // 30 min — exceeds MAX_TICK_GAP_MS (15 min)
        val (_, status) = tick(
            prev = s,
            batteryWh = 30_000,
            speedKmh = 60f,
            nowMs = 1_000 + gapMs,
        )
        assertTrue("expected gap skip, got '$status'", status.startsWith("skip:gap"))
    }

    @Test
    fun outliersAreRejected() {
        val (s, _) = tick(batteryWh = 50_000, speedKmh = 60f, nowMs = 1_000)
        // 1 minute, 1 km traveled, 1000 Wh consumed → 1000 Wh/km — implausible.
        val (s2, status) = tick(
            prev = s,
            batteryWh = 50_000 - 1_000,
            speedKmh = 60f,
            nowMs = 1_000 + 60_000,
        )
        assertTrue("expected outlier skip, got '$status'", status.startsWith("skip:outlier"))
        assertEquals(0f, s2.whPerKm, 0.001f)
    }

    @Test
    fun emaConvergesAcrossMultipleTicks() {
        // Seed.
        var s = EvLearnedRateEstimator.VehicleState()
        var battery = 80_000
        var nowMs = 1_000L
        s.lastBatteryWh = battery
        s.lastTickElapsedMs = nowMs
        s.lastSpeedKmh = 60f
        s.resetWindow(battery)

        // Repeatedly consume at 200 Wh/km for 5 km each tick (5 minutes at 60 km/h).
        repeat(5) {
            nowMs += 5 * 60 * 1000  // +5 min
            battery -= 5 * 200      // 5 km × 200 Wh/km
            val vd = ControlMessage.VehicleData(
                evBatteryLevelWh = battery.toFloat(),
                speedKmh = 60f,
                carMake = "T", carModel = "M", carYear = "2024",
            )
            val status = EvLearnedRateEstimator.applyTick(s, vd, nowMs)
            assertTrue(status.startsWith("ok:"))
        }
        assertEquals(200f, s.whPerKm, 5f)
        assertTrue("sample km should be > 1 km", s.sampleKm > 1f)

        // Snapshot built from this state should be considered usable.
        val snap = EvLearnedRateEstimator.Snapshot(
            key = "T|M|2024",
            whPerKm = s.whPerKm,
            sampleKm = s.sampleKm,
        )
        assertTrue("expected usable snapshot", snap.usable)
    }

    @Test
    fun missingBatteryIsSkipped() {
        val s = EvLearnedRateEstimator.VehicleState()
        val vd = ControlMessage.VehicleData(
            evBatteryLevelWh = null,
            speedKmh = 60f,
            carMake = "T", carModel = "M", carYear = "2024",
        )
        val status = EvLearnedRateEstimator.applyTick(s, vd, 1_000L)
        assertEquals("skip:noBattery", status)
    }
}
