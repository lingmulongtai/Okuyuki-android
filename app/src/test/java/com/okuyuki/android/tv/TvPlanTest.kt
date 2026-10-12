package com.okuyuki.android.tv

import com.okuyuki.protocol.Capabilities
import com.okuyuki.protocol.Screen
import org.junit.Assert.*
import org.junit.Test

class TvPlanTest {
    /** What an LG C5 reports. */
    private val c5 = Capabilities(Screen(1920, 1080, 3840, 2160),
        listOf("hevc-main10", "hevc-main", "h264-high", "av1-main10", "vp9-profile2", "vp9"), listOf("hdr10", "hlg"))
    // MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 and HEVCProfileMain.
    private val phone4k = TvPlan.Track(TvPlan.HEVC, 2, 3840, 2160, 50_000_000)

    @Test fun `stills match the video plane and never exceed 4K`() {
        assertEquals(TvPlan.Plane(3840, 2160), TvPlan.plane(c5))
        assertEquals(TvPlan.Plane(3840, 2160), TvPlan.plane(null))
        assertEquals(TvPlan.Plane(1920, 1080), TvPlan.plane(c5.copy(screen = Screen(1920, 1080, 1920, 1080))))
        assertEquals(TvPlan.Plane(3840, 2160), TvPlan.plane(c5.copy(screen = Screen(1920, 1080, 7680, 4320))))
    }

    @Test fun `the phone encodes what the TV decodes and makes HDR stills only for HLG TVs`() {
        assertEquals(TvPlan.HEVC, TvPlan.encoder(c5))
        assertEquals(TvPlan.HEVC, TvPlan.encoder(null))
        assertEquals(TvPlan.AVC, TvPlan.encoder(c5.copy(video = listOf("h264-high"))))
        assertNull(TvPlan.encoder(c5.copy(video = listOf("vp9"))))
        assertTrue(TvPlan.hdrStills(c5))
        assertFalse(TvPlan.hdrStills(c5.copy(hdr = listOf("hdr10"))))
        assertFalse(TvPlan.hdrStills(c5.copy(video = listOf("hevc-main", "h264-high"))))
        assertFalse(TvPlan.hdrStills(null))
        assertEquals("hvc1.2.4.L153.B0", TvPlan.codecs(TvPlan.HEVC, tenBit = true))
        assertEquals("hvc1.1.6.L153.B0", TvPlan.codecs(TvPlan.HEVC, tenBit = false))
        assertEquals("avc1.640033", TvPlan.codecs(TvPlan.AVC, tenBit = false))
        assertNull(TvPlan.codecs("video/av01", tenBit = true))
    }

    @Test fun `videos the TV plays are sent as they are`() {
        assertTrue(TvPlan.playsOriginal("video/mp4", phone4k, c5))
        assertTrue(TvPlan.playsOriginal("video/mp4", phone4k.copy(width = 2160, height = 3840), c5))
        assertTrue(TvPlan.playsOriginal("video/mp4", phone4k.copy(profile = 1), c5.copy(video = listOf("hevc-main"))))
        assertTrue(TvPlan.playsOriginal("video/mp4", phone4k.copy(bitrate = null), c5))
        assertFalse(TvPlan.playsOriginal("video/quicktime", phone4k, c5))
        assertFalse(TvPlan.playsOriginal("video/mp4", phone4k.copy(bitrate = 120_000_000), c5))
        assertFalse(TvPlan.playsOriginal("video/mp4", phone4k.copy(width = 7680, height = 4320), c5))
        assertFalse(TvPlan.playsOriginal("video/mp4", phone4k, c5.copy(video = listOf("hevc-main", "h264-high"))))
        assertFalse(TvPlan.playsOriginal("video/mp4", phone4k.copy(sampleMime = "video/dolby-vision"), c5))
    }

    @Test fun `track codecs get the TV's names`() {
        assertEquals("hevc-main", TvPlan.tvCodec(TvPlan.HEVC, 1))
        assertEquals("hevc-main10", TvPlan.tvCodec(TvPlan.HEVC, 4096))
        assertEquals("hevc-main10", TvPlan.tvCodec(TvPlan.HEVC, null))
        assertEquals("h264-high", TvPlan.tvCodec(TvPlan.AVC, 8))
        assertEquals("vp9-profile2", TvPlan.tvCodec("video/x-vnd.on2.vp9", 4))
        assertEquals("vp9", TvPlan.tvCodec("video/x-vnd.on2.vp9", 1))
        assertEquals("av1-main10", TvPlan.tvCodec("video/av01", 2))
        assertNull(TvPlan.tvCodec("video/mp4v-es", null))
    }

    @Test fun `other videos are converted to fit the TV and keep the HDR it shows`() {
        val eightK = phone4k.copy(width = 7680, height = 4320, bitrate = 100_000_000)
        assertEquals(TvPlan.Conversion(TvPlan.HEVC, 2160, true), TvPlan.conversion(eightK, "hdr10", c5))
        assertEquals(TvPlan.Conversion(TvPlan.HEVC, 2160, true), TvPlan.conversion(eightK, "hlg", c5))
        assertEquals(TvPlan.Conversion(TvPlan.HEVC, null, false), TvPlan.conversion(phone4k, "hdr10", c5.copy(hdr = listOf("hlg"))))
        assertEquals(TvPlan.Conversion(TvPlan.HEVC, null, false), TvPlan.conversion(phone4k, "hlg", c5.copy(video = listOf("hevc-main"))))
        val hdTv = c5.copy(screen = Screen(1920, 1080, 1920, 1080), video = listOf("h264-high"))
        assertEquals(TvPlan.Conversion(TvPlan.AVC, 1080, false), TvPlan.conversion(eightK, "hlg", hdTv))
        assertNull(TvPlan.conversion(eightK, "none", c5.copy(video = listOf("vp9"))))
    }

    @Test fun `photos are decoded with enough pixels for the plane in either orientation`() {
        assertEquals(1, TvPlan.sampleSize(4000, 3000, 3840, 2160))
        assertEquals(2, TvPlan.sampleSize(8160, 6120, 3840, 2160))
        assertEquals(2, TvPlan.sampleSize(6120, 8160, 3840, 2160))
        assertEquals(1, TvPlan.sampleSize(0, 0, 3840, 2160))
        assertEquals(TvPlan.Plane(2880, 2160), TvPlan.fit(4000, 3000, 3840, 2160))
        assertEquals(TvPlan.Plane(1620, 2160), TvPlan.fit(3000, 4000, 3840, 2160))
        assertEquals(TvPlan.Plane(1000, 800), TvPlan.fit(1000, 800, 3840, 2160))
        assertTrue(TvPlan.fits(2160, 3840, TvPlan.Plane(3840, 2160)))
        assertFalse(TvPlan.fits(4096, 2160, TvPlan.Plane(3840, 2160)))
    }
}
