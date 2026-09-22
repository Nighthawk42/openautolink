package com.openautolink.app.diagnostics

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class EvContributionHardeningTest {
    private fun dir(): File = Files.createTempDirectory("ev-hardening").toFile()
    private fun vehicleLine(vehicleClass: String = "0123456789abcdef") =
        """{"schema":2,"type":"vehicle","elapsedBucketS":60,"batteryWh":50000,"batteryPct":50,"rangeKm":200.0,"distanceM":42,"speedKmh":10.0,"capacityBandKwh":80,"chargeState":1,"chargeRateW":0.0,"chargePortConnected":false,"gearRaw":8,"ignition":4,"energyBasis":"absolute-wh","estimatorRevision":"rev2","vehicleClass":"$vehicleClass"}"""

    @Test fun `vehicle identity is normalized frozen non VIN and changes split drives`() {
        val first = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "  Chevrolet ", carModel = " Blazer EV ", carYear = "2024", evBatteryCapacityWh = 85_100f,
        ))!!
        val same = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "chevrolet", carModel = "blazer   ev", carYear = " 2024 ", evBatteryCapacityWh = 89_900f,
        ))!!
        val changed = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "Chevrolet", carModel = "Blazer EV", carYear = "2024", evBatteryCapacityWh = 91_000f,
        ))!!
        assertEquals(first, same)
        assertNotEquals(first, changed)
        assertTrue(first.pseudonymousLabel.startsWith("ev-class-"))
        assertFalse(first.frozenKey.contains("VIN", true))
        assertFalse(first.pseudonymousLabel.contains("Chevrolet", true))
    }

    @Test fun `strict record schema rejects wrong types ranges enums and free text`() {
        assertEquals(vehicleLine(), EvContributionPrivacy.requireAllowedJsonLine(vehicleLine()))
        val forecast = """{"schema":2,"type":"forecast","elapsedBucketS":60,"forecastWh":49000,"forecastDistanceM":12000,"forecastQuality":2,"vehicleClass":"0123456789abcdef"}"""
        assertEquals(forecast, EvContributionPrivacy.requireAllowedJsonLine(forecast))
        val bad = listOf(
            """{"schema":"2","type":"vehicle"}""",
            """{"schema":2,"type":"vehicle","batteryPct":101}""",
            """{"schema":2,"type":"vehicle","speedKmh":"fast"}""",
            """{"schema":2,"type":"vehicle","energyBasis":"free text"}""",
            """{"schema":2,"type":"vehicle","reason":"whatever"}""",
            """{"schema":2,"type":"unknown","event":"anything"}""",
        )
        bad.forEach { line -> assertThrows(line, IllegalArgumentException::class.java) { EvContributionPrivacy.requireAllowedJsonLine(line) } }
    }

    @Test fun `closed drive rejects every later append and pending owner cannot rotate`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "credential-a")
        q.close("drive", 2)
        assertThrows(IllegalStateException::class.java) { q.append("drive", 1, vehicleLine(), "credential-a") }
        var sent = false
        val uploader = EvContributionUploader(q) { _, _ -> sent = true; error("must not send") }
        assertEquals(EvContributionUploader.Outcome.OWNER_MISMATCH, uploader.uploadOldest(10, "credential-b"))
        assertFalse(sent)
        assertEquals(1, q.pending().size)
    }

    @Test fun `delete recursively removes open closed temp ack quarantine orphan and corrupt metadata bytes`() {
        val root = dir(); val q = EvContributionQueue(root, 1_000_000, 100)
        q.append("open", 1, vehicleLine(), "owner")
        q.append("closed", 2, vehicleLine(), "owner"); q.close("closed", 3)
        File(root, "crash.zip.tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "accepted.zip.acked").writeBytes(byteArrayOf(4))
        File(root, "orphan.zip.meta").writeText("broken")
        File(root, "quarantine").mkdirs()
        File(root, "quarantine/bad_422.zip").writeBytes(byteArrayOf(5, 6))
        val bytesBefore = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val result = q.deleteAllArtifacts()

        assertTrue(result.files >= 6)
        assertTrue(result.bytes >= bytesBefore)
        assertEquals(0, result.failures)
        assertTrue(root.walkTopDown().none { it.isFile })
    }

    @Test fun `one retention policy bounds open closed temporary acknowledged quarantine and corrupt artifacts`() {
        val root = dir(); val q = EvContributionQueue(root, maxBytes = 1_000_000, maxFiles = 3, maxAgeMs = 1_000_000)
        q.append("open", 1, vehicleLine(), "owner")
        File(root, "crash.zip.tmp").writeBytes(ByteArray(10)); File(root, "crash.zip.tmp").setLastModified(2)
        File(root, "quarantine").mkdirs(); File(root, "quarantine/bad.zip").writeBytes(ByteArray(10)); File(root, "quarantine/bad.zip").setLastModified(3)
        File(root, "orphan.zip.meta").writeText("bad"); File(root, "orphan.zip.meta").setLastModified(4)

        q.enforceRetention(10)

        assertTrue(q.storageUsage().units <= 3)
        assertTrue(q.counters().evicted > 0)
        assertTrue(q.counters().quarantined > 0)
    }

    @Test fun `stale blocked response is disconnected and cannot mutate queue`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disconnected = AtomicBoolean(false)
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); release.countDown() }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode(): Int { entered.countDown(); release.await(5, TimeUnit.SECONDS); return 200 }
            override fun getInputStream() = "{\"ok\":true,\"sha256\":\"${q.pending().single().sha256}\"}".byteInputStream()
        }
        val transport = EvContributionHttpTransport { connection }
        val fence = EvContributionGenerationFence(); val generation = fence.snapshot()
        var outcome: EvContributionUploader.Outcome? = null
        val worker = thread {
            outcome = EvContributionUploader(q, mayMutate = { fence.isCurrent(generation) }) { file, _ ->
                transport.send("https://logs.example/upload", "token", "ev-class-test", file)
            }.uploadOldest(10, "owner")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        fence.invalidate(); transport.cancel(); worker.join(5_000)

        assertTrue(disconnected.get())
        assertEquals(EvContributionUploader.Outcome.CANCELLED, outcome)
        assertEquals(1, q.pending().size)
        assertEquals(0, q.pending().single().attempts)
    }

    @Test fun `event driven drain uploads offline batches oldest first when network later arrives`() {
        val root = dir()
        EvContributionQueue(root, 100_000, 8).apply {
            append("a", 1, vehicleLine(), "owner"); close("a", 2)
        }
        val recreated = EvContributionQueue(root, 100_000, 8).apply {
            append("b", 3, vehicleLine(), "owner"); close("b", 4)
        }
        val sent = mutableListOf<String>()
        val drain = EvContributionDrainLoop(recreated) { q ->
            EvContributionUploader(q) { _, id ->
                sent += id
                EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${q.pending().first().sha256}\"}")
            }
        }

        assertEquals(EvContributionUploader.Outcome.EMPTY, drain.drainEligible(10, "owner"))
        assertEquals(listOf("a", "b"), sent)
        assertTrue(recreated.pending().isEmpty())
    }
}
