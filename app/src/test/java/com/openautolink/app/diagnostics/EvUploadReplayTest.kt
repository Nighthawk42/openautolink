package com.openautolink.app.diagnostics

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class EvUploadReplayTest {
    private val fixtureResource = "/ev-replay/0.1.501-a7ddb384-sanitized.jsonl"

    @Test fun sanitizedRealUploadFixtureExercisesCurrentRecorderSemantics() {
        val dataset = EvUploadReplay.loadSanitized(
            checkNotNull(javaClass.getResourceAsStream(fixtureResource))
        )
        assertReplayContract(dataset)
    }

    @Test fun committedFixtureMatchesProvenanceManifestWithoutPrivateArchive() {
        val manifest = Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/ev-replay/provenance.json"))
                .bufferedReader().use { it.readText() },
        ).jsonObject
        val payload = checkNotNull(javaClass.getResourceAsStream(fixtureResource)).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val dataset = EvUploadReplay.loadSanitized(payload.inputStream())

        assertEquals(manifest.getValue("sourceArchiveSha256").jsonPrimitive.content, dataset.metadata.sourceSha256)
        assertEquals(manifest.getValue("fixtureSha256").jsonPrimitive.content, digest)
        assertEquals(manifest.getValue("fixtureBytes").jsonPrimitive.content.toInt(), payload.size)
        assertEquals(manifest.getValue("eventCount").jsonPrimitive.content.toInt(), dataset.events.size)
    }

    @Test fun externallySuppliedArchiveProducesTheCommittedSanitizedInputsAndSemantics() {
        val archivePath = System.getenv("OAL_EV_REPLAY_ARCHIVE")
        assumeTrue("Set OAL_EV_REPLAY_ARCHIVE to run the private-archive gate", !archivePath.isNullOrBlank())
        val actual = EvUploadReplay.loadArchive(File(checkNotNull(archivePath)))
        val fixture = EvUploadReplay.loadSanitized(
            checkNotNull(javaClass.getResourceAsStream(fixtureResource))
        )
        assertEquals(fixture.metadata, actual.metadata)
        assertEquals(fixture.events, actual.events)
        assertReplayContract(actual)
    }

    private fun assertReplayContract(dataset: EvReplayDataset) {
        assertEquals("a7ddb384fa9ac0e998238d1dbce371b5a0c93b3cfd0d07405712bac419f6662d", dataset.metadata.sourceSha256)
        assertEquals("0.1.501", dataset.metadata.release)
        assertEquals(2819, dataset.metadata.sourceRecordCount)
        assertEquals(2468, dataset.events.size)
        assertEquals(2146, dataset.metadata.sourceTypeCounts["navigation"])
        assertEquals(291, dataset.metadata.sourceTypeCounts["vehicle"])

        val output = Files.createTempDirectory("ev-real-upload-replay").toFile()
        try {
            val result = EvUploadReplay.replay(dataset, output)
            assertEquals(1073, result.zeroNavigationInputs)
            assertEquals(1073, result.positiveNavigationInputs)
            assertEquals(1072, result.zerosAfterExplicitDistance)
            assertEquals(0, result.zerosThatOverwroteDistance)
            assertEquals(0, result.zerosThatRefreshedDistance)

            assertEquals(15, result.distinctBatteryObservationIds)
            assertEquals(14, result.batteryObservationIntervalsMs.size)
            assertEquals(100_000L, result.batteryObservationIntervalsMs.minOrNull())
            assertEquals(110_369L, result.batteryObservationIntervalsMs.maxOrNull())
            assertEquals(14, result.completedEnergyWindows)
            assertEquals(14, result.persistedCompletedEnergyWindows)

            assertEquals(0, result.arrivalCandidates)
            assertEquals(0, result.arrivalCandidatesAfterRouteInactive)
            assertEquals(1, result.requestedLearnedRecords)
            assertEquals(291, result.effectiveDerivedWhileLearnedRequested)
            assertEquals(291, result.inactiveLearnerDiagnostics)
            assertEquals(0, result.rawOldCompletionRecords)
        } finally {
            output.deleteRecursively()
        }
    }
}
