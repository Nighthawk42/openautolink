package com.openautolink.app.diagnostics

import android.os.SystemClock
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.navigation.VehicleEnergyForecast
import java.io.File
import java.util.UUID

/** Explicit logging consent owns this sink, not FileLogWriter's size-limited isActive. */
class EvTelemetryRecorder(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    boot: String,
) {
    companion object {
        val instance by lazy {
            EvTelemetryRecorder({ SystemClock.elapsedRealtime() }, { System.currentTimeMillis() },
                // Process-local fallback is explicit; never record a hardware/device identifier.
                runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrElse { "unavailable" })
        }
        fun flushForUpload(timeoutMs: Long = 2000): Boolean = instance.flushForUpload(timeoutMs)
    }
    private val core = EvTelemetryCore(boot, UUID.randomUUID().toString())
    private var token = "none"
    private var writer: EvTelemetryWriter? = null
    private var capture = false
    /** Stamp before deferring work; default arguments are for synchronous receipt callers only. */
    @Volatile var captureGeneration = 0L
        private set
    private var directory: File? = null
    private var lastVehicle: Map<String, Any?>? = null
    private var lastDropped = 0L
    private var lastIgnition: Int? = null
    private var lastEmissionMs = Long.MIN_VALUE
    private var lastEdge: List<Any?>? = null
    @get:Synchronized val enabled get() = capture

    @Synchronized fun enable(directory: File) {
        if (capture) return
        if (this.directory != directory) {
            writer?.close()
            writer = EvTelemetryWriter(directory)
            this.directory = directory
        }
        captureGeneration++
        capture = true
        lastDropped = 0
        lastVehicle = null
        lastEdge = null
        emit(core.startSession(token, elapsed(), wall()) + mapOf("type" to "consent_start", "sourceAvailability" to "awaiting_vehicle_observations"))
    }
    /** Stop NEW admission immediately. Previously consented entries may finish normal disk flushing. */
    @Synchronized fun disable() {
        emit(core.gap(token, "consent_stop", elapsed(), wall()))
        capture = false
        captureGeneration++
        lastVehicle = null
    }
    @Synchronized fun session(sessionToken: String) {
        captureGeneration++
        token = sessionToken
        lastVehicle = null
        lastEdge = null
        emit(core.startSession(token, elapsed(), wall()))
    }
    @Synchronized fun gap(sessionToken: String, reason: String, receiptGeneration: Long = captureGeneration) {
        if (receiptGeneration != captureGeneration || sessionToken != token) return
        emit(core.gap(sessionToken, reason, elapsed(), wall()))
        lastVehicle = null
        lastEdge = null
    }
    @Synchronized fun event(sessionToken: String, type: String, fields: Map<String, Any?> = emptyMap(), receiptGeneration: Long = captureGeneration) {
        if (receiptGeneration != captureGeneration || sessionToken != token || !capture) return
        emit(core.event(type, elapsed(), wall()) + fields)
    }
    @Synchronized fun vehicle(sessionToken: String, vd: ControlMessage.VehicleData, effective: Map<String, Any?> = emptyMap(), receiptGeneration: Long = captureGeneration) {
        val sink = writer ?: return
        if (receiptGeneration != captureGeneration || !capture || sessionToken != token) return
        val now = elapsed()
        if (sink.dropped.get() != lastDropped) {
            lastDropped = sink.dropped.get()
            emit(core.gap(token, "queue_drop", now, wall()))
        }
        if (lastIgnition != null && vd.ignitionState != lastIgnition) emit(core.gap(token, "ignition_transition", now, wall()))
        lastIgnition = vd.ignitionState
        val metadata = vd.evObservationMetadata
        fun observed(key: String): Long? = metadata[key]?.takeIf { it.status == 0 }
            ?.timestampElapsedNanos?.div(1000000)
        fun identity(key: String): EvObservationIdentity? = metadata[key]?.let {
            EvObservationIdentity(it.source, it.timestampElapsedNanos, it.receivedElapsedMs, it.status)
        }
        val sample = EvTelemetrySample(now, wall(), vd.speedKmh?.toDouble(), vd.evBatteryLevelWh?.toDouble(),
            observed("PERF_VEHICLE_SPEED"), observed("EV_BATTERY_LEVEL"),
            vd.gearRaw == 4, identity("EV_BATTERY_LEVEL"), identity("GEAR_SELECTION"))
        val record = core.vehicle(sample) + mapOf(
            "speedKmh" to vd.speedKmh, "batteryWh" to vd.evBatteryLevelWh,
            "designCapacityWh" to vd.evBatteryCapacityWh, "currentCapacityWh" to vd.evCurrentBatteryCapacityWh,
            "batteryPct" to vd.batteryPct, "rangeKm" to vd.rangeKm, "odometerKm" to vd.odometerKm,
            "gearRaw" to vd.gearRaw, "ignition" to vd.ignitionState, "parkingBrake" to vd.parkingBrake,
            "chargePortOpen" to vd.chargePortOpen, "chargePortConnected" to vd.chargePortConnected,
            "chargeState" to vd.evChargeState, "chargeRateW" to vd.evChargeRateW,
            "chargeTimeRemainingSec" to vd.evChargeTimeRemainingSec,
            "batteryTempC" to vd.evBatteryTempC, "ambientTempC" to vd.ambientTempC,
            "chargePercentLimit" to vd.evChargePercentLimit, "chargeCurrentDrawLimitA" to vd.evChargeCurrentDrawLimitA,
            "regenLevel" to vd.evRegenBrakingLevel, "stoppingMode" to vd.evStoppingMode,
            "motorPowerWUnvalidated" to vd.evMotorPowerW, "motorTorqueNmUnvalidated" to vd.evMotorTorqueNm,
            "effective" to effective,
            "observations" to metadata.mapValues { (_, m) -> mapOf("source" to m.source,
                "timestampElapsedNanos" to m.timestampElapsedNanos, "receivedElapsedMs" to m.receivedElapsedMs,
                "status" to m.status, "receiptAgeMs" to now - m.receivedElapsedMs) },
            "sourceAvailability" to if (metadata.isEmpty()) "no_metadata" else "observed_properties_only",
            "coverage" to "bounded_observed_adjacent_samples_not_full_vhal_stream")
        lastVehicle = record
        val edge = listOf(vd.gearRaw, vd.ignitionState, vd.chargePortConnected, vd.evChargeState, effective)
        if (lastEdge != edge || now - lastEmissionMs >= 5000 || record["arrivalCandidate"] == true ||
            record["energyWindowCompleted"] == true) {
            emit(record)
            lastEdge = edge
            lastEmissionMs = now
        }
    }
    @Synchronized fun navigation(sessionToken: String, nav: ControlMessage.NavState?, reroute: Boolean = false, receiptGeneration: Long = captureGeneration) {
        if (receiptGeneration != captureGeneration || sessionToken != token || !capture) return
        emit(core.navigation(nav?.destination, nav?.destDistanceMeters, nav?.timeToArrivalSeconds,
            nav == null && !reroute, elapsed(), wall(), reroute))
    }
    @Synchronized fun navigationLifecycle(sessionToken: String, active: Boolean, receiptGeneration: Long = captureGeneration) {
        if (receiptGeneration != captureGeneration || sessionToken != token || !capture) return
        emit(core.navigationLifecycle(active, elapsed(), wall()))
    }
    @Synchronized fun forecast(sessionToken: String, forecast: VehicleEnergyForecast?, receiptGeneration: Long = captureGeneration) {
        if (receiptGeneration != captureGeneration || sessionToken != token || !capture) return
        val stop = forecast?.energyAtNextStop
        emit(core.forecast(stop?.arrivalBatteryEnergyWh, stop?.distanceMeters, stop?.timeToArrivalSeconds,
            forecast?.forecastQuality ?: 0, elapsed(), wall(), forecast?.receivedAtElapsedMs ?: elapsed()) + mapOf(
            "receivedAtElapsedMs" to forecast?.receivedAtElapsedMs,
            "distanceToEmptyM" to forecast?.distanceToEmpty?.distanceMeters,
            "nextStopMayBeCharging" to (forecast?.nextChargingStop != null)))
    }
    @Synchronized fun nativeModel(sessionToken: String, tag: String, line: String, receiptGeneration: Long = captureGeneration) {
        if (receiptGeneration != captureGeneration || sessionToken != token || !capture || tag != "vem" || !line.startsWith("VEM session=")) return
        emit(core.event("native_model", elapsed(), wall()) + ("line" to line.take(500)))
    }
    fun flushForUpload(timeoutMs: Long = 2000): Boolean {
        val pending = synchronized(this) {
            val target = writer ?: return true
            val snapshot = if (capture) core.event("upload_snapshot", elapsed(), wall()) + mapOf(
                "vehicle" to lastVehicle, "parkSnapshot" to (lastVehicle?.get("gearRaw") == 4),
                "snapshotIsCached" to true) else null
            target to snapshot
        }
        // Snapshot admission and completion are ONE queued task; no producer monitor during IO.
        return pending.first.flushForUpload(timeoutMs, pending.second)
    }
    private fun emit(record: Map<String, Any?>?) { if (capture && record != null) writer?.offer(record) }
}
