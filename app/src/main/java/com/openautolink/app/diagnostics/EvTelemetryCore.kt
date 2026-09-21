package com.openautolink.app.diagnostics

/** Diagnostic observations only. Never used by the predictor or forwarded to the phone. */
data class EvObservationIdentity(
    val source: String,
    val timestampElapsedNanos: Long?,
    val receivedElapsedMs: Long,
    val status: Int?,
)

data class EvTelemetrySample(
    val elapsedMs: Long, val wallMs: Long,
    val speedKmh: Double? = null, val batteryWh: Double? = null,
    val speedObservedMs: Long? = null, val batteryObservedMs: Long? = null,
    val parked: Boolean = false,
    val batteryObservation: EvObservationIdentity? = batteryObservedMs?.let {
        EvObservationIdentity("legacy", it * 1_000_000, it, 0)
    },
    val gearObservation: EvObservationIdentity? = if (parked) {
        EvObservationIdentity("legacy", elapsedMs * 1_000_000, elapsedMs, 0)
    } else null,
)

class EvTelemetryCore(private val bootId: String, private val processId: String) {
    private var session = "none"
    private var routeEpoch = 0L // Legacy numeric field retained for schema compatibility.
    private var routeEpochId = java.util.UUID.randomUUID().toString()
    private var routeActive = false
    private var destinationHash: String? = null
    private var initialWh: Int? = null
    private var latestWh: Int? = null
    private var navMs = -100000L
    private var forecastMs = -100000L
    private var forecastRouteEpoch: String? = null
    private var remainingM: Int? = null
    private var remainingEtaSec: Long? = null
    private var routeStartedMs = Long.MIN_VALUE
    private fun resetRoute(elapsedMs: Long, active: Boolean = false) {
        routeStartedMs = elapsedMs
        routeEpoch++
        routeEpochId = java.util.UUID.randomUUID().toString()
        routeActive = active
        initialWh = null
        latestWh = null
        remainingM = null
        remainingEtaSec = null
        navMs = -100000
        forecastMs = -100000
        forecastRouteEpoch = null
    }
    fun navigationLifecycle(active: Boolean, elapsedMs: Long, wallMs: Long): Map<String, Any?> {
        if (active && !routeActive) resetRoute(elapsedMs, active = true)
        else if (!active) routeActive = false
        return record(if (active) "route_active" else "route_inactive", elapsedMs, wallMs)
    }
    fun navigation(destination: String?, distanceM: Int?, etaSec: Long?, clear: Boolean,
                   elapsedMs: Long, wallMs: Long, reroute: Boolean = false): Map<String, Any?> {
        if (clear) resetRoute(elapsedMs)
        else if (reroute) resetRoute(elapsedMs, active = true)
        destinationHash = null // Legacy field stays nullable; destination text never defines identity.
        if (!clear && distanceM != null && distanceM > 0) {
            remainingM = distanceM
            navMs = elapsedMs
        }
        if (!clear && etaSec != null && etaSec > 0) remainingEtaSec = etaSec
        return record(if (clear) "route_cancel_or_reset" else if (reroute) "reroute" else "navigation", elapsedMs, wallMs) +
            mapOf("remainingM" to remainingM, "remainingObservedMs" to navMs.takeIf { remainingM != null },
                "etaSec" to remainingEtaSec)
    }
    fun forecast(arrivalWh: Int?, distanceM: Int?, etaSec: Int?, quality: Int,
                 elapsedMs: Long, wallMs: Long, receivedAtElapsedMs: Long = elapsedMs): Map<String, Any?> {
        if (receivedAtElapsedMs < routeStartedMs) return record("forecast_uncorrelated", elapsedMs, wallMs) +
            mapOf("receivedAtElapsedMs" to receivedAtElapsedMs, "reason" to "pre_route_epoch")
        if (initialWh == null && arrivalWh != null) initialWh = arrivalWh
        latestWh = arrivalWh
        forecastMs = receivedAtElapsedMs
        forecastRouteEpoch = routeEpochId
        return record(if (arrivalWh == null) "forecast_empty" else "forecast", elapsedMs, wallMs) +
            mapOf("distanceM" to distanceM, "etaSec" to etaSec, "quality" to quality,
                "currentBatteryWh" to previous?.batteryWh, "batteryObservedMs" to previous?.batteryObservedMs)
    }
    private var previous: EvTelemetrySample? = null
    private var distanceM = 0.0
    private var netWh = 0.0
    private var energyAnchor: EvTelemetrySample? = null
    private var energyCovered = true
    private var batteryCovered = true
    private var uncoveredSpeedIntervals = 0L
    fun startSession(token: String, elapsedMs: Long, wallMs: Long): Map<String, Any?> {
        session = token
        previous = null
        energyAnchor = null
        batteryCovered = true
        energyCovered = true
        resetRoute(elapsedMs)
        destinationHash = null
        drive++
        distanceM = 0.0
        netWh = 0.0
        return record("session_start", elapsedMs, wallMs)
    }
    private var gaps = 0L
    private var drive = 0L
    fun gap(token: String, reason: String, elapsedMs: Long, wallMs: Long): Map<String, Any?>? {
        if (token != session) return null
        previous = null
        energyAnchor = null
        batteryCovered = true
        energyCovered = true
        gaps++
        resetRoute(elapsedMs)
        destinationHash = null
        drive++
        return record("gap", elapsedMs, wallMs) + ("reason" to reason)
    }
    private fun fresh(observed: Long?, now: Long) = observed != null && now - observed in 0..5000
    fun vehicle(sample: EvTelemetrySample): Map<String, Any?> {
        var intervalCovered = previous == null
        previous?.let { p ->
            val dt = sample.elapsedMs - p.elapsedMs
            if (dt in 1..5000) {
                if (p.speedKmh != null && sample.speedKmh != null &&
                    p.speedKmh.isFinite() && sample.speedKmh.isFinite() &&
                    p.speedKmh in 0.0..350.0 && sample.speedKmh in 0.0..350.0 &&
                    fresh(p.speedObservedMs, p.elapsedMs) && fresh(sample.speedObservedMs, sample.elapsedMs) &&
                    sample.speedObservedMs!! > p.speedObservedMs!!) {
                    intervalCovered = true
                    distanceM += (p.speedKmh + sample.speedKmh) / 2.0 / 3.6 *
                        (sample.speedObservedMs - p.speedObservedMs).coerceAtMost(dt) / 1000.0
                }
            } else { gaps++; drive++; energyAnchor = null; resetRoute(sample.elapsedMs); destinationHash = null }
        }
        if (!intervalCovered) uncoveredSpeedIntervals++
        energyCovered = energyCovered && intervalCovered
        var completedEnergyCoverage: Boolean? = null
        var completedBatteryCoverage: Boolean? = null
        val batteryValid = sample.batteryWh != null && sample.batteryWh.isFinite() && sample.batteryWh >= 0 &&
            sample.batteryObservation?.timestampElapsedNanos != null && sample.batteryObservation.status == 0
        if (batteryValid) {
            val anchor = energyAnchor
            if (anchor != null && sample.batteryObservation != anchor.batteryObservation) {
                netWh += anchor.batteryWh!! - sample.batteryWh
                completedEnergyCoverage = energyCovered
                completedBatteryCoverage = batteryCovered
                batteryCovered = true
                energyAnchor = sample
                energyCovered = true
            } else if (anchor == null) {
                batteryCovered = true
                energyAnchor = sample
                energyCovered = true
            }
        } else {
            batteryCovered = false
            energyAnchor = null
        }
        previous = sample
        return record("vehicle", sample.elapsedMs, sample.wallMs) + mapOf(
            "integratedDistanceM" to distanceM, "netPackUsedWh" to netWh,
            "energyWindowCompleted" to (completedEnergyCoverage != null),
            "energyWindowSpeedCovered" to completedEnergyCoverage,
            "energyWindowBatteryCovered" to completedBatteryCoverage,
            "uncoveredSpeedIntervals" to uncoveredSpeedIntervals,
            "arrivalCandidate" to (routeActive && forecastRouteEpoch == routeEpochId &&
                initialWh != null && latestWh != null &&
                sample.elapsedMs - navMs in 0..10000 && sample.elapsedMs - forecastMs in 0..10000 &&
                remainingM != null && remainingM!! in 1..150 && sample.parked &&
                sample.gearObservation?.timestampElapsedNanos != null && sample.gearObservation.status == 0 &&
                fresh(sample.speedObservedMs, sample.elapsedMs) && sample.speedKmh != null && sample.speedKmh in 0.0..1.0 &&
                batteryValid),
            "arrivalConfirmed" to false)
    }
    fun event(type: String, elapsedMs: Long, wallMs: Long): Map<String, Any?> = record(type, elapsedMs, wallMs)
    private fun record(type: String, elapsedMs: Long, wallMs: Long): Map<String, Any?> = linkedMapOf(
        "schema" to 1, "type" to type, "boot" to bootId, "process" to processId,
        "session" to session, "drive" to "$session:$drive", "gapCount" to gaps,
        "routeEpoch" to routeEpoch, "routeEpochId" to routeEpochId, "routeActive" to routeActive,
        "routeIdentityBasis" to "opaque_lifecycle", "routeContinuityUncertain" to true,
        "destinationHash" to destinationHash,
        "initialArrivalWh" to initialWh, "latestArrivalWh" to latestWh,
        "forecastRouteEpoch" to forecastRouteEpoch,
        "elapsedMs" to elapsedMs, "wallMs" to wallMs)
}
