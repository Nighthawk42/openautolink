package com.openautolink.app.transport.aasdk

/** Exact NavigationStateType values on Android Auto navigation message 0x8003. */
enum class NavigationRouteAction { ACTIVATE, CLEAR, REROUTE }

data class NavigationStatusMeaning(
    val name: String,
    val routeAction: NavigationRouteAction,
)

object NavigationStatusSemantics {
    fun fromWire(status: Int): NavigationStatusMeaning = when (status) {
        0 -> NavigationStatusMeaning("UNAVAILABLE", NavigationRouteAction.CLEAR)
        1 -> NavigationStatusMeaning("ACTIVE", NavigationRouteAction.ACTIVATE)
        2 -> NavigationStatusMeaning("INACTIVE", NavigationRouteAction.CLEAR)
        3 -> NavigationStatusMeaning("REROUTING", NavigationRouteAction.REROUTE)
        else -> NavigationStatusMeaning("UNKNOWN($status)", NavigationRouteAction.CLEAR)
    }
}
