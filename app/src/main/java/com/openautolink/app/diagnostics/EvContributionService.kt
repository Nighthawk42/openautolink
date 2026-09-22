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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val lifecycle = EvContributionLifecycleGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTransport = AtomicReference<EvContributionHttpTransport?>()
    private val activeUploadJob = EvUploadJobOwnership<Job>()
    private val destructiveMutex = Mutex()
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
        freshParkGate.clear()
        rawParked = false
        rawIgnition = null
        DiagnosticLog.d("ev_contribution", "processGeneration=${processGeneration.take(8)} freshParkObservedThisProcess=false")
        queue = EvContributionQueue(File(app.filesDir, "ev-contributions")).also {
            val deletionBlockedAtStartup = it.isDeletionBlocked()
            if (deletionBlockedAtStartup) {
                deletionFailureBlocked = true
                lifecycle.invalidate(blockAdmissions = true)
                DiagnosticLog.w("ev_contribution", "durable deletion block restored")
            }
            val recovered = if (deletionBlockedAtStartup) 0 else runCatching { it.recoverInterrupted(System.currentTimeMillis()) }
                .onFailure { error -> DiagnosticLog.w("ev_contribution", "recoveryFailed=${error.javaClass.simpleName}") }
                .getOrDefault(0)
            if (!deletionBlockedAtStartup) {
                runCatching { it.enforceRetention(System.currentTimeMillis()) }
                    .onFailure { error -> DiagnosticLog.w("ev_contribution", "retentionFailed=${error.javaClass.simpleName}") }
            }
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
                    destructiveMutex.withLock {
                        val decoded = EvContributionConsentBinding.decode(config.encoded)
                        val valid = config.enabled && decoded?.matches(config.url, config.token) == true
                        val deletionLease = lifecycle.beginDestructiveOperation {
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
                        var finished = false
                        try {
                            cancelTransport("consent-or-endpoint-change")
                            activeUploadJob.current()?.join()
                            val q = queue
                            val mustDelete = !valid || q?.isDeletionBlocked() == true
                            var deleted: EvContributionQueue.DeleteResult? = null
                            val deletionComplete = if (mustDelete && q != null) {
                                if (!q.beginDeletion()) {
                                    deleted = EvContributionQueue.DeleteResult(0, 0, 1)
                                    false
                                } else {
                                    deleted = lifecycle.exclusive { q.deleteAllArtifacts() }
                                    q.completeDeletion(checkNotNull(deleted))
                                }
                            } else !mustDelete
                            val currentValid = consentBinding?.matches(uploadUrl, token) == true && !authFenced
                            val resume = currentValid && deletionComplete && q?.isDeletionBlocked() != true
                            lifecycle.finishDestructiveOperation(deletionLease, resumeAdmissions = resume)
                            finished = true
                            deletionFailureBlocked = !deletionComplete || q?.isDeletionBlocked() == true
                            _status.value = _status.value.copy(
                                consentValid = currentValid,
                                consentInvalidReason = if (currentValid) "" else when {
                                    !config.enabled -> "Not opted in"
                                    decoded == null -> "Consent binding is missing or obsolete"
                                    else -> "Endpoint or credential changed; opt in again"
                                },
                                lastDeleteResult = deleted?.display() ?: _status.value.lastDeleteResult,
                            )
                            deleted?.let { DiagnosticLog.i("ev_contribution", "serializedDelete=${it.display()}") }
                            if (resume) {
                                DiagnosticLog.i("ev_contribution", "consent=on schema=2 compactEvOnly=true")
                                attemptNaturalDrain("consent-valid")
                            }
                            q?.let(::publishStatus)
                        } finally {
                            if (!finished) lifecycle.finishDestructiveOperation(deletionLease, resumeAdmissions = false)
                        }
                    }
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
        val parked = freshParkGate.observe(data, nowElapsedMs)
        if (!parked) {
            lifecycle.invalidate {
                rawIgnition = data.ignitionState
                rawParked = false
            }
            cancelTransport("vehicle-moving")
        } else {
            rawIgnition = data.ignitionState
            rawParked = true
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
        val captureLease = lifecycle.admitCapture {
            consentBinding == binding && !authFenced && !deletionFailureBlocked
        } ?: return
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
            val admissionNow = SystemClock.elapsedRealtime()
            val freshParkAuthorization = freshParkGate.authorization(admissionNow)
            val uploadContext = EvContributionPolicy.UploadContext(
                validatedInternet = validatedInternet && networkTracker.current() != null,
                parked = rawParked && freshParkAuthorization,
                idle = activeId == null,
                startupSensitive = admissionNow - processStartedElapsedMs < STARTUP_GRACE_MS,
                projectionActive = state != SessionState.IDLE,
                reconnecting = state == SessionState.CONNECTING,
                freshParkObservedThisProcess = freshParkAuthorization,
            )
            binding != null && !authFenced && !deletionFailureBlocked && binding.matches(uploadUrl, token) &&
                EvContributionPolicy.mayUpload(true, uploadContext)
        } ?: run {
            if (activeUploadJob.current() != null && !activeUploadJob.markDrainPending()) {
                scope.launch { attemptNaturalDrain("worker-finish-race") }
            }
            return
        }
        val binding = consentBinding ?: run { lifecycle.releaseUpload(uploadLease); return }
        val url = uploadUrl
        val secret = token
        val namespace = binding.tokenFingerprint
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = kotlin.coroutines.coroutineContext[Job]
            val transport = EvContributionHttpTransport()
            try {
                if (!lifecycle.isCurrent(uploadLease)) return@launch
                activeTransport.set(transport)
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
                        mayTransmit = {
                            lifecycle.isCurrent(uploadLease) &&
                                freshParkGate.authorization(SystemClock.elapsedRealtime())
                        },
                        mayMutate = {
                            lifecycle.isCurrent(uploadLease) &&
                                freshParkGate.authorization(SystemClock.elapsedRealtime())
                        },
                        mutateIfCurrent = { action ->
                            lifecycle.withCurrent(uploadLease) {
                                if (freshParkGate.authorization(SystemClock.elapsedRealtime())) action()
                                else EvContributionUploader.Outcome.CANCELLED
                            }
                        },
                    ) { file, _ ->
                        val transmissionAuthorized = lifecycle.isCurrent(uploadLease) &&
                            freshParkGate.authorization(SystemClock.elapsedRealtime())
                        if (!transmissionAuthorized) {
                            return@EvContributionUploader EvContributionUploader.Response(0, "")
                        }
                        val label = q.pending().firstOrNull { it.file == file }?.vehicleLabel ?: "ev-class-unknown"
                        transport.send(url, secret, label, file) {
                            lifecycle.isCurrent(uploadLease) &&
                                freshParkGate.authorization(SystemClock.elapsedRealtime())
                        }
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
                lifecycle.releaseUpload(uploadLease)
                publishStatus(q)
                if (self != null && activeUploadJob.finish(self)) {
                    scope.launch { attemptNaturalDrain("worker-finished-pending") }
                }
            }
        }
        if (!activeUploadJob.tryInstall(job)) {
            lifecycle.releaseUpload(uploadLease)
            if (!activeUploadJob.markDrainPending()) {
                scope.launch { attemptNaturalDrain("worker-install-race") }
            }
            return
        }
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

    suspend fun deletePendingEvContributions(): EvContributionQueue.DeleteResult = destructiveMutex.withLock {
        val deletionLease = lifecycle.beginDestructiveOperation {
            driveIdentityOwner.clear()
            activeId = null
            activeIdentity = null
            resetDriveContinuity()
        }
        var leaseFinished = false
        try {
            cancelTransport("delete")
            activeUploadJob.current()?.join()
            val q = queue
            val result = if (q == null) {
                EvContributionQueue.DeleteResult(0, 0, 0)
            } else if (!q.beginDeletion()) {
                EvContributionQueue.DeleteResult(0, 0, 1)
            } else {
                lifecycle.exclusive { q.deleteAllArtifacts() }
            }
            val deletionComplete = q?.let { result.failures == 0 && it.completeDeletion(result) } ?: true
            val verifiedResult = if (deletionComplete) result else result.copy(failures = result.failures.coerceAtLeast(1))
            val currentValid = consentBinding?.matches(uploadUrl, token) == true && !authFenced
            val resume = deletionComplete && currentValid && q?.isDeletionBlocked() != true
            lifecycle.finishDestructiveOperation(deletionLease, resumeAdmissions = resume)
            leaseFinished = true
            deletionFailureBlocked = !deletionComplete || q?.isDeletionBlocked() == true
            _status.value = _status.value.copy(lastDeleteResult = verifiedResult.display())
            q?.let(::publishStatus)
            verifiedResult
        } catch (failure: Throwable) {
            deletionFailureBlocked = true
            _status.value = _status.value.copy(lastDeleteResult = "files=0 bytes=0 failures=1")
            DiagnosticLog.w("ev_contribution", "deleteFailed=${failure.javaClass.simpleName}")
            EvContributionQueue.DeleteResult(0, 0, 1)
        } finally {
            if (!leaseFinished) lifecycle.finishDestructiveOperation(deletionLease, resumeAdmissions = false)
        }
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

internal object EvContributionDeadline {
    fun interface Cancellable { fun cancel() }
}

internal class EvContributionHttpTransport(
    private val attemptTimeoutMs: Long = 30_000L,
    private val monotonicNow: java.util.function.LongSupplier = java.util.function.LongSupplier { SystemClock.elapsedRealtime() },
    private val scheduleDeadline: (Long, () -> Unit) -> EvContributionDeadline.Cancellable = { delayMs, task ->
        val future = watchdog.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
        EvContributionDeadline.Cancellable { future.cancel(false) }
    },
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ev-upload-deadline").apply { isDaemon = true }
        }
    }

    private class Attempt(val owner: Thread) {
        val connection = AtomicReference<HttpURLConnection?>()
        val body = AtomicReference<java.io.OutputStream?>()
        val response = AtomicReference<InputStream?>()
        val cancelled = AtomicBoolean(false)

        fun bind(connection: HttpURLConnection) {
            check(this.connection.compareAndSet(null, connection)) { "attempt connection already bound" }
            if (cancelled.get()) {
                connection.disconnect()
                throw InterruptedIOException("upload cancelled during connection creation")
            }
        }

        fun cancel() {
            cancelled.set(true)
            connection.get()?.disconnect()
            owner.interrupt()
        }

        fun cleanup() {
            // Active cancellation must reach disconnect before a hostile stream
            // close can block. Only this attempt's immutable handles are touched.
            connection.get()?.disconnect()
            // disconnect owns cancellation cleanup. Never invoke an untrusted
            // stream close from the watchdog/cancel path or from worker teardown;
            // a close that ignores disconnect would otherwise pin deletion.
            body.set(null)
            response.set(null)
            connection.set(null)
        }
    }

    private val activeAttempt = AtomicReference<Attempt?>()

    fun cancel() {
        activeAttempt.get()?.cancel()
    }

    fun send(
        url: String,
        secret: String,
        label: String,
        file: File,
        mayContinue: () -> Boolean = { true },
    ): EvContributionUploader.Response {
        require(file.isFile && file.length() > 0)
        require(attemptTimeoutMs > 0)
        val deadline = monotonicNow.asLong + attemptTimeoutMs
        val attempt = Attempt(Thread.currentThread())
        check(activeAttempt.compareAndSet(null, attempt)) { "transport already active" }

        fun checkDeadline() {
            if (attempt.cancelled.get()) throw InterruptedIOException("EV upload attempt cancelled")
            if (monotonicNow.asLong >= deadline) throw java.net.SocketTimeoutException("EV upload attempt deadline exceeded")
            if (!mayContinue()) throw InterruptedIOException("upload generation is stale")
        }
        fun remainingTimeout(capMs: Long): Int {
            checkDeadline()
            return minOf(capMs, (deadline - monotonicNow.asLong).coerceAtLeast(1L)).toInt()
        }

        val deadlineTask = scheduleDeadline((deadline - monotonicNow.asLong).coerceAtLeast(0L)) {
            if (activeAttempt.get() === attempt) attempt.cancel()
        }
        return try {
            checkDeadline()
            val target = URL(url)
            checkDeadline()
            val connection = openConnection(target)
            attempt.bind(connection)
            checkDeadline()

            connection.requestMethod = "POST"
            checkDeadline()
            connection.doOutput = true
            checkDeadline()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = remainingTimeout(10_000L)
            connection.readTimeout = remainingTimeout(15_000L)
            connection.setRequestProperty("Content-Type", "application/zip")
            checkDeadline()
            connection.setRequestProperty("X-Upload-Token", secret)
            checkDeadline()
            connection.setRequestProperty("X-Device-Label", label.take(120))
            checkDeadline()
            connection.setRequestProperty("X-Orig-Name", "ev-contribution.zip")
            checkDeadline()
            connection.setFixedLengthStreamingMode(file.length())
            checkDeadline()

            val output = connection.outputStream
            check(attempt.body.compareAndSet(null, output)) { "request body already active" }
            checkDeadline()
            var bodyComplete = false
            try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkDeadline()
                        val count = input.read(buffer)
                        if (count < 0) break
                        checkDeadline()
                        output.write(buffer, 0, count)
                        checkDeadline()
                    }
                }
                checkDeadline()
                bodyComplete = true
            } finally {
                if (bodyComplete) {
                    attempt.body.compareAndSet(output, null)
                    output.close()
                }
            }

            checkDeadline()
            connection.readTimeout = remainingTimeout(15_000L)
            val status = connection.responseCode
            checkDeadline()
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = if (stream == null) "" else {
                check(attempt.response.compareAndSet(null, stream)) { "response stream already active" }
                val bytes = java.io.ByteArrayOutputStream()
                var responseComplete = false
                try {
                    val buffer = ByteArray(4 * 1024)
                    while (true) {
                        checkDeadline()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        checkDeadline()
                        if (bytes.size() + count > MAX_RESPONSE_BYTES) throw IOException("EV upload response exceeds 64 KiB")
                        bytes.write(buffer, 0, count)
                    }
                    checkDeadline()
                    responseComplete = true
                    bytes.toString(Charsets.UTF_8.name())
                } finally {
                    if (responseComplete) {
                        attempt.response.compareAndSet(stream, null)
                        stream.close()
                    }
                }
            }
            checkDeadline()
            EvContributionUploader.Response(status, body)
        } catch (interrupted: InterruptedException) {
            throw InterruptedIOException("EV upload attempt interrupted").apply { initCause(interrupted) }
        } finally {
            deadlineTask.cancel()
            attempt.cleanup()
            activeAttempt.compareAndSet(attempt, null)
            // A watchdog interrupt is attempt-owned; do not leak it into a later
            // sequential attempt on the same worker thread.
            Thread.interrupted()
        }
    }
}
