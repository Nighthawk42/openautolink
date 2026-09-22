package com.openautolink.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean


/** Process-scope, default-off compact EV capture. Network work is natural parked-tick driven. */
object EvContributionService {
    private val initialized = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val generationFence = EvContributionGenerationFence()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var consentBinding: EvContributionConsentBinding? = null
    @Volatile private var uploadUrl = ""
    @Volatile private var token = ""
    @Volatile private var deviceLabel = ""
    @Volatile private var authFenced = false
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
            it.enforceRetention(System.currentTimeMillis())
            if (recovered > 0) DiagnosticLog.i("ev_contribution", "recoveredInterrupted=$recovered")
        }
        val prefs = AppPreferences.getInstance(context)
        scope.launch {
            combine(
                prefs.evContributionConsent,
                prefs.evContributionBinding,
                prefs.logUploadUrl,
                prefs.logUploadToken,
                prefs.logUploadDeviceLabel,
            ) { enabled, encoded, url, secret, label -> arrayOf(enabled, encoded, url, secret, label) }
                .collect { values ->
                    val enabled = values[0] as Boolean
                    val encoded = values[1] as String
                    val url = values[2] as String
                    val secret = values[3] as String
                    val label = values[4] as String
                    uploadUrl = url; token = secret; deviceLabel = label
                    val binding = EvContributionConsentBinding.decode(encoded)
                    val valid = enabled && binding?.matches(url, secret) == true
                    consentBinding = binding.takeIf { valid }
                    generationFence.invalidate()
                    if (enabled && !valid) {
                        authFenced = true
                        prefs.setEvContributionConsent(false)
                        DiagnosticLog.w("ev_contribution", "consent revoked: endpoint or credential binding changed")
                    } else if (valid) {
                        authFenced = false
                        DiagnosticLog.i("ev_contribution", "consent=on schema=2 compactEvOnly=true")
                    } else {
                        activeId = null
                        resetDriveContinuity()
                        DiagnosticLog.i("ev_contribution", "consent=off pendingPreserved=true")
                    }
                }
        }
    }

    /** Called for every raw VHAL tick, independently of projection and manual logging. */
    @Synchronized
    fun onVehicle(vd: ControlMessage.VehicleData, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val q = queue ?: return
        val binding = consentBinding ?: return
        if (!EvContributionPolicy.mayCapture(binding, uploadUrl, token) || authFenced) return
        val parked = vd.gearRaw == 4 || vd.ignitionState == 1 || vd.ignitionState == 2
        if (!parked) {
            val id = activeId ?: UUID.randomUUID().toString().also { activeId = it; activeStartedMs = System.currentTimeMillis() }
            val namespace = sha256("${binding.tokenFingerprint}|$deviceLabel".toByteArray()).take(32)
            val capacity = vd.evBatteryCapacityWh?.takeIf { it.isFinite() && it > 0f }
            val capacityBand = capacity?.let { (it.toInt() / 5_000) * 5 }
            val speed = vd.speedKmh?.takeIf(Float::isFinite)
            if (lastTickElapsedMs > 0L && nowElapsedMs > lastTickElapsedMs && nowElapsedMs - lastTickElapsedMs <= 15 * 60_000L && speed != null) {
                distanceM += ((lastSpeedKmh + speed) / 2.0) * ((nowElapsedMs - lastTickElapsedMs) / 3_600.0)
            }
            lastTickElapsedMs = nowElapsedMs
            if (speed != null) lastSpeedKmh = speed
            val line = buildJsonObject {
                put("schema", 2); put("type", "vehicle"); put("elapsedBucketS", nowElapsedMs / 60_000 * 60)
                vd.evBatteryLevelWh?.takeIf(Float::isFinite)?.let { put("batteryWh", it) }
                vd.batteryPct?.let { put("batteryPct", it) }; vd.rangeKm?.takeIf(Float::isFinite)?.let { put("rangeKm", it) }
                speed?.let { put("speedKmh", it) }; put("distanceM", distanceM.toLong())
                capacityBand?.let { put("capacityBandKwh", it) }; vd.evChargeState?.let { put("chargeState", it) }
                vd.evChargeRateW?.takeIf(Float::isFinite)?.let { put("chargeRateW", it) }
                vd.chargePortConnected?.let { put("chargePortConnected", it) }; vd.gearRaw?.let { put("gearRaw", it) }
                vd.ignitionState?.let { put("ignition", it) }; put("energyBasis", "absolute-wh"); put("estimatorRevision", "rev2")
            }.toString()
            runCatching { q.append(id, activeStartedMs, line, namespace) }
                .onFailure { DiagnosticLog.w("ev_contribution", "captureRejected=${it.javaClass.simpleName}") }
            return
        }
        activeId?.let { id ->
            q.close(id, System.currentTimeMillis()); activeId = null; resetDriveContinuity()
            DiagnosticLog.i("ev_contribution", "closed retained=${q.counters().retained}")
        }
        attemptNaturalUpload(q, nowElapsedMs, parked)
    }

    @Synchronized
    fun onForecast(forecast: VehicleEnergyForecast?) {
        val binding = consentBinding ?: return
        if (forecast == null || !binding.matches(uploadUrl, token)) return
        val id = activeId ?: return; val q = queue ?: return
        val line = buildJsonObject {
            put("schema", 2); put("type", "forecast"); put("elapsedBucketS", forecast.receivedAtElapsedMs / 60_000 * 60)
            forecast.energyAtNextStop?.arrivalBatteryEnergyWh?.let { put("forecastWh", it) }
            forecast.energyAtNextStop?.distanceMeters?.let { put("forecastDistanceM", it) }
            put("forecastQuality", forecast.forecastQuality)
        }.toString()
        runCatching { q.append(id, activeStartedMs, line, sha256("${binding.tokenFingerprint}|$deviceLabel".toByteArray()).take(32)) }
            .onFailure { DiagnosticLog.w("ev_contribution", "forecastRejected=${it.javaClass.simpleName}") }
    }

    private fun attemptNaturalUpload(q: EvContributionQueue, nowElapsedMs: Long, parked: Boolean) {
        val context = appContext ?: return; val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val network = cm.activeNetwork ?: return; val capabilities = cm.getNetworkCapabilities(network) ?: return
        val state = com.openautolink.app.session.SessionManager.instanceOrNull()?.sessionState?.value ?: SessionState.IDLE
        val uploadContext = EvContributionPolicy.UploadContext(
            validatedInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            parked = parked, idle = activeId == null, startupSensitive = nowElapsedMs - processStartedElapsedMs < 60_000L,
            projectionActive = state != SessionState.IDLE, reconnecting = state == SessionState.CONNECTING,
        )
        val binding = consentBinding ?: return
        if (!EvContributionPolicy.mayUpload(true, uploadContext) || authFenced || !binding.matches(uploadUrl, token)) return
        val url = uploadUrl; val secret = token; val label = deviceLabel; val attemptGeneration = generationFence.snapshot()
        if (!uploading.compareAndSet(false, true)) return
        scope.launch {
            try {
                val outcome = EvContributionUploader(q, onUnauthorized = {
                    authFenced = true; generationFence.invalidate(); scope.launch { AppPreferences.getInstance(context).setEvContributionConsent(false) }
                }) { file, _ ->
                    if (!generationFence.isCurrent(attemptGeneration)) EvContributionUploader.Response(0, "")
                    else send(url, secret, label, file).takeIf { generationFence.isCurrent(attemptGeneration) } ?: EvContributionUploader.Response(0, "")
                }.uploadOldest(System.currentTimeMillis())
                val c = q.counters()
                DiagnosticLog.i("ev_contribution", "uploadOutcome=$outcome retained=${c.retained} accepted=${c.accepted} failures=${c.failures} evicted=${c.evicted} quarantined=${c.quarantined}")
            } finally { uploading.set(false) }
        }
    }

    internal fun send(url: String, secret: String, label: String, file: File): EvContributionUploader.Response =
        EvContributionHttpTransport().send(url, secret, label, file)

    private fun resetDriveContinuity() { lastTickElapsedMs = 0; lastSpeedKmh = 0f; distanceM = 0.0 }
    fun deletePendingEvContributions(): Int = queue?.deletePending() ?: 0
}

internal class EvContributionHttpTransport(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun send(url: String, secret: String, label: String, file: File): EvContributionUploader.Response {
        require(file.isFile && file.length() > 0)
        val connection = openConnection(URL(url)).apply {
            requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/zip"); setRequestProperty("X-Upload-Token", secret)
            if (label.isNotBlank()) setRequestProperty("X-Device-Label", label.take(120))
            setRequestProperty("X-Orig-Name", file.name); setFixedLengthStreamingMode(file.length())
        }
        return try {
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(64 * 1024) }.orEmpty()
            EvContributionUploader.Response(status, body)
        } finally { connection.disconnect() }
    }
}
