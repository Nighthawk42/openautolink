package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

class EvContributionProtocolTest {
    private fun dir(): File = Files.createTempDirectory("ev-protocol").toFile()
    private fun queue(root: File = dir()) = EvContributionQueue(root, maxBytes = 100_000, maxFiles = 8)
    private fun closed(q: EvContributionQueue): EvContributionQueue.Pending {
        q.append("drive", 1, "{\"schema\":2,\"type\":\"vehicle\",\"batteryWh\":50000}", "owner-device")
        q.close("drive", 2)
        return q.pending().single()
    }

    @Test fun `completed drive is immutable deterministic flat compact zip across recreation`() {
        val root = dir(); val q = queue(root); val first = closed(q)
        val bytes = first.file.readBytes()
        assertTrue(first.file.name.endsWith(".zip"))
        ZipFile(first.file).use { zip ->
            assertEquals(setOf("telemetry.log", "manifest.log"), zip.entries().asSequence().map { it.name }.toSet())
            assertTrue(zip.entries().asSequence().all { it.name.endsWith(".log") })
            assertTrue(zip.entries().asSequence().all { !it.name.contains('/') && !it.isDirectory })
        }
        val restored = queue(root).pending().single()
        assertArrayEquals(bytes, restored.file.readBytes())
        assertEquals(first.sha256, restored.sha256)
        assertEquals("owner-device", restored.namespace)
        assertFalse(root.walkTopDown().filter { it.isFile }.any { it.readText().contains("raw-secret-token") })
    }

    @Test fun `compiled candidate exports the server contract archive`() {
        val pending = closed(queue())
        val output = File("build/integration/ev-candidate.zip")
        output.parentFile!!.mkdirs()
        pending.file.copyTo(output, overwrite = true)
        ZipFile(output).use { zip ->
            assertEquals(setOf("telemetry.log", "manifest.log"), zip.entries().asSequence().map { it.name }.toSet())
        }
    }

    @Test fun `crash after zip publication keeps closed unit and removes redundant source`() {
        val root = dir(); val q = queue(root)
        q.append("drive", 1, "{\"schema\":2,\"type\":\"vehicle\",\"batteryWh\":50000}", "owner-device")
        val source = root.listFiles()!!.single { it.extension == "evc" }
        val sourceMeta = File(root, source.name + ".meta")
        val sourceBytes = source.readBytes(); val sourceMetaBytes = sourceMeta.readBytes()
        q.close("drive", 2)
        source.writeBytes(sourceBytes); sourceMeta.writeBytes(sourceMetaBytes)

        val recreated = queue(root)

        assertEquals(1, recreated.pending().size)
        assertFalse(source.exists())
        assertFalse(sourceMeta.exists())
    }

    @Test fun `wire archive names and strings omit exact epoch and uuid`() {
        val q = queue()
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val epoch = 1_725_555_444_333L
        q.append(id, epoch, "{\"schema\":2,\"type\":\"vehicle\",\"elapsedBucketS\":60}")
        q.close(id, epoch + 9_999)
        val pending = q.pending().single()
        val text = ZipFile(pending.file).use { zip ->
            zip.entries().asSequence().joinToString("\n") { entry ->
                entry.name + "\n" + zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }
        assertFalse(text.contains(id))
        assertFalse(text.contains(epoch.toString()))
        assertFalse(text.contains((epoch + 9_999).toString()))
        assertEquals(setOf("telemetry.log", "manifest.log"), ZipFile(pending.file).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        })
    }

    @Test fun `ack contract accepts only exact 200 ok digest and duplicate`() {
        fun outcome(status: Int, body: String): EvContributionUploader.Outcome {
            val q = queue(); val p = closed(q)
            return EvContributionUploader(q, jitter = { 0 }) { _, _ -> EvContributionUploader.Response(status, body) }
                .uploadOldest(100)
        }
        val digestQueue = queue(); val p = closed(digestQueue)
        val accepted = EvContributionUploader(digestQueue, jitter = { 0 }) { _, _ ->
            EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${p.sha256}\"}")
        }
        assertEquals(EvContributionUploader.Outcome.ACCEPTED, accepted.uploadOldest(100))
        for ((status, body) in listOf(
            201 to "{\"ok\":true,\"sha256\":\"x\"}",
            200 to "not-json", 200 to "{\"ok\":false,\"sha256\":\"x\"}",
            200 to "{\"ok\":true,\"sha256\":\"wrong\"}",
            500 to "{}", 429 to "{}",
        )) assertEquals(EvContributionUploader.Outcome.RETRY, outcome(status, body))

        val duplicateQ = queue(); val duplicateP = closed(duplicateQ)
        val duplicate = EvContributionUploader(duplicateQ) { _, _ ->
            EvContributionUploader.Response(200, "{\"ok\":true,\"duplicate\":true,\"sha256\":\"${duplicateP.sha256}\"}")
        }
        assertEquals(EvContributionUploader.Outcome.ACCEPTED_DUPLICATE, duplicate.uploadOldest(100))
    }

    @Test fun `401 fences and validation rejections quarantine while retryable failures retain`() {
        for (status in listOf(413, 422)) {
            val q = queue(); closed(q)
            val u = EvContributionUploader(q) { _, _ -> EvContributionUploader.Response(status, "{}") }
            assertEquals(EvContributionUploader.Outcome.QUARANTINED, u.uploadOldest(100))
            assertTrue(q.pending().isEmpty()); assertEquals(1, q.counters().quarantined)
        }
        val q = queue(); closed(q); var fenced = false
        val auth = EvContributionUploader(q, onUnauthorized = { fenced = true }) { _, _ -> EvContributionUploader.Response(401, "{}") }
        assertEquals(EvContributionUploader.Outcome.UNAUTHORIZED, auth.uploadOldest(100)); assertTrue(fenced); assertEquals(1, q.pending().size)
        assertTrue(q.pending().single().nextAttemptMs > 100)
    }

    @Test fun `backoff is jitter injectable and capped at 24 hours`() {
        val q = queue(); closed(q)
        val u = EvContributionUploader(q, jitter = { 1234 }) { _, _ -> EvContributionUploader.Response(503, "{}") }
        var now = 100L
        repeat(30) { u.uploadOldest(now); now = q.pending().single().nextAttemptMs }
        assertTrue(q.pending().single().nextAttemptMs - now <= EvContributionUploader.MAX_BACKOFF_MS + 1234)
        assertEquals(24L * 60 * 60 * 1000, EvContributionUploader.MAX_BACKOFF_MS)
    }

    @Test fun `network loss retries byte identical archive after recreation`() {
        val root = dir(); val q = queue(root); val p = closed(q); val original = p.file.readBytes()
        val lost = EvContributionUploader(q, jitter = { 0 }) { _, _ -> throw java.io.EOFException("ack lost") }
        assertEquals(EvContributionUploader.Outcome.RETRY, lost.uploadOldest(100))
        val restored = queue(root); val retryAt = restored.pending().single().nextAttemptMs
        var posted = ByteArray(0)
        val accepted = EvContributionUploader(restored) { file, _ ->
            posted = file.readBytes()
            EvContributionUploader.Response(200, "{\"ok\":true,\"duplicate\":true,\"sha256\":\"${restored.pending().single().sha256}\"}")
        }
        assertEquals(EvContributionUploader.Outcome.ACCEPTED_DUPLICATE, accepted.uploadOldest(retryAt))
        assertArrayEquals(original, posted)
    }

    @Test fun `upload policy blocks moving reconnecting startup active and unvalidated contexts`() {
        val ready = EvContributionPolicy.UploadContext(true, true, true, false)
        assertTrue(EvContributionPolicy.mayUpload(true, ready))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(parked = false)))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(reconnecting = true)))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(projectionActive = true)))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(startupSensitive = true)))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(validatedInternet = false)))
    }

    @Test fun `upload policy requires a fresh park observation from this process`() {
        val ready = EvContributionPolicy.UploadContext(
            validatedInternet = true,
            parked = true,
            idle = true,
            startupSensitive = false,
            freshParkObservedThisProcess = false,
        )
        assertFalse(EvContributionPolicy.mayUpload(true, ready))
        assertTrue(EvContributionPolicy.mayUpload(true, ready.copy(freshParkObservedThisProcess = true)))
    }

    @Test fun `revocation generation fences a completed in flight response`() {
        val fence = EvContributionGenerationFence()
        val attempt = fence.snapshot()
        fence.invalidate()
        assertFalse(fence.isCurrent(attempt))
        val next = fence.snapshot()
        assertTrue(fence.isCurrent(next))
    }

    @Test fun `accepted batch rearms for a subsequent completed drive`() {
        val q = queue(); var sends = 0
        fun uploader() = EvContributionUploader(q) { _, _ ->
            sends++
            EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${q.pending().first().sha256}\"}")
        }
        closed(q)
        assertEquals(EvContributionUploader.Outcome.ACCEPTED, uploader().uploadOldest(100))
        q.append("second", 3, "{\"schema\":2,\"type\":\"vehicle\"}"); q.close("second", 4)
        assertEquals(EvContributionUploader.Outcome.ACCEPTED, uploader().uploadOldest(200))
        assertEquals(2, sends); assertTrue(q.pending().isEmpty())
    }

    @Test fun `consent binding normalizes https origin and binds schema and token fingerprint`() {
        val a = EvContributionConsentBinding.create("HTTPS://Logs.Example:443/upload?q=1", "secret")!!
        val same = EvContributionConsentBinding.create("https://logs.example/other", "secret")!!
        assertEquals("https://logs.example", a.origin)
        assertEquals(a, same)
        assertFalse(a.tokenFingerprint.contains("secret"))
        assertTrue(a.matches("https://logs.example/upload", "secret"))
        assertFalse(a.matches("https://other.example/upload", "secret"))
        assertFalse(a.matches("https://logs.example/upload", "changed"))
        assertNull(EvContributionConsentBinding.create("http://logs.example/upload", "secret"))
    }

    @Test fun `privacy schema rejects nested containers exact wall time and identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            EvContributionPrivacy.requireAllowedJsonLine("{\"schema\":2,\"type\":\"vehicle\",\"config\":{\"batteryWh\":1}}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvContributionPrivacy.requireAllowedJsonLine("{\"schema\":2,\"type\":\"vehicle\",\"wallMs\":1}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvContributionPrivacy.requireAllowedJsonLine("{\"schema\":2,\"type\":\"vehicle\",\"deviceId\":\"x\"}")
        }
    }

    @Test fun `http transport posts fixed zip contract and disables redirects`() {
        val q = queue(); val pending = closed(q)
        val wire = java.io.ByteArrayOutputStream(); var disconnected = false
        val ack = "{\"ok\":true,\"sha256\":\"${pending.sha256}\"}"
        val connection = object : java.net.HttpURLConnection(java.net.URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
            override fun getOutputStream() = wire
            override fun getResponseCode() = 200
            override fun getInputStream() = ack.byteInputStream()
            fun configuredFixedLength(): Long = fixedContentLengthLong
        }
        val response = EvContributionHttpTransport { connection }
            .send("https://logs.example/upload", "token-value", "car", pending.file)
        assertEquals(200, response.statusCode)
        assertArrayEquals(pending.file.readBytes(), wire.toByteArray())
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/zip", connection.getRequestProperty("Content-Type"))
        assertEquals("token-value", connection.getRequestProperty("X-Upload-Token"))
        assertEquals("car", connection.getRequestProperty("X-Device-Label"))
        assertEquals("ev-contribution.zip", connection.getRequestProperty("X-Orig-Name"))
        assertFalse(connection.headerFields.toString().contains(pending.id))
        assertFalse(connection.headerFields.toString().contains(pending.startedMs.toString()))
        assertEquals(pending.file.length(), connection.configuredFixedLength())
        assertFalse(connection.instanceFollowRedirects)
        assertFalse("successful cleanup must not synchronously disconnect", disconnected)
    }

    @Test fun `real fixed length HttpURLConnection server accepts exact body and deduplicates replay`() {
        val q = queue(); val pending = closed(q)
        val requests = AtomicInteger()
        val acceptedBodies = mutableListOf<ByteArray>()
        val server = ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())
        val serverThread = kotlin.concurrent.thread(name = "ev-exact-server") {
            repeat(2) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().buffered()
                    val headers = mutableListOf<String>()
                    val line = StringBuilder()
                    var previous = -1
                    while (true) {
                        val value = input.read()
                        if (value < 0) error("unexpected request EOF")
                        if (previous == '\r'.code && value == '\n'.code) {
                            val header = line.substring(0, line.length - 1)
                            line.clear()
                            if (header.isEmpty()) break
                            headers += header
                        } else line.append(value.toChar())
                        previous = value
                    }
                    val length = headers.first { it.startsWith("Content-Length:", true) }
                        .substringAfter(':').trim().toInt()
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < body.size) {
                        val count = input.read(body, offset, body.size - offset)
                        if (count < 0) error("short fixed-length request")
                        offset += count
                    }
                    synchronized(acceptedBodies) { acceptedBodies += body }
                    val duplicate = requests.getAndIncrement() > 0
                    val ack = "{\"ok\":true,\"duplicate\":$duplicate,\"sha256\":\"${sha256(body)}\"}".toByteArray()
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${ack.size}\r\nConnection: close\r\n\r\n".toByteArray() + ack
                    socket.getOutputStream().apply { write(response); flush() }
                }
            }
        }
        try {
            val url = "http://127.0.0.1:${server.localPort}/upload"
            val first = EvContributionHttpTransport().send(url, "token", "ev-class-test", pending.file)
            val second = EvContributionHttpTransport().send(url, "token", "ev-class-test", pending.file)
            serverThread.join(2_000)
            assertFalse(serverThread.isAlive)
            assertEquals(200, first.statusCode)
            assertFalse(first.body.contains("\"duplicate\":true"))
            assertTrue(second.body.contains("\"duplicate\":true"))
            assertEquals(2, requests.get())
            assertTrue(acceptedBodies.all { it.contentEquals(pending.file.readBytes()) })
        } finally {
            server.close()
        }
    }

    @Test fun `retention enforces age and persists visible eviction counters`() {
        val root = dir(); val q = EvContributionQueue(root, 100_000, 8, maxAgeMs = 10)
        q.append("old", 1, "{\"schema\":2,\"type\":\"vehicle\"}"); q.close("old", 2)
        q.enforceRetention(20)
        assertTrue(q.pending().isEmpty())
        assertEquals(1L, EvContributionQueue(root, 100_000, 8, 10).counters().evicted)
    }
}
