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

    @Test fun `continuous identity rollover closes A and creates distinct immutable B`() {
        var ids = 0
        val owner = EvContributionDriveIdentityOwner { "drive-${++ids}" }
        val q = EvContributionQueue(dir(), 100_000, 8)
        val a = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "Maker", carModel = "Model", carYear = "2024", evBatteryCapacityWh = 80_000f,
        ))!!
        val b = EvVehicleCalibrationIdentity.from(ControlMessage.VehicleData(
            carMake = "Maker", carModel = "Model", carYear = "2024", evBatteryCapacityWh = 90_000f,
        ))!!
        val started = mutableMapOf<String, Long>()
        fun tick(identity: EvVehicleCalibrationIdentity, at: Long) {
            val transition = owner.observe(identity)
            transition.closeId?.let { q.close(it, at) }
            val startedAt = started.getOrPut(transition.activeId) { at }
            q.append(transition.activeId, startedAt, vehicleLine(identity.pseudonymousLabel.removePrefix("ev-class-")), "owner", identity.pseudonymousLabel)
        }
        tick(a, 1); tick(a, 2); tick(b, 3)
        q.close(owner.close()!!, 4)

        assertEquals(listOf("drive-1", "drive-2"), q.pending().map { it.id })
        assertEquals(2, q.pending().map { it.vehicleLabel }.distinct().size)
        assertTrue(q.pending().none { it.vehicleLabel.contains("VIN", true) })
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

    @Test fun `generation invalidation at mutation barrier leaves queue metadata untouched`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val gate = EvContributionLifecycleGate()
        val lease = gate.admitUpload { true }!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var outcome: EvContributionUploader.Outcome? = null
        val worker = thread {
            outcome = EvContributionUploader(
                q,
                mayMutate = { gate.isCurrent(lease) },
                mutateIfCurrent = { action ->
                    entered.countDown(); release.await(5, TimeUnit.SECONDS)
                    gate.withCurrent(lease, action)
                },
            ) { _, _ -> EvContributionUploader.Response(422, "{}") }.uploadOldest(10, "owner")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        gate.invalidate(); release.countDown(); worker.join(5_000)

        assertEquals(EvContributionUploader.Outcome.CANCELLED, outcome)
        assertEquals(1, q.pending().size)
        assertEquals(0, q.pending().single().attempts)
        assertEquals(0, q.counters().quarantined)
    }

    @Test fun `invalidated upload retains exact worker ownership until worker releases`() {
        val gate = EvContributionLifecycleGate()
        val old = gate.admitUpload { true }!!
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldExited = CountDownLatch(1)
        val worker = thread {
            oldEntered.countDown()
            releaseOld.await(5, TimeUnit.SECONDS)
            gate.releaseUpload(old)
            oldExited.countDown()
        }
        assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
        gate.invalidate()

        var openedConnections = 0
        if (gate.admitUpload { true } != null) openedConnections++
        assertEquals("replacement event must open no connection while old worker unwinds", 0, openedConnections)

        releaseOld.countDown()
        assertTrue(oldExited.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
        if (gate.admitUpload { true } != null) openedConnections++
        assertEquals("a later event starts exactly one replacement after exact exit", 1, openedConnections)
    }

    @Test fun `cancel closes exact blocking request body and later clean attempt succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val entered = CountDownLatch(1); val released = CountDownLatch(1); val bodyClosed = AtomicBoolean(false)
        val blockingBody = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                entered.countDown()
                released.await(5, TimeUnit.SECONDS)
                if (bodyClosed.get()) throw java.io.IOException("cancelled")
            }
            override fun close() { bodyClosed.set(true); released.countDown() }
        }
        val blockedConnection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = blockingBody
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        val transport = EvContributionHttpTransport { blockedConnection }
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        transport.cancel(); worker.join(5_000)

        assertTrue(bodyClosed.get())
        assertFalse(worker.isAlive)

        val wire = java.io.ByteArrayOutputStream()
        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = wire
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport { clean }.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
        assertTrue(wire.size() > 0)
    }

    @Test fun `upload check to start is generation owned and invalidation wins barrier`() {
        val gate = EvContributionLifecycleGate()
        val checked = CountDownLatch(1); val releaseCheck = CountDownLatch(1)
        var lease: EvContributionLifecycleGate.Lease? = null
        val admission = thread {
            lease = gate.admitUpload {
                checked.countDown(); releaseCheck.await(5, TimeUnit.SECONDS); true
            }
        }
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        val invalidated = CountDownLatch(1)
        val safety = thread { gate.invalidate(); invalidated.countDown() }
        releaseCheck.countDown(); admission.join(5_000); safety.join(5_000)

        assertTrue(invalidated.await(1, TimeUnit.SECONDS))
        assertNotNull(lease)
        assertFalse(gate.isCurrent(lease!!))
        assertNull(gate.withCurrent(lease!!) { "opened" })
    }

    @Test fun `capture admitted before revoke cannot append after revoke returns`() {
        val gate = EvContributionLifecycleGate()
        val lease = gate.admitCapture { true }!!
        gate.invalidate()
        var appended = false

        val result = gate.withCurrent(lease) { appended = true }

        assertNull(result)
        assertFalse(appended)
        assertNull(gate.admitCapture { false })
    }

    @Test fun `delete blocks new capture and upload admission until cleanup releases barrier`() {
        val gate = EvContributionLifecycleGate()
        gate.invalidate(blockAdmissions = true)
        assertNull(gate.admitCapture { true })
        assertNull(gate.admitUpload { true })
        gate.resumeAdmissions()
        assertNotNull(gate.admitCapture { true })
    }

    @Test fun `validated default network replacement ignores stale loss`() {
        val tracker = ValidatedDefaultNetworkTracker<String>()
        assertEquals(ValidatedDefaultNetworkTracker.Change.REPLACED, tracker.capabilities("A", true))
        assertEquals(ValidatedDefaultNetworkTracker.Change.REPLACED, tracker.capabilities("B", true))
        assertEquals(ValidatedDefaultNetworkTracker.Change.IGNORED, tracker.lost("A"))
        assertEquals("B", tracker.current())
        assertEquals(ValidatedDefaultNetworkTracker.Change.LOST, tracker.lost("B"))
        assertNull(tracker.current())
    }

    @Test fun `deadline scheduler keeps earliest generation owned one shot`() {
        var now = 1_000L
        data class Pending(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val pending = mutableListOf<Pending>()
        val scheduler = EarliestOneShotDeadline(
            now = { now },
            schedule = { delay, task ->
                val item = Pending(delay, task); pending += item
                object : EarliestOneShotDeadline.Cancellable { override fun cancel() { item.cancelled = true } }
            },
        )
        var generationCurrent = true
        var fires = 0
        assertTrue(scheduler.schedule(100, { generationCurrent }) { fires++ })
        assertFalse(scheduler.schedule(200, { generationCurrent }) { fires++ })
        assertTrue(scheduler.schedule(50, { generationCurrent }) { fires++ })
        assertEquals(2, pending.size)
        assertTrue(pending.first().cancelled)
        now += 50; pending.last().task(); pending.last().task()
        assertEquals(1, fires)
        generationCurrent = false
        assertTrue(scheduler.schedule(10, { generationCurrent }) { fires++ })
        pending.last().task()
        assertEquals(1, fires)
    }

    @Test fun `restart never inherits upload authorization and moving revokes it immediately`() {
        val gate = EvFreshParkGate()
        assertFalse(gate.freshParkObservedThisProcess)
        assertFalse(gate.observe(gearRaw = 4, ignitionState = null))
        assertFalse(gate.observe(gearRaw = 8, ignitionState = 4))
        assertTrue(gate.observe(gearRaw = 4, ignitionState = 2))
        assertFalse(gate.observe(gearRaw = 8, ignitionState = 4))
    }

    @Test fun `failed delete remains blocked until verified empty successful retry`() {
        val root = dir()
        var fail = true
        val q = EvContributionQueue(root, deleteFile = { file -> if (fail) false else file.delete() })
        q.append("drive", 1, vehicleLine(), "owner")
        val first = q.deleteAllArtifacts()
        assertTrue(first.failures > 0)
        assertTrue(q.storageUsage().units > 0)
        assertFalse(EvDeleteAdmissionPolicy.mayResume(first, q.storageUsage()))

        fail = false
        val retry = q.deleteAllArtifacts()
        assertEquals(0, retry.failures)
        assertEquals(0, q.storageUsage().units)
        assertTrue(EvDeleteAdmissionPolicy.mayResume(retry, q.storageUsage()))
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
