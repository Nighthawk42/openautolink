// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import org.mockito.Mockito.*
import org.robolectric.shadows.MediaCodecInfoBuilder
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import com.openautolink.carplaylab.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class CarPlayPresentationTest {
    // Reflect until the public API exists: baseline RED is an assertion, not unresolved Kotlin.
    private fun receipt(sink: Any, name: String, surface: Surface) {
        val method = sink.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(Surface::class.java)) }
        assertNotNull("public $name(Surface) presentation receipt required", method)
        method!!.invoke(sink, surface)
    }
    private class Decoder(kind: VideoCodec = VideoCodec.H264) {
        val configuredSurface = AtomicReference<Surface>()
        val format = AtomicReference<MediaFormat>()
        val listener = AtomicReference<MediaCodec.OnFrameRenderedListener>()
        val queued = CountDownLatch(1)
        val released = CountDownLatch(1)
        val effectThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val codec: MediaCodec = mock(MediaCodec::class.java)
        init {
            val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(if (kind == VideoCodec.H264) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC,1280,720))
                .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
                .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                    profile = if (kind == VideoCodec.H264) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    level = if (kind == VideoCodec.H264) MediaCodecInfo.CodecProfileLevel.AVCLevel52 else MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31
                })).build()
            val info = MediaCodecInfoBuilder.newBuilder().setName("test.avc").setIsEncoder(false).setCapabilities(caps).build()
            `when`(codec.codecInfo).thenReturn(info); `when`(codec.name).thenReturn("test.avc")
            `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java),anyLong())).thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER)
            `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(0)
            `when`(codec.getInputBuffer(0)).thenReturn(ByteBuffer.allocate(4096))
            doAnswer { effectThreads.add(Thread.currentThread().name); format.set(it.getArgument(0)); configuredSurface.set(it.getArgument(1)); null }.`when`(codec)
                .configure(any(MediaFormat::class.java),any(Surface::class.java),isNull(),eq(0))
            doAnswer { listener.set(it.getArgument(0)); null }.`when`(codec)
                .setOnFrameRenderedListener(any(MediaCodec.OnFrameRenderedListener::class.java),any(android.os.Handler::class.java))
            doAnswer { effectThreads.add(Thread.currentThread().name); queued.countDown(); null }.`when`(codec).queueInputBuffer(anyInt(),anyInt(),anyInt(),anyLong(),anyInt())
            doAnswer { effectThreads.add(Thread.currentThread().name); released.countDown(); null }.`when`(codec).release()
            doAnswer { effectThreads.add(Thread.currentThread().name); null }.`when`(codec).start()
            doAnswer { effectThreads.add(Thread.currentThread().name); null }.`when`(codec).stop()
        }
    }
    private class Fixture(
        val surface: Surface = Surface(SurfaceTexture(0)),
        private val statusObserver: (String) -> Unit = {},
        private val kind: VideoCodec = VideoCodec.H264,
        setup: (Decoder) -> Unit = {},
    ) {
        val created = LinkedBlockingQueue<Decoder>()
        val configured = LinkedBlockingQueue<String>()
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recovery = java.util.concurrent.atomic.AtomicInteger()
        val telemetry = ReceiverTelemetry(1)
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display = CarPlayDisplaySelection(CarPlayDisplayPreferences(),"test.avc",false),
            videoSelection = CarPlayVideoSelection(1280,720,30,"test.avc","test.hevc"), telemetry = telemetry,
            videoCodecFactory = { Decoder(kind).also { setup(it); created.offer(it) }.codec },
            onStatus = {
                statuses.add(it)
                if (it == "media stage=h264-configured" || it == "media stage=h265-configured") configured.offer(it)
                statusObserver(it)
            })
        init { sink.setVideoRecoveryHandler(110) { recovery.incrementAndGet() } }
        fun config() { sink.onVideoCodec(110, kind); sink.onVideoConfig(110, if (kind == VideoCodec.H264) GeneratedHevcFixture.avcc else GeneratedHevcFixture.hvcc) }
        fun decoder(): Decoder {
            assertNotNull("production worker configure receipt", configured.poll(3,TimeUnit.SECONDS))
            return checkNotNull(created.poll(3,TimeUnit.SECONDS))
        }
        fun close() { sink.close(); assertTrue(sink.awaitRetired(3000)); surface.release() }
    }
    private class PollGate(sink: AndroidMediaSink) {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        init {
            val once = java.util.concurrent.atomic.AtomicBoolean()
            sink.beforeVideoPoll = {
                if (once.compareAndSet(false, true)) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue("actual worker parked after config snapshot", entered.await(3, TimeUnit.SECONDS))
        }
    }
    private val idr get() = byteArrayOf(0,0,0,1,0x65,0)
    private fun changedConfig() = GeneratedHevcFixture.avcc.copyOf().apply { this[41] = (this[41].toInt() xor 1).toByte() }
    private fun parkedConfigIdr(changed: Boolean) {
        val f = Fixture()
        var gate: PollGate? = null
        try {
            val old = if (changed) { f.config(); f.decoder() } else null
            gate = PollGate(f.sink)
            if (changed) f.sink.onVideoConfig(110, changedConfig()) else f.config()
            f.sink.onVideoFrame(110, idr)
            gate.resume.countDown()
            val fresh = f.decoder()
            assertTrue("same waking IDR must reach new decoder", fresh.queued.await(3, TimeUnit.SECONDS))
            old?.listener?.get()?.onFrameRendered(old.codec,1,1)
            assertNull("predecessor callback fenced", f.telemetry.snapshot(1).renderedFrames)
            fresh.listener.get().onFrameRendered(fresh.codec,2,2)
            assertEquals(1L, f.telemetry.snapshot(2).renderedFrames)
            assertTrue(fresh.effectThreads.all { it == "lab-video" })
        } finally { gate?.resume?.countDown(); f.close() }
    }
    @Test fun initialConfigIdrWakingParkedPollRenders() = parkedConfigIdr(false)
    @Test fun changedConfigIdrWakingParkedPollRenders() = parkedConfigIdr(true)
    @Test fun duplicateConfigDuringBlockedConfigureKeepsCandidate() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val f = Fixture { decoder ->
            doAnswer { entered.countDown(); ignoreInterrupt(resume); null }.`when`(decoder.codec)
                .configure(any(MediaFormat::class.java),any(Surface::class.java),isNull(),eq(0))
        }
        try {
            f.config(); assertTrue(entered.await(3,TimeUnit.SECONDS))
            val candidate = checkNotNull(f.created.poll(3,TimeUnit.SECONDS))
            val config = latestReceipt(f.sink)
            f.sink.onVideoConfig(110,GeneratedHevcFixture.avcc.copyOf())
            assertSame("exact duplicate must retain in-flight receipt",config,latestReceipt(f.sink))
            f.sink.onVideoFrame(110,idr)
            resume.countDown()
            assertTrue(candidate.queued.await(3,TimeUnit.SECONDS))
            verify(candidate.codec).start(); verify(candidate.codec,never()).release()
            candidate.listener.get().onFrameRendered(candidate.codec,1,1)
            assertEquals(1L,f.telemetry.snapshot(1).renderedFrames)
            assertTrue(f.created.isEmpty())
        } finally { resume.countDown(); f.close() }
    }
    @Test fun duplicateConfigPreservesQueuedDeltasAnchorAndCurrentCallback() {
        val f = Fixture(); var gate: PollGate? = null
        try {
            f.config(); val decoder = f.decoder()
            f.sink.onVideoFrame(110,idr)
            assertTrue(decoder.queued.await(3,TimeUnit.SECONDS))
            gate = PollGate(f.sink)
            val receipt = latestReceipt(f.sink)
            val recoveries = f.recovery.get()
            val delta = byteArrayOf(0,0,0,1,0x41,0)
            f.sink.onVideoFrame(110,delta)
            f.sink.onVideoConfig(110,GeneratedHevcFixture.avcc.copyOf())
            f.sink.onVideoFrame(110,delta)
            assertSame(receipt,latestReceipt(f.sink))
            assertEquals(2,(field(f.sink,"videoQueue").get(f.sink) as java.util.Queue<*>).size)
            decoder.listener.get().onFrameRendered(decoder.codec,1,1)
            assertEquals(1L,f.telemetry.snapshot(1).renderedFrames)
            gate.resume.countDown()
            verify(decoder.codec,timeout(3000).times(3)).queueInputBuffer(anyInt(),anyInt(),anyInt(),anyLong(),anyInt())
            val settled = PollGate(f.sink)
            try {
                assertTrue(f.created.isEmpty()); assertEquals(recoveries,f.recovery.get())
                verify(decoder.codec,never()).release()
            } finally { settled.resume.countDown() }
        } finally { gate?.resume?.countDown(); f.close() }
    }
    @Test fun duplicateRecoveryFeedbackConfigIdrRendersWithoutReconfigureLoop() {
        val f = Fixture(); var gate: PollGate? = null
        try {
            gate = PollGate(f.sink)
            f.sink.setVideoRecoveryHandler(110) {
                f.recovery.incrementAndGet()
                f.sink.onVideoConfig(110,GeneratedHevcFixture.avcc.copyOf())
                f.sink.onVideoFrame(110,idr)
            }
            f.config(); gate.resume.countDown()
            val decoder = f.decoder()
            assertTrue("recovery's config + IDR reaches worker",decoder.queued.await(3,TimeUnit.SECONDS))
            val settled = PollGate(f.sink)
            try {
                assertTrue("duplicate feedback must not recreate",f.created.isEmpty())
                assertEquals(1,f.recovery.get()); verify(decoder.codec,times(1)).start()
                decoder.listener.get().onFrameRendered(decoder.codec,1,1)
                assertEquals(1L,f.telemetry.snapshot(1).renderedFrames)
            } finally { settled.resume.countDown() }
        } finally { gate?.resume?.countDown(); f.close() }
    }
    @Test fun changedConfigAbaMintsFreshReceiptsClearsOldFramesAndFencesCallback() {
        val f = Fixture(); var gate: PollGate? = null
        try {
            f.config(); val old = f.decoder(); val a = latestReceipt(f.sink)
            gate = PollGate(f.sink)
            f.sink.onVideoFrame(110,idr)
            f.sink.onVideoConfig(110,changedConfig()); val b = latestReceipt(f.sink)
            assertNotSame(a,b)
            assertEquals(0,(field(f.sink,"videoQueue").get(f.sink) as java.util.Queue<*>).size)
            f.config(); val freshA = latestReceipt(f.sink)
            assertNotSame(a,freshA); assertNotSame(b,freshA)
            f.sink.onVideoFrame(110,idr)
            gate.resume.countDown(); val fresh = f.decoder()
            assertTrue(fresh.queued.await(3,TimeUnit.SECONDS))
            verify(old.codec,never()).queueInputBuffer(anyInt(),anyInt(),anyInt(),anyLong(),anyInt())
            old.listener.get().onFrameRendered(old.codec,1,1)
            assertNull(f.telemetry.snapshot(1).renderedFrames)
            fresh.listener.get().onFrameRendered(fresh.codec,2,2)
            assertEquals(1L,f.telemetry.snapshot(2).renderedFrames)
        } finally { gate?.resume?.countDown(); f.close() }
    }
    @Test fun duplicateWhileDetachedRetainsConfigButReattachHasFreshOwner() {
        val f = Fixture()
        try {
            f.config(); val old = f.decoder(); val config = latestReceipt(f.sink)
            f.sink.detachSurface(f.surface)
            assertTrue(old.released.await(3,TimeUnit.SECONDS))
            f.config(); assertSame(config,latestReceipt(f.sink))
            f.sink.attachSurface(f.surface); val fresh = f.decoder()
            assertNotSame(old.codec,fresh.codec)
            f.sink.onVideoFrame(110,idr); assertTrue(fresh.queued.await(3,TimeUnit.SECONDS))
            old.listener.get().onFrameRendered(old.codec,1,1)
            assertNull(f.telemetry.snapshot(1).renderedFrames)
            fresh.listener.get().onFrameRendered(fresh.codec,2,2)
            assertEquals(1L,f.telemetry.snapshot(2).renderedFrames)
        } finally { f.close() }
    }
    @Test fun canonicalDuplicateDedupsButCodecAnnouncementMismatchStillRejects() {
        val f = Fixture(); var gate: PollGate? = null
        try {
            gate = PollGate(f.sink)
            f.config(); val receipt = latestReceipt(f.sink)
            val data = GeneratedHevcFixture.avcc
            val box = ByteBuffer.allocate(data.size+8).putInt(data.size+8).put("avcC".toByteArray()).put(data).array()
            f.sink.onVideoConfig(110,box)
            assertSame(receipt,latestReceipt(f.sink))
            // Test the existing mismatch guard without widening the decoder qualification policy.
            f.sink.onVideoCodec(110,VideoCodec.H265)
            assertThrows(IllegalArgumentException::class.java) { f.sink.onVideoConfig(110,data) }
            assertSame(receipt,latestReceipt(f.sink))
        } finally { gate?.resume?.countDown(); f.close() }
    }
    @Test fun configDiagnosticReceiptsAreBoundedAndContainOnlyLocalIdentityAndCounters() {
        val f = Fixture(); var gate: PollGate? = null
        try {
            gate = PollGate(f.sink)
            f.config()
            repeat(100) { f.sink.onVideoConfig(110,GeneratedHevcFixture.avcc.copyOf()) }
            f.sink.onVideoConfig(110,changedConfig()); f.config()
            val lines = f.statuses.filter { it.startsWith("media stage=video-config ") }
            assertEquals(listOf(
                "media stage=video-config action=accepted receipt=1 count=1 codec=H264",
                "media stage=video-config action=duplicate receipt=1 count=1 codec=H264",
                "media stage=video-config action=duplicate receipt=1 count=2 codec=H264",
                "media stage=video-config action=duplicate receipt=1 count=3 codec=H264",
                "media stage=video-config action=duplicate receipt=1 count=4 codec=H264",
                "media stage=video-config action=duplicate receipt=1 count=32 codec=H264",
                "media stage=video-config action=duplicate receipt=1 count=64 codec=H264",
                "media stage=video-config action=duplicate receipt=1 count=96 codec=H264",
                "media stage=video-config action=accepted receipt=2 count=2 codec=H264",
                "media stage=video-config action=accepted receipt=3 count=3 codec=H264"),lines)
        } finally { gate?.resume?.countDown(); f.close() }
    }
    @Test fun hevcParkedPollInitialChangedAndDuplicateRecoveryIdrRender() {
        val f = Fixture(kind = VideoCodec.H265); var gate: PollGate? = null
        val hevcIdr = byteArrayOf(0,0,0,1,0x26,1,0)
        try {
            gate = PollGate(f.sink)
            val data = AtomicReference(GeneratedHevcFixture.hvcc)
            f.sink.setVideoRecoveryHandler(110) {
                f.recovery.incrementAndGet()
                f.sink.onVideoConfig(110,data.get().copyOf())
                f.sink.onVideoFrame(110,hevcIdr)
            }
            f.config(); f.sink.onVideoFrame(110,hevcIdr); gate.resume.countDown()
            val old = f.decoder(); assertTrue(old.queued.await(3,TimeUnit.SECONDS))
            old.listener.get().onFrameRendered(old.codec,1,1)
            assertEquals(1L,f.telemetry.snapshot(1).renderedFrames)
            gate = PollGate(f.sink)
            assertTrue("duplicate HEVC feedback creates no successor",f.created.isEmpty())
            data.set(data.get().copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            f.sink.onVideoConfig(110,data.get()); f.sink.onVideoFrame(110,hevcIdr)
            gate.resume.countDown(); val fresh = f.decoder()
            assertTrue("changed HEVC config's waking IDR is admitted",fresh.queued.await(3,TimeUnit.SECONDS))
            old.listener.get().onFrameRendered(old.codec,2,2)
            assertEquals(1L,f.telemetry.snapshot(2).renderedFrames)
            fresh.listener.get().onFrameRendered(fresh.codec,3,3)
            assertEquals(2L,f.telemetry.snapshot(3).renderedFrames)
            assertTrue(fresh.effectThreads.all { it == "lab-video" })
        } finally { gate?.resume?.countDown(); f.close() }
    }
    @Test fun reconfigureAndRecoveryReasonsDistinguishInputTimeoutFromOversize() {
        for (reason in listOf("input-timeout","input-size")) {
            val recovered = CountDownLatch(1)
            val f = Fixture(statusObserver = {
                if (it.startsWith("media stage=video-recovery reason=$reason ")) recovered.countDown()
            })
            var gate: PollGate? = null
            try {
                f.config(); val decoder = f.decoder(); gate = PollGate(f.sink)
                assertTrue(f.statuses.contains("media stage=video-reconfigure reason=config-change receipt=1 count=1"))
                assertTrue(f.statuses.contains("media stage=video-recovery reason=config-change receipt=1 count=1"))
                if (reason == "input-timeout") `when`(decoder.codec.dequeueInputBuffer(anyLong())).thenReturn(-1)
                f.sink.onVideoFrame(110,if (reason == "input-timeout") idr else idr + ByteArray(5000))
                gate.resume.countDown()
                assertTrue("bounded recovery reason receipt $reason",recovered.await(3,TimeUnit.SECONDS))
                assertTrue(decoder.released.await(3,TimeUnit.SECONDS))
            } finally { gate?.resume?.countDown(); f.close() }
        }
    }
    private fun latestReceipt(sink: AndroidMediaSink) =
        (field(sink,"latestVideoConfig").get(sink) as AtomicReference<*>).get()
    @Test fun detachReattachUsesFreshCodecAndStaleDetachCannotRemoveSuccessor() {
        val f = Fixture(); val successor = Surface(SurfaceTexture(1))
        try {
            f.config(); val old = f.decoder()
            receipt(f.sink,"detachSurface",f.surface)
            assertTrue("detached codec released on worker", old.released.await(3,TimeUnit.SECONDS))
            receipt(f.sink,"attachSurface",successor)
            receipt(f.sink,"detachSurface",f.surface)
            val fresh = f.decoder()
            assertSame(successor,fresh.configuredSurface.get())
            assertNotSame(old.codec,fresh.codec)
            old.listener.get().onFrameRendered(old.codec,1,1)
            assertNull("stale rendered callback fenced",f.telemetry.snapshot(System.nanoTime()).renderedFrames)
            f.sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x65,0))
            assertTrue("new IDR reaches production worker",fresh.queued.await(3,TimeUnit.SECONDS))
            fresh.listener.get().onFrameRendered(fresh.codec,2,2)
            assertEquals(1L,f.telemetry.snapshot(System.nanoTime()).renderedFrames)
        } finally { f.close(); successor.release() }
    }
    @Test fun staleInvalidSurfaceObservationCannotDetachSameSurfaceSuccessor() {
        // Spy only the platform validity boundary; keep a real Robolectric Surface underneath.
        val surface = spy(Surface(SurfaceTexture(0)))
        val armed = java.util.concurrent.atomic.AtomicBoolean()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val reconciled = CountDownLatch(1)
        val recovery = LinkedBlockingQueue<Unit>()
        doAnswer {
            val sinkCaller = Thread.currentThread().stackTrace.firstOrNull {
                it.className == AndroidMediaSink::class.java.name
            }?.methodName
            // Other validity checks (visible/configure/drain) must not consume this pause.
            if (Thread.currentThread().name == "lab-video" && sinkCaller == "runVideo" &&
                armed.compareAndSet(true, false)) {
                val invalidObservation = false
                entered.countDown()
                ignoreInterrupt(resume)
                invalidObservation
            } else it.callRealMethod()
        }.`when`(surface).isValid
        val f = Fixture(surface, statusObserver = {
            if (it == "media stage=surface-lost" || it == "media stage=presentation-attached generation=2")
                reconciled.countDown()
        })
        try {
            f.sink.setVideoRecoveryHandler(110) { recovery.offer(Unit) }
            f.config(); val old = f.decoder()
            assertNotNull("initial production recovery", recovery.poll(3, TimeUnit.SECONDS))
            val presentation = field(f.sink, "presentation").get(f.sink) as VideoPresentation
            val captured = presentation.snapshot()
            armed.set(true)
            assertTrue("old receipt's invalidity observation is paused", entered.await(3, TimeUnit.SECONDS))
            assertSame(captured, presentation.snapshot())
            f.sink.detachSurface(surface)
            f.sink.attachSurface(surface)
            val freshReceipt = presentation.snapshot()
            assertNotSame(captured, freshReceipt)
            assertSame(surface, freshReceipt.surface)
            assertEquals(2L, freshReceipt.generation)
            assertTrue("same Java Surface is valid again", surface.isValid)
            resume.countDown()
            assertTrue("worker resumes invalidation/reconciliation", reconciled.await(3, TimeUnit.SECONDS))
            assertSame("stale invalid observation must not clear the fresh same-Surface receipt",
                freshReceipt, presentation.snapshot())
            val fresh = f.decoder()
            assertTrue(old.released.await(3, TimeUnit.SECONDS))
            assertSame(surface, fresh.configuredSurface.get())
            assertNotSame(old.codec, fresh.codec)
            verify(fresh.codec).start()
            assertNotNull("fresh receipt executes forced production recovery", recovery.poll(3, TimeUnit.SECONDS))
            f.sink.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x41, 0))
            f.sink.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x65, 0))
            assertTrue("fresh IDR reaches codec", fresh.queued.await(3, TimeUnit.SECONDS))
            verify(fresh.codec, times(1)).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            old.listener.get().onFrameRendered(old.codec, 1, 1)
            assertNull("old same-Surface callback is fenced", f.telemetry.snapshot(System.nanoTime()).renderedFrames)
            fresh.listener.get().onFrameRendered(fresh.codec, 2, 2)
            assertEquals(1L, f.telemetry.snapshot(System.nanoTime()).renderedFrames)
            assertEquals(1, f.statuses.count { it.startsWith("media stage=presentation-rendered generation=2 ") })
            assertSame(freshReceipt, presentation.snapshot())
            assertFalse(f.sink.isVideoRetired)
            assertTrue(fresh.effectThreads.all { it == "lab-video" })
        } finally { resume.countDown(); f.close() }
    }
    @Test fun renderedPredecessorDoesNotCertifyReattachedPresentation() {
        val f = Fixture()
        try {
            f.config(); val old = f.decoder()
            assertFalse("configure is not rendering", f.telemetry.snapshot(0).presentationRendered)
            old.listener.get().onFrameRendered(old.codec, 1, 1)
            assertTrue(f.telemetry.snapshot(1).presentationRendered)
            f.sink.detachSurface(f.surface)
            assertFalse("detach clears readiness synchronously", f.telemetry.snapshot(1).presentationRendered)
            f.sink.attachSurface(f.surface)
            val fresh = f.decoder()
            assertEquals(1L, f.telemetry.snapshot(1).renderedFrames)
            assertFalse(f.telemetry.snapshot(1).presentationRendered)
            old.listener.get().onFrameRendered(old.codec, 2, 2)
            assertFalse("old callback cannot restore readiness", f.telemetry.snapshot(2).presentationRendered)
            fresh.listener.get().onFrameRendered(fresh.codec, 3, 3)
            assertTrue("actual current callback certifies readiness", f.telemetry.snapshot(3).presentationRendered)
        } finally { f.close() }
    }
    @Test fun lostSurfaceDoesNotRetireVideoOwner() {
        val surface = Surface(SurfaceTexture(0))
        val lost = CountDownLatch(1)
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication(), surface,
            display = CarPlayDisplaySelection(CarPlayDisplayPreferences(), "test.avc", false),
            videoSelection = CarPlayVideoSelection(1280,720,30,"test.avc"),
            onStatus = { if (it == "media stage=surface-lost") lost.countDown() })
        try {
            surface.release()
            assertTrue("worker observes lost presentation", lost.await(3, TimeUnit.SECONDS))
            val thread = AndroidMediaSink::class.java.getDeclaredField("videoThread").apply { isAccessible = true }.get(sink) as Thread
            thread.join(100)
            assertTrue("presentation loss must retain the production session video worker", thread.isAlive)
            assertFalse(sink.isVideoRetired)
        } finally { sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }
    private fun ignoreInterrupt(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { }
    }
    @Test fun newerConfigDuringBlockedConfigureCannotStartObsoleteCandidate() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val f = Fixture { decoder -> if (count.incrementAndGet() == 1) {
            doAnswer {
                decoder.format.set(it.getArgument(0)); decoder.configuredSurface.set(it.getArgument(1))
                entered.countDown(); ignoreInterrupt(resume); null
            }.`when`(decoder.codec).configure(any(MediaFormat::class.java),any(Surface::class.java),isNull(),eq(0))
        } }
        try {
            f.config()
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val obsolete = checkNotNull(f.created.poll(3,TimeUnit.SECONDS))
            f.sink.onVideoConfig(110,GeneratedHevcFixture.avcc.copyOf().apply { this[41] = (this[41].toInt() xor 1).toByte() })
            resume.countDown()
            assertTrue(obsolete.released.await(3,TimeUnit.SECONDS))
            val fresh = checkNotNull(f.created.poll(3,TimeUnit.SECONDS))
            verify(fresh.codec,timeout(3000)).start()
            verify(obsolete.codec,never()).start()
        } finally { resume.countDown(); f.close() }
    }
    @Test fun blockedConfigureCoalescesUiReceiptsToOneAppliedSuccessor() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val f = Fixture { decoder -> if (count.incrementAndGet() == 1) {
            doAnswer { entered.countDown(); ignoreInterrupt(resume); null }.`when`(decoder.codec)
                .configure(any(MediaFormat::class.java),any(Surface::class.java),isNull(),eq(0))
        } }
        val successor = Surface(SurfaceTexture(1))
        val ui = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            f.config(); assertTrue(entered.await(3,TimeUnit.SECONDS))
            val obsolete = checkNotNull(f.created.poll(3,TimeUnit.SECONDS))
            ui.submit {
                repeat(10000) { f.sink.detachSurface(f.surface); f.sink.attachSurface(successor); f.sink.detachSurface(f.surface) }
            }.get(1,TimeUnit.SECONDS)
            assertFalse("configure stall is not retirement",f.sink.isVideoRetired)
            assertFalse(f.sink.awaitRetired(0))
            resume.countDown()
            assertTrue(obsolete.released.await(3,TimeUnit.SECONDS))
            val fresh = f.decoder()
            assertSame(successor,fresh.configuredSurface.get())
            verify(obsolete.codec,never()).start()
            assertEquals("coalesced lifecycle receipt, not one status per UI call",1,
                f.statuses.count { it.startsWith("media stage=presentation-attached generation=") })
            assertTrue(fresh.effectThreads.all { it == "lab-video" })
        } finally { resume.countDown(); ui.shutdownNow(); f.close(); successor.release() }
    }
    @Test fun detachWhileOutputBoundaryIsBlockedDoesNotRenderRetiredBuffer() {
        val f = Fixture(); val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        try {
            f.config(); val old = f.decoder()
            doAnswer { entered.countDown(); ignoreInterrupt(resume); 0 }.`when`(old.codec)
                .dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java),anyLong())
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            f.sink.detachSurface(f.surface)
            resume.countDown()
            assertTrue(old.released.await(3,TimeUnit.SECONDS))
            verify(old.codec).releaseOutputBuffer(0,false)
            verify(old.codec,never()).releaseOutputBuffer(0,true)
            assertFalse(f.sink.isVideoRetired)
        } finally { resume.countDown(); f.close() }
    }
    @Test fun cancellationDuringBlockedConfigureFencesLateAttachUntilExactWorkerRetires() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val f = Fixture { decoder ->
            doAnswer { entered.countDown(); ignoreInterrupt(resume); null }.`when`(decoder.codec)
                .configure(any(MediaFormat::class.java),any(Surface::class.java),isNull(),eq(0))
        }
        val successor = Surface(SurfaceTexture(1))
        val ui = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            f.config(); assertTrue(entered.await(3,TimeUnit.SECONDS))
            val obsolete = checkNotNull(f.created.poll(3,TimeUnit.SECONDS))
            ui.submit { f.sink.revokePendingWork(); f.sink.attachSurface(successor); f.sink.detachSurface(f.surface) }.get(1,TimeUnit.SECONDS)
            assertFalse("cancellation is not stalled platform-owner retirement",f.sink.isVideoRetired)
            assertFalse(f.sink.awaitRetired(50))
            verify(obsolete.codec,never()).release()
            resume.countDown()
            assertTrue(obsolete.released.await(3,TimeUnit.SECONDS))
            f.sink.close(); assertTrue(f.sink.awaitRetired(3000))
            verify(obsolete.codec,never()).start()
            verify(obsolete.codec,times(1)).release()
            assertTrue(f.created.isEmpty())
            assertTrue(obsolete.effectThreads.all { it == "lab-video" })
            assertEquals("one concrete video-owner retirement receipt",1,f.statuses.count { it == "media stage=video-retired" })
        } finally { resume.countDown(); ui.shutdownNow(); f.close(); successor.release() }
    }
    @Test fun offscreenFloodCannotDiscardLatestCodecConfig() {
        val f = Fixture(); val successor = Surface(SurfaceTexture(1))
        val releasing = CountDownLatch(1); val resume = CountDownLatch(1)
        try {
            f.config(); val old = f.decoder()
            doAnswer { releasing.countDown(); ignoreInterrupt(resume); old.released.countDown(); null }.`when`(old.codec).release()
            f.sink.detachSurface(f.surface)
            assertTrue(releasing.await(3,TimeUnit.SECONDS))
            val latest = GeneratedHevcFixture.avcc.copyOf().apply { this[41] = (this[41].toInt() xor 1).toByte() }
            val expected = CarPlayVideoQualification.mediaFormat(VideoCodec.H264,latest,CarPlayVideoSelection(1280,720,30,"test.avc"))
                .getByteBuffer("csd-1")!!
            f.sink.onVideoConfig(110,latest)
            repeat(1000) { f.sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x41,0)) }
            f.sink.attachSurface(successor)
            resume.countDown()
            val fresh = f.decoder()
            assertEquals("latest configuration survives offscreen frame flood and queue reset",expected,fresh.format.get().getByteBuffer("csd-1"))
        } finally { resume.countDown(); f.close(); successor.release() }
    }
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun readCommand(peer: java.net.Socket, cipher: com.shilapi.xcertplay.airplay.ControlCipher): Map<*, *> {
        val input = peer.getInputStream()
        val header = input.readNBytes(2)
        assertEquals(2,header.size)
        val size = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
        val plaintext = cipher.decrypt(header + input.readNBytes(size + 16)).data
        val split = plaintext.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        assertTrue(split >= 0)
        assertTrue(plaintext.toString(Charsets.ISO_8859_1).startsWith("POST /command RTSP/1.0"))
        return com.shilapi.xcertplay.airplay.BplistCodec.decode(plaintext.copyOfRange(split + 4,plaintext.size)) as Map<*, *>
    }
    @Test fun realEngineRequestsForceKeyFrameOnlyOnscreenAndFreshIdrRendersAfterReattach() {
        val f = Fixture(); val successor = Surface(SurfaceTexture(1))
        val engine = com.shilapi.xcertplay.airplay.CarPlayMediaEngine(f.sink)
        java.net.ServerSocket(0).use { listener ->
            java.net.Socket("127.0.0.1",listener.localPort).use { peer ->
                val event = listener.accept()
                val session = com.shilapi.xcertplay.airplay.AirPlaySession(event,
                    com.shilapi.xcertplay.airplay.AirPlayConfig("presentation","synthetic","synthetic","1",com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(1280,720)),
                    com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),com.shilapi.xcertplay.airplay.PairingStore(),null,
                    object : com.shilapi.xcertplay.airplay.AirPlaySessionListener {},engine)
                val pairVerify = checkNotNull(field(session,"pairVerify").get(session))
                field(pairVerify,"sharedSecret").set(pairVerify,ByteArray(32))
                (field(session,"authenticated").get(session) as java.util.concurrent.atomic.AtomicBoolean).set(true)
                field(session,"eventSocket").set(session,event)
                field(session,"eventCipher").set(session,com.shilapi.xcertplay.airplay.ControlCipher(ByteArray(32),ByteArray(32)))
                val cipher = com.shilapi.xcertplay.airplay.ControlCipher(ByteArray(32),ByteArray(32))
                try {
                    assertNotNull(engine.onScreen(session,110,mapOf("streamConnectionID" to 1L)))
                    f.config(); val old = f.decoder()
                    peer.soTimeout = 3000
                    assertEquals("forceKeyFrame",readCommand(peer,cipher)["type"])
                    f.sink.detachSurface(f.surface)
                    assertTrue(old.released.await(3,TimeUnit.SECONDS))
                    f.config()
                    repeat(1000) { f.sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x41,0)) }
                    peer.soTimeout = 1200
                    assertThrows(java.net.SocketTimeoutException::class.java) { peer.getInputStream().read() }
                    assertFalse(session.isClosed)
                    f.sink.attachSurface(successor)
                    val fresh = f.decoder()
                    peer.soTimeout = 3000
                    assertEquals("forceKeyFrame",readCommand(peer,cipher)["type"])
                    f.sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x41,0))
                    f.sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x65,0))
                    assertTrue(fresh.queued.await(3,TimeUnit.SECONDS))
                    verify(fresh.codec,times(1)).queueInputBuffer(anyInt(),anyInt(),anyInt(),anyLong(),anyInt())
                    old.listener.get().onFrameRendered(old.codec,1,1)
                    assertNull(f.telemetry.snapshot(System.nanoTime()).renderedFrames)
                    fresh.listener.get().onFrameRendered(fresh.codec,2,2)
                    fresh.listener.get().onFrameRendered(fresh.codec,3,3)
                    assertEquals(2L,f.telemetry.snapshot(System.nanoTime()).renderedFrames)
                    assertEquals("finite actual render receipt for successor presentation",1,
                        f.statuses.count { it.startsWith("media stage=presentation-rendered generation=2 ") })
                    // Immediate second reattach must bypass the ordinary one-second recovery throttle.
                    f.sink.detachSurface(successor)
                    assertTrue(fresh.released.await(3,TimeUnit.SECONDS))
                    f.sink.attachSurface(f.surface)
                    val third = f.decoder()
                    assertSame(f.surface,third.configuredSurface.get())
                    assertEquals("forceKeyFrame",readCommand(peer,cipher)["type"])
                    assertFalse(session.isClosed)
                } finally { engine.onTeardown(session,110); session.close(); f.close(); successor.release() }
            }
        }
    }
    @Test fun surfaceDestroyedAfterCallerQualificationCanBeReconciledAfterConstruction() {
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(Decoder().codec.codecInfo)
        val surface = Surface(SurfaceTexture(0))
        assertTrue("caller initial qualification",surface.isValid)
        surface.release()
        val construction = runCatching {
            ProjectionReceiver(RuntimeEnvironment.getApplication(),"02:00:00:00:00:01",surface,
                mock(com.shilapi.xcertplay.mfi.MfiAuthenticator::class.java),wired = true,onStatus = {})
        }
        assertTrue("destroy during construction handoff must not abort session owner: ${construction.exceptionOrNull()}",construction.isSuccess)
        val receiver = construction.getOrThrow()
        try {
            receiver.detachSurface(surface)
            receiver.setRuntimeIdentification(com.shilapi.xcertplay.transport.Iap2IdentificationConfig(
                name="presentation",modelIdentifier="synthetic",manufacturer="test",serialNumber="test",firmwareVersion="1",hardwareVersion="test",carPlayUsbInterfaceNumber=0))
            receiver.start("192.0.2.10")
            assertTrue(receiver.port > 0)
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(3000)) }
    }
    @Test fun receiverDetachBeforeStartKeepsBoundTransportForReattach() {
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(Decoder().codec.codecInfo)
        val surface = Surface(SurfaceTexture(0)); val successor = Surface(SurfaceTexture(1))
        val receiver = ProjectionReceiver(RuntimeEnvironment.getApplication(),"02:00:00:00:00:01",surface,
            mock(com.shilapi.xcertplay.mfi.MfiAuthenticator::class.java),wired = true,onStatus = {})
        try {
            receiver.setRuntimeIdentification(com.shilapi.xcertplay.transport.Iap2IdentificationConfig(
                name="presentation",modelIdentifier="synthetic",manufacturer="test",serialNumber="test",firmwareVersion="1",hardwareVersion="test",carPlayUsbInterfaceNumber=0))
            receipt(receiver,"detachSurface",surface)
            surface.release()
            val started = runCatching { receiver.start("192.0.2.10") }
            assertTrue("qualified initial surface destruction cannot veto transport startup: ${started.exceptionOrNull()}",started.isSuccess)
            val port = receiver.port
            assertTrue(port > 0)
            receipt(receiver,"attachSurface",successor)
            receipt(receiver,"detachSurface",surface)
            assertEquals("presentation receipts cannot close receiver transport",port,receiver.port)
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(3000)); surface.release(); successor.release() }
    }
}
