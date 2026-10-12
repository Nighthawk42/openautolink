package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** The complete constructor input family, using real /info binary plist serialization. */
class WiredPcmMicrophoneTest {
    @Test fun everyMainConstructorInputHasTruthful24kMonoPcm() {
        val kinds = listOf("default", "telephony", "speechRecognition")
        val cap = MicrophoneCapability.of(kinds.associate { it.lowercase() to setOf(0x40L) })
        val cfg = AirPlayConfig("test", "id", "00:11:22:33:44:55", "950.7.1",
            AirPlayDisplayConfig(800,480), microphone = true)
        val info = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(cfg,
            AudioOutputSupport.implementedBits, cap))) as Map<*, *>
        val rows = (info["audioFormats"] as List<*>).map { it as Map<*, *> }
        for (kind in kinds) {
            val row = rows.single { (it["type"] as Number).toInt() == 100 && it["audioType"] == kind }
            assertEquals("missing implemented PCM input for $kind", 0x40L, (row["audioInputFormats"] as? Number)?.toLong())
            val mic = MicrophoneSupport.negotiate(cap,100,kind,0x40L,6000,480,
                InetAddress.getLoopbackAddress(),ByteArray(32))
            assertNotNull(mic)
            assertEquals(24000,mic!!.sampleRate)
            assertEquals(1,mic.channels)
            assertEquals(960,mic.frameBytes)
            assertEquals(480,mic.rtpSamplesPerPacket)
            assertEquals(0,mic.bitrate) // PCM is not a claimed encoder
            assertTrue(MicrophoneSupport.admits(AudioOutputSupport.negotiate(0x40,100,kind)!!,100,6000,480))
        }
        val denied = AirPlayInfoPlist.build(cfg.copy(microphone=false),AudioOutputSupport.implementedBits,cap)
        assertTrue((denied["audioFormats"] as List<*>).none { (it as Map<*,*>).containsKey("audioInputFormats") })
        assertFalse(MicrophoneCapability.of(mapOf("default" to setOf(0x80L))).available) // stereo not implemented
    }
}
