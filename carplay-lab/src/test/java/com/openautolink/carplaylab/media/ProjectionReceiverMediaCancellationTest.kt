// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowMediaCodecList
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class ProjectionReceiverMediaCancellationTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun receiver(): ProjectionReceiver {
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("cancellation.avc")
            .setIsEncoder(false).setCapabilities(caps).build())
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        return ProjectionReceiver(RuntimeEnvironment.getApplication(), "02:00:00:00:00:01", surface,
            mock(MfiAuthenticator::class.java), wired = true, onStatus = {})
    }
    private fun session(event: Socket): AirPlaySession {
        val session = AirPlaySession(Socket(),
            AirPlayConfig("cancellation", "synthetic", "synthetic", "1", AirPlayDisplayConfig(1280, 720)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        (field(session, "authenticated").get(session) as AtomicBoolean).set(true)
        field(session, "eventSocket").set(session, event)
        field(session, "eventCipher").set(session, ControlCipher(ByteArray(32), ByteArray(32)))
        return session
    }
    private fun admit(receiver: ProjectionReceiver, session: AirPlaySession) {
        @Suppress("UNCHECKED_CAST")
        (field(receiver, "sessions").get(receiver) as MutableSet<AirPlaySession>).add(session)
        @Suppress("UNCHECKED_CAST")
        (field(receiver, "allSessions").get(receiver) as MutableSet<AirPlaySession>).add(session)
        field(receiver, "active").set(receiver, session)
        field(receiver, "runtimeOwner").set(receiver, session)
        field(receiver, "runtimeReady").set(receiver, true)
    }
    private fun bounded(action: () -> Unit) {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "test-cancellation").apply { isDaemon = true } }
        try {
            try { worker.submit(action).get(1, TimeUnit.SECONDS) }
            catch (_: TimeoutException) { fail("cancellation/close did not return within 1 second") }
        } finally { worker.shutdownNow() }
    }
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { /* OS call does not honor interruption */ }
    }
    /** Concrete event transport, not a sendMedia stub: production encryption/framing writes here.
     * The output decorator holds the OS boundary after cancellation to prove worker acknowledgment
     * separately from actual loopback socket closure. It delegates all successful bytes to TCP. */
    private inner class BlockedEventSocket(val transport: Socket) : Socket() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transportClosed = CountDownLatch(1)
        private val output = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                entered.countDown()
                awaitIgnoringInterrupt(release)
                transport.getOutputStream().write(bytes, offset, length)
            }
        }
        override fun getOutputStream(): OutputStream = output
        override fun isClosed(): Boolean = transport.isClosed
        override fun close() { transport.close(); transportClosed.countDown() }
    }
    @Test fun runningEncryptedMediaWriteCannotBlockCloseOrPrematurelyReleasePairingOwner() {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { peer ->
                peer.soTimeout = 1000
                val event = BlockedEventSocket(listener.accept())
                val receiver = receiver()
                val session = session(event)
                admit(receiver, session)
                val input = field(receiver, "input").get(receiver) as ExecutorService
                try {
                    assertTrue(receiver.mediaCommand(session, 2))
                    assertTrue("real receiver worker entered encrypted event output", event.entered.await(3, TimeUnit.SECONDS))
                    bounded { receiver.revokePendingAdmission() }
                    bounded { receiver.close() }
                    assertTrue("actual event socket closed without its writer lock", event.transportClosed.await(1, TimeUnit.SECONDS))
                    assertEquals("peer observes concrete transport cancellation", -1, peer.getInputStream().read())
                    assertFalse("exact input worker still blocked", input.isTerminated)
                    assertFalse("transport close is not worker retirement", receiver.awaitClosed(50))
                    assertThrows(java.io.IOException::class.java) { receiver() }
                    bounded { receiver.close() } // retained retirement must tolerate Retry while blocked
                    event.release.countDown()
                    assertTrue(receiver.awaitClosed(3000))
                    assertTrue(input.isTerminated)
                    val successor = receiver()
                    successor.close(); assertTrue(successor.awaitClosed(3000))
                } finally {
                    event.release.countDown(); receiver.close(); assertTrue(receiver.awaitClosed(3000))
                    session.close(); event.close()
                }
            }
        }
    }
    @Test fun saturatedRealTcpEventWriteIsCancelledByReceiverClose() {
        ServerSocket(0).use { listener ->
            Socket().use { peer ->
                peer.receiveBufferSize = 1024
                peer.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort))
                val event = listener.accept().apply { sendBufferSize = 1024; tcpNoDelay = true }
                val receiver = receiver()
                val session = session(event)
                admit(receiver, session)
                val worker = (field(receiver, "input").get(receiver) as ThreadPoolExecutor)
                val filling = AtomicBoolean(true)
                val producer = Thread({ while (filling.get()) { receiver.mediaCommand(session, 2); Thread.yield() } },
                    "test-event-fill").apply { isDaemon = true; start() }
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var blocked = false
                    var blockedSince = 0L
                    while (System.nanoTime() < deadline && !blocked) {
                        val writing = Thread.getAllStackTraces().any { (thread, stack) ->
                            thread.name == "lab-media-input" && stack.any {
                                it.className == "sun.nio.ch.SocketDispatcher" && it.methodName.startsWith("write")
                            }
                        }
                        if (!writing) blockedSince = 0L
                        else if (blockedSince == 0L) blockedSince = System.nanoTime()
                        blocked = writing && System.nanoTime() - blockedSince >= TimeUnit.MILLISECONDS.toNanos(500)
                        if (!blocked) Thread.sleep(5)
                    }
                    assertTrue("actual production HID command must block in kernel TCP write, not a mock", blocked)
                    bounded { receiver.revokePendingAdmission() }
                    bounded { receiver.close() }
                    assertTrue("actual stalled TCP socket closed", event.isClosed)
                    assertTrue(receiver.awaitClosed(3000))
                    assertTrue(worker.isTerminated)
                    val successor = receiver()
                    successor.close(); assertTrue(successor.awaitClosed(3000))
                } finally {
                    filling.set(false); event.close(); producer.join(3000)
                    assertFalse(producer.isAlive)
                    receiver.close(); assertTrue(receiver.awaitClosed(3000)); session.close()
                }
            }
        }
    }
    @Test
    @Config(shadows = [BlockedFocusManager::class])
    fun runningPlatformFocusCannotBlockCloseOrSendAfterRevocation() {
        BlockedFocusManager.entered = CountDownLatch(1)
        BlockedFocusManager.release = CountDownLatch(1)
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { peer ->
                peer.soTimeout = 1000
                val event = listener.accept()
                val receiver = receiver()
                val session = session(event)
                admit(receiver, session)
                val input = field(receiver, "input").get(receiver) as ExecutorService
                try {
                    assertTrue(receiver.mediaCommand(session, 1))
                    assertTrue("real AudioManager focus boundary entered", BlockedFocusManager.entered.await(3, TimeUnit.SECONDS))
                    bounded { receiver.revokePendingAdmission() }
                    bounded { receiver.close() }
                    assertTrue("focus stall cannot prevent transport cancellation", event.isClosed)
                    assertEquals("revoked PLAY sent no encrypted bytes", -1, peer.getInputStream().read())
                    assertFalse(input.isTerminated)
                    assertFalse(receiver.awaitClosed(50))
                    assertThrows(java.io.IOException::class.java) { receiver() }
                    bounded { receiver.close() }
                    BlockedFocusManager.release.countDown()
                    assertTrue(receiver.awaitClosed(3000)); assertTrue(input.isTerminated)
                    val sink = field(receiver, "sink").get(receiver) as AndroidMediaSink
                    assertFalse("late platform focus result cannot reclaim retired sink", sink.requestFocusForUserPlay())
                    val successor = receiver()
                    successor.close(); assertTrue(successor.awaitClosed(3000))
                } finally {
                    BlockedFocusManager.release.countDown(); receiver.close(); assertTrue(receiver.awaitClosed(3000))
                    session.close(); event.close()
                }
            }
        }
    }
    @Implements(AudioManager::class)
    class BlockedFocusManager : ShadowAudioManager() {
        @Implementation
        override fun requestAudioFocus(request: android.media.AudioFocusRequest?): Int {
            entered.countDown()
            while (true) try { release.await(); break } catch (_: InterruptedException) { }
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        companion object {
            @JvmField var entered = CountDownLatch(1)
            @JvmField var release = CountDownLatch(1)
        }
    }
}
