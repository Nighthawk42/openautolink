package com.openautolink.app.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
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

class EvContributionGenerationFence {
    private val generation = AtomicLong()
    fun snapshot(): Long = generation.get()
    fun invalidate(): Long = generation.incrementAndGet()
    fun isCurrent(snapshot: Long): Boolean = generation.get() == snapshot
}

object EvContributionPolicy {
    data class UploadContext(
        val validatedInternet: Boolean,
        val parked: Boolean,
        val idle: Boolean,
        val startupSensitive: Boolean,
        val projectionActive: Boolean = false,
        val reconnecting: Boolean = false,
    )

    fun mayCapture(consent: Boolean, uploadUrl: String, token: String): Boolean =
        consent && EvContributionConsentBinding.create(uploadUrl, token) != null

    fun mayCapture(binding: EvContributionConsentBinding?, uploadUrl: String, token: String): Boolean =
        binding?.matches(uploadUrl, token) == true

    fun mayUpload(consent: Boolean, context: UploadContext): Boolean = consent &&
        context.validatedInternet && context.parked && context.idle && !context.startupSensitive &&
        !context.projectionActive && !context.reconnecting
}

/** Flat scalar-only versioned schema; recursive arbitrary data is never accepted. */
object EvContributionPrivacy {
    private val scalarFields = setOf(
        "schema", "type", "elapsedBucketS", "batteryWh", "batteryPct", "rangeKm", "distanceM",
        "speedKmh", "forecastWh", "forecastDistanceM", "forecastQuality", "capacityBandKwh",
        "chargeState", "chargeRateW", "chargePortConnected", "gearRaw", "ignition",
        "drivingWhPerKm", "learnerWhPerKm", "learnerSampleKm", "learnerStatus", "requestedMode",
        "effectiveMode", "safetyHolds", "energyBasis", "estimatorRevision", "vehicleClass", "event",
        "reason", "widthMm", "heightMm", "wheelbaseMm", "capacityBandMinKwh", "capacityBandMaxKwh",
    )

    fun requireAllowedJsonLine(line: String): String {
        val root = runCatching { Json.parseToJsonElement(line) }.getOrElse {
            throw IllegalArgumentException("compact EV record is not valid JSON", it)
        }
        require(root is JsonObject) { "compact EV record must be an object" }
        require(root["schema"]?.jsonPrimitive?.content == EvContributionConsentBinding.SCHEMA_VERSION.toString()) {
            "unsupported compact EV schema"
        }
        root.forEach { (key, value) ->
            require(key in scalarFields) { "field is not allowed in compact EV contribution: $key" }
            require(value !is JsonObject && value !is kotlinx.serialization.json.JsonArray) {
                "nested compact EV values are not allowed"
            }
        }
        return line
    }
}

/** Durable queue whose closed unit is the exact immutable ZIP posted on every retry. */
class EvContributionQueue(
    private val directory: File,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxFiles: Int = 32,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
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
    )
    data class Counters(val retained: Int, val evicted: Long, val accepted: Long, val failures: Long, val quarantined: Int)
    private data class Meta(
        val id: String, val startedMs: Long, val completedMs: Long, val attempts: Int,
        val nextAttemptMs: Long, val sha256: String, val namespace: String, val originalName: String,
    )

    private val stateFile get() = File(directory, "queue-state.properties")
    private val evicted = AtomicLong(readCounter("evicted"))
    private val accepted = AtomicLong(readCounter("accepted"))
    private val failures = AtomicLong(readCounter("failures"))

    init { require(maxBytes > 0); require(maxFiles > 0); require(maxAgeMs > 0); recoverAcks() }

    @Synchronized
    fun append(id: String, startedMs: Long, jsonLine: String, namespace: String = "default") {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid contribution id" }
        require(namespace.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "invalid contribution namespace" }
        val safe = EvContributionPrivacy.requireAllowedJsonLine(jsonLine)
        ensureDirectory()
        val file = openFile(id, startedMs)
        if (!file.exists()) writeMeta(file, Meta(id, startedMs, 0, 0, 0, "", namespace, "ev_${id}.zip"))
        file.appendText(safe + "\n", Charsets.UTF_8)
        if (file.length() > maxBytes) {
            file.delete(); metaFile(file).delete(); evictOne()
            throw IOException("single compact EV contribution exceeds retention budget")
        }
    }

    @Synchronized
    fun close(id: String, completedMs: Long) {
        val entry = allOpen().firstOrNull { it.id == id } ?: return
        val zip = zipFile(entry.id, entry.startedMs)
        val tmp = File(directory, zip.name + ".tmp")
        val logBytes = entry.file.readBytes()
        FileOutputStream(tmp).use { fos ->
            ZipOutputStream(fos).use { out ->
                addZip(out, "ev_${entry.id}.log", logBytes)
                val manifest = "schema=2\nid=${entry.id}\nnamespace=${entry.namespace}\nstartedBucket=${entry.startedMs / 3_600_000}\ncompletedBucket=${completedMs / 3_600_000}\n"
                addZip(out, "upload_manifest.log", manifest.toByteArray(Charsets.UTF_8))
            }
            runCatching { fos.fd.sync() }
        }
        check(tmp.renameTo(zip)) { "cannot publish compact EV archive" }
        val digest = sha256(zip.readBytes())
        writeMeta(zip, Meta(entry.id, entry.startedMs, completedMs, entry.attempts, entry.nextAttemptMs, digest, entry.namespace, zip.name))
        entry.file.delete(); metaFile(entry.file).delete()
        retainBounded(completedMs)
    }

    @Synchronized
    fun recoverInterrupted(completedMs: Long): Int {
        val entries = allOpen()
        entries.forEach { close(it.id, completedMs) }
        return entries.size
    }

    @Synchronized fun pending(): List<Pending> = allClosed().sortedWith(compareBy<Pending> { it.startedMs }.thenBy { it.id })

    @Synchronized
    fun updateRetry(entry: Pending, attempts: Int, nextAttemptMs: Long) {
        writeMeta(entry.file, entry.toMeta(attempts, nextAttemptMs)); failures.incrementAndGet(); persistCounters()
    }

    @Synchronized
    fun markAccepted(entry: Pending): Boolean {
        if (!entry.file.exists()) return true
        val ack = File(entry.file.parentFile, entry.file.name + ".acked")
        if (!entry.file.renameTo(ack)) return false
        metaFile(entry.file).delete()
        val deleted = ack.delete()
        if (deleted) { accepted.incrementAndGet(); persistCounters() }
        return deleted
    }

    @Synchronized
    fun quarantine(entry: Pending, reason: String): Boolean {
        val qdir = File(directory, "quarantine").apply { mkdirs() }
        val target = File(qdir, entry.file.name.removeSuffix(".zip") + "_${reason}.zip")
        val moved = entry.file.renameTo(target)
        if (moved) metaFile(entry.file).delete()
        return moved
    }

    @Synchronized fun deletePending(): Int {
        val entries = allOpen() + allClosed()
        entries.forEach { it.file.delete(); metaFile(it.file).delete() }
        return entries.size
    }

    @Synchronized fun enforceRetention(nowMs: Long) = retainBounded(nowMs)

    @Synchronized fun counters(): Counters = Counters(
        pending().size, evicted.get(), accepted.get(), failures.get(),
        File(directory, "quarantine").listFiles()?.count { it.extension == "zip" } ?: 0,
    )

    private fun Pending.toMeta(attempts: Int = this.attempts, next: Long = nextAttemptMs) =
        Meta(id, startedMs, completedMs, attempts, next, sha256, namespace, originalName)
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "cannot create compact EV queue" } }
    private fun openFile(id: String, startedMs: Long) = File(directory, "%019d_%s.evc".format(startedMs, id))
    private fun zipFile(id: String, startedMs: Long) = File(directory, "%019d_%s.zip".format(startedMs, id))
    private fun metaFile(file: File) = File(file.parentFile, file.name + ".meta")
    private fun addZip(out: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0L; extra = ByteArray(0); comment = null }
        out.putNextEntry(entry); out.write(bytes); out.closeEntry()
    }

    private fun allOpen() = files(".evc")
    private fun allClosed() = files(".zip").filter { it.completedMs > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) }
    private fun files(suffix: String): List<Pending> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().asSequence().filter { it.isFile && it.name.endsWith(suffix) }
            .mapNotNull { f -> readMeta(f)?.let { Pending(it.id, it.startedMs, it.completedMs, it.attempts, it.nextAttemptMs, f, it.sha256, it.namespace, it.originalName) } }
            .toList()
    }
    private fun readMeta(file: File): Meta? = runCatching {
        val p = Properties().apply { metaFile(file).inputStream().use(::load) }
        Meta(p.getProperty("id"), p.getProperty("startedMs").toLong(), p.getProperty("completedMs").toLong(),
            p.getProperty("attempts").toInt(), p.getProperty("nextAttemptMs").toLong(), p.getProperty("sha256", ""),
            p.getProperty("namespace", "default"), p.getProperty("originalName", file.name))
    }.getOrNull()
    private fun writeMeta(file: File, m: Meta) {
        ensureDirectory(); val target = metaFile(file); val tmp = File(directory, target.name + ".tmp")
        Properties().apply {
            setProperty("id", m.id); setProperty("startedMs", m.startedMs.toString()); setProperty("completedMs", m.completedMs.toString())
            setProperty("attempts", m.attempts.toString()); setProperty("nextAttemptMs", m.nextAttemptMs.toString())
            setProperty("sha256", m.sha256); setProperty("namespace", m.namespace); setProperty("originalName", m.originalName)
        }.also { p -> FileOutputStream(tmp).use { out -> p.store(out, null); runCatching { out.fd.sync() } } }
        check(tmp.renameTo(target) || run { tmp.copyTo(target, true); tmp.delete(); true })
    }
    private fun retainBounded(nowMs: Long) {
        var entries = pending()
        entries.filter { nowMs - it.completedMs > maxAgeMs }.forEach { removeEvicted(it) }
        entries = pending()
        while (entries.size > maxFiles || entries.sumOf { it.file.length() } > maxBytes) {
            removeEvicted(entries.firstOrNull() ?: break); entries = pending()
        }
    }
    private fun removeEvicted(p: Pending) { p.file.delete(); metaFile(p.file).delete(); evictOne() }
    private fun evictOne() { evicted.incrementAndGet(); persistCounters() }
    private fun recoverAcks() { if (directory.isDirectory) directory.listFiles().orEmpty().filter { it.name.endsWith(".acked") }.forEach { it.delete() } }
    private fun readCounter(key: String): Long = runCatching { Properties().apply { stateFile.inputStream().use(::load) }.getProperty(key, "0").toLong() }.getOrDefault(0)
    private fun persistCounters() { ensureDirectory(); Properties().apply {
        setProperty("evicted", evicted.get().toString()); setProperty("accepted", accepted.get().toString()); setProperty("failures", failures.get().toString())
    }.also { it.store(stateFile.outputStream(), null) } }
}

class EvContributionUploader(
    private val queue: EvContributionQueue,
    private val jitter: (Long) -> Long = { bound -> if (bound <= 0) 0 else Random.nextLong(bound + 1) },
    private val onUnauthorized: () -> Unit = {},
    private val send: (file: File, idempotencyKey: String) -> Response,
) {
    data class Response(val statusCode: Int, val body: String)
    enum class Outcome { EMPTY, BACKOFF, RETRY, ACCEPTED, ACCEPTED_DUPLICATE, UNAUTHORIZED, QUARANTINED }
    companion object { const val MAX_BACKOFF_MS = 24L * 60 * 60 * 1000; private const val BASE_BACKOFF_MS = 30_000L }

    fun uploadOldest(nowMs: Long): Outcome {
        val entry = queue.pending().firstOrNull() ?: return Outcome.EMPTY
        if (nowMs < entry.nextAttemptMs) return Outcome.BACKOFF
        val response = runCatching { send(entry.file, entry.id) }.getOrElse { Response(0, "") }
        if (response.statusCode == 401) { onUnauthorized(); retry(entry, nowMs); return Outcome.UNAUTHORIZED }
        if (response.statusCode == 413 || response.statusCode == 422) {
            return if (queue.quarantine(entry, response.statusCode.toString())) Outcome.QUARANTINED else retry(entry, nowMs)
        }
        val ack = parseAck(response.body)
        if (response.statusCode == 200 && ack?.ok == true && ack.sha256.equals(entry.sha256, true)) {
            if (!queue.markAccepted(entry)) return retry(entry, nowMs)
            return if (ack.duplicate) Outcome.ACCEPTED_DUPLICATE else Outcome.ACCEPTED
        }
        return retry(entry, nowMs)
    }
    private data class Ack(val ok: Boolean, val sha256: String, val duplicate: Boolean)
    private fun parseAck(body: String): Ack? = runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        Ack(o["ok"]?.jsonPrimitive?.booleanOrNull == true, o["sha256"]?.jsonPrimitive?.content ?: "",
            o["duplicate"]?.jsonPrimitive?.booleanOrNull == true)
    }.getOrNull()
    private fun retry(entry: EvContributionQueue.Pending, nowMs: Long): Outcome {
        val attempts = entry.attempts + 1; val shift = (attempts - 1).coerceAtMost(20)
        val base = (BASE_BACKOFF_MS * (1L shl shift)).coerceAtMost(MAX_BACKOFF_MS)
        val extra = jitter((base / 4).coerceAtLeast(0)).coerceIn(0, MAX_BACKOFF_MS - base)
        queue.updateRetry(entry, attempts, nowMs + base + extra); return Outcome.RETRY
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
