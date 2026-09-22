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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private const val LOCAL_SAFETY_PREFS = "ev-contribution-safety"
    private const val LAST_SAFE_PARKED_MS = "last-safe-parked-ms"
    private val initialized = AtomicBoolean(false)
    private val lifecycle = EvContributionLifecycleGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTransport = AtomicReference<EvContributionHttpTransport?>()
    private val activeUploadJob = AtomicReference<Job?>()
    private val reevaluation = EarliestOneShotDeadline(SystemClock::elapsedRealtime) { delayMs, task ->
        val job = scope.launch { delay(delayMs); task() }
        EarliestOneShotDeadline.Cancellable { job.cancel() }
    }
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var consentBinding: EvContributionConsentBinding? = null
    @Volatile private var uploadUrl = ""
    @Volatile private var token = ""
    @Volatile private var authFenced = false
    @Volatile private var deletionFailureBlocked = false
    @Volatile private var queue: EvContributionQueue? = null
    @Volatile private var appContext: Context? = null
    private val driveIdentityOwner = EvContributionDriveIdentityOwner { UUID.randomUUID().toString() }
    private val processGeneration = UUID.randomUUID().toString()
    private val freshParkGate = EvFreshParkGate()
    @Volatile private var activeId: String? = null
    @Volatile private var activeIdentity: EvVehicleCalibrationIdentity? = null
    @Volatile private var activeStartedMs = 0L
    @Volatile private var processStartedElapsedMs = 0L
    @Volatile private var validatedInternet = false
    private val networkTracker = ValidatedDefaultNetworkTracker<Network>()
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
        freshParkGate.observe(null, null)
        rawParked = false
        rawIgnition = null
        DiagnosticLog.d("ev_contribution", "processGeneration=${processGeneration.take(8)} freshParkObservedThisProcess=false")
        queue = EvContributionQueue(File(app.filesDir, "ev-contributions")).also {
            val recovered = runCatching { it.recoverInterrupted(System.currentTimeMillis()) }
                .onFailure { error -> DiagnosticLog.w("ev_contribution", "recoveryFailed=${error.javaClass.simpleName}") }
                .getOrDefault(0)
            runCatching { it.enforceRetention(System.currentTimeMillis()) }
                .onFailure { error -> DiagnosticLog.w("ev_contribution", "retentionFailed=${error.javaClass.simpleName}") }
            if (recovered > 0) DiagnosticLog.i("ev_contribution", "recoveredInterrupted=$recovered")
            runCatching { publishStatus(it) }
                .onFailure { error -> DiagnosticLog.w("ev_contribution", "statusRecoveryFailed=${error.javaClass.simpleName}") }
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
                    val decoded = EvContributionConsentBinding.decode(config.encoded)
                    val valid = config.enabled && decoded?.matches(config.url, config.token) == true
                    lifecycle.invalidate(blockAdmissions = true) {
                        uploadUrl = config.url
                        token = config.token
                        consentBinding = decoded.takeIf { valid }
                        authFenced = config.enabled && !valid
                        if (!valid) {
                            driveIdentityOwner.clear()
                            activeId = null
                            activeIdentity = null
                            resetDriveContinuity()
                        }
                    }
                    cancelTransport("consent-or-endpoint-change")
                    activeUploadJob.get()?.join()
                    if (!valid) {
                        val deleted = lifecycle.exclusive { queue?.deleteAllArtifacts() }
                        val retained = queue?.storageUsage() ?: EvContributionQueue.StorageUsage(0, 0)
                        deletionFailureBlocked = deleted != null && !EvDeleteAdmissionPolicy.mayResume(deleted, retained)
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
                        if (!deletionFailureBlocked) lifecycle.resumeAdmissions()
                        _status.value = _status.value.copy(consentValid = true, consentInvalidReason = "")
                        DiagnosticLog.i("ev_contribution", "consent=on schema=2 compactEvOnly=true")
                        if (!deletionFailureBlocked) attemptNaturalDrain("consent-valid")
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
                when (networkTracker.capabilities(network, valid)) {
                    ValidatedDefaultNetworkTracker.Change.REPLACED -> {
                        lifecycle.invalidate { validatedInternet = true }
                        cancelTransport("network-replaced")
                        attemptNaturalDrain("validated-network")
                    }
                    ValidatedDefaultNetworkTracker.Change.UNVALIDATED -> {
                        lifecycle.invalidate { validatedInternet = false }
                        cancelTransport("network-unvalidated")
                    }
                    ValidatedDefaultNetworkTracker.Change.IGNORED -> {
                        if (valid) attemptNaturalDrain("network-capabilities")
                    }
                    ValidatedDefaultNetworkTracker.Change.LOST -> Unit
                }
            }

            override fun onLost(network: Network) {
                if (networkTracker.lost(network) != ValidatedDefaultNetworkTracker.Change.LOST) return
                lifecycle.invalidate { validatedInternet = false }
                cancelTransport("network-lost")
            }
        })
    }

    private fun evaluateProcessStartNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val active = cm.activeNetwork
        val capabilities = active?.let(cm::getNetworkCapabilities)
        validatedInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (active != null && validatedInternet) networkTracker.capabilities(active, true)
        attemptNaturalDrain("process-start")
    }

    /** Called for every raw VHAL batch, independently of projection and tuning. */
    @Synchronized
    fun onVehicle(data: ControlMessage.VehicleData, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val parked = freshParkGate.observe(data.gearRaw, data.ignitionState)
        if (!parked) {
            lifecycle.invalidate {
                rawIgnition = data.ignitionState
                rawParked = false
            }
            appContext?.getSharedPreferences(LOCAL_SAFETY_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.remove(LAST_SAFE_PARKED_MS)?.commit()
            cancelTransport("vehicle-moving")
        } else {
            rawIgnition = data.ignitionState
            rawParked = true
            appContext?.getSharedPreferences(LOCAL_SAFETY_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putLong(LAST_SAFE_PARKED_MS, System.currentTimeMillis())?.commit()
        }

        val q = queue ?: return
        val binding = consentBinding
        val captureLease = lifecycle.admitCapture {
            binding != null && EvContributionPolicy.mayCapture(binding, uploadUrl, token) && !authFenced && !deletionFailureBlocked
        } ?: return
        if (!parked) {
            val identity = EvVehicleCalibrationIdentity.from(data) ?: return
            val previousId = activeId
            val transition = lifecycle.withCurrent(captureLease) {
                driveIdentityOwner.observe(identity).also { change ->
                    change.closeId?.let { closing -> q.close(closing, System.currentTimeMillis()) }
                }
            } ?: return
            if (transition.closeId != null) resetDriveContinuity()
            val id = transition.activeId
            activeId = id
            activeIdentity = identity
            if (previousId != id) activeStartedMs = System.currentTimeMillis()
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
            runCatching {
                checkNotNull(lifecycle.withCurrent(captureLease) {
                    q.append(id, activeStartedMs, line, binding!!.tokenFingerprint, identity.pseudonymousLabel)
                }) { "capture generation invalidated" }
            }
                .onFailure {
                    driveIdentityOwner.clear()
                    activeId = null; activeIdentity = null; resetDriveContinuity()
                    DiagnosticLog.w("ev_contribution", "captureRejected=${it.javaClass.simpleName}")
                }
            publishStatus(q)
            return
        }
        activeId?.let { id ->
            lifecycle.withCurrent(captureLease) { q.close(id, System.currentTimeMillis()) } ?: return
            driveIdentityOwner.close()
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
        val captureLease = lifecycle.admitCapture { consentBinding == binding && !authFenced } ?: return
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
        runCatching {
            checkNotNull(lifecycle.withCurrent(captureLease) {
                q.append(id, activeStartedMs, line, binding.tokenFingerprint, identity.pseudonymousLabel)
            }) { "capture generation invalidated" }
        }
            .onFailure { DiagnosticLog.w("ev_contribution", "forecastRejected=${it.javaClass.simpleName}") }
    }

    fun onProjectionStateChanged(state: SessionState) {
        if (state != SessionState.IDLE) {
            lifecycle.invalidate { projectionState = state }
            cancelTransport("projection-$state")
        } else {
            projectionState = state
            attemptNaturalDrain("projection-idle")
        }
    }

    private fun attemptNaturalDrain(trigger: String) {
        val q = queue ?: return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val graceRemaining = STARTUP_GRACE_MS - (nowElapsedMs - processStartedElapsedMs)
        if (graceRemaining > 0) {
            scheduleReevaluation(graceRemaining, "startup-grace")
            return
        }
        val nextRetryMs = q.pending().firstOrNull()?.nextAttemptMs ?: 0L
        val retryRemaining = nextRetryMs - System.currentTimeMillis()
        if (retryRemaining > 0) {
            scheduleReevaluation(retryRemaining, "retry-backoff")
            return
        }
        val uploadLease = lifecycle.admitUpload {
            val binding = consentBinding
            val state = projectionState
            val uploadContext = EvContributionPolicy.UploadContext(
                validatedInternet = validatedInternet && networkTracker.current() != null,
                parked = rawParked,
                idle = activeId == null,
                startupSensitive = SystemClock.elapsedRealtime() - processStartedElapsedMs < STARTUP_GRACE_MS,
                projectionActive = state != SessionState.IDLE,
                reconnecting = state == SessionState.CONNECTING,
                freshParkObservedThisProcess = freshParkGate.freshParkObservedThisProcess,
            )
            binding != null && !authFenced && !deletionFailureBlocked && binding.matches(uploadUrl, token) &&
                EvContributionPolicy.mayUpload(true, uploadContext)
        } ?: return
        val binding = consentBinding ?: run { lifecycle.releaseUpload(uploadLease); return }
        val url = uploadUrl
        val secret = token
        val namespace = binding.tokenFingerprint
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = kotlin.coroutines.coroutineContext[Job]
            val transport = EvContributionHttpTransport()
            if (!lifecycle.isCurrent(uploadLease)) {
                lifecycle.releaseUpload(uploadLease)
                return@launch
            }
            activeTransport.set(transport)
            try {
                val outcome = EvContributionDrainLoop(q) { queueForAttempt ->
                    EvContributionUploader(
                        queueForAttempt,
                        onUnauthorized = {
                            lifecycle.invalidate(blockAdmissions = true) { authFenced = true }
                            _status.value = _status.value.copy(
                                consentValid = false,
                                consentInvalidReason = "Server rejected the credential; opt in again",
                            )
                            cancelTransport("unauthorized")
                            scope.launch { AppPreferences.getInstance(appContext ?: return@launch).setEvContributionConsent(false) }
                        },
                        mayMutate = { lifecycle.isCurrent(uploadLease) },
                        mutateIfCurrent = { action -> lifecycle.withCurrent(uploadLease, action) },
                    ) { file, _ ->
                        if (!lifecycle.isCurrent(uploadLease)) return@EvContributionUploader EvContributionUploader.Response(0, "")
                        val label = q.pending().firstOrNull { it.file == file }?.vehicleLabel ?: "ev-class-unknown"
                        transport.send(url, secret, label, file) { lifecycle.isCurrent(uploadLease) }
                    }
                }.drainEligible(System.currentTimeMillis(), namespace)
                if (lifecycle.isCurrent(uploadLease)) {
                    _status.value = _status.value.copy(lastUploadOutcome = "$outcome ($trigger)")
                    DiagnosticLog.i("ev_contribution", "uploadOutcome=$outcome trigger=$trigger")
                    if (outcome == EvContributionUploader.Outcome.BACKOFF || outcome == EvContributionUploader.Outcome.RETRY) {
                        val delayMs = (q.pending().firstOrNull()?.nextAttemptMs ?: 0L) - System.currentTimeMillis()
                        if (delayMs > 0) scheduleReevaluation(delayMs, "retry-backoff")
                    }
                }
            } finally {
                activeTransport.compareAndSet(transport, null)
                if (self != null) activeUploadJob.compareAndSet(self, null)
                lifecycle.releaseUpload(uploadLease)
                publishStatus(q)
            }
        }
        check(activeUploadJob.compareAndSet(null, job)) { "upload worker ownership already held" }
        job.start()
    }

    private fun scheduleReevaluation(delayMs: Long, trigger: String) {
        if (delayMs <= 0) { attemptNaturalDrain(trigger); return }
        val token = lifecycle.snapshot()
        reevaluation.schedule(delayMs, { lifecycle.isCurrent(token) }) { attemptNaturalDrain(trigger) }
    }

    private fun cancelTransport(reason: String) {
        reevaluation.cancel()
        activeTransport.getAndSet(null)?.cancel()
        DiagnosticLog.d("ev_contribution", "cancelled=$reason")
    }

    suspend fun deletePendingEvContributions(): EvContributionQueue.DeleteResult {
        lifecycle.invalidate(blockAdmissions = true) {
            driveIdentityOwner.clear()
            activeId = null
            activeIdentity = null
            resetDriveContinuity()
        }
        cancelTransport("delete")
        activeUploadJob.get()?.join()
        val result = lifecycle.exclusive {
            queue?.deleteAllArtifacts() ?: EvContributionQueue.DeleteResult(0, 0, 0)
        }
        val usage = queue?.storageUsage() ?: EvContributionQueue.StorageUsage(0, 0)
        deletionFailureBlocked = !EvDeleteAdmissionPolicy.mayResume(result, usage)
        if (!deletionFailureBlocked &&
            consentBinding?.matches(uploadUrl, token) == true && !authFenced
        ) lifecycle.resumeAdmissions()
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
    private val activeBody = AtomicReference<java.io.OutputStream?>()

    fun cancel() {
        activeBody.getAndSet(null)?.let { runCatching { it.close() } }
        active.getAndSet(null)?.disconnect()
    }

    fun send(
        url: String,
        secret: String,
        label: String,
        file: File,
        mayContinue: () -> Boolean = { true },
    ): EvContributionUploader.Response {
        require(file.isFile && file.length() > 0)
        check(mayContinue()) { "upload generation is stale" }
        val connection = openConnection(URL(url)).apply {
            requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/zip"); setRequestProperty("X-Upload-Token", secret)
            setRequestProperty("X-Device-Label", label.take(120))
            setRequestProperty("X-Orig-Name", "ev-contribution.zip"); setFixedLengthStreamingMode(file.length())
        }
        check(mayContinue()) { "upload generation is stale" }
        check(active.compareAndSet(null, connection)) { "transport already active" }
        return try {
            check(mayContinue()) { "upload generation is stale" }
            val output = connection.outputStream
            check(activeBody.compareAndSet(null, output)) { "request body already active" }
            if (active.get() !== connection || !mayContinue()) {
                activeBody.compareAndSet(output, null)
                runCatching { output.close() }
                throw java.io.InterruptedIOException("upload cancelled before body")
            }
            try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        check(mayContinue()) { "upload generation is stale" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    check(mayContinue()) { "upload generation is stale" }
                }
            } finally {
                activeBody.compareAndSet(output, null)
                runCatching { output.close() }
            }
            check(mayContinue()) { "upload generation is stale" }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(64 * 1024) }.orEmpty()
            check(mayContinue()) { "upload generation is stale" }
            EvContributionUploader.Response(status, body)
        } finally {
            activeBody.getAndSet(null)?.let { runCatching { it.close() } }
            active.compareAndSet(connection, null)
            connection.disconnect()
        }
    }
}
