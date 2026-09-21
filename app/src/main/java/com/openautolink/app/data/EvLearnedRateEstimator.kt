package com.openautolink.app.data

import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Phase 3' — learned driving Wh/km estimator.
 *
 * Computes a rolling exponential moving average of energy consumption from
 * `Δ(EV_BATTERY_LEVEL × capacity) ÷ Δdistance`, where Δdistance is integrated
 * from VHAL `speedKmh × Δt`. (GM AAOS blocks `PERF_ODOMETER`, so VHAL speed is
 * the only viable distance source — it's also what the dashboard's own trip
 * computer uses.)
 *
 * Per-vehicle state (keyed by `Make|Model|Year`) is persisted to DataStore
 * as a small JSON blob so the value survives car-off / sleep / app restart.
 *
 * See docs/ev-energy-model-tuning-plan.md.
 */
class EvLearnedRateEstimator private constructor(
    private val store: Store,
    private val scope: CoroutineScope,
    private val config: Config,
) {

    internal data class Config(
        val persistDebounceMs: Long = PERSIST_DEBOUNCE_MS,
        val persistMaxDelayMs: Long = 60_000L,
        val persistRetryMs: Long = 5_000L,
        val maxEntries: Int = 32,
        val maxJsonBytes: Int = 16 * 1024,
    ) {
        init {
            require(persistDebounceMs >= 0L)
            require(persistMaxDelayMs >= 0L)
            require(persistRetryMs >= 0L)
            require(maxEntries >= 0)
            require(maxJsonBytes >= 2) { "maxJsonBytes must fit an empty JSON object" }
        }
    }

    internal interface Store {
        suspend fun load(): String
        suspend fun save(value: String)
    }

    private class PreferencesStore(private val prefs: AppPreferences) : Store {
        override suspend fun load(): String = prefs.evLearnedRatesJson.first()
        override suspend fun save(value: String) = prefs.setEvLearnedRatesJson(value)
    }

    /** Public snapshot for the UI. */
    data class Snapshot(
        val key: String? = null,
        val whPerKm: Float = 0f,
        val sampleKm: Float = 0f,
        val lastUpdateMs: Long = 0L,
        /** Reason the most recent tick was rejected (informational). */
        val lastTickStatus: String = "",
    ) {
        /** True when [whPerKm] is trustworthy enough to send to Maps. */
        val usable: Boolean get() = whPerKm in 50f..800f && sampleKm >= MIN_USABLE_KM
    }

    companion object {
        private const val TAG = "EvLearnedRate"
        private const val MIN_USABLE_KM = 1.0f
        private const val MIN_INST_WH_PER_KM = 50f   // < this is implausible
        private const val MAX_INST_WH_PER_KM = 800f  // > this too
        private const val MIN_SAMPLE_KM_PER_TICK = 0.05f
        // Restart the sample window if more than this elapses (e.g. car was
        // off, app was backgrounded, etc.). Prevents stale lastBatteryWh from
        // creating a giant Δ when the car turns back on.
        private const val MAX_TICK_GAP_MS = 15 * 60 * 1000L
        // Throttle DataStore writes so we don't thrash on every sub-second tick.
        private const val PERSIST_DEBOUNCE_MS = 5_000L

        @Volatile private var instance: EvLearnedRateEstimator? = null

        fun getInstance(prefs: AppPreferences): EvLearnedRateEstimator =
            instance ?: synchronized(this) {
                instance ?: EvLearnedRateEstimator(
                    PreferencesStore(prefs),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    Config(),
                ).also { instance = it }
            }

        internal fun createForTest(
            store: Store,
            scope: CoroutineScope,
            config: Config = Config(),
        ): EvLearnedRateEstimator = EvLearnedRateEstimator(store, scope, config)

        /**
         * Pure tick math. Mutates `s` in place, returns a short status string.
         * Exposed `internal` so [EvLearnedRateEstimatorTest] can exercise the
         * regen / outlier / gap-reset / EMA branches without DataStore or
         * coroutines. Returned status starts with `"ok:"` only for accepted
         * samples.
         */
        internal fun applyTick(
            s: VehicleState,
            vd: ControlMessage.VehicleData,
            nowElapsedMs: Long,
        ): String {
            val batteryWh = vd.evBatteryLevelWh?.toInt() ?: return "skip:noBattery"
            val speedKmh = vd.speedKmh ?: 0f
            val charging = (vd.evChargeRateW ?: 0f) > 0f

            val prevElapsed = s.lastTickElapsedMs
            val prevBattery = s.lastBatteryWh
            val prevSpeedKmh = s.lastSpeedKmh

            // Advance only the interval baseline here. Window baselines are
            // retained until at least 50 m has accumulated.
            s.lastTickElapsedMs = nowElapsedMs
            s.lastBatteryWh = batteryWh
            s.lastSpeedKmh = speedKmh

            if (charging) {
                s.resetWindow(batteryWh)
                return "skip:charging"
            }
            if (prevElapsed == 0L || prevBattery <= 0) {
                s.resetWindow(batteryWh)
                return "init"
            }

            val dtMs = nowElapsedMs - prevElapsed
            if (dtMs <= 0 || dtMs > MAX_TICK_GAP_MS) {
                s.resetWindow(batteryWh)
                return "skip:gap=${dtMs}ms"
            }

            val dtH = dtMs / 3_600_000f
            val dKm = ((prevSpeedKmh + speedKmh) / 2f).coerceAtLeast(0f) * dtH
            s.windowDistanceKm += dKm

            val motorW = vd.evMotorPowerW
            if (motorW == null) {
                s.windowMotorPowerFullyCovered = false
            } else {
                s.windowMotorEnergyWh += motorW * dtH
            }
            if (s.windowDistanceKm + 1e-6f < MIN_SAMPLE_KM_PER_TICK) {
                return "accum:dKm=${s.windowDistanceKm}"
            }

            val windowDistanceKm = s.windowDistanceKm
            val batteryDeltaWh = (s.windowStartBatteryWh - batteryWh).toFloat()
            val useMotorPower = s.windowMotorPowerFullyCovered
            val dWh = if (useMotorPower) s.windowMotorEnergyWh else batteryDeltaWh
            val source = if (useMotorPower) "gt" else "bd"
            s.resetWindow(batteryWh)
            if (dWh <= 0f) return "skip:regen(dWh=${dWh.toInt()})"

            val instWhPerKm = dWh / windowDistanceKm
            if (instWhPerKm < MIN_INST_WH_PER_KM || instWhPerKm > MAX_INST_WH_PER_KM) {
                return "skip:outlier(${instWhPerKm.toInt()},src=$source)"
            }

            // EMA — alpha scales with sample distance so a long stretch counts more
            // than a 50 m blip.
            val alpha = (windowDistanceKm / 5f).coerceIn(0.01f, 0.3f)
            s.whPerKm = if (s.whPerKm <= 0f) instWhPerKm
                        else (1f - alpha) * s.whPerKm + alpha * instWhPerKm
            s.sampleKm += windowDistanceKm
            s.lastUpdateMs = System.currentTimeMillis()

            return "ok:$source inst=${instWhPerKm.toInt()} ema=${s.whPerKm.toInt()}"
        }
    }

    /** Mutable per-vehicle state, owned by the command actor. The volatile
     * fields also support direct pure-math tests and immutable UI snapshots. */
    internal class VehicleState {
        @Volatile var whPerKm: Float = 0f          // 0 = no data yet
        @Volatile var sampleKm: Float = 0f
        @Volatile var lastUpdateMs: Long = 0L
        @Volatile var lastBatteryWh: Int = 0       // last absolute battery (Wh)
        @Volatile var lastTickElapsedMs: Long = 0L // SystemClock.elapsedRealtime
        @Volatile var lastSpeedKmh: Float = 0f
        internal var windowStartBatteryWh: Int = 0
        internal var windowDistanceKm: Float = 0f
        internal var windowMotorEnergyWh: Float = 0f
        internal var windowMotorPowerFullyCovered: Boolean = true

        internal fun resetTransient() {
            lastBatteryWh = 0
            lastTickElapsedMs = 0L
            lastSpeedKmh = 0f
            resetWindow(0)
        }

        internal fun resetWindow(batteryWh: Int) {
            windowStartBatteryWh = batteryWh
            windowDistanceKm = 0f
            windowMotorEnergyWh = 0f
            windowMotorPowerFullyCovered = true
        }
    }

    private val states = linkedMapOf<String, VehicleState>()
    @Volatile private var publishedStates: Map<String, Snapshot> = emptyMap()
    @Volatile private var debouncePersist: Job? = null
    @Volatile private var maxDelayPersist: Job? = null
    @Volatile private var retryPersist: Job? = null
    private var dirty = false
    private var activeKey: String? = null

    private sealed interface Command {
        data class Tick(val data: ControlMessage.VehicleData, val elapsedMs: Long) : Command
        data class Reset(val key: String?) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
        data class Stop(val done: CompletableDeferred<Unit>) : Command
        data object Persist : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val _activeSnapshot = MutableStateFlow(Snapshot())
    val activeSnapshot: StateFlow<Snapshot> = _activeSnapshot.asStateFlow()

    init {
        scope.launch { runActor() }
    }

    private suspend fun runActor() {
        loadOnce()
        for (command in commands) {
            when (command) {
                is Command.Tick -> onVehicleTickLoaded(command.data, command.elapsedMs)
                is Command.Reset -> resetLoaded(command.key)
                is Command.Persist -> persistDirty()
                is Command.Barrier -> command.done.complete(Unit)
                is Command.Stop -> {
                    debouncePersist?.cancel()
                    maxDelayPersist?.cancel()
                    retryPersist?.cancel()
                    persistNow()
                    command.done.complete(Unit)
                    commands.close()
                }
            }
        }
    }

    private suspend fun loadOnce() {
        try {
            val raw = store.load()
            if (raw.isNotBlank() && raw != "{}") {
                val obj = Json.parseToJsonElement(raw).jsonObject
                for ((k, element) in obj) {
                    val v = runCatching { element.jsonObject }.getOrNull() ?: continue
                    states[k] = VehicleState().apply {
                        whPerKm = v["whPerKm"]?.jsonPrimitive?.floatOrNull ?: 0f
                        sampleKm = v["sampleKm"]?.jsonPrimitive?.floatOrNull ?: 0f
                        lastUpdateMs = v["lastUpdateMs"]?.jsonPrimitive?.longOrNull ?: 0L
                    }
                }
                enforceEntryLimit()
                publishStateIndex()
                OalLog.i(TAG, "loaded ${states.size} per-vehicle learned rates")
            }
        } catch (e: Exception) {
            OalLog.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun keyOf(vd: ControlMessage.VehicleData): String? {
        val mk = vd.carMake?.takeIf { it.isNotBlank() } ?: return null
        val md = vd.carModel?.takeIf { it.isNotBlank() } ?: return null
        val yr = vd.carYear?.takeIf { it.isNotBlank() } ?: "?"
        return "$mk|$md|$yr"
    }

    /**
     * Feed one VHAL tick. No-op when the data is too thin to learn from.
     * `nowElapsedMs` should be `SystemClock.elapsedRealtime()` (monotonic).
     */
    fun onVehicleTick(vd: ControlMessage.VehicleData, nowElapsedMs: Long) {
        commands.trySend(Command.Tick(vd, nowElapsedMs))
    }

    private fun onVehicleTickLoaded(vd: ControlMessage.VehicleData, nowElapsedMs: Long) {
        val key = keyOf(vd)
        if (key == null) {
            activeKey?.let { states[it]?.resetTransient() }
            activeKey = null
            return
        }
        if (activeKey != key) {
            activeKey?.let { states[it]?.resetTransient() }
            states[key]?.resetTransient()
            activeKey = key
        }
        val s = states.getOrPut(key) { VehicleState() }
        val status = applyTick(s, vd, nowElapsedMs)
        if (status.startsWith("ok:")) {
            enforceEntryLimit()
            markDirty()
        }
        publishActive(key, s, status)
    }

    private fun publishActive(key: String, s: VehicleState, status: String) {
        _activeSnapshot.value = Snapshot(
            key = key,
            whPerKm = s.whPerKm,
            sampleKm = s.sampleKm,
            lastUpdateMs = s.lastUpdateMs,
            lastTickStatus = status,
        )
        publishStateIndex()
    }

    private fun publishStateIndex() {
        publishedStates = states.mapValues { (key, s) ->
            Snapshot(key, s.whPerKm, s.sampleKm, s.lastUpdateMs)
        }
    }

    /** Returns the snapshot for [key], or empty when never seen. */
    fun snapshotFor(key: String?): Snapshot {
        if (key.isNullOrBlank()) return Snapshot()
        return publishedStates[key] ?: Snapshot()
    }

    /** Clear learned state for the currently active vehicle (or all). */
    fun reset(key: String? = null) {
        commands.trySend(Command.Reset(key))
    }

    private suspend fun resetLoaded(key: String?) {
        if (key == null) states.clear() else states.remove(key)
        if (key == null || activeKey == key) activeKey = null
        _activeSnapshot.value = Snapshot(key = key, lastTickStatus = "reset")
        publishStateIndex()
        debouncePersist?.cancel()
        maxDelayPersist?.cancel()
        dirty = true
        persistDirty()
        DiagnosticLog.i("ev_learned", "reset key=${key ?: "ALL"}")
    }

    internal suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Barrier(done))
        done.await()
    }

    internal suspend fun stop() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Stop(done))
        done.await()
    }

    private fun markDirty() {
        if (!dirty) {
            dirty = true
            maxDelayPersist = scope.launch {
                kotlinx.coroutines.delay(config.persistMaxDelayMs)
                commands.send(Command.Persist)
            }
        }
        debouncePersist?.cancel()
        debouncePersist = scope.launch {
            kotlinx.coroutines.delay(config.persistDebounceMs)
            commands.send(Command.Persist)
        }
    }

    private suspend fun persistDirty() {
        if (!dirty) return
        if (persistNow()) {
            dirty = false
            debouncePersist?.cancel()
            maxDelayPersist?.cancel()
            retryPersist?.cancel()
        } else {
            retryPersist?.cancel()
            retryPersist = scope.launch {
                kotlinx.coroutines.delay(config.persistRetryMs)
                commands.send(Command.Persist)
            }
        }
    }

    private fun enforceEntryLimit() {
        while (states.size > config.maxEntries.coerceAtLeast(0)) {
            val victim = states.entries.minWithOrNull(
                compareBy<Map.Entry<String, VehicleState>> { it.value.lastUpdateMs }
                    .thenBy { it.key },
            ) ?: break
            states.remove(victim.key)
            if (activeKey == victim.key) activeKey = null
        }
    }

    private suspend fun persistNow(): Boolean {
        val values = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        val newestFirst = states.entries.sortedWith(
            compareByDescending<Map.Entry<String, VehicleState>> { it.value.lastUpdateMs }
                .thenByDescending { it.key },
        )
        for ((k, s) in newestFirst) {
            if (s.whPerKm <= 0f && s.sampleKm <= 0f) continue
            val value = buildJsonObject {
                put("whPerKm", s.whPerKm.toDouble())
                put("sampleKm", s.sampleKm.toDouble())
                put("lastUpdateMs", s.lastUpdateMs)
            }
            values[k] = value
            if (JsonObject(values).toString().toByteArray(Charsets.UTF_8).size > config.maxJsonBytes) {
                values.remove(k)
            }
        }
        val encoded = JsonObject(values).toString()
        try {
            store.save(encoded)
            return true
        } catch (e: Exception) {
            OalLog.w(TAG, "persist failed: ${e.message}")
            return false
        }
    }
}
