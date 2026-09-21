package com.openautolink.app.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogUploadArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun log(name: String, text: String = "sample\n", age: Long = 0): File =
        File(temp.root, name).apply { parentFile.mkdirs(); writeText(text); setLastModified(now - age) }
    private fun entries(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }
    }

    @Test fun receiverLimitsNeverUseOversizedFallback() {
        val huge = log("logcat_huge.log")
        RandomAccessFile(huge, "rw").use { it.setLength(40L * 1024 * 1024) }
        huge.setLastModified(now)
        val onlyHuge = LogUploadArchive.select(listOf(huge), now)
        assertTrue(onlyHuge.selected.isEmpty())
        assertEquals("member_bytes_limit", onlyHuge.skipped.single().reason)
        val files = (0..69).map { log("v$it/ev_${it}.log") }
        val plan = LogUploadArchive.select(files + huge, now)
        assertEquals(63, plan.selected.size)
        assertEquals(7, plan.skipped.count { it.reason == "member_count_limit" })
        LogUploadArchive.create(plan, temp.root).use { assertEquals(64, entries(it.file).size) }
    }

    @Test fun totalBudgetReservesManifestAndPrioritizesEv() {
        val regular = (0..2).map { log("logcat_$it.log").apply {
            RandomAccessFile(this, "rw").use { it.setLength(10L * 1024 * 1024) }; setLastModified(now)
        } }
        val ev = log("ev_old.log", age = 3 * 86_400_000L)
        val plan = LogUploadArchive.select(regular + ev, now)
        assertEquals(ev, plan.selected.first().source)
        assertEquals(2, plan.selected.count { !it.ev })
        assertEquals("total_bytes_limit", plan.skipped.single().reason)
        LogUploadArchive.create(plan, temp.root).use { archive ->
            assertTrue(entries(archive.file).values.sumOf { it.size.toLong() } <= 25L * 1024 * 1024)
        }
    }

    @Test fun multivolumeDuplicateNamesAreCasefoldUniqueAndSafe() {
        val files = listOf(log("one/oal_same.log"), log("two/OAL_SAME.log"), log("three/bad name.log"), log("upload_manifest.log"))
        val plan = LogUploadArchive.select(files + files.first(), now)
        LogUploadArchive.create(plan, temp.root).use { archive ->
            val bytes = entries(archive.file)
            assertEquals(5, bytes.size)
            assertEquals(bytes.size, bytes.keys.map { it.lowercase() }.toSet().size)
            assertTrue(bytes.keys.all { it.matches(Regex("[A-Za-z0-9._-]+\\.log", RegexOption.IGNORE_CASE)) })
            assertTrue(bytes.keys.contains("oal_same.log"))
            assertFalse(bytes.getValue("upload_manifest.log").toString(Charsets.UTF_8).contains(temp.root.path))
        }
    }

    @Test fun growingSourceUploadsOnlySelectedBytesAndReportsOmittedTail() {
        val source = log("oal_growing.log", "before\n")
        val plan = LogUploadArchive.select(listOf(source), now)
        source.appendText("after\n")
        LogUploadArchive.create(plan, temp.root).use { archive ->
            val bytes = entries(archive.file)
            assertEquals("before\n", bytes.getValue(source.name).toString(Charsets.UTF_8))
            val manifest = bytes.getValue("upload_manifest.log").toString(Charsets.UTF_8)
            assertTrue(manifest.contains("selected_bytes=7"))
            assertTrue(manifest.contains("regular_truncated=1"))
            assertTrue(manifest.contains("modified_ms=$now"))
        }
        assertEquals("before\nafter\n", source.readText())
    }

    @Test fun incompleteUtf8TailIsOmittedWithoutInvalidReceiverText() {
        val source = log("oal_unicode.log")
        source.writeBytes("ok\n".toByteArray() + byteArrayOf(0xe2.toByte(), 0x82.toByte()))
        source.setLastModified(now)
        LogUploadArchive.create(LogUploadArchive.select(listOf(source), now), temp.root).use { archive ->
            assertEquals("ok\n", entries(archive.file).getValue(source.name).toString(Charsets.UTF_8))
        }
    }

    @Test fun shrinkingSourceFailsWithoutDeletingLogsOrLeavingTemporaryZip() {
        val source = log("ev_shrinking.log", "before\n")
        val plan = LogUploadArchive.select(listOf(source), now)
        source.writeText("x")
        assertThrows(java.io.IOException::class.java) { LogUploadArchive.create(plan, temp.root) }
        assertEquals("x", source.readText())
        assertFalse(temp.root.listFiles()!!.any { it.extension == "zip" })
    }

    @Test fun uploadFailureCleansOnlyTemporaryArchiveAndSourcesCanRetry() {
        val source = log("ev_keep.log")
        val plan = LogUploadArchive.select(listOf(source), now)
        var payload: File? = null
        assertThrows(java.io.IOException::class.java) {
            LogUploadArchive.create(plan, temp.root).use { archive ->
                payload = archive.file
                assertArrayEquals(source.readBytes(), entries(archive.file).getValue(source.name))
                throw java.io.IOException("simulated rejected transport")
            }
        }
        assertTrue(source.exists())
        assertFalse(payload!!.exists())
        LogUploadArchive.create(plan, temp.root).use { assertTrue(it.file.exists()) }
    }

    @Test fun changedSameLengthSourceFailsRatherThanClaimingTheSelectedSnapshot() {
        val source = log("ev_replaced.log", "first\n")
        val plan = LogUploadArchive.select(listOf(source), now)
        source.writeText("other\n")
        source.setLastModified(now + 1)
        assertThrows(java.io.IOException::class.java) { LogUploadArchive.create(plan, temp.root) }
        assertTrue(source.exists())
    }

    @Test fun exactServerMemberBoundaryIsExcluded() {
        val source = log("logcat_boundary.log")
        RandomAccessFile(source, "rw").use { it.setLength(16L * 1024 * 1024) }
        source.setLastModified(now)
        assertTrue(LogUploadArchive.select(listOf(source), now).selected.isEmpty())
        RandomAccessFile(source, "rw").use { it.setLength(16L * 1024 * 1024 - 1) }
        source.setLastModified(now)
        assertEquals(1, LogUploadArchive.select(listOf(source), now).selected.size)
    }

    @Test fun retainedEvRotationsPrecedeRecentRegularLogsInRealZip() {
        val recent = log("oal_recent.log")
        val rotations = (0..3).map { log("ev_trip_$it.log", "{\"sequence\":$it}\n", (it + 1) * 86_400_000L) }
        val expired = log("ev_expired.log", age = 8 * 86_400_000L)
        val plan = LogUploadArchive.select(listOf(recent, expired) + rotations, now)
        assertEquals(rotations.toSet(), plan.selected.take(4).map { it.source }.toSet())
        LogUploadArchive.create(plan, temp.root).use { archive ->
            val bytes = entries(archive.file)
            assertEquals(rotations.map { it.name }.toSet() + recent.name + "upload_manifest.log", bytes.keys)
            rotations.forEach { assertArrayEquals(it.readBytes(), bytes.getValue(it.name)) }
            val manifest = bytes.getValue("upload_manifest.log").toString(Charsets.UTF_8)
            assertTrue(manifest.contains("ev_files=4"))
            assertTrue(manifest.contains("outside_ev_retention"))
            assertTrue(manifest.contains("coverage=filesystem_mtime_not_continuous_drive"))
            System.getProperty("oal.upload.fixture")?.let { archive.file.copyTo(File(it), overwrite = true) }
        }
    }
}
