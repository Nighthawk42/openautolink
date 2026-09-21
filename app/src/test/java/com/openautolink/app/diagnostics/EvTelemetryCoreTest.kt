package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class EvTelemetryCoreTest {
    @Test fun missingBatteryCannotQualifyAndBreaksBatteryCoverage() {
        val c = EvTelemetryCore("b", "p")
        c.startSession("s", 0, 0)
        c.navigation("stop", 10, 1, false, 1000, 1000)
        c.forecast(40000, 10, 1, 2, 1000, 1000)
        c.vehicle(EvTelemetrySample(1000, 1000, 0.0, 50000.0, 1000, 1000, true))
        val missing = c.vehicle(EvTelemetrySample(2000, 2000, 0.0, null, 2000, 2000, true))
        assertEquals(false, missing["arrivalCandidate"])
        val after = c.vehicle(EvTelemetrySample(3000, 3000, 0.0, 49000.0, 3000, 3000, true))
        assertEquals(false, after["energyWindowBatteryCovered"])
    }

    @Test fun delayedPreEpochForecastCannotSeedNewRouteInEitherOrder() {
        for (delayed in listOf(false, true)) {
            val c = EvTelemetryCore("b", "p")
            c.startSession("s", 0, 0)
            c.navigation("old", 10000, 100, false, 1000, 1000)
            if (!delayed) c.forecast(40000, 10000, 100, 2, 1000, 1000, 1000)
            c.navigation("new", 10, 1, false, 2000, 2000)
            if (delayed) c.forecast(40000, 10000, 100, 2, 2000, 2000, 1000)
            val v = c.vehicle(EvTelemetrySample(2000, 2000, 0.0, 39000.0, 2000, 2000, true))
            assertNull(v["initialArrivalWh"])
            assertEquals(false, v["arrivalCandidate"])
        }
    }

    @Test fun emptyForecastInvalidatesArrivalCandidateWithoutLosingInitialForecast() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.navigation("private stop", 10, 1, false, 1000, 1000)
        core.forecast(40000, 10, 1, 2, 1000, 1000)
        core.forecast(null, null, null, 0, 2000, 2000)
        val record = core.vehicle(EvTelemetrySample(2000, 2000, 0.0, 40000.0, 2000, 2000, true))
        assertEquals(40000, record["initialArrivalWh"])
        assertEquals(false, record["arrivalCandidate"])
    }

    @Test fun staleBatteryObservationCannotCreateAnEnergyDelta() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.vehicle(EvTelemetrySample(1000, 1000, 36.0, 50000.0, 1000, 1000))
        val stale = core.vehicle(EvTelemetrySample(7000, 7000, 36.0, 49000.0, 7000, 1500))
        val fresh = core.vehicle(EvTelemetrySample(8000, 8000, 36.0, 48000.0, 8000, 8000))
        assertEquals(0.0, stale["netPackUsedWh"] as Double, 0.0)
        assertEquals(0.0, fresh["netPackUsedWh"] as Double, 0.0)
    }

    @Test fun onChangeEnergyPreservesSignedDeltaAcrossContinuousSpeedCoverageForDifferentPacks() {
        for (pack in listOf(30000.0, 120000.0)) {
            val core = EvTelemetryCore("boot", "process")
            core.startSession("one", 0, 0)
            core.vehicle(EvTelemetrySample(1000, 1000, 36.0, pack, 1000, 1000))
            for (t in 2000L..10000L step 1000) core.vehicle(EvTelemetrySample(t, t, 36.0, pack, t, 1000))
            val changed = core.vehicle(EvTelemetrySample(11000, 11000, 36.0, pack + 20, 11000, 11000))
            assertEquals(-20.0, changed["netPackUsedWh"] as Double, 0.0)
            assertEquals(true, changed["energyWindowSpeedCovered"])
        }
    }

    @Test fun retainsInitialForecastAndRequiresFreshSameRouteParkEvidence() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.navigation("secret home address", 100, 30, false, 1000, 1000)
        core.forecast(40000, 100, 30, 1, 1000, 1000)
        val latest = core.forecast(39000, 50, 20, 2, 2000, 2000)
        assertEquals(40000, latest["initialArrivalWh"])
        assertEquals(39000, latest["latestArrivalWh"])
        assertFalse(latest.toString().contains("secret home address"))
        val sample = EvTelemetrySample(2000, 2000, 0.0, 39000.0, 2000, 2000, parked = true)
        assertEquals(true, core.vehicle(sample)["arrivalCandidate"])
        core.navigation("another private stop", 30, 10, false, 3000, 3000)
        assertEquals(false, core.vehicle(sample.copy(elapsedMs = 3000))["arrivalCandidate"])
        core.navigation(null, null, null, true, 4000, 4000)
        assertNull(core.forecast(null, null, null, 0, 5000, 5000)["initialArrivalWh"])
    }

    @Test fun rejectsStaleCachedSpeedAndBreaksExplicitSessionGaps() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.vehicle(EvTelemetrySample(1000, 1000, 36.0, 50000.0, 1000, 1000))
        val stale = core.vehicle(EvTelemetrySample(2000, 2000, 36.0, 49000.0, 1000, 1000))
        assertEquals(0.0, stale["integratedDistanceM"] as Double, 0.0)
        assertEquals(1L, stale["uncoveredSpeedIntervals"])
        core.gap("one", "sleep", 2100, 2100)
        val after = core.vehicle(EvTelemetrySample(3000, 3000, 36.0, 48000.0, 3000, 3000))
        assertEquals(0.0, after["netPackUsedWh"] as Double, 0.0)
        core.startSession("two", 3100, 3100)
        assertNull(core.gap("one", "stale_callback", 3200, 3200))
        core.vehicle(EvTelemetrySample(4000, 4000, 36.0, 48000.0, 4000, 4000))
        val next = core.vehicle(EvTelemetrySample(5000, 5000, 36.0, 47990.0, 5000, 5000))
        assertEquals(10.0, next["integratedDistanceM"] as Double, 0.0)
    }

    @Test fun integratesAdjacentFreshSpeedAndSignedPackDelta() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.vehicle(EvTelemetrySample(1000, 1000, 36.0, 50000.0, speedObservedMs = 1000, batteryObservedMs = 1000))
        val record = core.vehicle(EvTelemetrySample(2000, 2000, 36.0, 50010.0, speedObservedMs = 2000, batteryObservedMs = 2000))
        assertEquals(10.0, record["integratedDistanceM"] as Double, 0.0001)
        assertEquals(-10.0, record["netPackUsedWh"] as Double, 0.0001)
        assertFalse(record.containsKey("charging"))
    }
}
