package com.openautolink.app.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

/** Consent is independent of the pre-existing manual diagnostic-upload switch. */
object EvContributionPolicy {
    data class UploadContext(
        val validatedInternet: Boolean,
        val parked: Boolean,
        val idle: Boolean,
        val startupSensitive: Boolean,
    )

    fun mayCapture(consent: Boolean, uploadUrl: String, token: String): Boolean =
        consent && uploadUrl.startsWith("https://") && token.isNotBlank()

    fun mayUpload(consent: Boolean, context: UploadContext): Boolean =
        consent && context.validatedInternet && context.parked && context.idle && !context.startupSensitive
}

/** Fail-closed field allowlist for the compact automatic EV contribution channel. */
object EvContributionPrivacy {
    private val scalarFields = setOf(
        "schema", "type", "wallMs", "elapsedMs", "batteryWh", "batteryPct", "rangeKm",
        "distanceM", "speedKmh", "forecastWh", "forecastDistanceM", "forecastQuality",
        "designCapacityWh", "currentCapacityWh", "capacityBandKwh", "chargeState",
        "chargeRateW", "chargePortConnected", "gearRaw", "ignition", "parkingBrake",
        "drivingWhPerKm", "learnerWhPerKm", "learnerSampleKm", "learnerStatus",
        "requestedMode", "effectiveMode", "safetyHolds", "energyBasis", "estimatorRevision",
        "config", "vehicleClass", "modelDimensions", "event", "reason", "widthMm", "heightMm",
        "wheelbaseMm", "capacityBandMinKwh", "capacityBandMaxKwh",
    )

    fun requireAllowedJsonLine(line: String): String {
        val root = runCatching { Json.parseToJsonElement(line) }.getOrElse {
            throw IllegalArgumentException("compact EV record is not valid JSON", it)
        }
        require(root is JsonObject) { "compact EV record must be an object" }
        validate(root)
        return line
    }

    private fun validate(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                require(key in scalarFields) {
                    "field is not allowed in compact EV contribution: $key"
                }
                validate(value)
            }
            is JsonArray -> element.forEach { validate(it) }
            else -> Unit
        }
    }
}

/** Durable bounded queue. Completed contributions are immutable and ordered by drive start. */
class EvContributionQueue(
    private val directory: File,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxFiles: Int = 32,
) {
    data class Pending(
        val id: String,
        val startedMs: Long,
        val completedMs: Long,
        val attempts: Int,
        val nextAttemptMs: Long,
        val file: File,
    )
    data class Counters(
        val retained: Int,
        val evicted: Long,
        val accepted: Long,
        val failures: Long,
    )

    private val evicted = AtomicLong()
    private val accepted = AtomicLong()
    private val failures = AtomicLong()

    init {
        require(maxBytes > 0)
        require(maxFiles > 0)
    }

    @Synchronized
    fun append(id: String, startedMs: Long, jsonLine: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "invalid contribution id" }
        val safe = EvContributionPrivacy.requireAllowedJsonLine(jsonLine)
        ensureDirectory()
        val file = dataFile(id, startedMs)
        if (!file.exists()) {
            writeMeta(file, Meta(id, startedMs, 0L, 0, 0L))
        }
        file.appendText(safe + "\n", Charsets.UTF_8)
        if (file.length() > maxBytes) {
            file.delete()
            metaFile(file).delete()
            throw IOException("single compact EV contribution exceeds retention budget")
        }
    }

    @Synchronized
    fun close(id: String, completedMs: Long) {
        val entry = all().firstOrNull { it.id == id && it.completedMs == 0L } ?: return
        writeMeta(entry.file, Meta(id, entry.startedMs, completedMs, entry.attempts, entry.nextAttemptMs))
        retainBounded()
    }

    /** Previous-process open files are complete prefixes and become retryable. */
    @Synchronized
    fun recoverInterrupted(completedMs: Long): Int {
        val interrupted = all().filter { it.completedMs == 0L && it.file.isFile }
        interrupted.forEach {
            writeMeta(it.file, Meta(it.id, it.startedMs, completedMs, it.attempts, it.nextAttemptMs))
        }
        retainBounded()
        return interrupted.size
    }

    @Synchronized
    fun pending(): List<Pending> = all()
        .filter { it.completedMs > 0L && it.file.isFile }
        .sortedWith(compareBy<Pending> { it.startedMs }.thenBy { it.id })

    @Synchronized
    fun updateRetry(entry: Pending, attempts: Int, nextAttemptMs: Long) {
        writeMeta(entry.file, Meta(entry.id, entry.startedMs, entry.completedMs, attempts, nextAttemptMs))
        failures.incrementAndGet()
    }

    @Synchronized
    fun markAccepted(entry: Pending): Boolean {
        val removedData = !entry.file.exists() || entry.file.delete()
        val removedMeta = !metaFile(entry.file).exists() || metaFile(entry.file).delete()
        if (removedData && removedMeta) accepted.incrementAndGet()
        return removedData && removedMeta
    }

    @Synchronized
    fun deletePending(): Int {
        val entries = all()
        entries.forEach {
            it.file.delete()
            metaFile(it.file).delete()
        }
        return entries.size
    }

    @Synchronized
    fun counters(): Counters = Counters(pending().size, evicted.get(), accepted.get(), failures.get())

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "cannot create compact EV queue" }
    }

    private fun dataFile(id: String, startedMs: Long) = File(directory, "%019d_%s.evc".format(startedMs, id))
    private fun metaFile(file: File) = File(file.parentFile, file.name + ".meta")

    private data class Meta(
        val id: String,
        val startedMs: Long,
        val completedMs: Long,
        val attempts: Int,
        val nextAttemptMs: Long,
    )

    private fun all(): List<Pending> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.endsWith(".evc") }
            .mapNotNull { file -> readMeta(file)?.let { m ->
                Pending(m.id, m.startedMs, m.completedMs, m.attempts, m.nextAttemptMs, file)
            } }
            .toList()
    }

    private fun readMeta(file: File): Meta? = runCatching {
        val p = Properties().apply { metaFile(file).inputStream().use(::load) }
        Meta(
            p.getProperty("id") ?: return null,
            p.getProperty("startedMs").toLong(),
            p.getProperty("completedMs").toLong(),
            p.getProperty("attempts").toInt(),
            p.getProperty("nextAttemptMs").toLong(),
        )
    }.getOrNull()

    private fun writeMeta(file: File, meta: Meta) {
        ensureDirectory()
        val target = metaFile(file)
        val temp = File(target.parentFile, target.name + ".tmp")
        Properties().apply {
            setProperty("id", meta.id)
            setProperty("startedMs", meta.startedMs.toString())
            setProperty("completedMs", meta.completedMs.toString())
            setProperty("attempts", meta.attempts.toString())
            setProperty("nextAttemptMs", meta.nextAttemptMs.toString())
        }.also { p -> temp.outputStream().use { p.store(it, null) } }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun retainBounded() {
        var completed = pending()
        fun totalBytes() = completed.sumOf { it.file.length() }
        while (completed.size > maxFiles || totalBytes() > maxBytes) {
            val victim = completed.firstOrNull() ?: break
            victim.file.delete()
            metaFile(victim.file).delete()
            evicted.incrementAndGet()
            completed = pending()
        }
    }
}

/** One natural-event-driven attempt; this class owns no timer or polling loop. */
class EvContributionUploader(
    private val queue: EvContributionQueue,
    private val send: (file: File, idempotencyKey: String) -> Response,
) {
    data class Response(val statusCode: Int, val duplicate: Boolean)
    enum class Outcome { EMPTY, BACKOFF, RETRY, ACCEPTED, ACCEPTED_DUPLICATE }

    companion object {
        const val MAX_BACKOFF_MS = 6L * 60 * 60 * 1000
        private const val BASE_BACKOFF_MS = 30_000L
    }

    fun uploadOldest(nowMs: Long): Outcome {
        val entry = queue.pending().firstOrNull() ?: return Outcome.EMPTY
        if (nowMs < entry.nextAttemptMs) return Outcome.BACKOFF
        val response = runCatching { send(entry.file, entry.id) }.getOrElse { Response(0, false) }
        val accepted = response.statusCode in 200..299 || response.duplicate
        if (accepted) {
            if (!queue.markAccepted(entry)) return retry(entry, nowMs)
            return if (response.duplicate) Outcome.ACCEPTED_DUPLICATE else Outcome.ACCEPTED
        }
        return retry(entry, nowMs)
    }

    private fun retry(entry: EvContributionQueue.Pending, nowMs: Long): Outcome {
        val attempts = entry.attempts + 1
        val shift = (attempts - 1).coerceAtMost(20)
        val delay = (BASE_BACKOFF_MS * (1L shl shift)).coerceAtMost(MAX_BACKOFF_MS)
        queue.updateRetry(entry, attempts, nowMs + delay)
        return Outcome.RETRY
    }
}
