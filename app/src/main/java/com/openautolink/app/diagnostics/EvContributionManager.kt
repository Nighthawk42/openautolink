package com.openautolink.app.diagnostics

import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A consent grant is valid only for this compact schema, HTTPS origin, and credential. */
data class EvContributionConsentBinding(
    val schemaVersion: Int,
    val origin: String,
    val tokenFingerprint: String,
) {
    fun encode(): String = "$schemaVersion|$origin|$tokenFingerprint"
    fun matches(url: String, token: String): Boolean = create(url, token) == this

    companion object {
        const val SCHEMA_VERSION = 2
        fun decode(value: String): EvContributionConsentBinding? {
            val parts = value.split('|')
            if (parts.size != 3) return null
            return EvContributionConsentBinding(parts[0].toIntOrNull() ?: return null, parts[1], parts[2])
                .takeIf { it.schemaVersion == SCHEMA_VERSION }
        }
        fun create(url: String, token: String): EvContributionConsentBinding? {
            if (token.isBlank()) return null
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            val host = uri.host.lowercase()
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return EvContributionConsentBinding(SCHEMA_VERSION, "https://$host$port", sha256(token.toByteArray()))
        }
    }
}

/** Frozen non-VIN calibration identity for one drive. */
data class EvVehicleCalibrationIdentity(
    val frozenKey: String,
    val pseudonymousLabel: String,
    val capacityBandKwh: Int,
) {
    companion object {
        private const val CAPACITY_BAND_WH = 5_000
        private const val REVISION = "rev2"
        private const val ENERGY_BASIS = "absolute-wh"

        fun from(data: ControlMessage.VehicleData): EvVehicleCalibrationIdentity? {
            fun normalize(value: String?): String? = value?.trim()?.lowercase()
                ?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
            val make = normalize(data.carMake) ?: return null
            val model = normalize(data.carModel) ?: return null
            val year = normalize(data.carYear)?.takeIf { it.matches(Regex("[0-9]{4}")) } ?: return null
            val capacityWh = data.evBatteryCapacityWh?.takeIf { it.isFinite() && it in 1_000f..500_000f } ?: return null
            val bandWh = (capacityWh.toInt() / CAPACITY_BAND_WH) * CAPACITY_BAND_WH
            val key = "$make|$model|$year|cap${bandWh}-${bandWh + CAPACITY_BAND_WH}|$REVISION|$ENERGY_BASIS"
            return EvVehicleCalibrationIdentity(
                frozenKey = key,
                pseudonymousLabel = "ev-class-${sha256(key.toByteArray()).take(16)}",
                capacityBandKwh = bandWh / 1_000,
            )
        }
    }
}

class EvContributionDriveIdentityOwner(
    private val idFactory: () -> String,
) {
    data class Transition(val activeId: String, val closeId: String?)
    var activeId: String? = null
        private set
    var activeIdentity: EvVehicleCalibrationIdentity? = null
        private set

    @Synchronized
    fun observe(identity: EvVehicleCalibrationIdentity): Transition {
        val previous = activeId.takeIf { activeIdentity != null && activeIdentity != identity }
        if (activeId == null || previous != null) {
            activeId = idFactory()
            activeIdentity = identity
        }
        return Transition(checkNotNull(activeId), previous)
    }

    @Synchronized
    fun close(): String? = activeId.also {
        activeId = null
        activeIdentity = null
    }

    @Synchronized fun clear() { activeId = null; activeIdentity = null }
}

class EvContributionGenerationFence {
    private val generation = AtomicLong()
    fun snapshot(): Long = generation.get()
    fun invalidate(): Long = generation.incrementAndGet()
    fun isCurrent(snapshot: Long): Boolean = generation.get() == snapshot
}

/** Single monitor for admission, generation invalidation, and exact upload ownership. */
class EvContributionLifecycleGate {
    class Lease internal constructor(internal val generation: Long)
    class DestructiveLease internal constructor(internal val id: Long)
    private var generation = 0L
    private var uploadOwner: Lease? = null
    private var admissionsBlocked = false
    private var destructiveSequence = 0L
    private var destructiveOwner: DestructiveLease? = null
    private val destructiveLeases = linkedSetOf<DestructiveLease>()
    private var destructiveBatchMayResume = true

    @Synchronized
    fun admitCapture(eligible: () -> Boolean): Lease? =
        if (!admissionsBlocked && eligible()) Lease(generation) else null

    @Synchronized
    fun admitUpload(eligible: () -> Boolean): Lease? {
        if (admissionsBlocked || uploadOwner != null || !eligible()) return null
        return Lease(generation).also { uploadOwner = it }
    }

    @Synchronized
    fun invalidate(blockAdmissions: Boolean = false, update: () -> Unit = {}): Long {
        update()
        if (blockAdmissions) admissionsBlocked = true
        return ++generation
    }

    @Synchronized
    fun beginDestructiveOperation(update: () -> Unit = {}): DestructiveLease {
        update()
        if (destructiveLeases.isEmpty()) destructiveBatchMayResume = true
        admissionsBlocked = true
        generation++
        return DestructiveLease(++destructiveSequence).also {
            destructiveOwner = it
            destructiveLeases += it
        }
    }

    @Synchronized
    fun finishDestructiveOperation(lease: DestructiveLease, resumeAdmissions: Boolean): Boolean {
        if (!destructiveLeases.remove(lease)) return false
        destructiveBatchMayResume = destructiveBatchMayResume && resumeAdmissions
        val wasLatest = destructiveOwner === lease
        if (destructiveLeases.isEmpty()) {
            admissionsBlocked = !destructiveBatchMayResume
            destructiveOwner = null
        }
        return wasLatest
    }

    @Synchronized
    fun <T> withCurrent(lease: Lease, action: () -> T): T? =
        if (lease.generation == generation) action() else null

    @Synchronized fun snapshot(): Lease = Lease(generation)

    @Synchronized fun isCurrent(lease: Lease): Boolean = lease.generation == generation

    @Synchronized
    fun <T> exclusive(action: () -> T): T = action()

    @Synchronized
    fun releaseUpload(lease: Lease) {
        if (uploadOwner == lease) uploadOwner = null
    }
}

/** Exact worker ownership plus a completion-owned handoff for triggers rejected while owned. */
class EvUploadJobOwnership<T : Any> {
    data class Completion(val finished: Boolean, val drainPending: Boolean, val wasOrphaned: Boolean)

    private val active = AtomicReference<T?>()
    private val orphaned = AtomicReference<T?>()
    private val drainPending = AtomicBoolean(false)

    @Synchronized fun tryInstall(owner: T): Boolean = active.compareAndSet(null, owner)
    @Synchronized fun markDrainPending(): Boolean {
        if (active.get() == null) return false
        drainPending.set(true)
        return true
    }
    @Synchronized fun markOrphaned(owner: T): Boolean {
        if (active.get() !== owner) return false
        val current = orphaned.get()
        return current === owner || (current == null && orphaned.compareAndSet(null, owner))
    }
    @Synchronized fun isOrphaned(owner: T? = orphaned.get()): Boolean =
        owner != null && active.get() === owner && orphaned.get() === owner

    @Synchronized fun finishDetailed(owner: T): Completion {
        if (!active.compareAndSet(owner, null)) return Completion(false, false, false)
        val wasOrphaned = orphaned.compareAndSet(owner, null)
        return Completion(true, drainPending.getAndSet(false), wasOrphaned)
    }
    @Synchronized fun finish(owner: T): Boolean = finishDetailed(owner).drainPending
    fun current(): T? = active.get()
}

/** Idempotent cleanup shared by the worker body and Job completion callback. */
class EvUploadOwnerCleanup<T : Any>(
    private val owner: T,
    private val ownership: EvUploadJobOwnership<T>,
    private val releaseLease: () -> Unit,
    private val onOwnedCompletion: (EvUploadJobOwnership.Completion) -> Unit = {},
) {
    private val completed = AtomicBoolean(false)

    fun complete(): EvUploadJobOwnership.Completion {
        if (!completed.compareAndSet(false, true)) {
            return EvUploadJobOwnership.Completion(false, false, false)
        }
        var releaseFailure: Throwable? = null
        try {
            releaseLease()
        } catch (failure: Throwable) {
            releaseFailure = failure
        }
        val completion = ownership.finishDetailed(owner)
        if (completion.finished) onOwnedCompletion(completion)
        releaseFailure?.let { throw it }
        return completion
    }
}

/** Installs exact ownership before starting a lazy upload and owns every cleanup path. */
object EvLazyUploadStartup {
    fun launch(
        scope: CoroutineScope,
        ownership: EvUploadJobOwnership<Job>,
        releaseLease: () -> Unit,
        afterOwnerInstalled: (Job) -> Unit = {},
        onOwnedCompletion: (EvUploadJobOwnership.Completion) -> Unit = {},
        body: suspend CoroutineScope.() -> Unit,
    ): Job? {
        lateinit var cleanup: EvUploadOwnerCleanup<Job>
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                body()
            } finally {
                cleanup.complete()
            }
        }
        cleanup = EvUploadOwnerCleanup(job, ownership, releaseLease, onOwnedCompletion)
        job.invokeOnCompletion { cleanup.complete() }

        if (!ownership.tryInstall(job)) {
            cleanup.complete()
            job.cancel()
            return null
        }
        try {
            afterOwnerInstalled(job)
            job.start()
        } catch (failure: Throwable) {
            job.cancel()
            cleanup.complete()
            throw failure
        }
        return job
    }
}

/** Bounded exact-owner wait used after destructive admission has already been fenced. */
object EvUploadQuiescence {
    data class Result(val quiesced: Boolean, val orphaned: Boolean)

    suspend fun <T : Any> awaitExactOrOrphan(
        ownership: EvUploadJobOwnership<T>,
        exact: T?,
        timeoutMs: Long,
        cancelExact: (T) -> Unit,
        awaitExact: suspend (T, timeoutMs: Long) -> Boolean,
    ): Result {
        require(timeoutMs > 0)
        if (exact == null) return Result(quiesced = true, orphaned = false)
        cancelExact(exact)
        if (ownership.current() !== exact) return Result(quiesced = true, orphaned = false)
        if (ownership.isOrphaned(exact)) return Result(quiesced = false, orphaned = true)
        if (awaitExact(exact, timeoutMs) && ownership.current() !== exact) {
            return Result(quiesced = true, orphaned = false)
        }
        if (ownership.markOrphaned(exact)) return Result(quiesced = false, orphaned = true)
        return Result(quiesced = ownership.current() !== exact, orphaned = false)
    }
}

/** FIFO destructive ownership; its synchronized section never spans suspension or I/O. */
class EvDestructiveLane {
    private class Turn(
        private val predecessor: CompletableDeferred<Unit>,
        private val completion: CompletableDeferred<Unit>,
    ) {
        suspend fun awaitTurn() = predecessor.await()
        fun finish(): Boolean = completion.complete(Unit)
        fun finishAfterPredecessor() {
            predecessor.invokeOnCompletion { completion.complete(Unit) }
        }
    }

    private var tail = CompletableDeferred(Unit)

    @Synchronized
    private fun enqueue(): Turn {
        val predecessor = tail
        val completion = CompletableDeferred<Unit>()
        tail = completion
        return Turn(predecessor, completion)
    }

    suspend fun <T> withTurn(block: suspend () -> T): T {
        val turn = enqueue()
        var predecessorCompleted = false
        try {
            turn.awaitTurn()
            predecessorCompleted = true
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            if (predecessorCompleted) turn.finish() else turn.finishAfterPredecessor()
        }
    }
}

class ValidatedDefaultNetworkTracker<T : Any> {
    enum class Change { REPLACED, UNVALIDATED, LOST, IGNORED }
    private var current: T? = null

    @Synchronized fun current(): T? = current

    @Synchronized
    fun capabilities(network: T, validated: Boolean): Change {
        if (validated) {
            if (current == network) return Change.IGNORED
            current = network
            return Change.REPLACED
        }
        if (current != network) return Change.IGNORED
        current = null
        return Change.UNVALIDATED
    }

    @Synchronized
    fun lost(network: T): Change {
        if (current != network) return Change.IGNORED
        current = null
        return Change.LOST
    }
}

class EarliestOneShotDeadline(
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Cancellable,
) {
    fun interface Cancellable { fun cancel() }
    private var deadline = Long.MAX_VALUE
    private var ticket = 0L
    private var pending: Cancellable? = null

    @Synchronized
    fun schedule(delayMs: Long, isCurrent: () -> Boolean, action: () -> Unit): Boolean {
        require(delayMs >= 0)
        val target = now() + delayMs
        if (pending != null && deadline <= target) return false
        pending?.cancel()
        deadline = target
        val ownTicket = ++ticket
        pending = schedule(delayMs) {
            val run = synchronized(this) {
                if (ticket != ownTicket || deadline != target) return@synchronized false
                pending = null
                deadline = Long.MAX_VALUE
                ticket++
                isCurrent()
            }
            if (run) action()
        }
        return true
    }

    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
        deadline = Long.MAX_VALUE
        ticket++
    }
}

class EvFreshParkGate {
    companion object { const val MAX_OBSERVATION_AGE_MS = 30_000L }

    @Volatile var freshParkObservedThisProcess: Boolean = false
        private set
    private var registrationGeneration: Long? = null
    private var gearSafe = false
    private var ignitionSafe = false
    private var latestUnsafeSequence = 0L
    private var gearObservation: com.openautolink.app.transport.VehiclePropertyObservation? = null
    private var ignitionObservation: com.openautolink.app.transport.VehiclePropertyObservation? = null

    @Synchronized
    fun observe(data: ControlMessage.VehicleData, nowElapsedMs: Long): Boolean {
        val batchGeneration = data.vhalRegistrationGeneration
        if (batchGeneration != null && batchGeneration != registrationGeneration) {
            registrationGeneration = batchGeneration
            gearSafe = false
            ignitionSafe = false
            latestUnsafeSequence = 0L
            gearObservation = null
            ignitionObservation = null
        }
        fun fresh(observation: com.openautolink.app.transport.VehiclePropertyObservation): Boolean {
            val sourceNanos = observation.timestampElapsedNanos ?: return false
            val observedGeneration = observation.registrationGeneration ?: return false
            if (!observation.subscriptionActive || observedGeneration != registrationGeneration || observation.status != 0) return false
            if (observation.receivedElapsedMs > nowElapsedMs || nowElapsedMs - observation.receivedElapsedMs >= MAX_OBSERVATION_AGE_MS) return false
            val nowNanos = nowElapsedMs * 1_000_000L
            return sourceNanos <= nowNanos && nowNanos - sourceNanos < MAX_OBSERVATION_AGE_MS * 1_000_000L
        }
        fun newer(candidate: com.openautolink.app.transport.VehiclePropertyObservation, prior: com.openautolink.app.transport.VehiclePropertyObservation?): Boolean =
            prior == null || candidate.receivedElapsedMs > prior.receivedElapsedMs ||
                (candidate.receivedElapsedMs == prior.receivedElapsedMs && candidate.sequence > prior.sequence)
        data.evObservationMetadata["GEAR_SELECTION"]?.let { observation ->
            if (newer(observation, gearObservation)) {
                gearObservation = observation
                gearSafe = fresh(observation) && data.gearRaw == 4
                if (!gearSafe) latestUnsafeSequence = maxOf(latestUnsafeSequence, observation.sequence)
            }
        }
        data.evObservationMetadata["IGNITION_STATE"]?.let { observation ->
            if (newer(observation, ignitionObservation)) {
                ignitionObservation = observation
                ignitionSafe = fresh(observation) && data.ignitionState in setOf(1, 2)
                if (!ignitionSafe) latestUnsafeSequence = maxOf(latestUnsafeSequence, observation.sequence)
            }
        }
        freshParkObservedThisProcess = authorization(nowElapsedMs)
        return freshParkObservedThisProcess
    }

    @Synchronized
    fun authorization(currentElapsedRealtime: Long): Boolean {
        fun current(observation: com.openautolink.app.transport.VehiclePropertyObservation?, safe: Boolean): Boolean {
            if (!safe || observation == null || !observation.subscriptionActive || observation.status != 0) return false
            if (observation.registrationGeneration != registrationGeneration) return false
            if (observation.sequence <= latestUnsafeSequence) return false
            val source = observation.timestampElapsedNanos ?: return false
            if (observation.receivedElapsedMs > currentElapsedRealtime || currentElapsedRealtime - observation.receivedElapsedMs >= MAX_OBSERVATION_AGE_MS) return false
            val nowNanos = currentElapsedRealtime * 1_000_000L
            return source <= nowNanos && nowNanos - source < MAX_OBSERVATION_AGE_MS * 1_000_000L
        }
        return (current(gearObservation, gearSafe) || current(ignitionObservation, ignitionSafe)).also {
            freshParkObservedThisProcess = it
        }
    }

    /**
     * Re-checks monotonic freshness and commits [mutation] under the same monitor
     * used by observe/clear. Callers composing this with the lifecycle gate must
     * acquire lifecycle first, then this safety gate; no safety-gate callback may
     * acquire lifecycle. Thus an unsafe observation/expiry either precedes the
     * commit and rejects it, or is ordered after the completed commit.
     */
    @Synchronized
    fun <T> mutateIfAuthorized(currentElapsedRealtime: Long, mutation: () -> T): T? =
        if (authorization(currentElapsedRealtime)) mutation() else null

    @Synchronized
    fun clear() {
        registrationGeneration = null
        gearSafe = false
        ignitionSafe = false
        latestUnsafeSequence = 0L
        gearObservation = null
        ignitionObservation = null
        freshParkObservedThisProcess = false
    }
}

object EvContributionPolicy {
    data class UploadContext(
        val validatedInternet: Boolean,
        val parked: Boolean,
        val idle: Boolean,
        val startupSensitive: Boolean,
        val projectionActive: Boolean = false,
        val reconnecting: Boolean = false,
        val freshParkObservedThisProcess: Boolean = true,
    )

    fun mayCapture(consent: Boolean, uploadUrl: String, token: String): Boolean =
        consent && EvContributionConsentBinding.create(uploadUrl, token) != null

    fun mayCapture(binding: EvContributionConsentBinding?, uploadUrl: String, token: String): Boolean =
        binding?.matches(uploadUrl, token) == true

    fun mayUpload(consent: Boolean, context: UploadContext): Boolean = consent &&
        context.validatedInternet && context.parked && context.freshParkObservedThisProcess &&
        context.idle && !context.startupSensitive &&
        !context.projectionActive && !context.reconnecting
}

/** Versioned, flat, typed schema. Free text and unknown enums are rejected. */
object EvContributionPrivacy {
    private val vehicleFields = setOf(
        "schema", "type", "elapsedBucketS", "batteryWh", "batteryPct", "rangeKm", "distanceM",
        "speedKmh", "capacityBandKwh", "chargeState", "chargeRateW", "chargePortConnected",
        "gearRaw", "ignition", "energyBasis", "estimatorRevision", "vehicleClass",
    )
    private val forecastFields = setOf(
        "schema", "type", "elapsedBucketS", "forecastWh", "forecastDistanceM", "forecastQuality",
        "vehicleClass",
    )

    fun requireAllowedJsonLine(line: String): String {
        val root = runCatching { Json.parseToJsonElement(line) }.getOrElse {
            throw IllegalArgumentException("compact EV record is not valid JSON", it)
        }
        require(root is JsonObject) { "compact EV record must be an object" }
        require(root["schema"]?.asInt() == EvContributionConsentBinding.SCHEMA_VERSION) {
            "unsupported compact EV schema"
        }
        val type = root["type"]?.asString() ?: throw IllegalArgumentException("record type is required")
        val allowed = when (type) {
            "vehicle" -> vehicleFields
            "forecast" -> forecastFields
            else -> throw IllegalArgumentException("unsupported compact EV record type")
        }
        root.forEach { (key, value) ->
            require(key in allowed) { "field is not allowed in compact EV contribution: $key" }
            require(value !is JsonObject && value !is JsonArray) { "nested compact EV values are not allowed" }
        }
        root["elapsedBucketS"]?.let { requireRange(it.asLong(), 0, Long.MAX_VALUE, "elapsedBucketS"); require(it.asLong() % 60L == 0L) }
        root["vehicleClass"]?.let { require(it.asString().matches(Regex("[0-9a-f]{16,64}"))) { "invalid vehicleClass" } }
        when (type) {
            "vehicle" -> validateVehicle(root)
            "forecast" -> validateForecast(root)
        }
        return line
    }

    private fun validateVehicle(root: JsonObject) {
        root["batteryWh"]?.finiteFloat(0f, 500_000f, "batteryWh")
        root["batteryPct"]?.let { requireRange(it.asInt().toLong(), 0, 100, "batteryPct") }
        root["rangeKm"]?.finiteFloat(0f, 3_000f, "rangeKm")
        root["distanceM"]?.let { requireRange(it.asLong(), 0, 10_000_000, "distanceM") }
        root["speedKmh"]?.finiteFloat(0f, 400f, "speedKmh")
        root["capacityBandKwh"]?.let { requireRange(it.asInt().toLong(), 1, 500, "capacityBandKwh") }
        root["chargeState"]?.let { requireRange(it.asInt().toLong(), 0, 10, "chargeState") }
        root["chargeRateW"]?.finiteFloat(-1_000_000f, 1_000_000f, "chargeRateW")
        root["chargePortConnected"]?.asBoolean()
        root["gearRaw"]?.let { requireRange(it.asInt().toLong(), 0, 1024, "gearRaw") }
        root["ignition"]?.let { requireRange(it.asInt().toLong(), 0, 5, "ignition") }
        root["energyBasis"]?.let { require(it.asString() == "absolute-wh") { "invalid energyBasis" } }
        root["estimatorRevision"]?.let { require(it.asString() == "rev2") { "invalid estimatorRevision" } }
    }

    private fun validateForecast(root: JsonObject) {
        root["forecastWh"]?.finiteFloat(0f, 500_000f, "forecastWh")
        root["forecastDistanceM"]?.let { requireRange(it.asLong(), 0, 10_000_000, "forecastDistanceM") }
        root["forecastQuality"]?.let { require(it.asInt() in 0..2) { "invalid forecastQuality" } }
    }

    private fun kotlinx.serialization.json.JsonElement.asPrimitive(): JsonPrimitive =
        this as? JsonPrimitive ?: throw IllegalArgumentException("value must be scalar")
    private fun kotlinx.serialization.json.JsonElement.asString(): String {
        val p = asPrimitive(); require(p.isString) { "value must be a string" }; return p.content
    }
    private fun kotlinx.serialization.json.JsonElement.asInt(): Int {
        val p = asPrimitive(); require(!p.isString) { "value must be an integer" }
        return p.intOrNull ?: throw IllegalArgumentException("value must be an integer")
    }
    private fun kotlinx.serialization.json.JsonElement.asLong(): Long {
        val p = asPrimitive(); require(!p.isString) { "value must be an integer" }
        return p.longOrNull ?: throw IllegalArgumentException("value must be an integer")
    }
    private fun kotlinx.serialization.json.JsonElement.asBoolean(): Boolean =
        asPrimitive().booleanOrNull ?: throw IllegalArgumentException("value must be boolean")
    private fun kotlinx.serialization.json.JsonElement.finiteFloat(min: Float, max: Float, name: String) {
        val primitive = asPrimitive()
        require(!primitive.isString) { "$name must be numeric" }
        val value = primitive.floatOrNull ?: throw IllegalArgumentException("$name must be numeric")
        require(value.isFinite() && value in min..max) { "$name out of range" }
    }
    private fun requireRange(value: Long, min: Long, max: Long, name: String) =
        require(value in min..max) { "$name out of range" }
}

/** Durable queue whose closed unit is the exact immutable ZIP posted on every retry. */
class EvContributionQueue(
    private val directory: File,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxFiles: Int = 32,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    data class Pending(
        val id: String,
        val startedMs: Long,
        val completedMs: Long,
        val attempts: Int,
        val nextAttemptMs: Long,
        val file: File,
        val sha256: String = "",
        val namespace: String = "default",
        val originalName: String = file.name,
        val vehicleLabel: String = "ev-class-unknown",
    )
    data class Counters(val retained: Int, val evicted: Long, val accepted: Long, val failures: Long, val quarantined: Int)
    data class DeleteResult(val files: Int, val bytes: Long, val failures: Int)
    data class StorageUsage(val units: Int, val bytes: Long)
    private data class Meta(
        val id: String, val startedMs: Long, val completedMs: Long, val attempts: Int,
        val nextAttemptMs: Long, val sha256: String, val namespace: String, val originalName: String,
        val vehicleLabel: String,
    )
    private data class StorageUnit(val primary: File, val files: List<File>, val timestampMs: Long)

    private val stateFile get() = File(directory, "queue-state.properties")
    // Deliberately outside the queue root: recursive queue deletion can never erase intent.
    private val deletionBlockFile get() = File(directory.parentFile ?: directory, ".${directory.name}.deletion-blocked")
    private val evicted = AtomicLong(readCounter("evicted"))
    private val accepted = AtomicLong(readCounter("accepted"))
    private val failures = AtomicLong(readCounter("failures"))

    init {
        require(maxBytes > 0); require(maxFiles > 0); require(maxAgeMs > 0)
        if (!isDeletionBlocked()) runCatching { recoverArtifacts() }
    }

    @Synchronized
    fun append(
        id: String,
        startedMs: Long,
        jsonLine: String,
        namespace: String = "default",
        vehicleLabel: String = "ev-class-unknown",
    ) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid contribution id" }
        require(namespace.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "invalid contribution namespace" }
        require(vehicleLabel.matches(Regex("ev-class-[A-Za-z0-9._-]{4,100}"))) { "invalid vehicle label" }
        val safe = EvContributionPrivacy.requireAllowedJsonLine(jsonLine)
        ensureDirectory()
        check(allClosed().none { it.id == id }) { "cannot append to closed contribution" }
        val file = openFile(id, startedMs)
        val existing = if (file.exists()) readMeta(file) else null
        check(existing == null || (existing.id == id && existing.startedMs == startedMs && existing.namespace == namespace && existing.vehicleLabel == vehicleLabel)) {
            "open contribution identity changed"
        }
        if (!file.exists()) writeMeta(file, Meta(id, startedMs, 0, 0, 0, "", namespace, "ev-contribution.zip", vehicleLabel))
        FileOutputStream(file, true).use { out ->
            out.write((safe + "\n").toByteArray(Charsets.UTF_8)); runCatching { out.fd.sync() }
        }
        if (file.length() > maxBytes) {
            deleteUnit(file); evictOne()
            throw IOException("single compact EV contribution exceeds retention budget")
        }
        retainBounded(startedMs)
    }

    @Synchronized
    fun close(id: String, completedMs: Long) {
        val entry = allOpen().firstOrNull { it.id == id } ?: return
        check(allClosed().none { it.id == id }) { "contribution already closed" }
        val zip = zipFile(entry.id, entry.startedMs)
        val tmp = File(directory, zip.name + ".tmp")
        val logBytes = entry.file.readBytes()
        FileOutputStream(tmp).use { fos ->
            ZipOutputStream(fos).use { out ->
                addZip(out, "telemetry.log", logBytes)
                val manifest = "schema=2\nvehicleClass=${entry.vehicleLabel.removePrefix("ev-class-")}\nstartedHourBucket=${entry.startedMs / 3_600_000}\ncompletedHourBucket=${completedMs / 3_600_000}\n"
                addZip(out, "manifest.log", manifest.toByteArray(Charsets.UTF_8))
            }
            runCatching { fos.fd.sync() }
        }
        atomicMove(tmp, zip)
        val digest = sha256(zip.readBytes())
        writeMeta(zip, Meta(entry.id, entry.startedMs, completedMs, entry.attempts, entry.nextAttemptMs, digest, entry.namespace, zip.name, entry.vehicleLabel))
        deleteUnit(entry.file)
        syncDirectory(directory)
        retainBounded(completedMs)
    }

    @Synchronized
    fun recoverInterrupted(completedMs: Long): Int {
        recoverArtifacts()
        // Apply the same age/count/byte policy while entries still carry their
        // original open-drive timestamp; recovery must not make stale data young.
        retainBounded(completedMs)
        val entries = allOpen()
        entries.forEach { close(it.id, completedMs) }
        return entries.size
    }

    @Synchronized fun pending(): List<Pending> = allClosed().sortedWith(compareBy<Pending> { it.startedMs }.thenBy { it.id })

    @Synchronized
    fun updateRetry(entry: Pending, attempts: Int, nextAttemptMs: Long) {
        if (!entry.file.exists()) return
        writeMeta(entry.file, entry.toMeta(attempts, nextAttemptMs)); failures.incrementAndGet(); persistCounters()
    }

    @Synchronized
    fun markAccepted(entry: Pending): Boolean {
        if (!entry.file.exists()) return true
        val ack = File(entry.file.parentFile, entry.file.name + ".acked")
        if (!atomicMove(entry.file, ack, throwOnFailure = false)) return false
        metaFile(entry.file).delete()
        syncDirectory(directory)
        val deleted = ack.delete()
        if (deleted) { accepted.incrementAndGet(); persistCounters(); syncDirectory(directory) }
        return deleted
    }

    @Synchronized
    fun quarantine(entry: Pending, reason: String): Boolean = quarantineFile(entry.file, reason)

    @Synchronized
    fun deletePending(): Int = deleteAllArtifacts().files

    @Synchronized
    fun deleteAllArtifacts(): DeleteResult {
        if (!directory.exists()) return DeleteResult(0, 0, 0)
        val files = runCatching { directory.walkBottomUp().filter { it.isFile }.toList() }
            .getOrElse { return DeleteResult(0, 0, 1) }
        val bytes = runCatching { files.sumOf { it.length() } }.getOrDefault(0)
        var failed = 0
        files.forEach { file ->
            if (file.exists() && runCatching { deleteFile(file) }.getOrDefault(false).not()) failed++
        }
        runCatching { directory.walkBottomUp().filter { it.isDirectory && it != directory }.forEach { it.delete() } }
            .onFailure { failed++ }
        if (failed == 0 && directory.walkTopDown().none { it.isFile }) {
            evicted.set(0); accepted.set(0); failures.set(0)
        }
        syncDirectory(directory)
        return DeleteResult(files.size, bytes, failed)
    }

    @Synchronized fun isDeletionBlocked(): Boolean = deletionBlockFile.isFile

    /** Synchronously commits the fence before any destructive queue I/O. */
    @Synchronized
    fun beginDeletion(): Boolean = runCatching {
        val parent = deletionBlockFile.parentFile ?: return false
        check(parent.isDirectory || parent.mkdirs()) { "cannot create deletion marker parent" }
        val tmp = File(parent, deletionBlockFile.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write("blocked\n".toByteArray())
            out.fd.sync()
        }
        check(atomicMove(tmp, deletionBlockFile, throwOnFailure = false)) { "cannot publish deletion marker" }
        syncDirectory(parent)
        true
    }.getOrDefault(false)

    /** Persist failure before reopening admissions; clear only after a verified empty pass. */
    @Synchronized
    fun completeDeletion(result: DeleteResult): Boolean {
        val empty = EvDeleteAdmissionPolicy.mayResume(result, storageUsage())
        if (!empty) {
            if (!deletionBlockFile.exists()) beginDeletion()
            return false
        }
        if (deletionBlockFile.exists() && !deletionBlockFile.delete()) return false
        syncDirectory(deletionBlockFile.parentFile ?: directory)
        return true
    }

    @Synchronized fun enforceRetention(nowMs: Long) { recoverArtifacts(); retainBounded(nowMs) }

    @Synchronized fun storageUsage(): StorageUsage {
        val units = storageUnits()
        return StorageUsage(units.size, units.sumOf { unit -> unit.files.sumOf(File::length) })
    }

    @Synchronized fun counters(): Counters = Counters(
        storageUnits().size, evicted.get(), accepted.get(), failures.get(),
        File(directory, "quarantine").walkTopDown().count { it.isFile && !it.name.endsWith(".meta") },
    )

    private fun Pending.toMeta(attempts: Int = this.attempts, next: Long = nextAttemptMs) =
        Meta(id, startedMs, completedMs, attempts, next, sha256, namespace, originalName, vehicleLabel)
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "cannot create compact EV queue" } }
    private fun openFile(id: String, startedMs: Long) = File(directory, "%019d_%s.evc".format(startedMs, id))
    private fun zipFile(id: String, startedMs: Long) = File(directory, "%019d_%s.zip".format(startedMs, id))
    private fun metaFile(file: File) = File(file.parentFile, file.name + ".meta")
    private fun addZip(out: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0L; extra = ByteArray(0); comment = null }
        out.putNextEntry(entry); out.write(bytes); out.closeEntry()
    }

    private fun allOpen() = files(".evc")
    private fun allClosed() = files(".zip").filter { pending ->
        pending.completedMs > 0 && pending.sha256.matches(Regex("[0-9a-f]{64}")) &&
            runCatching { sha256(pending.file.readBytes()) == pending.sha256 }.getOrDefault(false)
    }
    private fun files(suffix: String): List<Pending> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().asSequence().filter { it.isFile && it.name.endsWith(suffix) }
            .mapNotNull { file -> readMeta(file)?.let { meta ->
                Pending(meta.id, meta.startedMs, meta.completedMs, meta.attempts, meta.nextAttemptMs, file,
                    meta.sha256, meta.namespace, meta.originalName, meta.vehicleLabel)
            } }.toList()
    }
    private fun readMeta(file: File): Meta? = runCatching {
        val p = Properties().apply { metaFile(file).inputStream().use(::load) }
        Meta(
            p.getProperty("id"), p.getProperty("startedMs").toLong(), p.getProperty("completedMs").toLong(),
            p.getProperty("attempts").toInt(), p.getProperty("nextAttemptMs").toLong(), p.getProperty("sha256", ""),
            p.getProperty("namespace", "default"), p.getProperty("originalName", file.name),
            p.getProperty("vehicleLabel", "ev-class-unknown"),
        ).takeIf {
            it.id.matches(Regex("[A-Za-z0-9._-]{1,80}")) && it.namespace.matches(Regex("[A-Za-z0-9._-]{1,120}")) &&
                it.vehicleLabel.matches(Regex("ev-class-[A-Za-z0-9._-]{4,100}"))
        }
    }.getOrNull()
    private fun writeMeta(file: File, meta: Meta) {
        ensureDirectory(); val target = metaFile(file); val tmp = File(directory, target.name + ".tmp")
        Properties().apply {
            setProperty("id", meta.id); setProperty("startedMs", meta.startedMs.toString()); setProperty("completedMs", meta.completedMs.toString())
            setProperty("attempts", meta.attempts.toString()); setProperty("nextAttemptMs", meta.nextAttemptMs.toString())
            setProperty("sha256", meta.sha256); setProperty("namespace", meta.namespace); setProperty("originalName", meta.originalName)
            setProperty("vehicleLabel", meta.vehicleLabel)
        }.also { properties -> FileOutputStream(tmp).use { out -> properties.store(out, null); runCatching { out.fd.sync() } } }
        atomicMove(tmp, target)
        syncDirectory(directory)
    }

    private fun recoverArtifacts() {
        if (!directory.isDirectory) return
        directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".acked") }.forEach {
            if (it.delete()) { accepted.incrementAndGet(); persistCounters() }
        }
        directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { quarantineFile(it, "crash_tmp") }
        val publishedIds = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "zip" }
            .mapNotNull { zip -> readMeta(zip)?.takeIf { meta ->
                meta.completedMs > 0 && meta.sha256.matches(Regex("[0-9a-f]{64}")) &&
                    runCatching { ZipFile(zip).use { }; sha256(zip.readBytes()) == meta.sha256 }.getOrDefault(false)
            }?.id }
            .toSet()
        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "evc" }.forEach { source ->
            if (readMeta(source)?.id in publishedIds) deleteUnit(source)
        }
        directory.listFiles().orEmpty().filter { it.isFile && (it.extension == "evc" || it.extension == "zip") }.forEach { file ->
            val meta = readMeta(file)
            val validZip = file.extension != "zip" || (meta?.sha256?.matches(Regex("[0-9a-f]{64}")) == true &&
                runCatching { ZipFile(file).use { }; sha256(file.readBytes()) == meta.sha256 }.getOrDefault(false))
            if (meta == null || !validZip) quarantineFile(file, "corrupt")
        }
        directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".meta") }.forEach { meta ->
            val base = File(directory, meta.name.removeSuffix(".meta"))
            if (!base.exists()) quarantineFile(meta, "orphan_meta")
        }
    }

    private fun storageUnits(): List<StorageUnit> {
        if (!directory.isDirectory) return emptyList()
        val payloads = directory.listFiles().orEmpty().filter { file -> file.isFile &&
            (file.extension in setOf("evc", "zip", "tmp", "acked") || (file.name.endsWith(".meta") && !File(directory, file.name.removeSuffix(".meta")).exists())) }
            .map { file ->
                val companion = metaFile(file).takeIf(File::isFile)
                val meta = readMeta(file)
                StorageUnit(file, listOfNotNull(file, companion), meta?.completedMs?.takeIf { it > 0 } ?: meta?.startedMs ?: file.lastModified())
            }
        val quarantined = File(directory, "quarantine").walkTopDown().filter { it.isFile }.map {
            StorageUnit(it, listOf(it), it.lastModified())
        }.toList()
        return (payloads + quarantined).distinctBy { it.primary.absolutePath }
    }

    private fun retainBounded(nowMs: Long) {
        var units = storageUnits().sortedWith(compareBy<StorageUnit> { it.timestampMs }.thenBy { it.primary.name })
        units.filter { it.timestampMs > 0 && nowMs - it.timestampMs > maxAgeMs }.forEach { removeEvicted(it) }
        units = storageUnits().sortedWith(compareBy<StorageUnit> { it.timestampMs }.thenBy { it.primary.name })
        while (units.size > maxFiles || units.sumOf { unit -> unit.files.sumOf(File::length) } > maxBytes) {
            removeEvicted(units.firstOrNull() ?: break)
            units = storageUnits().sortedWith(compareBy<StorageUnit> { it.timestampMs }.thenBy { it.primary.name })
        }
    }
    private fun removeEvicted(unit: StorageUnit) { unit.files.forEach { it.delete() }; evictOne(); syncDirectory(directory) }
    private fun deleteUnit(file: File) { file.delete(); metaFile(file).delete(); syncDirectory(file.parentFile ?: directory) }
    private fun evictOne() { evicted.incrementAndGet(); persistCounters() }
    private fun quarantineFile(file: File, reason: String): Boolean {
        if (!file.exists()) return true
        val qdir = File(directory, "quarantine").apply { mkdirs() }
        val safeReason = reason.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        var target = File(qdir, "${file.name}_$safeReason")
        var index = 1
        while (target.exists()) target = File(qdir, "${file.name}_${safeReason}_${index++}")
        val moved = atomicMove(file, target, throwOnFailure = false)
        if (moved) {
            val meta = metaFile(file)
            if (meta.exists()) atomicMove(meta, File(qdir, target.name + ".meta"), throwOnFailure = false)
            syncDirectory(qdir); syncDirectory(directory)
        }
        return moved
    }
    private fun readCounter(key: String): Long = runCatching {
        Properties().apply { stateFile.inputStream().use(::load) }.getProperty(key, "0").toLong()
    }.getOrDefault(0)
    private fun persistCounters() {
        ensureDirectory(); val tmp = File(directory, stateFile.name + ".tmp")
        Properties().apply {
            setProperty("evicted", evicted.get().toString()); setProperty("accepted", accepted.get().toString()); setProperty("failures", failures.get().toString())
        }.also { properties -> FileOutputStream(tmp).use { out -> properties.store(out, null); runCatching { out.fd.sync() } } }
        atomicMove(tmp, stateFile); syncDirectory(directory)
    }

    private fun atomicMove(source: File, target: File, throwOnFailure: Boolean = true): Boolean {
        val moved = runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrElse { source.renameTo(target) }
        if (!moved && throwOnFailure) error("cannot atomically publish ${target.name}")
        return moved
    }

    private fun syncDirectory(dir: File) {
        runCatching {
            val descriptor = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        }
    }
}

object EvDeleteAdmissionPolicy {
    fun mayResume(result: EvContributionQueue.DeleteResult, retained: EvContributionQueue.StorageUsage): Boolean =
        result.failures == 0 && retained.units == 0 && retained.bytes == 0L
}

class EvContributionUploader(
    private val queue: EvContributionQueue,
    private val jitter: (Long) -> Long = { bound -> if (bound <= 0) 0 else Random.nextLong(bound + 1) },
    private val onUnauthorized: () -> Unit = {},
    private val mayTransmit: () -> Boolean = { true },
    private val mayMutate: () -> Boolean = { true },
    private val mutateIfCurrent: ((() -> Outcome) -> Outcome?) = { action -> if (mayMutate()) action() else null },
    private val send: (file: File, idempotencyKey: String) -> Response,
) {
    data class Response(val statusCode: Int, val body: String)
    enum class Outcome { EMPTY, BACKOFF, RETRY, ACCEPTED, ACCEPTED_DUPLICATE, UNAUTHORIZED, QUARANTINED, OWNER_MISMATCH, CANCELLED }
    companion object { const val MAX_BACKOFF_MS = 24L * 60 * 60 * 1000; private const val BASE_BACKOFF_MS = 30_000L }

    fun uploadOldest(nowMs: Long, authorizedNamespace: String? = null): Outcome {
        val entry = queue.pending().firstOrNull() ?: return Outcome.EMPTY
        if (authorizedNamespace != null && entry.namespace != authorizedNamespace) return Outcome.OWNER_MISMATCH
        if (nowMs < entry.nextAttemptMs) return Outcome.BACKOFF
        if (!mayTransmit()) return Outcome.CANCELLED
        val response = runCatching { send(entry.file, entry.id) }.getOrElse {
            if (!mayMutate()) return Outcome.CANCELLED
            Response(0, "")
        }
        if (!mayMutate()) return Outcome.CANCELLED
        if (response.statusCode == 401) { onUnauthorized(); if (!mayMutate()) return Outcome.CANCELLED; retry(entry, nowMs); return Outcome.UNAUTHORIZED }
        if (response.statusCode == 413 || response.statusCode == 422) {
            return mutateIfCurrent {
                if (queue.quarantine(entry, response.statusCode.toString())) Outcome.QUARANTINED else retry(entry, nowMs)
            } ?: Outcome.CANCELLED
        }
        val ack = parseAck(response.body)
        if (response.statusCode == 200 && ack?.ok == true && ack.sha256.equals(entry.sha256, true)) {
            return mutateIfCurrent {
                if (!queue.markAccepted(entry)) retry(entry, nowMs)
                else if (ack.duplicate) Outcome.ACCEPTED_DUPLICATE else Outcome.ACCEPTED
            } ?: Outcome.CANCELLED
        }
        return retry(entry, nowMs)
    }
    private data class Ack(val ok: Boolean, val sha256: String, val duplicate: Boolean)
    private fun parseAck(body: String): Ack? = runCatching {
        val obj = Json.parseToJsonElement(body).jsonObject
        Ack(obj["ok"]?.jsonPrimitive?.booleanOrNull == true, obj["sha256"]?.jsonPrimitive?.content ?: "",
            obj["duplicate"]?.jsonPrimitive?.booleanOrNull == true)
    }.getOrNull()
    private fun retry(entry: EvContributionQueue.Pending, nowMs: Long): Outcome {
        if (!mayMutate()) return Outcome.CANCELLED
        val attempts = entry.attempts + 1; val shift = (attempts - 1).coerceAtMost(20)
        val base = (BASE_BACKOFF_MS * (1L shl shift)).coerceAtMost(MAX_BACKOFF_MS)
        val extra = jitter((base / 4).coerceAtLeast(0)).coerceIn(0, MAX_BACKOFF_MS - base)
        return mutateIfCurrent {
            queue.updateRetry(entry, attempts, nowMs + base + extra)
            Outcome.RETRY
        } ?: Outcome.CANCELLED
    }
}

/** Drains completed eligible batches oldest-first and stops at the first fence. */
class EvContributionDrainLoop(
    private val queue: EvContributionQueue,
    private val uploaderFactory: (EvContributionQueue) -> EvContributionUploader,
) {
    fun drainEligible(nowMs: Long, authorizedNamespace: String): EvContributionUploader.Outcome {
        while (true) {
            when (val outcome = uploaderFactory(queue).uploadOldest(nowMs, authorizedNamespace)) {
                EvContributionUploader.Outcome.ACCEPTED,
                EvContributionUploader.Outcome.ACCEPTED_DUPLICATE -> continue
                else -> return outcome
            }
        }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
