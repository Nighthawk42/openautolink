package com.openautolink.app.carplay

import org.robolectric.annotation.ConscryptMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayPresentationExportTest {
    @Test fun presentationReceiptsSurviveRealSanitizerAndDiagnosticArchive() {
        val receipts = listOf(
            "media stage=presentation-attached generation=2",
            "media stage=presentation-detached generation=3",
            "media stage=presentation-rendered generation=4 codecGeneration=5",
            "media stage=surface-lost",
            "media stage=video-retired",
            "media stage=video-config action=accepted receipt=1 count=1 codec=H265",
            "media stage=video-config action=duplicate receipt=1 count=32 codec=H265",
            "media stage=video-reconfigure reason=config-change receipt=2 count=2",
            "media stage=video-recovery reason=input-timeout receipt=2 count=3",
            "media stage=video-recovery reason=input-size receipt=2 count=4",
        )
        val lines = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(7, { 1L }, lines::add)
        receipts.forEach(reporter::record)
        assertEquals(receipts.size, lines.size)
        receipts.zip(lines).forEach { (receipt, line) ->
            assertEquals("CARPLAY_AUDIO session=7 $receipt", line)
        }
        val folder = java.nio.file.Files.createTempDirectory(
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "presentation-export"
        ).toFile()
        try {
            val contents = lines.joinToString("\n", postfix = "\n")
            val log = java.io.File(folder, "oal_presentation.log").apply { writeText(contents) }
            val plan = com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(log), log.lastModified())
            com.openautolink.app.diagnostics.LogUploadArchive.create(plan, folder).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertEquals(contents, zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                }
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun presentationSchemasRejectFreeTextSuffixesAndMalformedCounters() {
        listOf(
            "media stage=presentation-attached generation=2 private=secret",
            "media stage=presentation-detached generation=-1",
            "media stage=presentation-rendered generation=4 codecGeneration=5\nsecret",
            "media stage=presentation-rendered generation=null codecGeneration=5",
            "media stage=video-retired secret",
            "media stage=video-config action=duplicate receipt=1 count=1 codec=H265 bytes=secret",
            "media stage=video-config action=accepted receipt=-1 count=1 codec=H265",
            "media stage=video-config action=accepted receipt=1 count=1 codec=secret",
            "media stage=video-reconfigure reason=secret receipt=1 count=1",
            "media stage=video-recovery reason=input-timeout receipt=1 count=1\nsecret",
        ).forEach { assertNull(it, CarPlayAudioDiagnostics.sanitize(7, it)) }
    }
}
