package com.openautolink.app.input

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.Looper
import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32],manifest=Config.NONE)
class GnssProviderActivationTest {
    private fun fixture(registered: Boolean=true): Pair<Context,LocationManager> {
        val context=mockk<Context>(relaxed=true)
        val manager=mockk<LocationManager>(relaxed=true)
        every {context.getSystemService(Context.LOCATION_SERVICE)} returns manager
        every {manager.isProviderEnabled(LocationManager.GPS_PROVIDER)} returns true
        every {manager.addNmeaListener(any<OnNmeaMessageListener>(),any<Handler>())} returns registered
        return context to manager
    }
    @Test fun rejectedNmeaRegistrationCannotClaimActiveSource() {
        val (context,manager)=fixture(false)
        val source=GnssForwarderImpl(context) {}
        source.start(); assertFalse("registration false is not an active GNSS source",source.isActive)
        source.stop(); verify(exactly=0) {manager.requestLocationUpdates(any<String>(),any<Long>(),any<Float>(),any<LocationListener>(),any<Looper>())}
    }
    @Test fun carPlayOwnsProviderRequestOnlyUntilStopAndDoesNotForwardLocationObjects() {
        val (context,manager)=fixture()
        val messages=mutableListOf<ControlMessage.Gnss>()
        val source=GnssForwarderImpl(context,{messages+=it},true)
        source.start(); source.start(); assertTrue(source.isActive)
        val captor=slot<LocationListener>()
        verify(exactly=1) {manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,250L,0f,capture(captor),Looper.getMainLooper())}
        captor.captured.onLocationChanged(android.location.Location(LocationManager.GPS_PROVIDER))
        assertTrue("activation must not fabricate NMEA",messages.isEmpty())
        source.stop(); source.stop(); assertFalse(source.isActive)
        verify(exactly=1) {manager.removeUpdates(captor.captured)}
        verify(exactly=1) {manager.removeNmeaListener(any<OnNmeaMessageListener>())}
    }
}
