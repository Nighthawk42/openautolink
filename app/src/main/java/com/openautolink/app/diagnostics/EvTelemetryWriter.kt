package com.openautolink.app.diagnostics

import java.io.File
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*

/** Bounded best-effort local JSONL sink; never runs disk work on the caller. */
class EvTelemetryWriter(
    private val directory: File,
    private val maxFileBytes: Long = 1024 * 1024,
    private val maxTotalBytes: Long = 8 * 1024 * 1024,
    private val maxFiles: Int = 32,
    queueSize: Int = 256,
) : Closeable {
    val dropped = AtomicLong()
    val errors = AtomicLong()
    val evictedFiles = AtomicLong()
    private val queue = ArrayBlockingQueue<() -> Unit>(queueSize)
    @Volatile private var closed = false
    private var current: File? = null
    private val worker = Thread({
        while (!closed || queue.isNotEmpty()) {
            val task = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
            try { task() } catch (_: Exception) { errors.incrementAndGet() }
        }
    }, "OalEvCapture").apply { isDaemon = true; start() }

    fun offer(record: Map<String, Any?>): Boolean {
        if (closed || !queue.offer { append(record) }) { dropped.incrementAndGet(); return false }
        return true
    }
    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> if (value.toDouble().isFinite()) JsonPrimitive(value) else JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonArray(value.map { json(it) })
        else -> JsonPrimitive(value.toString())
    }
    private fun append(record: Map<String, Any?>) {
        val bytes = (json(record).toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFileBytes) { dropped.incrementAndGet(); return }
        check(directory.isDirectory || directory.mkdirs())
        if (current == null || !current!!.exists() || current!!.length() + bytes.size > maxFileBytes) {
            current = File(directory, "ev_${System.currentTimeMillis()}_${UUID.randomUUID()}.log")
        }
        retain(bytes.size.toLong())
        // A failed eviction must not allow the configured storage budget to grow.
        val files = files()
        check(files.sumOf { it.length() } + bytes.size <= maxTotalBytes)
        check(files.size + (if (current!!.exists()) 0 else 1) <= maxFiles)
        current!!.appendBytes(bytes) // closed on each compact record; upload reads a stable prefix
    }
    private fun files() = directory.listFiles()?.filter { it.name.startsWith("ev_") && it.name.endsWith(".log") && it.isFile } ?: emptyList()
    private fun retain(incoming: Long) {
        var files = files().sortedBy { it.lastModified() }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        for (file in files) {
            if (file == current) continue
            if (file.lastModified() < cutoff || files.sumOf { it.length() } + incoming > maxTotalBytes ||
                files.size + (if (current!!.exists()) 0 else 1) > maxFiles) {
                if (file.delete()) { evictedFiles.incrementAndGet(); files = files - file }
            }
        }
    }
    fun flushForUpload(timeoutMs: Long = 2000, snapshot: Map<String, Any?>? = null): Boolean {
        // A closed writer cannot admit a required snapshot, even if its queue drained.
        // An empty queue alone also does not prove an in-flight append has finished.
        if (closed) return snapshot == null && !worker.isAlive && errors.get() == 0L
        val done = CountDownLatch(1)
        val start = System.nanoTime()
        val snapshotWritten = java.util.concurrent.atomic.AtomicBoolean(snapshot == null)
        val accepted = queue.offer({
            try {
                if (snapshot != null) {
                    val before = dropped.get()
                    append(snapshot + mapOf("dropped" to before, "writerErrors" to errors.get(),
                        "retentionEvictedFiles" to evictedFiles.get()))
                    snapshotWritten.set(dropped.get() == before)
                }
            } finally { done.countDown() }
        }, timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        val left = timeoutMs - (System.nanoTime() - start) / 1000000
        return accepted && done.await(left.coerceAtLeast(0), TimeUnit.MILLISECONDS) && errors.get() == 0L && snapshotWritten.get()
    }
    override fun close() { closed = true }
}
