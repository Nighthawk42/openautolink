package com.openautolink.app.diagnostics

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogUploaderTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun cleanup() = unmockkAll()

    @Test fun manualUploadFlushesBeforeSelectionStreamsZipAndRetainsSourcesOnRejection() {
        val base = temp.newFolder("volume")
        val logs = File(base, "openautolink/logs").apply { mkdirs() }
        val cache = temp.newFolder("cache")
        val context = mockk<Context>()
        every { context.getExternalFilesDirs(null) } returns arrayOf(base)
        every { context.cacheDir } returns cache
        mockkObject(OalLog)
        every { OalLog.w(any(), any()) } returns Unit
        every { OalLog.i(any(), any()) } returns Unit
        every { OalLog.e(any(), any()) } returns Unit
        val source = File(logs, "ev_flushed.log")
        val wire = ByteArrayOutputStream()
        var disconnected = false
        var response = 503
        val conn = object : HttpURLConnection(URL("https://logs.example.test/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
            override fun getOutputStream() = wire
            override fun getResponseCode() = response
        }
        val uploader = LogUploader(context, { conn }, {
            source.writeText("{\"schema\":1,\"sample\":true}\n")
            true
        })
        val result = uploader.upload("https://logs.example.test/upload", "secret-test-token", "test-car")
        assertTrue(result is UploadResult.Failure)
        assertTrue(disconnected)
        assertTrue(source.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
        val members = mutableMapOf<String, String>()
        ZipInputStream(wire.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; members[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(source.readText(), members[source.name])
        assertTrue(members.getValue("upload_manifest.log").contains("ev_flush_confirmed=true"))
        assertFalse(members.values.any { it.contains("secret-test-token") })
        assertEquals("secret-test-token", conn.getRequestProperty("X-Upload-Token"))
        assertFalse(conn.instanceFollowRedirects)
        response = 200
        wire.reset()
        val success = uploader.upload("https://logs.example.test/upload", "secret-test-token", "test-car")
        assertTrue(success is UploadResult.Success)
        assertEquals(1, (success as UploadResult.Success).fileCount)
        assertTrue(success.coverage.contains("EV 1 included"))
        assertTrue(success.coverage.contains("regular 0 omitted, 0 partial"))
        assertTrue(source.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
