package com.openautolink.app.media

import android.content.Context
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import com.openautolink.app.carplay.MetadataHostOwnership
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,application=android.app.Application::class)
class CarPlayMediaControlRoutingTest {
    @After fun cleanup() { unmockkAll() }
    private fun manager(): Pair<OalMediaSessionManager,MediaSessionCompat.Callback> {
        val app=RuntimeEnvironment.getApplication()
        val callback=slot<MediaSessionCompat.Callback>()
        mockkConstructor(MediaSessionCompat::class)
        every { anyConstructed<MediaSessionCompat>().setCallback(capture(callback)) } just Runs
        every { anyConstructed<MediaSessionCompat>().setPlaybackState(any()) } just Runs
        every { anyConstructed<MediaSessionCompat>().setMetadata(any()) } just Runs
        every { anyConstructed<MediaSessionCompat>().setActive(any()) } just Runs
        val manager=OalMediaSessionManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible=true }.newInstance(app)
        manager.initialize()
        return manager to callback.captured
    }
    @Test fun productionHostMapsCommandsToConsumerHidAndRestoresAaOnClose() {
        val (manager,callback)=manager()
        val app=RuntimeEnvironment.getApplication()
        val prefs=mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        mockkObject(OalMediaSessionManager.Companion)
        every { OalMediaSessionManager.getInstance(any()) } returns manager
        val aa=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        manager.mediaControlCallback=aa
        val sent=mutableListOf<Int>()
        val constructor=com.openautolink.app.carplay.OalCarPlayMetadataHost::class.java.declaredConstructors.find { it.parameterCount==2 }
        assertNotNull("metadata host must install a real command transport",constructor)
        val sender: (Int)->Boolean = { sent.add(it);true }
        val host=constructor!!.apply { isAccessible=true }.newInstance(app,sender) as com.openautolink.app.carplay.CarPlayMetadataHost
        try {
            assertTrue(host.start())
            callback.onPlay();callback.onPause();callback.onStop();callback.onSkipToNext();callback.onSkipToPrevious()
            assertEquals(listOf(1,2,2,4,5),sent)
            verify(exactly=0) { aa.onCommand(any()) }
            host.close();host.close();callback.onStop()
            verify(exactly=1) { aa.onCommand(GmMediaControlPolicy.Command.STOP) }
        } finally { host.close();manager.release() }
    }
    @Test fun exactCarPlayCommandsAndTeardownRestoreRetainedAndroidAutoCallback() {
        val (manager,callback)=manager()
        val aa=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        val cp=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        manager.mediaControlCallback=aa
        val owner=Any(); val replacement=Any()
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            manager.installCarPlayControls(owner,cp)
            callback.onPlay(); callback.onPause(); callback.onStop(); callback.onSkipToNext(); callback.onSkipToPrevious()
            verifySequence { cp.onCommand(GmMediaControlPolicy.Command.PLAY); cp.onCommand(GmMediaControlPolicy.Command.PAUSE); cp.onCommand(GmMediaControlPolicy.Command.STOP); cp.onCommand(GmMediaControlPolicy.Command.NEXT); cp.onCommand(GmMediaControlPolicy.Command.PREVIOUS) }
            verify(exactly=0) { aa.onCommand(any()) }
            manager.retireCarPlayControls(replacement)
            callback.onStop()
            verify(exactly=2) { cp.onCommand(GmMediaControlPolicy.Command.STOP) }
            MetadataHostOwnership.process.retire(owner) { manager.retireCarPlayControls(owner) }
            callback.onStop()
            verify(exactly=1) { aa.onCommand(GmMediaControlPolicy.Command.STOP) }
        } finally { MetadataHostOwnership.process.retire(owner) {}; manager.release() }
    }
    @Test fun carPlayOwnedStopNeverFallsBackToAndroidAuto() {
        val (manager,callback)=manager()
        val aa=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        manager.mediaControlCallback=aa
        val owner=Any()
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            callback.onStop()
            verify(exactly=0) { aa.onCommand(any()) }
        } finally { MetadataHostOwnership.process.retire(owner) {}; manager.release() }
    }
}
