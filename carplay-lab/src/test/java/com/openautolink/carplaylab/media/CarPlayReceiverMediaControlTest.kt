package com.openautolink.carplaylab.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class CarPlayReceiverMediaControlTest {
    private fun field(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }
    private fun receiver(): ProjectionReceiver {
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply { profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52 })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("media.avc").setIsEncoder(false).setCapabilities(caps).build())
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val app=RuntimeEnvironment.getApplication(); java.io.File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        return ProjectionReceiver(app,"02:00:00:00:00:01",surface,mock(MfiAuthenticator::class.java),wired=true,onStatus={})
    }
    private fun admit(receiver: ProjectionReceiver,session: AirPlaySession) {
        `when`(session.controlAuthenticated).thenReturn(true); `when`(session.encryptedEventReady).thenReturn(true)
        (field(receiver,"sessions").get(receiver) as MutableSet<AirPlaySession>).add(session)
        field(receiver,"active").set(receiver,session);field(receiver,"runtimeOwner").set(receiver,session);field(receiver,"runtimeReady").set(receiver,true)
    }
    private fun command(receiver: ProjectionReceiver,session: AirPlaySession,index: Int): Boolean {
        val method=receiver.javaClass.methods.find { it.name=="mediaCommand" }
        assertNotNull("authenticated exact-session media transport must be exposed to host",method)
        return method!!.invoke(receiver,session,index) as Boolean
    }
    @Test fun commandUsesBoundedInputWorkerAndExactAuthenticatedSession() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);val sent=CountDownLatch(1)
        admit(receiver,session)
        `when`(session.sendMedia(2)).thenAnswer { assertEquals("lab-media-input",Thread.currentThread().name);sent.countDown();true }
        try {
            assertTrue(command(receiver,session,2));assertTrue(sent.await(3,TimeUnit.SECONDS))
            assertFalse(command(receiver,mock(AirPlaySession::class.java),2))
            assertFalse(command(receiver,session,7))
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun userPlayTransportWaitsForFocusGrantInsteadOfOverridingCallDenial() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val manager=RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        try {
            org.robolectric.Shadows.shadowOf(manager).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            assertTrue(command(receiver,session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session,never()).sendMedia(1)
            org.robolectric.Shadows.shadowOf(manager).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            assertTrue(command(receiver,session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session).sendMedia(1)
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun queuedCommandCannotReachReplacedOwner() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        input.execute { entered.countDown();release.await() }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(command(receiver,session,2))
            admit(receiver,mock(AirPlaySession::class.java));release.countDown()
            input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session,never()).sendMedia(anyInt())
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun queuedCommandCannotReachRevokedOwner() {
        val receiver=receiver();val session=mock(AirPlaySession::class.java);admit(receiver,session)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val input=field(receiver,"input").get(receiver) as ExecutorService
        input.execute { entered.countDown();release.await() }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(command(receiver,session,2))
            receiver.revokePendingAdmission();release.countDown()
            input.submit {}.get(3,TimeUnit.SECONDS)
            verify(session,never()).sendMedia(anyInt())
            assertFalse(command(receiver,session,2))
        } finally { release.countDown();receiver.close();assertTrue(receiver.awaitClosed(3000)) }
    }
}
