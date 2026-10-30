package com.okuyuki.android.tv

import com.okuyuki.protocol.Capabilities
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What to send a TV, decided from what it reported. Plain Kotlin, so it is unit tested without a device. */
object TvPlan {
    const val HEVC = "video/hevc"
    const val AVC = "video/avc"
    /** Stills and conversions stop at 4K; the TV scales up on a larger panel. */
    const val MAX_WIDTH = 3840
    const val MAX_HEIGHT = 2160
    /** Videos above this average bitrate are converted. LG lists 60 Mbps for HEVC 4K60 on the C5. */
    const val MAX_VIDEO_BITRATE = 60_000_000L
    /** Assumed when a TV has not reported what it plays: every LG webOS TV since 2022 decodes these. */
    private val DEFAULT_VIDEO = listOf("hevc-main10", "hevc-main", "h264-high")
    private val PLAYABLE_CONTAINERS = setOf("video/mp4", "video/webm")
    // MediaCodecInfo.CodecProfileLevel values, repeated so this file needs no Android classes.
    private const val HEVC_MAIN = 1
    private const val HEVC_MAIN_STILL = 4
    private const val VP9_PROFILE_2 = 4

    data class Plane(val width: Int, val height: Int)

    /** A video track as MediaExtractor describes it. [bitrate] is in bits per second. */
    data class Track(val sampleMime: String?, val profile: Int?, val width: Int, val height: Int, val bitrate: Long?)

    /** How to convert a video: [shortSide] scales it down when it is larger than the TV, [keepHdr] keeps HDR. */
    data class Conversion(val sampleMime: String, val shortSide: Int?, val keepHdr: Boolean)

    /** The TV's video plane, at most 4K. Stills are made at exactly this size, padded with black. */
    fun plane(capabilities: Capabilities?): Plane {
        val screen = capabilities?.screen
        val width = screen?.videoWidth?.takeIf { it > 0 } ?: MAX_WIDTH
        val height = screen?.videoHeight?.takeIf { it > 0 } ?: MAX_HEIGHT
        val scale = min(1.0, min(MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height))
        return Plane(even(width * scale), even(height * scale))
    }

    fun videoCodecs(capabilities: Capabilities?): List<String> = capabilities?.video?.takeIf { it.isNotEmpty() } ?: DEFAULT_VIDEO

    /** The encoder for stills and conversions: HEVC when the TV plays it, else H.264, else none. */
    fun encoder(capabilities: Capabilities?): String? {
        val video = videoCodecs(capabilities)
        return when {
            "hevc-main10" in video || "hevc-main" in video -> HEVC
            "h264-high" in video -> AVC
            else -> null
        }
    }

    /** Ultra HDR photos become HLG stills only for a TV that shows HLG and decodes 10-bit HEVC. */
    fun hdrStills(capabilities: Capabilities?): Boolean =
        encoder(capabilities) == HEVC && "hevc-main10" in videoCodecs(capabilities) && "hlg" in capabilities?.hdr.orEmpty()

    /** RFC 6381 codecs of what the phone encodes, so the TV can check it with `canPlayType`. */
    fun codecs(sampleMime: String?, tenBit: Boolean): String? = when (sampleMime) {
        HEVC -> if (tenBit) "hvc1.2.4.L153.B0" else "hvc1.1.6.L153.B0"
        AVC -> "avc1.640033"
        else -> null
    }

    /** The TV's name for a video codec, from the track's mime type and profile. Unknown HEVC profiles count as 10-bit. */
    fun tvCodec(sampleMime: String?, profile: Int?): String? = when (sampleMime) {
        HEVC -> if (profile == HEVC_MAIN || profile == HEVC_MAIN_STILL) "hevc-main" else "hevc-main10"
        AVC -> "h264-high"
        "video/av01" -> "av1-main10"
        "video/x-vnd.on2.vp9" -> if (profile != null && profile >= VP9_PROFILE_2) "vp9-profile2" else "vp9"
        "video/dolby-vision" -> "dolby-vision"
        else -> null
    }

    /** Whether [width] x [height], in either orientation, fits the plane. */
    fun fits(width: Int, height: Int, plane: Plane) =
        max(width, height) <= max(plane.width, plane.height) && min(width, height) <= min(plane.width, plane.height)

    /** Whether the TV plays the file as it is: a container and codec it reads, no larger than its plane, not too fast. */
    fun playsOriginal(containerMime: String, track: Track, capabilities: Capabilities?): Boolean {
        if (containerMime !in PLAYABLE_CONTAINERS) return false
        val codec = tvCodec(track.sampleMime, track.profile) ?: return false
        if (codec !in videoCodecs(capabilities)) return false
        if (!fits(track.width, track.height, plane(capabilities))) return false
        return (track.bitrate ?: 0) <= MAX_VIDEO_BITRATE
    }

    /** How to convert a video the TV cannot play as it is, or null when the TV plays nothing the phone can encode. */
    fun conversion(track: Track, hdr: String, capabilities: Capabilities?): Conversion? {
        val encoder = encoder(capabilities) ?: return null
        val plane = plane(capabilities)
        val shortSide = if (fits(track.width, track.height, plane)) null else min(plane.width, plane.height)
        val shows = capabilities?.hdr.orEmpty()
        val keepHdr = encoder == HEVC && "hevc-main10" in videoCodecs(capabilities) && when (hdr) {
            "hdr10", "hdr10plus" -> "hdr10" in shows
            "hlg" -> "hlg" in shows
            else -> false
        }
        return Conversion(encoder, shortSide, keepHdr)
    }

    /**
     * A decoder sample size that keeps at least the pixels needed to fill [maxWidth] x [maxHeight]. It does not depend
     * on whether the decoder reports the size before or after the photo's rotation.
     */
    fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val scale = max(min(maxWidth.toDouble() / width, maxHeight.toDouble() / height),
            min(maxWidth.toDouble() / height, maxHeight.toDouble() / width))
        return if (scale >= 1) 1 else floor(1 / scale).toInt().coerceAtLeast(1)
    }

    /** The size of [width] x [height] scaled down to fit [maxWidth] x [maxHeight], never up. */
    fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Plane {
        val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
        return Plane((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    private fun even(value: Double) = (value.toInt() / 2 * 2).coerceAtLeast(2)
}
