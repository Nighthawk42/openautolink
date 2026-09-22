package com.openautolink.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scope compact EV recorder/uploader. It reacts only to vehicle ticks;
 * it owns no alarm, worker, wake lock, poller, or startup network request.
 */
object EvContributionService {
    private val initialized = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var consent = false
    @Volatile private var uploadUrl = ""
    @Volatile private var token = ""
    @Volatile private var queue: EvContributionQueue? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var activeId: String? = null
    @Volatile private var activeStartedMs = 0L
    @Volatile private var processStartedElapsedMs = 0L
    private var lastTickElapsedMs = 0L
    private var lastSpeedKmh = 0f
    private var distanceM = 0.0

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        processStartedElapsedMs = SystemClock.elapsedRealtime()
        queue = EvContributionQueue(File(context.filesDir, "ev-contributions")).also {
            val recovered = it.recoverInterrupted(System.currentTimeMillis())
            if (recovered > 0) DiagnosticLog.i("ev_contribution", "recoveredInterrupted=$recovered")
        }
        val prefs = AppPreferences.getInstance(context)
        scope.launch { prefs.evContributionConsent.collect { enabled ->
            consent = enabled
            if (!enabled) {
                activeId = null
                synchronized(this@EvContributionService) {
                    lastTickElapsedMs = 0L
                    lastSpeedKmh = 0f
                    distanceM = 0.0
                }
                queue?.deletePending()
                DiagnosticLog.i("ev_contribution", "consent=off pendingDeleted=true")
            } else {
                DiagnosticLog.i("ev_contribution", "consent=on compactEvOnly=true")
            }
        } }
        scope.launch { prefs.logUploadUrl.collect { uploadUrl = it } }
        scope.launch { prefs.logUploadToken.collect { token = it } }
    }

    /** Vehicle-only allowlisted capture. Navigation state is deliberately not accepted. */
    @Synchronized
    fun onVehicle(vd: ControlMessage.VehicleData, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val q = queue ?: return
        if (!EvContributionPolicy.mayCapture(consent, uploadUrl, token)) return
        val parked = vd.gearRaw == 4 || vd.parkingBrake == true || vd.driving == false
        if (!parked) {
            val id = activeId ?: UUID.randomUUID().toString().also {
                activeId = it
                activeStartedMs = System.currentTimeMillis()
            }
            val capacity = vd.evBatteryCapacityWh?.takeIf { it.isFinite() && it > 0f }
            val capacityBand = capacity?.let { (it.toInt() / 5_000) * 5 }
            val speed = vd.speedKmh?.takeIf(Float::isFinite)
            if (lastTickElapsedMs > 0L && nowElapsedMs > lastTickElapsedMs && nowElapsedMs - lastTickElapsedMs <= 15 * 60_000L && speed != null) {
                distanceM += ((lastSpeedKmh + speed) / 2.0) * ((nowElapsedMs - lastTickElapsedMs) / 3_600.0)
            }
            lastTickElapsedMs = nowElapsedMs
            if (speed != null) lastSpeedKmh = speed
            val line = buildJsonObject {
                put("schema", 1)
                put("type", "vehicle")
                put("wallMs", System.currentTimeMillis())
                put("elapsedMs", nowElapsedMs)
                vd.evBatteryLevelWh?.takeIf(Float::isFinite)?.let { put("batteryWh", it) }
                vd.batteryPct?.let { put("batteryPct", it) }
                vd.rangeKm?.takeIf(Float::isFinite)?.let { put("rangeKm", it) }
                vd.speedKmh?.takeIf(Float::isFinite)?.let { put("speedKmh", it) }
                put("distanceM", distanceM.toLong())
                capacityBand?.let { put("capacityBandKwh", it) }
                vd.evChargeState?.let { put("chargeState", it) }
                vd.evChargeRateW?.takeIf(Float::isFinite)?.let { put("chargeRateW", it) }
                vd.chargePortConnected?.let { put("chargePortConnected", it) }
                vd.gearRaw?.let { put("gearRaw", it) }
                vd.ignitionState?.let { put("ignition", it) }
                put("energyBasis", "absolute-wh")
                put("estimatorRevision", "rev2")
            }.toString()
            runCatching { q.append(id, activeStartedMs, line) }
                .onFailure { DiagnosticLog.w("ev_contribution", "captureRejected=${it.javaClass.simpleName}") }
            return
        }

        activeId?.let { id ->
            q.close(id, System.currentTimeMillis())
            DiagnosticLog.i("ev_contribution", "closed id=$id retained=${q.counters().retained}")
            activeId = null
            lastTickElapsedMs = 0L
            lastSpeedKmh = 0f
            distanceM = 0.0
        }
        attemptNaturalUpload(q, nowElapsedMs, parked)
    }

    /** Forecast-only compact record; names, route geometry and coordinates are absent by construction. */
    @Synchronized
    fun onForecast(forecast: VehicleEnergyForecast?) {
        if (!consent || forecast == null) return
        val id = activeId ?: return
        val q = queue ?: return
        val stop = forecast.energyAtNextStop
        val line = buildJsonObject {
            put("schema", 1)
            put("type", "forecast")
            put("wallMs", System.currentTimeMillis())
            put("elapsedMs", forecast.receivedAtElapsedMs)
            stop?.arrivalBatteryEnergyWh?.let { put("forecastWh", it) }
            stop?.distanceMeters?.let { put("forecastDistanceM", it) }
            put("forecastQuality", forecast.forecastQuality)
        }.toString()
        runCatching { q.append(id, activeStartedMs, line) }
            .onFailure { DiagnosticLog.w("ev_contribution", "forecastRejected=${it.javaClass.simpleName}") }
    }

    private fun attemptNaturalUpload(q: EvContributionQueue, nowElapsedMs: Long, parked: Boolean) {
        val context = appContext ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val network = cm.activeNetwork ?: return
        val capabilities = cm.getNetworkCapabilities(network) ?: return
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val uploadContext = EvContributionPolicy.UploadContext(
            validatedInternet = validated,
            parked = parked,
            idle = activeId == null,
            startupSensitive = nowElapsedMs - processStartedElapsedMs < 60_000L,
        )
        if (!EvContributionPolicy.mayUpload(consent, uploadContext)) return
        val url = uploadUrl
        val secret = token
        if (!EvContributionPolicy.mayCapture(true, url, secret)) return
        if (!uploading.compareAndSet(false, true)) return
        scope.launch {
            try {
                val outcome = EvContributionUploader(q) { file, key -> send(url, secret, file, key) }
                    .uploadOldest(System.currentTimeMillis())
                val c = q.counters()
                DiagnosticLog.i(
                    "ev_contribution",
                    "uploadOutcome=$outcome retained=${c.retained} accepted=${c.accepted} failures=${c.failures} evicted=${c.evicted}",
                )
            } finally {
                uploading.set(false)
            }
        }
    }

    private fun send(url: String, secret: String, file: File, key: String): EvContributionUploader.Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-ndjson")
            setRequestProperty("X-Upload-Token", secret)
            setRequestProperty("X-Idempotency-Key", key)
            setFixedLengthStreamingMode(file.length())
        }
        return try {
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            val status = connection.responseCode
            val duplicate = connection.getHeaderField("X-Duplicate")?.equals("true", ignoreCase = true) == true || status == 409
            EvContributionUploader.Response(status, duplicate)
        } finally {
            connection.disconnect()
        }
    }

    fun deletePendingEvContributions(): Int = queue?.deletePending() ?: 0
}
