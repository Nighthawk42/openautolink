package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class EvTelemetryCoreTest {
    @Test fun forecastWithoutProtocolRouteIdentityCannotSeedComparisonOrCandidate() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.navigationLifecycle(true, 1000, 1000)
        core.navigation(null, 100, 30, false, 1100, 1100)

        val forecast = core.forecast(40000, 100, 30, 2, 1200, 1200)
        val parked = core.vehicle(EvTelemetrySample(1300, 1300, 0.0, 40000.0, 1300, 1300, true))

        assertEquals(40000, forecast["arrivalWh"])
        assertEquals("uncertain_no_protocol_route_identity", forecast["forecastCorrelation"])
        assertNull(forecast["initialArrivalWh"])
        assertNull(forecast["latestArrivalWh"])
        assertEquals(false, parked["arrivalCandidate"])
    }

    @Test fun positiveDistanceDiscontinuityRotatesOpaqueEpoch() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.navigationLifecycle(true, 100, 100)
        val first = core.navigation(null, 1000, 60, false, 200, 200)
        val progress = core.navigation(null, 900, 55, false, 300, 300)
        val discontinuity = core.navigation(null, 1800, 100, false, 400, 400)

        assertEquals(first["routeEpochId"], progress["routeEpochId"])
        assertNotEquals(progress["routeEpochId"], discontinuity["routeEpochId"])
        assertEquals("positive_distance_discontinuity", discontinuity["routeBoundaryReason"])
        assertEquals(true, discontinuity["routeContinuityUncertain"])
    }

    @Test fun schemaTwoRetainsSchemaOneParserFieldsAdditively() {
        val record = EvTelemetryCore("boot", "process").startSession("one", 0, 0)
        assertEquals(2, record["schema"])
        val schemaOneParserFields = setOf(
            "schema", "type", "boot", "process", "session", "drive", "gapCount",
            "routeEpoch", "destinationHash", "initialArrivalWh", "latestArrivalWh",
            "elapsedMs", "wallMs",
        )
        assertTrue(record.keys.containsAll(schemaOneParserFields))
        assertTrue(record["routeEpoch"] is Long)
        assertTrue(record["routeEpochId"] is String)
        assertTrue(record.containsKey("destinationHash"))
        assertNull(record["destinationHash"])
        fun legacyFieldView(input: Map<String, Any?>): Map<String, Any?> {
            require(input["schema"] in setOf(1, 2))
            return input.filterKeys { it in schemaOneParserFields }
        }
        val schemaOneRecord = record + ("schema" to 1)
        assertEquals(schemaOneParserFields, legacyFieldView(schemaOneRecord).keys)
        assertEquals(schemaOneParserFields, legacyFieldView(record).keys)
        assertEquals(2, legacyFieldView(record)["schema"])
    }

    @Test fun alternatingPartialNavigationPreservesExplicitDistanceWithoutRefreshingIt() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        val explicit = core.navigation(null, 100, 30, false, 1000, 1000)
        val absent = core.navigation("PRIVATE_DESTINATION", -1, -1, false, 8000, 8000)
        val partial = core.navigation("PRIVATE_DESTINATION", 0, 0, false, 9000, 9000)

        assertEquals(100, explicit["remainingM"])
        assertEquals(100, absent["remainingM"])
        assertEquals(1000L, absent["remainingObservedMs"])
        assertEquals(100, partial["remainingM"])
        assertEquals(1000L, partial["remainingObservedMs"])
        core.forecast(40000, 100, 30, 2, 9000, 9000)
        val parked = core.vehicle(EvTelemetrySample(11001, 11001, 0.0, 40000.0, 11001, 11001, true))
        assertEquals(false, parked["arrivalCandidate"])
        assertFalse(partial.toString().contains("PRIVATE_DESTINATION"))
    }

    @Test fun activeLifecycleOwnsOpaqueEpochIndependentOfDestinationText() {
        val core = EvTelemetryCore("boot", "process")
        val session = core.startSession("one", 0, 0)
        val active = core.navigationLifecycle(true, 100, 100)
        val duplicate = core.navigationLifecycle(true, 200, 200)
        val first = core.navigation("PRIVATE_HOME", 1000, 60, false, 300, 300)
        val renamed = core.navigation("PRIVATE_WORK", 900, 50, false, 400, 400)
        val rerouted = core.navigation(null, null, null, false, 500, 500, reroute = true)

        assertNotEquals(session["routeEpoch"], active["routeEpoch"])
        assertEquals(active["routeEpoch"], duplicate["routeEpoch"])
        assertEquals(first["routeEpoch"], renamed["routeEpoch"])
        assertNotEquals(renamed["routeEpoch"], rerouted["routeEpoch"])
        assertEquals("opaque_lifecycle", active["routeIdentityBasis"])
        assertEquals(true, active["routeContinuityUncertain"])
        assertNull(active["destinationHash"])
        assertFalse(listOf(session, active, duplicate, first, renamed, rerouted).toString().contains("PRIVATE_"))
    }

    @Test fun candidateIsSuppressedWhenProtocolCannotCorrelateForecastToRoute() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.navigationLifecycle(true, 100000, 100000)
        core.navigation(null, 100, 30, false, 100000, 100000)
        val forecast = core.forecast(40000, 100, 30, 2, 100000, 100000)
        assertNull(forecast["forecastRouteEpoch"])
        assertEquals("uncertain_no_protocol_route_identity", forecast["forecastCorrelation"])
        val battery = EvObservationIdentity("vhal", 1, 1000, 0)
        val gear = EvObservationIdentity("vhal", 2, 1000, 0)
        val valid = EvTelemetrySample(100001, 100001, 0.0, 40000.0, 100001, 1000, true,
            batteryObservation = battery, gearObservation = gear)
        assertEquals(false, core.vehicle(valid)["arrivalCandidate"])
        assertEquals(false, core.vehicle(valid.copy(batteryObservation = battery.copy(status = 1)))["arrivalCandidate"])
        assertEquals(false, core.vehicle(valid.copy(gearObservation = gear.copy(status = 1)))["arrivalCandidate"])
        core.navigation(null, null, null, false, 100002, 100002, reroute = true)
        assertEquals(false, core.vehicle(valid.copy(elapsedMs = 100002))["arrivalCandidate"])
    }

    @Test fun missingBatteryCannotQualifyAndBreaksBatteryCoverage() {
        val c = EvTelemetryCore("b", "p")
        c.startSession("s", 0, 0)
        c.navigation("stop", 10, 1, false, 1000, 1000)
        c.forecast(40000, 10, 1, 2, 1000, 1000)
        c.vehicle(EvTelemetrySample(1000, 1000, 0.0, 50000.0, 1000, 1000, true))
        val missing = c.vehicle(EvTelemetrySample(2000, 2000, 0.0, null, 2000, 2000, true))
        assertEquals(false, missing["arrivalCandidate"])
        val after = c.vehicle(EvTelemetrySample(3000, 3000, 0.0, 49000.0, 3000, 3000, true))
        assertNull(after["energyWindowBatteryCovered"])
    }

    @Test fun delayedPreEpochForecastCannotSeedNewRouteInEitherOrder() {
        for (delayed in listOf(false, true)) {
            val c = EvTelemetryCore("b", "p")
            c.startSession("s", 0, 0)
            c.navigation("old", 10000, 100, false, 1000, 1000)
            if (!delayed) c.forecast(40000, 10000, 100, 2, 1000, 1000, 1000)
            c.navigation("new", 10, 1, false, 2000, 2000, reroute = true)
            val delayedRecord = if (delayed) {
                c.forecast(40000, 10000, 100, 2, 2000, 2000, 1000)
            } else null
            val v = c.vehicle(EvTelemetrySample(2000, 2000, 0.0, 39000.0, 2000, 2000, true))
            assertNull(v["initialArrivalWh"])
            assertEquals(false, v["arrivalCandidate"])
            if (delayedRecord != null) {
                assertEquals(40000, delayedRecord["arrivalWh"])
                assertEquals("pre_route_epoch", delayedRecord["forecastCorrelation"])
            }
        }
    }

    @Test fun emptyForecastRemainsRawEvidenceWithoutCreatingComparison() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.navigation("private stop", 10, 1, false, 1000, 1000)
        val populated = core.forecast(40000, 10, 1, 2, 1000, 1000)
        val empty = core.forecast(null, null, null, 0, 2000, 2000)
        val record = core.vehicle(EvTelemetrySample(2000, 2000, 0.0, 40000.0, 2000, 2000, true))
        assertEquals(40000, populated["arrivalWh"])
        assertNull(empty["arrivalWh"])
        assertNull(record["initialArrivalWh"])
        assertNull(record["latestArrivalWh"])
        assertEquals(false, record["arrivalCandidate"])
    }

    @Test fun explicitlyInvalidBatteryObservationCannotCreateAnEnergyDelta() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.vehicle(EvTelemetrySample(1000, 1000, 36.0, 50000.0, 1000, 1000))
        val invalid = EvObservationIdentity("vhal", 1500000000, 1500, 1)
        val stale = core.vehicle(EvTelemetrySample(7000, 7000, 36.0, 49000.0, 7000, 1500,
            batteryObservation = invalid))
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

    @Test fun onChangeBatteryRemainsValidAcrossRealCadenceWithoutCachedDeltas() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.vehicle(EvTelemetrySample(1000, 1000, 0.0, 50000.0, 1000, 1000))
        var cached: Map<String, Any?>? = null
        for (t in 6000L..106000L step 5000) {
            cached = core.vehicle(EvTelemetrySample(t, t, 0.0, 50000.0, t, 1000))
        }
        assertEquals(0.0, cached!!["netPackUsedWh"] as Double, 0.0)
        val changed = core.vehicle(EvTelemetrySample(111000, 111000, 0.0, 49000.0, 111000, 111000))
        assertEquals(1000.0, changed["netPackUsedWh"] as Double, 0.0)
        assertEquals(true, changed["energyWindowBatteryCovered"])
    }

    @Test fun recordsRawForecastsButSuppressesUnprovableInitialLatestComparison() {
        val core = EvTelemetryCore("boot", "process")
        core.startSession("one", 0, 0)
        core.navigationLifecycle(true, 500, 500)
        core.navigation("secret home address", 100, 30, false, 1000, 1000)
        val initial = core.forecast(40000, 100, 30, 1, 1000, 1000)
        val latest = core.forecast(39000, 50, 20, 2, 2000, 2000)
        assertEquals(40000, initial["arrivalWh"])
        assertEquals(39000, latest["arrivalWh"])
        assertNull(latest["initialArrivalWh"])
        assertNull(latest["latestArrivalWh"])
        assertFalse(latest.toString().contains("secret home address"))
        val sample = EvTelemetrySample(2000, 2000, 0.0, 39000.0, 2000, 2000, parked = true)
        assertEquals(false, core.vehicle(sample)["arrivalCandidate"])
        core.navigation("another private stop", 30, 10, false, 3000, 3000)
        assertEquals(false, core.vehicle(sample.copy(elapsedMs = 3000))["arrivalCandidate"])
        core.navigation(null, null, null, false, 3500, 3500, reroute = true)
        assertEquals(false, core.vehicle(sample.copy(elapsedMs = 3500))["arrivalCandidate"])
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
