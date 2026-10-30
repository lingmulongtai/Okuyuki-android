package com.okuyuki.android

import org.junit.Assert.*
import org.junit.Test

class OsmoTracksTest {
    @Test fun `select both hevc lenses and ignore cover art`() {
        val left = OsmoTrack(0, 0, "video/hevc", 3840, 3840, true)
        val right = OsmoTrack(1, 0, "video/hevc", 3840, 3840, true)
        val cover = OsmoTrack(2, 0, "image/jpeg", 720, 720, true)
        assertEquals(listOf(left, right), osmoTracks(listOf(left, right, cover)))
        assertNull(osmoTracks(listOf(left, cover)))
        assertNull(osmoTracks(listOf(left, right.copy(supported = false))))
        assertNull(osmoTracks(listOf(left, right.copy(width = 1920, height = 1920))))
        assertNull(osmoTracks(listOf(left, right, right)))
    }
}
