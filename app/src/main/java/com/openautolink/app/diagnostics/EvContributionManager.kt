package com.openautolink.app.diagnostics

import com.openautolink.app.transport.ControlMessage
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
import java.util.concurrent.atomic.AtomicLong
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
    private var generation = 0L
    private var uploadOwner: Lease? = null
    private var admissionsBlocked = false

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

    @Synchronized fun resumeAdmissions() { admissionsBlocked = false }

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
    @Volatile var freshParkObservedThisProcess: Boolean = false
        private set

    @Synchronized
    fun observe(gearRaw: Int?, ignitionState: Int?): Boolean {
        freshParkObservedThisProcess = gearRaw == 4 && ignitionState in setOf(1, 2)
        return freshParkObservedThisProcess
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
    private val evicted = AtomicLong(readCounter("evicted"))
    private val accepted = AtomicLong(readCounter("accepted"))
    private val failures = AtomicLong(readCounter("failures"))

    init {
        require(maxBytes > 0); require(maxFiles > 0); require(maxAgeMs > 0)
        runCatching { recoverArtifacts() }
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
        val files = directory.walkBottomUp().filter { it.isFile }.toList()
        val bytes = files.sumOf { it.length() }
        var failed = 0
        files.forEach { if (it.exists() && !deleteFile(it)) failed++ }
        directory.walkBottomUp().filter { it.isDirectory && it != directory }.forEach { it.delete() }
        if (failed == 0 && directory.walkTopDown().none { it.isFile }) {
            evicted.set(0); accepted.set(0); failures.set(0)
        }
        syncDirectory(directory)
        return DeleteResult(files.size, bytes, failed)
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
