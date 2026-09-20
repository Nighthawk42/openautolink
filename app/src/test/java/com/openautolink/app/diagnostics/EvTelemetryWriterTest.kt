package com.openautolink.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EvTelemetryWriterTest {
    @Test fun blockedDiskNeverBlocksProducerAndOverflowIsCounted() {
        val dir = Files.createTempDirectory("ev-blocked").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object : java.io.File(dir.path) {
            override fun isDirectory(): Boolean {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return super.isDirectory()
            }
        }
        val writer = EvTelemetryWriter(blocking, queueSize = 1)
        try {
            assertTrue(writer.offer(mapOf("sequence" to 1)))
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(writer.offer(mapOf("sequence" to 2)))
            assertFalse(writer.offer(mapOf("sequence" to 3)))
            assertEquals(1L, writer.dropped.get())
            assertFalse(writer.flushForUpload(0))
            release.countDown()
            assertTrue(writer.flushForUpload())
            val text = dir.listFiles()!!.joinToString { it.readText() }
            assertTrue(text.contains("\"sequence\":1"))
            assertTrue(text.contains("\"sequence\":2"))
            assertFalse(text.contains("\"sequence\":3"))
        } finally { release.countDown(); writer.close(); dir.deleteRecursively() }
    }

    @Test fun boundsChunksRetentionAndFlushesAnUploadSnapshot() {
        val dir = Files.createTempDirectory("ev-capture").toFile()
        val old = java.io.File(dir, "ev_old.log").apply { writeText("old"); setLastModified(1) }
        val writer = EvTelemetryWriter(dir, maxFileBytes = 200, maxTotalBytes = 600, maxFiles = 3)
        try {
            repeat(30) { writer.offer(mapOf("type" to "sample", "value" to "x".repeat(80))) }
            assertTrue(writer.flushForUpload())
            val files = dir.listFiles()!!.filter { it.name.startsWith("ev_") }
            assertFalse(old.exists())
            assertTrue(files.size <= 3)
            assertTrue(files.sumOf { it.length() } <= 600)
            assertTrue(files.all { it.length() <= 200 && it.readLines().all { s -> s.startsWith("{") && s.endsWith("}") } })
            assertTrue(writer.evictedFiles.get() > 0)
        } finally { writer.close(); dir.deleteRecursively() }
    }
}
