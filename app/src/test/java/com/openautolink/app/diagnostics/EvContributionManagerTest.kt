package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EvContributionManagerTest {
    private fun dir(): File = Files.createTempDirectory("ev-contributions").toFile()

    @Test fun `default consent is off and existing upload key does not grant consent`() {
        assertFalse(EvContributionPolicy.mayCapture(consent = false, uploadUrl = "https://logs.test/upload", token = "existing"))
        assertTrue(EvContributionPolicy.mayCapture(consent = true, uploadUrl = "https://logs.test/upload", token = "existing"))
    }

    @Test fun `upload requires validated internet parked idle and outside startup`() {
        val ready = EvContributionPolicy.UploadContext(true, parked = true, idle = true, startupSensitive = false)
        assertTrue(EvContributionPolicy.mayUpload(consent = true, ready))
        assertFalse(EvContributionPolicy.mayUpload(consent = false, ready))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(validatedInternet = false)))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(parked = false)))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(idle = false)))
        assertFalse(EvContributionPolicy.mayUpload(true, ready.copy(startupSensitive = true)))
    }

    @Test fun `closed contributions survive recreation and are oldest first`() {
        val root = dir()
        EvContributionQueue(root, maxBytes = 10_000, maxFiles = 8).apply {
            append("b", 20, "{\"type\":\"vehicle\"}")
            close("b", 21)
            append("a", 10, "{\"type\":\"vehicle\"}")
            close("a", 11)
        }
        val restored = EvContributionQueue(root, maxBytes = 10_000, maxFiles = 8)
        assertEquals(listOf("a", "b"), restored.pending().map { it.id })
    }

    @Test fun `process recreation closes an interrupted drive without losing it`() {
        val root = dir()
        EvContributionQueue(root, maxBytes = 10_000, maxFiles = 8)
            .append("wife-drive", 10, "{\"type\":\"vehicle\",\"batteryWh\":50000}")
        val restored = EvContributionQueue(root, maxBytes = 10_000, maxFiles = 8)
        assertEquals(1, restored.recoverInterrupted(20))
        assertEquals("wife-drive", restored.pending().single().id)
    }

    @Test fun `queue is bounded and evicts oldest completed contribution`() {
        val root = dir()
        val queue = EvContributionQueue(root, maxBytes = 100, maxFiles = 2)
        for (id in listOf("a", "b", "c")) {
            queue.append(id, id[0].code.toLong(), "{\"type\":\"vehicle\",\"safetyHolds\":\"1234567890\"}")
            queue.close(id, id[0].code.toLong())
        }
        assertEquals(listOf("b", "c"), queue.pending().map { it.id })
        assertEquals(1L, queue.counters().evicted)
    }

    @Test fun `privacy allowlist rejects coordinates destinations logs and identifiers`() {
        val allowed = """{"schema":1,"type":"vehicle","batteryWh":50000,"distanceM":42,"forecastWh":49000,"config":{"capacityBandKwh":80}}"""
        assertEquals(allowed, EvContributionPrivacy.requireAllowedJsonLine(allowed))
        for (forbidden in listOf("latitude", "longitude", "destination", "vin", "deviceId", "token", "road", "logcat", "rawLog")) {
            val line = "{\"type\":\"vehicle\",\"$forbidden\":\"secret\"}"
            assertThrows(IllegalArgumentException::class.java) { EvContributionPrivacy.requireAllowedJsonLine(line) }
        }
    }

    @Test fun `accepted and duplicate responses delete exactly once while failures retain`() {
        val root = dir()
        val queue = EvContributionQueue(root, maxBytes = 10_000, maxFiles = 8)
        queue.append("drive", 1, "{\"type\":\"vehicle\"}")
        queue.close("drive", 2)
        val failed = EvContributionUploader(queue) { _, _ -> EvContributionUploader.Response(503, false) }
        assertEquals(EvContributionUploader.Outcome.RETRY, failed.uploadOldest(nowMs = 100))
        assertEquals(1, queue.pending().size)
        val retryAt = queue.pending().single().nextAttemptMs
        val duplicate = EvContributionUploader(queue) { _, key ->
            assertEquals("drive", key)
            EvContributionUploader.Response(200, duplicate = true)
        }
        assertEquals(EvContributionUploader.Outcome.ACCEPTED_DUPLICATE, duplicate.uploadOldest(nowMs = retryAt))
        assertTrue(queue.pending().isEmpty())
        assertEquals(EvContributionUploader.Outcome.EMPTY, duplicate.uploadOldest(nowMs = 201))
    }

    @Test fun `retry is exponential bounded and does not wake poll`() {
        val root = dir()
        val queue = EvContributionQueue(root, maxBytes = 10_000, maxFiles = 8)
        queue.append("drive", 1, "{\"type\":\"vehicle\"}")
        queue.close("drive", 2)
        val uploader = EvContributionUploader(queue) { _, _ -> EvContributionUploader.Response(500, false) }
        assertEquals(EvContributionUploader.Outcome.RETRY, uploader.uploadOldest(100))
        val first = queue.pending().single().nextAttemptMs
        assertEquals(EvContributionUploader.Outcome.BACKOFF, uploader.uploadOldest(first - 1))
        assertEquals(EvContributionUploader.Outcome.RETRY, uploader.uploadOldest(first))
        val second = queue.pending().single().nextAttemptMs
        assertTrue(second - first > first - 100)
        assertTrue(second - first <= EvContributionUploader.MAX_BACKOFF_MS)
    }

    @Test fun `revocation deletes only pending compact contributions`() {
        val root = dir()
        val ordinary = File(root, "oal_ordinary.log").apply { writeText("keep") }
        val queue = EvContributionQueue(File(root, "compact"), maxBytes = 10_000, maxFiles = 8)
        queue.append("drive", 1, "{\"type\":\"vehicle\"}")
        queue.close("drive", 2)
        assertEquals(1, queue.deletePending())
        assertTrue(queue.pending().isEmpty())
        assertTrue(ordinary.isFile)
    }
}
