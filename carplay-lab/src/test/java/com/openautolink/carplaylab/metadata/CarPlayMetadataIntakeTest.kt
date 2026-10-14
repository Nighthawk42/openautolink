package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class CarPlayMetadataIntakeTest {
    @Test fun acceptedRouteCrossingInstructionDeadlinePurgesOldDetailsAndEstimates() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); string(3,"Old road"); u32(10,10); u64(6,20); u32(7,30) })
        val epoch = intake.routeMetadata?.epoch
        now = 29_999; intake.tick()
        now = 30_001
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNull("route renewal must first purge the crossed instruction deadline", intake.navigation)
        assertEquals(epoch, intake.routeMetadata?.epoch)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
        val fresh = requireNotNull(intake.navigation)
        assertEquals(3, fresh.maneuverType)
        assertNull(fresh.road); assertNull(fresh.distanceMeters)
        assertNull(fresh.remainingSeconds); assertNull(fresh.remainingMeters)
    }

    @Test fun acceptedRouteCrossingOwnershipDeadlineCreatesNewEpochWithoutOldCache() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        val epoch = intake.routeMetadata?.epoch
        now = 119_999; intake.tick()
        now = 120_001
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNotEquals("route renewal cannot erase a crossed ownership deadline", epoch, intake.routeMetadata?.epoch)
        assertNull(intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
        assertEquals(3, intake.navigation?.maneuverType)
    }

    @Test fun acceptedManeuverCrossingOwnershipDeadlineCannotRetainOldRoute() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        val epoch = intake.routeMetadata?.epoch
        now = 119_999; intake.tick()
        now = 120_001
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,3) })
        assertNull("maneuver traffic must enforce old ownership expiry", intake.routeMetadata)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNotEquals(epoch, intake.routeMetadata?.epoch)
        assertNull("old selected detail must be purged", intake.navigation)
    }

    @Test fun invalidFramesCrossingDeadlinesLeaveOldSnapshotUntouchedUntilTick() {
        for (deadline in listOf(30_000L, 120_000L)) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            now = deadline - 1; intake.tick()
            val before = intake.routeMetadata
            now = deadline + 1
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,99); u16List(13,listOf(8)) })
            assertEquals(before, intake.routeMetadata)
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,3); bytes(11,byteArrayOf(1)) })
            assertEquals(before, intake.routeMetadata)
            intake.tick()
            assertNull(intake.navigation)
            if (deadline == 120_000L) assertNull(intake.routeMetadata)
        }
    }

    @Test fun staleLoadingRouteDiscardsCachedFutureDetailsBeforeFreshSelection() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(8)) })
        val epoch = intake.routeMetadata?.epoch
        assertNull(intake.navigation)
        now = 30_000; intake.tick()
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNull("stale cached future instruction must not be replayed", intake.navigation)
        assertEquals(epoch, intake.routeMetadata?.epoch)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
        assertEquals(3, intake.navigation?.maneuverType)
    }

    @Test fun staleInstructionDegradesAtThirtySecondsButRouteExpiresAtTwoMinutesWithoutFreshRoute() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }
        intake.authenticate()
        fun details() = intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        fun route() = intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        details(); route()
        val epoch = requireNotNull(intake.routeMetadata).epoch
        now = 29_999; intake.tick(); assertNotNull(intake.navigation)
        now = 30_000; intake.tick()
        assertNull("stale turn must disappear", intake.navigation)
        assertNotNull("instruction expiry is not route termination", intake.routeMetadata)
        assertEquals(epoch, intake.routeMetadata?.epoch)
        details()
        assertNull("maneuver-only traffic cannot revive stale route guidance", intake.navigation)
        now = 119_999; intake.tick(); assertNotNull(intake.routeMetadata)
        now = 120_000; intake.tick(); assertNull(intake.routeMetadata)
        route()
        assertNull("expired route cannot reuse previous cached instruction", intake.navigation)
        assertNotEquals(epoch, intake.routeMetadata?.epoch)
        details(); assertNotNull(intake.navigation)
    }

    @Test fun firstParseFailuresAndFirstManeuverAreNotHiddenBySameWindowRoute() {
        val lines = mutableListOf<String>()
        val intake = CarPlayMetadataIntake { 0L }.apply { onDiagnostic = lines::add }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,99) })
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8) })
        assertTrue(lines.any { it.startsWith("carplay-nav kind=maneuver parsed=1 failed=0 result=parsed") })
        assertTrue(lines.any { it.startsWith("carplay-nav kind=route parsed=1 failed=1 result=failed") })
        assertTrue(lines.any { it.startsWith("carplay-nav kind=maneuver parsed=1 failed=1 result=failed") })
        repeat(100) { intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8) }) }
        assertEquals("same-window failures must remain bounded",4,lines.size)
        assertNull(intake.navigation)
    }

    @Test fun observabilityPreservesAbsentEmptyStopArrivalEvictionAndRevokeSemantics() {
        var now = 0L
        val lines = mutableListOf<String>()
        val intake = CarPlayMetadataIntake { now }.apply { onDiagnostic = lines::add }
        intake.authenticate()
        fun route() { intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) }) }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Private") })
        route(); assertNotNull(intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u32(10,99) })
        assertNotNull(intake.navigation) // absent current list retains selected maneuver
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,0); u8(1,1) })
        assertNotNull(intake.navigation) // duplicate TLVs still rejected
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,emptyList()) })
        assertNull(intake.navigation) // no speculative empty-list grace
        route(); assertNotNull(intake.navigation)
        for (id in 8..15) intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,id); u8(3,2) })
        assertNotNull(intake.navigation) // future entries cannot evict selected index
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(8)) })
        assertNull(intake.navigation) // oldest unselected entry was evicted
        assertTrue(lines.any { it.contains("transition=clear reason=cache_miss") })
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        route()
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,2) })
        assertNull(intake.navigation)
        assertTrue(lines.any { it.contains("transition=clear reason=arrived") })
        route(); assertNull(intake.navigation) // arrival purged cached maneuvers
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.revoke(); assertNull(intake.navigation)
        assertTrue(lines.any { it.contains("transition=clear reason=revoke") })
        intake.authenticate(); route(); assertNull(intake.navigation)
        assertFalse(lines.any { it.contains("Private") })
    }

    @Test fun trackChangeWithoutNewArtReferenceClearsPreviousCover() {
        val intake = CarPlayMetadataIntake { 0L }; intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"First"); u8(26,7) } })
        intake.offerArtwork(7, byteArrayOf(1))
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Second") } })
        assertNull(intake.artwork)
        assertNull(intake.media?.artworkTransferId)
        intake.offerArtwork(7, byteArrayOf(2))
        assertNull(intake.artwork)
    }

    @Test fun bluetoothBootstrapDoesNotClaimLongLivedMetadataEndpoints() {
        val base = Iap2Messages.buildRaw(0x1d01) {
            u16List(6,listOf(0xaa01,0x5000,0x5002,0x5200,0x5203))
            u16List(7,listOf(0xaa00,0x5001,0x5201,0x5202))
            group(30) { u16(0,42); string(1,"RouteGuidance") }
        }
        val b = com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(CarPlayMetadataIdentification.bootstrap(base))
        assertEquals(listOf(0xaa01),b.u16List(6))
        assertEquals(listOf(0xaa00),b.u16List(7))
        assertFalse(b.has(30))
    }

    @Test fun malformedAndOversizedFramesCannotPartiallyMutateState() {
        val intake = CarPlayMetadataIntake { 0L }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Good") } })
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Bad"); bytes(4,byteArrayOf(1)) } })
        assertEquals("Good",intake.media?.title)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"x".repeat(20000)) } })
        assertEquals("Good",intake.media?.title)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,10); u16List(13,listOf(7)) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,0); bytes(10,byteArrayOf(1)) })
        assertEquals(10,intake.navigation?.distanceMeters)
        intake.revoke()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Late") } })
        assertNull(intake.media)
        assertNull(intake.navigation)
    }

    @Test fun artworkIsBoundToAuthenticatedCurrentTransferAndRetiredGeneration() {
        val intake = CarPlayMetadataIntake { 0L }
        intake.offerArtwork(7, byteArrayOf(1,2))
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { u8(26,7) } })
        assertNull(intake.artwork)
        intake.offerArtwork(7, byteArrayOf(1,2))
        assertArrayEquals(byteArrayOf(1,2),intake.artwork)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { u8(26,8) } })
        assertNull(intake.artwork)
        intake.offerArtwork(7, byteArrayOf(3))
        assertNull(intake.artwork)
        intake.offerArtwork(8, byteArrayOf(4))
        intake.revoke()
        intake.authenticate()
        intake.offerArtwork(8, byteArrayOf(5))
        assertNull(intake.artwork)
        assertNull(intake.media)
    }

    @Test fun boundedNavigationIsProviderAgnosticAndRetiresOnNoRoute() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Road"); u8(8,0) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,350); u16List(13,listOf(7)); u64(6,120); u32(7,1500) })
        assertEquals(2,intake.navigation?.maneuverType)
        assertEquals(350,intake.navigation?.distanceMeters)
        assertEquals("Road",intake.navigation?.road)
        now = 30000
        intake.tick()
        assertNull(intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,0) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNull(intake.navigation) // previous route's cached index must not revive
    }

    @Test fun authenticatedNowPlayingRetainsOmittedFieldsWithoutInventingPlayback() {
        val intake = CarPlayMetadataIntake { 0L }
        val title = Iap2Messages.buildRaw(0x5001) { group(0) { string(1, "Song"); string(12,"Artist") } }
        intake.handle(title)
        assertNull(intake.media)
        intake.authenticate()
        intake.handle(title)
        assertEquals("Song", intake.media?.title)
        assertNull(intake.media?.playing)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u32(1,1234); u8(0,1) } })
        assertEquals("Artist",intake.media?.artist)
        assertEquals(1234L,intake.media?.elapsedMillis)
        assertEquals(true,intake.media?.playing)
    }
}
