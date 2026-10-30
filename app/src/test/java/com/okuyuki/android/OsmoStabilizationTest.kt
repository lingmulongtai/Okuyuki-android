package com.okuyuki.android

import org.junit.Assert.*
import org.junit.Test

class OsmoStabilizationTest {
    @Test fun `timestamps require exact release match and remain bounded`() {
        val map = FrameTimestampMap(2)
        map.record(100, 10); map.record(200, 20); map.record(300, 30)
        assertNull(map.take(100)); assertNull(map.take(201))
        assertEquals(20L, map.take(200)); assertNull(map.take(200))
        map.clear(); assertNull(map.take(300))
        map.record(-1, 4); map.record(1, -1); assertNull(map.take(1))
        map.record(400, 40); map.record(400, 50); assertNull(map.take(400))
    }
    @Test fun `stored camera axes and rotations are converted consistently`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), OsmoStabilization.fromStored(listOf(0f, 0f, 0f, 1f)), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, -1f, 0f, 0f), OsmoStabilization.fromStored(listOf(0f, 1f, 0f, 0f)), 0.0001f)
    }
    @Test fun `smoothing is identity when stationary and handles quaternion sign changes`() {
        val frames = listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f)), OsmoMotionFrame(40000, listOf(0f, 0f, 0f, -1f)))
        val smooth = OsmoStabilization(OsmoMotionTrack(frames, 40000))
        for (time in listOf(0L, 40000L)) {
            val q = smooth.correctionAt(time)!!
            assertEquals(1f, kotlin.math.abs(q[0]), 0.0001f)
            assertEquals(0f, q[1], 0.0001f); assertEquals(0f, q[2], 0.0001f); assertEquals(0f, q[3], 0.0001f)
        }
        assertNull(smooth.correctionAt(-1)); assertNull(smooth.correctionAt(61000))
    }
    @Test fun `smoothing compensates a sudden rotation and maps seek positions`() {
        val frames = listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f)), OsmoMotionFrame(40000, listOf(0f, 0f, 0.70710677f, 0.70710677f)))
        val smooth = OsmoStabilization(OsmoMotionTrack(frames, 40000))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), smooth.correctionAt(0), 0.0001f)
        assertTrue(smooth.correctionAt(40000)!![2] < -0.5f)
        assertArrayEquals(smooth.correctionAt(40000), smooth.correctionAt(39000), 0.0001f)
    }
}
