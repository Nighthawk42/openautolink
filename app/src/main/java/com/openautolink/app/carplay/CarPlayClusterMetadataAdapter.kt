package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType

/** Provider-independent vocabulary. Does not equate trip remaining time with time to next turn. */
internal object CarPlayClusterMetadataAdapter {
    fun maneuver(value: CarPlayNavigationMetadata): ManeuverState? {
        val distance = value.distanceMeters
        // accNav enum names/sign from Maps 23G71 binary reconstruction; accessoryd
        // 24A446 corroborates the wire enum/angle widths. Not owner-phone acceptance.
        val goesLeft = value.junctionExitAngleDegrees?.takeIf { it != 0 }?.let { it < 0 }
        val uTurnLeft = goesLeft ?: when (value.drivingSide) { 0 -> true; 1 -> false; else -> null }
        val rampLeft = goesLeft ?: when (value.drivingSide) { 0 -> false; 1 -> true; else -> null }
        val roundabout = when (value.drivingSide) {
            0 -> ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW
            1 -> ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW
            else -> ManeuverType.UNKNOWN
        }
        val type = when (value.maneuverType) {
            1,20 -> ManeuverType.TURN_LEFT
            2,21 -> ManeuverType.TURN_RIGHT
            4,18,26 -> when (uTurnLeft) { true -> ManeuverType.U_TURN_LEFT; false -> ManeuverType.U_TURN_RIGHT; null -> ManeuverType.UNKNOWN }
            8 -> when (rampLeft) { true -> ManeuverType.OFF_RAMP_SLIGHT_LEFT; false -> ManeuverType.OFF_RAMP_SLIGHT_RIGHT; null -> ManeuverType.UNKNOWN }
            9 -> when (rampLeft) { true -> ManeuverType.ON_RAMP_SLIGHT_LEFT; false -> ManeuverType.ON_RAMP_SLIGHT_RIGHT; null -> ManeuverType.UNKNOWN }
            13 -> ManeuverType.KEEP_LEFT
            14 -> ManeuverType.KEEP_RIGHT
            22 -> ManeuverType.OFF_RAMP_SLIGHT_LEFT
            23 -> ManeuverType.OFF_RAMP_SLIGHT_RIGHT
            in 28..46 -> roundabout
            47 -> ManeuverType.TURN_SHARP_LEFT
            48 -> ManeuverType.TURN_SHARP_RIGHT
            49,52 -> ManeuverType.TURN_SLIGHT_LEFT
            50,53 -> ManeuverType.TURN_SLIGHT_RIGHT
            3,5,11,51 -> ManeuverType.STRAIGHT
            6 -> ManeuverType.ROUNDABOUT_ENTER
            7 -> ManeuverType.ROUNDABOUT_EXIT
            10,12,24,25,27 -> ManeuverType.DESTINATION
            // 19 is a roundabout U-turn, but no exit ordinal is sourced for the
            // host's mandatory ordinal. Leave unknown instead of inventing exit 1.
            else -> ManeuverType.UNKNOWN
        }
        val exit = (value.maneuverType - 27).takeIf { value.maneuverType in 28..46 && type != ManeuverType.UNKNOWN }
        return ManeuverState(type,distance,"",value.road,null,
            roundaboutExitNumber = exit,
            timeToArrivalSeconds = value.remainingSeconds, destDistanceMeters = value.remainingMeters)
    }
}
