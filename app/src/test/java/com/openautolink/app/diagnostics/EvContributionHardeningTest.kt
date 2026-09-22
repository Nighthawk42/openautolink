package com.openautolink.app.diagnostics

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
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
            override fun disconnect() { released.countDown() }
            override fun getOutputStream() = blockingBody
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        val transport = EvContributionHttpTransport { blockedConnection }
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        transport.cancel(); worker.join(5_000)

        assertFalse("cancellation cleanup must not call a potentially blocking close", bodyClosed.get())
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
        val deletion = gate.beginDestructiveOperation()
        assertNull(gate.admitCapture { true })
        assertNull(gate.admitUpload { true })
        assertTrue(gate.finishDestructiveOperation(deletion, resumeAdmissions = true))
        assertNotNull(gate.admitCapture { true })
    }

    @Test fun `older destructive completion cannot reopen or clear latest owner`() {
        val gate = EvContributionLifecycleGate()
        val older = gate.beginDestructiveOperation()
        val newer = gate.beginDestructiveOperation()

        assertFalse(gate.finishDestructiveOperation(older, resumeAdmissions = true))
        assertNull(gate.admitCapture { true })
        assertTrue(gate.finishDestructiveOperation(newer, resumeAdmissions = false))
        assertNull(gate.admitUpload { true })
        val retry = gate.beginDestructiveOperation()
        assertTrue(gate.finishDestructiveOperation(retry, resumeAdmissions = true))
        assertNotNull(gate.admitCapture { true })
    }

    @Test fun `failed deletion marker survives recreation until verified empty retry`() {
        val root = dir()
        var fail = true
        var q = EvContributionQueue(root, deleteFile = { file -> if (fail) false else file.delete() })
        q.append("drive", 1, vehicleLine(), "owner")
        val failed = q.deleteAllArtifacts()
        assertFalse(q.completeDeletion(failed))
        assertTrue(q.isDeletionBlocked())

        q = EvContributionQueue(root)
        assertTrue(q.isDeletionBlocked())
        fail = false
        val retry = q.deleteAllArtifacts()
        assertTrue(q.completeDeletion(retry))
        assertFalse(EvContributionQueue(root).isDeletionBlocked())
        assertEquals(EvContributionQueue.StorageUsage(0, 0), q.storageUsage())
    }

    @Test fun `stale worker finally hands lost trigger to exactly one replacement`() {
        val gate = EvContributionLifecycleGate()
        val ownership = EvUploadJobOwnership<String>()
        val oldLease = gate.admitUpload { true }!!
        assertTrue(ownership.tryInstall("old"))
        gate.invalidate() // old worker is stale before its body starts
        assertFalse(gate.isCurrent(oldLease))
        assertNull(gate.admitUpload { true }) // replacement network callback loses admission to old owner
        ownership.markDrainPending()

        gate.releaseUpload(oldLease) // old worker's finally
        assertTrue(ownership.finish("old"))
        val replacementLease = gate.admitUpload { true }
        assertNotNull(replacementLease)
        assertTrue(ownership.tryInstall("replacement"))
        gate.releaseUpload(replacementLease!!)
        assertFalse(ownership.finish("replacement"))
        assertFalse(ownership.finish("replacement"))
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
        fun observation(timeMs: Long, status: Int = 0, generation: Long = 1) =
            VehiclePropertyObservation(
                timeMs * 1_000_000, timeMs, status,
                registrationGeneration = generation,
                subscriptionActive = true,
                sequence = timeMs,
            )
        assertTrue(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000)),
            vhalRegistrationGeneration = 1,
        ), 1_000))
        assertTrue("fresh OFF alone is sufficient", gate.observe(ControlMessage.VehicleData(
            ignitionState = 2,
            evObservationMetadata = mapOf("IGNITION_STATE" to observation(1_100)),
            vhalRegistrationGeneration = 1,
        ), 1_100))
        assertFalse(gate.observe(ControlMessage.VehicleData(
            gearRaw = null,
            ignitionState = 4,
            evObservationMetadata = mapOf(
                "GEAR_SELECTION" to observation(1_200, status = 1),
                "IGNITION_STATE" to observation(1_200),
            ),
            vhalRegistrationGeneration = 1,
        ), 1_200))
        assertFalse("repeating cached observations in an unrelated batch cannot refresh safety", gate.observe(
            ControlMessage.VehicleData(
                gearRaw = 4,
                ignitionState = 2,
                evObservationMetadata = mapOf(
                    "GEAR_SELECTION" to observation(1_200, status = 1),
                    "IGNITION_STATE" to observation(1_200, status = 1),
                ),
                vhalRegistrationGeneration = 1,
            ),
            1_300,
        ))
        assertFalse("stale observations are rejected", gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000)),
            vhalRegistrationGeneration = 1,
        ), 1_000 + EvFreshParkGate.MAX_OBSERVATION_AGE_MS + 1))
        assertFalse("service registration change clears prior authorization", gate.observe(ControlMessage.VehicleData(
            vhalRegistrationGeneration = 2,
        ), 2_000))
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

    @Test fun `upload authorization expires at exact monotonic boundary and same millisecond newer event wins`() {
        val gate = EvFreshParkGate()
        fun observation(timeMs: Long, status: Int = 0, sequence: Long) = VehiclePropertyObservation(
            timestampElapsedNanos = timeMs * 1_000_000,
            receivedElapsedMs = timeMs,
            status = status,
            registrationGeneration = 7,
            propertyId = 0x11400400,
            subscriptionActive = true,
            sequence = sequence,
        )
        assertTrue(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000, sequence = 1)),
            vhalRegistrationGeneration = 7,
        ), 1_000))
        assertTrue(gate.authorization(30_999))
        assertFalse(gate.authorization(31_000))
        assertFalse(gate.authorization(31_001))

        assertFalse(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(1_000, status = 1, sequence = 2)),
            vhalRegistrationGeneration = 7,
        ), 1_000))
        assertTrue(gate.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation(31_001, sequence = 3)),
            vhalRegistrationGeneration = 7,
        ), 31_001))
        assertTrue(gate.authorization(31_001))
    }

    @Test fun `delete intent is durable before IO and unchecked partial failure survives recreation`() {
        val root = dir()
        var deletes = 0
        var q = EvContributionQueue(root, deleteFile = { file ->
            deletes++
            if (deletes == 1) file.delete() else throw IllegalStateException("injected partial deletion")
        })
        q.append("a", 1, vehicleLine(), "owner")
        q.append("b", 2, vehicleLine(), "owner")
        assertTrue(q.beginDeletion())
        val result = q.deleteAllArtifacts()
        assertTrue(result.failures > 0)
        q = EvContributionQueue(root)
        assertTrue(q.isDeletionBlocked())
    }

    @Test fun `bounded HTTP rejects oversized response and later retry succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val oversized = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArray(64 * 1024 + 1) { 'x'.code.toByte() }.inputStream()
        }
        assertThrows(java.io.IOException::class.java) {
            EvContributionHttpTransport(attemptTimeoutMs = 1_000, openConnection = { oversized }).send(
                "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
            )
        }

        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport(attemptTimeoutMs = 1_000, openConnection = { clean }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
    }

    @Test fun `overall HTTP deadline closes blocked body and permits later attempt`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val closed = CountDownLatch(1)
        val blocked = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { closed.countDown() }
            override fun getOutputStream() = object : java.io.OutputStream() {
                @Volatile var stopped = false
                override fun write(b: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    while (!stopped) Thread.sleep(5)
                    throw java.io.InterruptedIOException("closed")
                }
                override fun close() { stopped = true; closed.countDown() }
            }
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = { blocked },
        )
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)

        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport(openConnection = { clean }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
    }

    @Test fun `overall HTTP deadline stops trickle response`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val closed = AtomicBoolean(false)
        val trickle = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { closed.set(true) }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int {
                    if (closed.get()) throw java.io.InterruptedIOException("closed")
                    Thread.sleep(30)
                    return 'x'.code
                }
                override fun close() { closed.set(true) }
            }
        }
        val started = System.nanoTime()
        assertThrows(java.io.IOException::class.java) {
            EvContributionHttpTransport(
                attemptTimeoutMs = 100,
                monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
                openConnection = { trickle },
            ).send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file)
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertTrue(closed.get())
    }

    @Test fun `delayed first ACK after safety expiry retains both immutable ZIPs and opens no second request`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("a", 1, vehicleLine(), "owner"); q.close("a", 2)
        q.append("b", 3, vehicleLine(), "owner"); q.close("b", 4)
        var nowElapsed = 1_000L
        val safety = EvFreshParkGate()
        val observation = VehiclePropertyObservation(
            timestampElapsedNanos = nowElapsed * 1_000_000,
            receivedElapsedMs = nowElapsed,
            status = 0,
            registrationGeneration = 9,
            propertyId = 0x11400400,
            subscriptionActive = true,
            sequence = 1,
        )
        assertTrue(safety.observe(ControlMessage.VehicleData(
            gearRaw = 4,
            evObservationMetadata = mapOf("GEAR_SELECTION" to observation),
            vhalRegistrationGeneration = 9,
        ), nowElapsed))
        val sent = mutableListOf<String>()
        val drain = EvContributionDrainLoop(q) { queue ->
            EvContributionUploader(
                queue,
                mayTransmit = { safety.authorization(nowElapsed) },
                mayMutate = { true },
                mutateIfCurrent = { action ->
                    if (safety.authorization(nowElapsed)) action() else null
                },
            ) { _, id ->
                sent += id
                nowElapsed += EvFreshParkGate.MAX_OBSERVATION_AGE_MS
                val entry = queue.pending().first { it.id == id }
                EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${entry.sha256}\"}")
            }
        }

        assertEquals(EvContributionUploader.Outcome.CANCELLED, drain.drainEligible(10, "owner"))
        assertEquals(listOf("a"), sent)
        assertEquals(listOf("a", "b"), q.pending().map { it.id })
        assertEquals(0, q.counters().accepted)
    }

    @Test fun `blocked connection creation obeys total deadline and later attempt succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val entered = CountDownLatch(1)
        val cleanWire = java.io.ByteArrayOutputStream()
        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = cleanWire
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        var calls = 0
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = {
                if (++calls == 1) {
                    entered.countDown()
                    try {
                        while (true) Thread.sleep(10_000)
                    } catch (interrupted: InterruptedException) {
                        throw java.io.InterruptedIOException("open interrupted").apply { initCause(interrupted) }
                    }
                }
                clean
            },
        )
        val first = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        first.join(2_000)
        assertFalse("deadline must interrupt blocked openConnection", first.isAlive)

        assertEquals(200, transport.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
        assertTrue(cleanWire.size() > 0)
    }

    @Test fun `timeout disconnects before blocking body close and replacement succeeds`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val writeEntered = CountDownLatch(1)
        val disconnectCalled = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val blockingBody = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeEntered.countDown()
                while (!disconnected.get()) Thread.sleep(5)
                throw java.io.InterruptedIOException("disconnected")
            }
            override fun close() {
                while (!disconnected.get()) Thread.sleep(5)
            }
        }
        val blocked = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); disconnectCalled.countDown() }
            override fun getOutputStream() = blockingBody
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = { blocked },
        )
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
        assertTrue(disconnectCalled.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)

        val clean = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        assertEquals(200, EvContributionHttpTransport(openConnection = { clean }).send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
    }

    @Test fun `cancel disconnects before blocking body close and worker exits`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val writeEntered = CountDownLatch(1)
        val disconnectCalled = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val body = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeEntered.countDown()
                while (!disconnected.get()) Thread.sleep(5)
                throw java.io.InterruptedIOException("disconnected")
            }
            override fun close() { while (!disconnected.get()) Thread.sleep(5) }
        }
        val blocked = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true); disconnectCalled.countDown() }
            override fun getOutputStream() = body
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(openConnection = { blocked })
        val worker = thread { runCatching { transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file) } }
        assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
        transport.cancel()
        assertTrue(disconnectCalled.await(1, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals(1, q.pending().size)
        assertEquals(0, q.deleteAllArtifacts().failures)
        assertTrue(q.pending().isEmpty())
    }

    @Test fun `blocking connection configuration is covered by initial deadline`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val configuring = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val connection = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.set(true) }
            override fun setRequestProperty(key: String?, value: String?) {
                configuring.countDown()
                while (!disconnected.get()) Thread.sleep(5)
                throw java.io.InterruptedIOException("configuration disconnected")
            }
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
        }
        val transport = EvContributionHttpTransport(
            attemptTimeoutMs = 100,
            monotonicNow = java.util.function.LongSupplier { System.nanoTime() / 1_000_000 },
            openConnection = { connection },
        )
        val worker = thread { runCatching {
            transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file)
        } }
        assertTrue(configuring.await(1, TimeUnit.SECONDS))
        worker.join(2_000)
        assertTrue(disconnected.get())
        assertFalse(worker.isAlive)
    }

    @Test fun `stale watchdog from completed attempt cannot disconnect replacement`() {
        val q = EvContributionQueue(dir(), 100_000, 8)
        q.append("drive", 1, vehicleLine(), "owner"); q.close("drive", 2)
        val watchdogs = mutableListOf<() -> Unit>()
        val bEntered = CountDownLatch(1)
        val bDisconnected = AtomicBoolean(false)
        val a = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = "{}".byteInputStream()
        }
        val b = object : HttpURLConnection(URL("https://logs.example/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { bDisconnected.set(true) }
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    bEntered.countDown()
                    while (!bDisconnected.get()) Thread.sleep(5)
                    throw java.io.InterruptedIOException("disconnected")
                }
            }
            override fun getResponseCode() = 200
        }
        var opens = 0
        val transport = EvContributionHttpTransport(
            openConnection = { if (opens++ == 0) a else b },
            scheduleDeadline = { _, task ->
                watchdogs += task
                EvContributionDeadline.Cancellable { }
            },
        )
        assertEquals(200, transport.send(
            "https://logs.example/upload", "token", "ev-class-test", q.pending().single().file,
        ).statusCode)
        val replacement = thread { runCatching {
            transport.send("https://logs.example/upload", "token", "ev-class-test", q.pending().single().file)
        } }
        assertTrue(bEntered.await(1, TimeUnit.SECONDS))

        watchdogs.first().invoke()
        assertFalse("attempt A watchdog must not touch attempt B", bDisconnected.get())

        transport.cancel()
        replacement.join(2_000)
        assertFalse(replacement.isAlive)
        assertTrue(bDisconnected.get())
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
