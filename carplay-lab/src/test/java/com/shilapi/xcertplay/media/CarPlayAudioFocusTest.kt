package com.shilapi.xcertplay.media

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class CarPlayAudioFocusTest {
    private fun exercise(block: (AndroidMediaSink, AudioManager, AudioFocusRequest, AudioStreamId) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        val sink = AndroidMediaSink(app, surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(), "avc.exact", false))
        val format = requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,"media"))
        val id = AudioStreamId(100,"media")
        try {
            sink.onAudioStarted(id,format,0)
            val request = AndroidMediaSink::class.java.getDeclaredField("focus").apply { isAccessible=true }.get(sink) as AudioFocusRequest
            block(sink,manager,request,id)
        } finally { sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }
    private fun volume(sink: AndroidMediaSink, id: AudioStreamId): Float {
        val tracks = AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible=true }.get(sink) as Map<*,*>
        val track = requireNotNull(tracks[id])
        return track.javaClass.getDeclaredField("requestedVolume").apply { isAccessible=true }.getFloat(track)
    }
    private fun change(request: AudioFocusRequest, value: Int) {
        val listener = AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request) as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(value)
    }
    @Test fun newStreamDuringTransientLossInheritsSilence() = exercise { sink,_,request,id ->
        change(request,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0f,volume(sink,id),0f)
        val next = AudioStreamId(101,"default")
        sink.onAudioStarted(next,requireNotNull(AudioOutputSupport.negotiate(0x4000L,101,"default")),0)
        assertEquals(0f,volume(sink,next),0f)
        change(request,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f,volume(sink,next),0f)
    }
    @Test fun permanentLossCannotBeUndoneByNewStreamOrLateGain() = exercise { sink,_,request,id ->
        change(request,AudioManager.AUDIOFOCUS_LOSS)
        change(request,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0f,volume(sink,id),0f)
        val next = AudioStreamId(101,"default")
        sink.onAudioStarted(next,requireNotNull(AudioOutputSupport.negotiate(0x4000L,101,"default")),0)
        assertEquals(0f,volume(sink,next),0f)
    }
    @Test fun deniedFocusDoesNotStartAudibleTracks() {
        exercise { sink,manager,_,id ->
            sink.onAudioStopped(id)
            shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            sink.onAudioStarted(id,requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,"media")),0)
            assertEquals(0f,volume(sink,id),0f)
        }
    }
    @Test fun onlyExplicitUserPlayMayRequestAgainAfterPermanentLoss() = exercise { sink,manager,request,id ->
        change(request,AudioManager.AUDIOFOCUS_LOSS)
        val method=sink.javaClass.methods.find { it.name=="requestFocusForUserPlay" }
        assertNotNull("permanent loss requires an explicit user PLAY focus seam",method)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals(false,method!!.invoke(sink))
        assertEquals(0f,volume(sink,id),0f)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertEquals(true,method.invoke(sink))
        assertEquals(1f,volume(sink,id),0f)
        val current = AndroidMediaSink::class.java.getDeclaredField("focus").apply { isAccessible=true }.get(sink) as AudioFocusRequest
        change(current,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0f,volume(sink,id),0f)
        change(current,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f,volume(sink,id),0f)
    }
    @Test fun lateCallbackAfterAbandonCannotRestoreAnything() = exercise { sink,_,request,id ->
        change(request,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        sink.onAudioStopped(id)
        change(request,AudioManager.AUDIOFOCUS_GAIN)
        val tracks=AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible=true }.get(sink) as Map<*,*>
        assertTrue(tracks.isEmpty())
    }
    @Test fun streamRestartAfterPermanentLossDoesNotReclaimFocus() = exercise { sink,_,request,id ->
        change(request,AudioManager.AUDIOFOCUS_LOSS)
        sink.onAudioStopped(id)
        sink.onAudioStarted(id,requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,"media")),0)
        assertEquals(0f,volume(sink,id),0f)
    }
    @Test fun abandonedRequestCallbackCannotMuteReplacementTrack() = exercise { sink,_,request,id ->
        sink.onAudioStopped(id)
        sink.onAudioStarted(id,requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,"media")),0)
        change(request,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1f,volume(sink,id),0f)
    }
    @Test fun notificationCanDuckIsNotSilenceAndGainRestores() = exercise { sink,_,request,id ->
        change(request,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0.2f,volume(sink,id),0f)
        change(request,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f,volume(sink,id),0f)
    }
}
