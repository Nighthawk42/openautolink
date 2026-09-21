package com.openautolink.app.diagnostics

import com.openautolink.app.navigation.EnergyAtDistance
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

data class EvReplayMetadata(
    val format: Int,
    val release: String,
    val sourceSha256: String,
    val sourceRecordCount: Int,
    val sourceTypeCounts: Map<String, Int>,
    val eventCount: Int,
    val sourceOldCompletionRecords: Int,
)

data class EvReplayDataset(val metadata: EvReplayMetadata, val events: List<JsonObject>)

data class EvReplayResult(
    val zeroNavigationInputs: Int,
    val positiveNavigationInputs: Int,
    val zerosAfterExplicitDistance: Int,
    val zerosThatOverwroteDistance: Int,
    val zerosThatRefreshedDistance: Int,
    val distinctBatteryObservationIds: Int,
    val batteryObservationIntervalsMs: List<Long>,
    val completedEnergyWindows: Int,
    val persistedCompletedEnergyWindows: Int,
    val arrivalCandidates: Int,
    val arrivalCandidatesAfterRouteInactive: Int,
    val requestedLearnedRecords: Int,
    val effectiveDerivedWhileLearnedRequested: Int,
    val inactiveLearnerDiagnostics: Int,
    val rawOldCompletionRecords: Int,
)

/** Test-only adapter from old recorder output to current semantic inputs. */
object EvUploadReplay {
    private val json = Json { ignoreUnknownKeys = false }
    private val replayTypes = setOf(
        "consent_start", "session_start", "gap", "requested_settings", "vehicle",
        "forecast", "forecast_empty", "navigation_status", "route_cancel_or_reset", "navigation"
    )
    private val allowedEventKeys = setOf(
        "type", "t", "session", "distanceM", "etaSec", "active", "arrivalWh", "quality",
        "receivedT", "speedKmh", "batteryWh", "gearRaw", "observations", "effective", "requested"
    )

    fun loadSanitized(input: InputStream): EvReplayDataset {
        val lines = input.bufferedReader().use { it.readLines().filter(String::isNotBlank) }
        require(lines.isNotEmpty())
        val header = json.parseToJsonElement(lines.first()).jsonObject
        require(header.keys == setOf("meta"))
        val metadata = metadata(header.getValue("meta").jsonObject)
        val events = lines.drop(1).map { json.parseToJsonElement(it).jsonObject }
        require(events.size == metadata.eventCount)
        validatePrivacy(events)
        return EvReplayDataset(metadata, events)
    }

    fun loadArchive(file: File): EvReplayDataset {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        val records = ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { File(it.name).name.startsWith("ev_") && it.name.endsWith(".log") }
                .flatMap { entry ->
                    zip.getInputStream(entry).bufferedReader().use { reader ->
                        reader.readLines().asSequence().filter(String::isNotBlank)
                            .map { json.parseToJsonElement(it).jsonObject }.toList().asSequence()
                    }
                }.toList()
        }.sortedBy { it.long("elapsedMs") ?: 0L }
        return sanitize(records, digest, "0.1.501")
    }

    private fun sanitize(records: List<JsonObject>, digest: String, release: String): EvReplayDataset {
        val base = records.mapNotNull { it.long("elapsedMs") }.minOrNull() ?: 0L
        val sessionAliases = linkedMapOf<String, String>()
        fun alias(raw: String?): String {
            if (raw == null) return "none"
            return sessionAliases.getOrPut(raw) { "s${sessionAliases.size + 1}" }
        }
        val events = records.mapNotNull { record ->
            val oldType = record.string("type") ?: return@mapNotNull null
            if (oldType !in replayTypes) return@mapNotNull null
            val event = linkedMapOf<String, JsonElement>(
                "type" to JsonPrimitive(oldType),
                "t" to JsonPrimitive(record.long("elapsedMs")!! - base),
                "session" to JsonPrimitive(alias(record.string("session"))),
            )
            when (oldType) {
                "requested_settings" -> event["requested"] = settings(record.obj("requested"))
                "navigation" -> {
                    event["distanceM"] = nullablePrimitive(record["remainingM"])
                    event["etaSec"] = nullablePrimitive(record["etaSec"])
                }
                "navigation_status" -> {
                    event["type"] = JsonPrimitive("route_lifecycle")
                    event["active"] = JsonPrimitive(record.int("status") == 1)
                }
                "route_cancel_or_reset" -> event["type"] = JsonPrimitive("route_reset")
                "forecast", "forecast_empty" -> {
                    event["arrivalWh"] = if (oldType == "forecast") nullablePrimitive(record["latestArrivalWh"]) else JsonNull
                    event["distanceM"] = nullablePrimitive(record["distanceM"])
                    event["etaSec"] = nullablePrimitive(record["etaSec"])
                    event["quality"] = JsonPrimitive(record.int("quality") ?: 0)
                    event["receivedT"] = JsonPrimitive((record.long("receivedAtElapsedMs") ?: record.long("elapsedMs")!!) - base)
                }
                "vehicle" -> {
                    event["speedKmh"] = nullablePrimitive(record["speedKmh"])
                    event["batteryWh"] = nullablePrimitive(record["batteryWh"])
                    event["gearRaw"] = nullablePrimitive(record["gearRaw"])
                    val rawObservations = record.obj("observations")
                    event["observations"] = buildJsonObject {
                        listOf("PERF_VEHICLE_SPEED", "EV_BATTERY_LEVEL", "GEAR_SELECTION").forEach { key ->
                            rawObservations?.obj(key)?.let { put(key, observation(it, base)) }
                        }
                    }
                    event["effective"] = effective(record.obj("effective"))
                }
            }
            JsonObject(event)
        }.sortedBy { it.long("t") }
        val typeCounts = records.mapNotNull { it.string("type") }.groupingBy { it }.eachCount().toSortedMap()
        val metadata = EvReplayMetadata(
            1, release, digest, records.size, typeCounts, events.size,
            records.count { it.boolean("energyWindowCompleted") == true },
        )
        validatePrivacy(events)
        return EvReplayDataset(metadata, events)
    }

    fun replay(dataset: EvReplayDataset, output: File): EvReplayResult {
        val navigationCore = EvTelemetryCore("replay", "navigation")
        var navSession = "none"
        var routeActive = false
        var zeroNav = 0
        var positiveNav = 0
        var zerosAfterExplicit = 0
        var zeroOverwrite = 0
        var zeroRefresh = 0
        var lastExplicitDistance: Int? = null
        var lastExplicitObserved: Long? = null
        var arrivalCandidates = 0
        var arrivalAfterInactive = 0

        // Navigation keeps the real relative timeline. Energy uses a separate cadence-normalized
        // lane because 0.1.501 persisted only ~5 s snapshots, not every input tick received by Core.
        dataset.events.forEach { event ->
            val t = event.long("t")!!
            when (event.string("type")) {
                "consent_start", "session_start" -> {
                    navSession = event.string("session")!!
                    navigationCore.startSession(navSession, t, t)
                    routeActive = false
                }
                "gap" -> {
                    navigationCore.gap(navSession, "source_gap", t, t)
                    routeActive = false
                    lastExplicitDistance = null
                    lastExplicitObserved = null
                }
                "route_lifecycle" -> {
                    routeActive = event.boolean("active") == true
                    navigationCore.navigationLifecycle(routeActive, t, t)
                }
                "route_reset" -> {
                    navigationCore.navigation(null, null, null, true, t, t)
                    routeActive = false
                    lastExplicitDistance = null
                    lastExplicitObserved = null
                }
                "navigation" -> {
                    val distance = event.int("distanceM")
                    val eta = event.long("etaSec")
                    val result = navigationCore.navigation(null, distance, eta, false, t, t)
                    if (distance != null && distance > 0) {
                        positiveNav++
                        lastExplicitDistance = distance
                        lastExplicitObserved = t
                    } else if (distance == 0) {
                        zeroNav++
                        if (lastExplicitDistance != null) {
                            zerosAfterExplicit++
                            if (result["remainingM"] != lastExplicitDistance) zeroOverwrite++
                            if (result["remainingObservedMs"] != lastExplicitObserved) zeroRefresh++
                        }
                    }
                }
                "forecast", "forecast_empty" -> navigationCore.forecast(
                    event.int("arrivalWh"), event.int("distanceM"), event.int("etaSec"),
                    event.int("quality") ?: 0, t, t, event.long("receivedT") ?: t,
                )
                "vehicle" -> if (!routeActive) {
                    // Only replay post-inactive vehicle evidence in this lane. The old artifact
                    // omitted intervening input ticks, so replaying every ~5 s persisted snapshot
                    // would manufacture Core gaps and erase navigation state.
                    val record = navigationCore.vehicle(sample(event, t, normalizeObservationClock = false))
                    if (record["arrivalCandidate"] == true) {
                        arrivalCandidates++
                        arrivalAfterInactive++
                    }
                }
            }
        }

        val vehicles = dataset.events.filter { it.string("type") == "vehicle" }
        var replayNow = 100_000L
        val energyCore = EvTelemetryCore("replay", "energy")
        energyCore.startSession("energy", replayNow, replayNow)
        var completed = 0
        val recorder = EvTelemetryRecorder({ replayNow }, { replayNow }, "replay")
        recorder.session("energy")
        recorder.enable(output)
        try {
            vehicles.forEach { event ->
                replayNow += 1_000L
                val sample = sample(event, replayNow, normalizeObservationClock = true)
                if (energyCore.vehicle(sample)["energyWindowCompleted"] == true) completed++
                recorder.vehicle("energy", vehicleData(event, replayNow), effectiveMap(event.obj("effective")))
            }
            check(recorder.flushForUpload(10_000))
            val persisted = output.listFiles().orEmpty().flatMap { it.readLines() }
                .filter(String::isNotBlank).map { json.parseToJsonElement(it).jsonObject }
            val persistedCompleted = persisted.count { it.boolean("energyWindowCompleted") == true }
            val batteryIds = vehicles.mapNotNull {
                it.obj("observations")?.obj("EV_BATTERY_LEVEL")?.long("timestampNanos")
            }.distinct().sorted()
            val intervals = batteryIds.zipWithNext { a, b -> (b - a) / 1_000_000L }
            val requestedLearned = dataset.events.count {
                it.string("type") == "requested_settings" && it.obj("requested")?.boolean("enabled") == true &&
                    it.obj("requested")?.string("mode") == "learned"
            }
            val mismatches = vehicles.count {
                val effective = it.obj("effective")
                effective?.obj("requested")?.boolean("enabled") == true &&
                    effective.obj("requested")?.string("mode") == "learned" &&
                    effective.boolean("enabled") == false && effective.string("mode") == "derived"
            }
            val inactive = vehicles.count {
                val effective = it.obj("effective")
                effective?.boolean("observerStarted") == false &&
                    effective.string("learnerState") == "inactive_not_initialized"
            }
            return EvReplayResult(
                zeroNav, positiveNav, zerosAfterExplicit, zeroOverwrite, zeroRefresh,
                batteryIds.size, intervals, completed, persistedCompleted,
                arrivalCandidates, arrivalAfterInactive, requestedLearned, mismatches, inactive,
                dataset.metadata.sourceOldCompletionRecords,
            )
        } finally {
            recorder.disable()
            recorder.flushForUpload(10_000)
        }
    }

    private fun sample(event: JsonObject, now: Long, normalizeObservationClock: Boolean): EvTelemetrySample {
        val observations = event.obj("observations")
        fun identity(key: String): EvObservationIdentity? = observations?.obj(key)?.let {
            val timestamp = it.long("timestampNanos")
            EvObservationIdentity(it.string("source") ?: "vhal", timestamp,
                it.long("receivedT") ?: now, it.int("status"))
        }
        val speed = identity("PERF_VEHICLE_SPEED")
        val battery = identity("EV_BATTERY_LEVEL")
        val gear = identity("GEAR_SELECTION")
        return EvTelemetrySample(
            now, now, event.double("speedKmh"), event.double("batteryWh"),
            if (normalizeObservationClock && speed != null) now else speed?.receivedElapsedMs,
            if (normalizeObservationClock && battery != null) now else battery?.receivedElapsedMs,
            event.int("gearRaw") == 4, battery, gear,
        )
    }

    private fun vehicleData(event: JsonObject, now: Long): ControlMessage.VehicleData {
        val observations = event.obj("observations")
        fun metadata(key: String): VehiclePropertyObservation? = observations?.obj(key)?.let {
            VehiclePropertyObservation(
                if (key == "PERF_VEHICLE_SPEED") now * 1_000_000L else it.long("timestampNanos"),
                if (key == "PERF_VEHICLE_SPEED") now else (it.long("receivedT") ?: now),
                it.int("status"), it.string("source") ?: "vhal",
            )
        }
        return ControlMessage.VehicleData(
            speedKmh = event.double("speedKmh")?.toFloat(), gearRaw = event.int("gearRaw"),
            evBatteryLevelWh = event.double("batteryWh")?.toFloat(),
            evObservationMetadata = listOf("PERF_VEHICLE_SPEED", "EV_BATTERY_LEVEL", "GEAR_SELECTION")
                .mapNotNull { key -> metadata(key)?.let { key to it } }.toMap(),
        )
    }

    private fun effectiveMap(value: JsonObject?): Map<String, Any?> = value?.mapValues { (_, v) ->
        when (v) {
            is JsonObject -> effectiveMap(v)
            JsonNull -> null
            is JsonPrimitive -> v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
            else -> v.toString()
        }
    } ?: emptyMap()

    private fun validatePrivacy(events: List<JsonObject>) {
        events.forEach { require(it.keys.all(allowedEventKeys::contains)) }
        val text = events.joinToString("\n")
        require(!Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b").containsMatchIn(text))
        require(!Regex("(?i)\\b(vin|latitude|longitude|destination|road|street|avenue|household)\\b").containsMatchIn(text))
    }

    private fun metadata(o: JsonObject) = EvReplayMetadata(
        o.int("format")!!, o.string("release")!!, o.string("sourceSha256")!!,
        o.int("sourceRecordCount")!!, o.obj("sourceTypeCounts")!!.mapValues { it.value.jsonPrimitive.int },
        o.int("eventCount")!!, o.int("sourceOldCompletionRecords")!!,
    )
    private fun settings(o: JsonObject?) = if (o == null) JsonNull else buildJsonObject {
        put("enabled", nullablePrimitive(o["enabled"])); put("mode", nullablePrimitive(o["mode"]))
    }
    private fun effective(o: JsonObject?) = if (o == null) JsonNull else buildJsonObject {
        put("requested", settings(o.obj("requested")))
        listOf("enabled", "mode", "observerStarted", "learnerState").forEach { put(it, nullablePrimitive(o[it])) }
    }
    private fun observation(o: JsonObject, base: Long) = buildJsonObject {
        put("source", nullablePrimitive(o["source"]))
        put("timestampNanos", o.long("timestampElapsedNanos")?.let { JsonPrimitive(it - base * 1_000_000L) } ?: JsonNull)
        put("receivedT", o.long("receivedElapsedMs")?.let { JsonPrimitive(it - base) } ?: JsonNull)
        put("status", nullablePrimitive(o["status"]))
    }
    private fun nullablePrimitive(value: JsonElement?): JsonElement = value?.takeUnless { it is JsonNull } ?: JsonNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.double(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
}
