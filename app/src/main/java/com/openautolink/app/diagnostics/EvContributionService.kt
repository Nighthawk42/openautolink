package com.openautolink.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Process-scope, default-off compact EV capture and passive event-driven drain. */
object EvContributionService {
    data class Status(
        val consentValid: Boolean = false,
        val consentInvalidReason: String = "Not opted in",
        val retainedCount: Int = 0,
        val lastUploadOutcome: String = "None",
        val evictedCount: Long = 0,
        val quarantinedCount: Int = 0,
        val lastDeleteResult: String = "Not run",
    )

    private const val STARTUP_GRACE_MS = 60_000L
    private val initialized = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val generationFence = EvContributionGenerationFence()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTransport = AtomicReference<EvContributionHttpTransport?>()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var consentBinding: EvContributionConsentBinding? = null
    @Volatile private var uploadUrl = ""
    @Volatile private var token = ""
    @Volatile private var authFenced = false
    @Volatile private var queue: EvContributionQueue? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var activeId: String? = null
    @Volatile private var activeIdentity: EvVehicleCalibrationIdentity? = null
    @Volatile private var activeStartedMs = 0L
    @Volatile private var processStartedElapsedMs = 0L
    @Volatile private var validatedInternet = false
    @Volatile private var rawParked = false
    @Volatile private var rawIgnition: Int? = null
    @Volatile private var projectionState = SessionState.IDLE
    private var lastTickElapsedMs = 0L
    private var lastSpeedKmh = 0f
    private var distanceM = 0.0

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext
        appContext = app
        processStartedElapsedMs = SystemClock.elapsedRealtime()
        queue = EvContributionQueue(File(app.filesDir, "ev-contributions")).also {
            val recovered = it.recoverInterrupted(System.currentTimeMillis())
            it.enforceRetention(System.currentTimeMillis())
            if (recovered > 0) DiagnosticLog.i("ev_contribution", "recoveredInterrupted=$recovered")
            publishStatus(it)
        }
        installPassiveNetworkObserver(app)
        observeConsent(app)
        evaluateProcessStartNetwork(app)
    }

    private fun observeConsent(context: Context) {
        val prefs = AppPreferences.getInstance(context)
        scope.launch {
            combine(
                prefs.evContributionConsent,
                prefs.evContributionBinding,
                prefs.logUploadUrl,
                prefs.logUploadToken,
                prefs.logUploadDeviceLabel,
            ) { enabled, encoded, url, secret, label -> ConsentConfig(enabled, encoded, url, secret, label) }
                .collect { config ->
                    cancelInFlight("consent-or-endpoint-change")
                    uploadUrl = config.url
                    token = config.token
                    val decoded = EvContributionConsentBinding.decode(config.encoded)
                    val valid = config.enabled && decoded?.matches(config.url, config.token) == true
                    consentBinding = decoded.takeIf { valid }
                    authFenced = config.enabled && !valid
                    if (!valid) {
                        activeId = null
                        activeIdentity = null
                        resetDriveContinuity()
                        val deleted = queue?.deleteAllArtifacts()
                        _status.value = _status.value.copy(
                            consentValid = false,
                            consentInvalidReason = when {
                                !config.enabled -> "Not opted in"
                                decoded == null -> "Consent binding is missing or obsolete"
                                else -> "Endpoint or credential changed; opt in again"
                            },
                            lastDeleteResult = deleted?.display() ?: _status.value.lastDeleteResult,
                        )
                        deleted?.let { DiagnosticLog.i("ev_contribution", "revocationDelete=${it.display()}") }
                    } else {
                        authFenced = false
                        _status.value = _status.value.copy(consentValid = true, consentInvalidReason = "")
                        DiagnosticLog.i("ev_contribution", "consent=on schema=2 compactEvOnly=true")
                        attemptNaturalDrain("consent-valid")
                    }
                    queue?.let(::publishStatus)
                }
        }
    }

    /** Passive default-network callback: no requestNetwork, bind, alarm, polling, or wakelock. */
    private fun installPassiveNetworkObserver(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val valid = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val becameValid = valid && !validatedInternet
                validatedInternet = valid
                if (becameValid) attemptNaturalDrain("validated-network")
                if (!valid) cancelInFlight("network-unvalidated")
            }

            override fun onLost(network: Network) {
                validatedInternet = false
                cancelInFlight("network-lost")
            }
        })
    }

    private fun evaluateProcessStartNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val capabilities = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        validatedInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        attemptNaturalDrain("process-start")
    }

    /** Called for every raw VHAL batch, independently of projection and tuning. */
    @Synchronized
    fun onVehicle(data: ControlMessage.VehicleData, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        rawIgnition = data.ignitionState
        val parked = data.gearRaw == 4 || data.ignitionState == 1 || data.ignitionState == 2
        rawParked = parked
        if (!parked && uploading.get()) cancelInFlight("vehicle-moving")

        val q = queue ?: return
        val binding = consentBinding
        if (binding == null || !EvContributionPolicy.mayCapture(binding, uploadUrl, token) || authFenced) return
        if (!parked) {
            val identity = EvVehicleCalibrationIdentity.from(data) ?: return
            if (activeId != null && activeIdentity != identity) {
                q.close(activeId!!, System.currentTimeMillis())
                activeId = null
                resetDriveContinuity()
            }
            val id = activeId ?: UUID.randomUUID().toString().also {
                activeId = it; activeIdentity = identity; activeStartedMs = System.currentTimeMillis()
            }
            val speed = data.speedKmh?.takeIf(Float::isFinite)
            if (lastTickElapsedMs > 0L && nowElapsedMs > lastTickElapsedMs && nowElapsedMs - lastTickElapsedMs <= 15 * 60_000L && speed != null) {
                distanceM += ((lastSpeedKmh + speed) / 2.0) * ((nowElapsedMs - lastTickElapsedMs) / 3_600.0)
            }
            lastTickElapsedMs = nowElapsedMs
            if (speed != null) lastSpeedKmh = speed
            val line = buildJsonObject {
                put("schema", 2); put("type", "vehicle"); put("elapsedBucketS", nowElapsedMs / 60_000 * 60)
                data.evBatteryLevelWh?.takeIf(Float::isFinite)?.let { put("batteryWh", it) }
                data.batteryPct?.let { put("batteryPct", it) }
                data.rangeKm?.takeIf(Float::isFinite)?.let { put("rangeKm", it) }
                speed?.let { put("speedKmh", it) }
                put("distanceM", distanceM.toLong())
                put("capacityBandKwh", identity.capacityBandKwh)
                data.evChargeState?.let { put("chargeState", it) }
                data.evChargeRateW?.takeIf(Float::isFinite)?.let { put("chargeRateW", it) }
                data.chargePortConnected?.let { put("chargePortConnected", it) }
                data.gearRaw?.let { put("gearRaw", it) }
                data.ignitionState?.let { put("ignition", it) }
                put("energyBasis", "absolute-wh"); put("estimatorRevision", "rev2")
                put("vehicleClass", identity.pseudonymousLabel.removePrefix("ev-class-"))
            }.toString()
            runCatching { q.append(id, activeStartedMs, line, binding.tokenFingerprint, identity.pseudonymousLabel) }
                .onFailure {
                    activeId = null; activeIdentity = null; resetDriveContinuity()
                    DiagnosticLog.w("ev_contribution", "captureRejected=${it.javaClass.simpleName}")
                }
            publishStatus(q)
            return
        }
        activeId?.let { id ->
            q.close(id, System.currentTimeMillis())
            activeId = null; activeIdentity = null; resetDriveContinuity()
            DiagnosticLog.i("ev_contribution", "closed retained=${q.counters().retained}")
        }
        publishStatus(q)
        attemptNaturalDrain("parked-tick")
    }

    @Synchronized
    fun onForecast(forecast: VehicleEnergyForecast?) {
        val binding = consentBinding ?: return
        if (forecast == null || !binding.matches(uploadUrl, token)) return
        val id = activeId ?: return
        val identity = activeIdentity ?: return
        val q = queue ?: return
        val line = buildJsonObject {
            put("schema", 2); put("type", "forecast"); put("elapsedBucketS", forecast.receivedAtElapsedMs / 60_000 * 60)
            forecast.energyAtNextStop?.arrivalBatteryEnergyWh?.let { put("forecastWh", it) }
            forecast.energyAtNextStop?.distanceMeters?.let { put("forecastDistanceM", it) }
            put("forecastQuality", forecast.forecastQuality)
            put("vehicleClass", identity.pseudonymousLabel.removePrefix("ev-class-"))
        }.toString()
        runCatching { q.append(id, activeStartedMs, line, binding.tokenFingerprint, identity.pseudonymousLabel) }
            .onFailure { DiagnosticLog.w("ev_contribution", "forecastRejected=${it.javaClass.simpleName}") }
    }

    fun onProjectionStateChanged(state: SessionState) {
        projectionState = state
        if (state != SessionState.IDLE) cancelInFlight("projection-$state")
        else attemptNaturalDrain("projection-idle")
    }

    private fun attemptNaturalDrain(trigger: String) {
        val q = queue ?: return
        val binding = consentBinding ?: return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val state = projectionState
        val uploadContext = EvContributionPolicy.UploadContext(
            validatedInternet = validatedInternet,
            parked = rawParked,
            idle = activeId == null,
            startupSensitive = nowElapsedMs - processStartedElapsedMs < STARTUP_GRACE_MS,
            projectionActive = state != SessionState.IDLE,
            reconnecting = state == SessionState.CONNECTING,
        )
        if (!EvContributionPolicy.mayUpload(true, uploadContext) || authFenced || !binding.matches(uploadUrl, token)) return
        val url = uploadUrl
        val secret = token
        val namespace = binding.tokenFingerprint
        val attemptGeneration = generationFence.snapshot()
        if (!uploading.compareAndSet(false, true)) return
        scope.launch {
            val transport = EvContributionHttpTransport()
            activeTransport.set(transport)
            try {
                val outcome = EvContributionDrainLoop(q) { queueForAttempt ->
                    EvContributionUploader(
                        queueForAttempt,
                        onUnauthorized = {
                            authFenced = true
                            _status.value = _status.value.copy(
                                consentValid = false,
                                consentInvalidReason = "Server rejected the credential; opt in again",
                            )
                            cancelInFlight("unauthorized")
                            scope.launch { AppPreferences.getInstance(appContext ?: return@launch).setEvContributionConsent(false) }
                        },
                        mayMutate = { generationFence.isCurrent(attemptGeneration) },
                    ) { file, _ ->
                        if (!generationFence.isCurrent(attemptGeneration)) return@EvContributionUploader EvContributionUploader.Response(0, "")
                        val label = q.pending().firstOrNull { it.file == file }?.vehicleLabel ?: "ev-class-unknown"
                        transport.send(url, secret, label, file)
                    }
                }.drainEligible(System.currentTimeMillis(), namespace)
                _status.value = _status.value.copy(lastUploadOutcome = "$outcome ($trigger)")
                DiagnosticLog.i("ev_contribution", "uploadOutcome=$outcome trigger=$trigger")
            } finally {
                activeTransport.compareAndSet(transport, null)
                uploading.set(false)
                publishStatus(q)
            }
        }
    }

    private fun cancelInFlight(reason: String) {
        generationFence.invalidate()
        activeTransport.getAndSet(null)?.cancel()
        DiagnosticLog.d("ev_contribution", "cancelled=$reason")
    }

    fun deletePendingEvContributions(): EvContributionQueue.DeleteResult {
        cancelInFlight("delete")
        activeId = null; activeIdentity = null; resetDriveContinuity()
        val result = queue?.deleteAllArtifacts() ?: EvContributionQueue.DeleteResult(0, 0, 0)
        _status.value = _status.value.copy(lastDeleteResult = result.display())
        queue?.let(::publishStatus)
        return result
    }

    fun reportConsentInvalid(reason: String) {
        _status.value = _status.value.copy(consentValid = false, consentInvalidReason = reason)
    }

    private fun publishStatus(queue: EvContributionQueue) {
        val counters = queue.counters()
        _status.value = _status.value.copy(
            retainedCount = counters.retained,
            evictedCount = counters.evicted,
            quarantinedCount = counters.quarantined,
        )
    }

    private fun resetDriveContinuity() { lastTickElapsedMs = 0; lastSpeedKmh = 0f; distanceM = 0.0 }
    private fun EvContributionQueue.DeleteResult.display() = "files=$files bytes=$bytes failures=$failures"
    // label is intentionally never transmitted; retaining it in the observed
    // configuration ensures even a legacy label edit cancels exact in-flight I/O.
    private data class ConsentConfig(
        val enabled: Boolean,
        val encoded: String,
        val url: String,
        val token: String,
        @Suppress("unused") val legacyLabel: String,
    )
}

internal class EvContributionHttpTransport(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val active = AtomicReference<HttpURLConnection?>()

    fun cancel() {
        active.getAndSet(null)?.disconnect()
    }

    fun send(url: String, secret: String, label: String, file: File): EvContributionUploader.Response {
        require(file.isFile && file.length() > 0)
        val connection = openConnection(URL(url)).apply {
            requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/zip"); setRequestProperty("X-Upload-Token", secret)
            setRequestProperty("X-Device-Label", label.take(120))
            setRequestProperty("X-Orig-Name", file.name); setFixedLengthStreamingMode(file.length())
        }
        check(active.compareAndSet(null, connection)) { "transport already active" }
        return try {
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(64 * 1024) }.orEmpty()
            EvContributionUploader.Response(status, body)
        } finally {
            active.compareAndSet(connection, null)
            connection.disconnect()
        }
    }
}
