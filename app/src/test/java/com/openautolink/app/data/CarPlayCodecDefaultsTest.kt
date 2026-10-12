package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class CarPlayCodecDefaultsTest {
    @Test fun freshAndMissingCodecSettingsOfferQualifiedHevc() {
        for (settings in listOf(CarPlaySettings(), CarPlaySettings.decode(null),
            CarPlaySettings.decode("{}"), CarPlaySettings.decode("""{"resolution":"1440p"}"""))) {
            assertEquals("hevc", settings.videoCodec)
            assertTrue(settings.displayRequest().preferHevc)
        }
    }

    @Test fun freshAndMissingVideoSettingsTargetAutomatic1440p() {
        for (settings in listOf(CarPlaySettings(), CarPlaySettings.decode(null), CarPlaySettings.decode("{}"))) {
            assertEquals("1440p", settings.resolution)
            assertTrue(settings.autoResolution)
            assertTrue(settings.displayRequest().preferHevc)
        }
    }

    @Test fun explicitManualResolutionSurvivesRestoreAndRoundTrip() {
        val saved = CarPlaySettings.decode("""{"autoResolution":false,"resolution":"720p","videoCodec":"h264"}""")
        val restored = CarPlaySettings.decode(CarPlaySettings.encode(saved))
        assertFalse(restored.autoResolution)
        assertEquals("720p", restored.resolution)
        assertEquals("h264", restored.videoCodec)
    }

    @Test fun explicitH264CompatibilityChoiceSurvivesRestoreAndRoundTrip() {
        val restored = CarPlaySettings.decode("""{"videoCodec":"h264","resolution":"1440p"}""")
        assertEquals("h264", restored.videoCodec)
        assertFalse(restored.displayRequest().preferHevc)
        assertEquals("h264", CarPlaySettings.decode(CarPlaySettings.encode(restored)).videoCodec)
        assertEquals("1440p", restored.resolution)
    }

    @Test fun explicitAutomaticCodecSurvivesRestoreAndRoundTrip() {
        val restored = CarPlaySettings.decode("""{"videoCodec":"hevc","resolution":"1440p"}""")
        assertTrue(CarPlaySettings.decode(CarPlaySettings.encode(restored)).displayRequest().preferHevc)
    }

    @Test fun legacySavedManualResolutionDoesNotBecomeAutomatic() {
        val restored = CarPlaySettings.decode("""{"resolution":"1080p","videoCodec":"h264"}""")
        assertFalse(restored.autoResolution)
        assertEquals("1080p", restored.resolution)
        assertEquals("h264", restored.videoCodec)
    }

    @Test fun freshSettingsQualify1440pClassHevcAtMeasuredWindowAspect() {
        val input = com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(
            2560, 1440, 2560, 1440, 200.0, 200.0, 200)
        val display = CarPlaySettings().qualifiedDisplay(input,
            com.shilapi.xcertplay.media.VideoDecoderProbe { "qualified-${it.codec}" })
        assertEquals(2560, display.width)
        assertEquals(1440, display.height)
        assertTrue(display.qualifiedVideoSelection!!.hevcOffered)
    }

    @Test fun freshSettingsKeepAvcWhenHevcDoesNotQualify() {
        val input = com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(
            2560, 1440, 2560, 1440, 200.0, 200.0, 200)
        val display = CarPlaySettings().qualifiedDisplay(input,
            com.shilapi.xcertplay.media.VideoDecoderProbe {
                if (it.codec == com.shilapi.xcertplay.airplay.VideoCodec.H264) "avc" else null
            })
        assertEquals(2560, display.width)
        assertEquals(1440, display.height)
        assertFalse(display.qualifiedVideoSelection!!.hevcOffered)
    }

    @Test fun invalidStoredCodecRemainsConservativeH264() {
        assertEquals("h264", CarPlaySettings.decode("""{"videoCodec":"invalid"}""").videoCodec)
    }
}
