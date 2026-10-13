package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import com.openautolink.app.navigation.ManeuverType
import org.junit.Assert.*
import org.junit.Test

class CarPlayClusterMetadataAdapterTest {
    @Test fun sameWireManeuverWorksWithoutAnAppleMapsPackageFilter() {
        val result = CarPlayClusterMetadataAdapter.maneuver(CarPlayNavigationMetadata(2,0,350,"Road",120,1500))
        assertEquals(ManeuverType.TURN_RIGHT,result?.type)
        assertEquals(350,result?.distanceMeters)
        assertEquals(120L,result?.timeToArrivalSeconds)
        assertNull(result?.navImageBase64)
    }
    private fun state(type: Int, side: Int? = null, angle: Int? = null) =
        CarPlayClusterMetadataAdapter.maneuver(CarPlayNavigationMetadata(type,side,null,null,null,null,
            junctionExitAngleDegrees=angle))!!

    @Test fun uTurnFamiliesHonorSignedAngleBeforeKnownTrafficSide() {
        listOf(4,18,26).forEach { type ->
            assertEquals(ManeuverType.U_TURN_LEFT,state(type,1,-180).type)
            assertEquals(ManeuverType.U_TURN_RIGHT,state(type,0,180).type)
            assertEquals(ManeuverType.U_TURN_LEFT,state(type,0).type)
            assertEquals(ManeuverType.U_TURN_RIGHT,state(type,1).type)
            assertEquals(ManeuverType.UNKNOWN,state(type).type)
            assertEquals(ManeuverType.UNKNOWN,state(type,255,0).type)
        }
    }

    @Test fun keepAndDirectionalRampsUseNamedHostPrimitives() {
        assertEquals(ManeuverType.KEEP_LEFT,state(13).type)
        assertEquals(ManeuverType.KEEP_RIGHT,state(14).type)
        assertEquals(ManeuverType.OFF_RAMP_SLIGHT_LEFT,state(8,1,-30).type)
        assertEquals(ManeuverType.ON_RAMP_SLIGHT_RIGHT,state(9,0,30).type)
        assertEquals(ManeuverType.OFF_RAMP_SLIGHT_RIGHT,state(8,0).type)
        assertEquals(ManeuverType.ON_RAMP_SLIGHT_LEFT,state(9,1).type)
        assertEquals(ManeuverType.UNKNOWN,state(8).type)
        assertEquals(ManeuverType.UNKNOWN,state(9,255).type)
        assertEquals(ManeuverType.OFF_RAMP_SLIGHT_LEFT,state(22,0,30).type)
        assertEquals(ManeuverType.OFF_RAMP_SLIGHT_RIGHT,state(23,1,-30).type)
    }

    @Test fun numberedRoundaboutsRetainExactExitAndKnownRotation() {
        (28..46).forEach { type ->
            val rightTraffic = state(type,0)
            assertEquals(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW,rightTraffic.type)
            assertEquals(type-27,rightTraffic.roundaboutExitNumber)
            assertEquals(ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW,state(type,1).type)
            assertEquals(ManeuverType.UNKNOWN,state(type).type)
            assertNull(state(type).roundaboutExitNumber)
            assertNull(rightTraffic.etaSeconds)
            assertNull(rightTraffic.timeToArrivalSeconds)
            assertNull(rightTraffic.distanceMeters)
        }
        // Type 19 is a roundabout U-turn, not an ordinary U-turn. The host requires
        // an exit ordinal which is not sourced here; do not invent one.
        assertEquals(ManeuverType.UNKNOWN,state(19,0,-180).type)
        assertEquals(ManeuverType.UNKNOWN,state(255,0).type)
    }

    @Test fun unknownDistanceOrTimingDoesNotSynthesizeClusterEstimates() {
        val value = CarPlayClusterMetadataAdapter.maneuver(CarPlayNavigationMetadata(2,0,null,"Road",null,null))
        assertNotNull(value)
        assertNull(value?.distanceMeters)
        assertNull(value?.etaSeconds)
    }
}
