package com.openautolink.app.transport.aasdk

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStatusSemanticsTest {
    @Test fun protocolEnumNamesAndLifecycleActionsAreExact() {
        assertEquals("UNAVAILABLE", NavigationStatusSemantics.fromWire(0).name)
        assertEquals("ACTIVE", NavigationStatusSemantics.fromWire(1).name)
        assertEquals("INACTIVE", NavigationStatusSemantics.fromWire(2).name)
        assertEquals("REROUTING", NavigationStatusSemantics.fromWire(3).name)
        assertEquals("UNKNOWN(9)", NavigationStatusSemantics.fromWire(9).name)

        assertEquals(NavigationRouteAction.ACTIVATE, NavigationStatusSemantics.fromWire(1).routeAction)
        assertEquals(NavigationRouteAction.CLEAR, NavigationStatusSemantics.fromWire(2).routeAction)
        assertEquals(NavigationRouteAction.REROUTE, NavigationStatusSemantics.fromWire(3).routeAction)
        assertEquals(NavigationRouteAction.CLEAR, NavigationStatusSemantics.fromWire(0).routeAction)
        assertEquals(NavigationRouteAction.CLEAR, NavigationStatusSemantics.fromWire(9).routeAction)
    }
}
