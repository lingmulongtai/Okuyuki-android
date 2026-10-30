package com.okuyuki.android

import com.okuyuki.protocol.PanoramaVideoFormat
import com.okuyuki.protocol.OsmoMotionFrame
import com.okuyuki.protocol.OsmoMotionTrack
import com.okuyuki.protocol.OsmoMotionCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class OsmoMotionReaderTest {
    @Test fun `long high-timescale frame timestamps do not overflow`() {
        assertEquals(9_999_980_000L, OsmoMotionReader.timestampUs(499_999, 20_000_000, 1_000_000_000))
        assertEquals(9_999_980_000L, OsmoMotionReader.timestampUs(499_999, 1000, 50_000))
        assertThrows(ArithmeticException::class.java) { OsmoMotionReader.timestampUs(Long.MAX_VALUE, 2, 1) }
        val wideOffset = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(4_294_967_296L + 123).array()
        assertEquals(4_294_967_296L + 123, OsmoCalibrationReader.u64(wideOffset, 0, wideOffset.size))
    }
    private fun varint(value: Int): ByteArray {
        val out = ByteArrayOutputStream(); var v = value
        while (v >= 128) { out.write((v and 127) or 128); v = v ushr 7 }
        out.write(v); return out.toByteArray()
    }
    private fun message(id: Int, bytes: ByteArray) = varint(id * 8 + 2) + varint(bytes.size) + bytes
    private fun fixed(id: Int, value: Float) = varint(id * 8 + 5) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
    @Test fun `attitude accepts finite normalized quaternions and rejects damaged samples`() {
        fun sample(q: ByteArray) = message(3, message(2, message(9, q)))
        val attitude = OsmoMotionReader.parseAttitude(sample(fixed(1, 0.5f) + fixed(4, 0.5f)))!!
        assertEquals(0.70710677f, attitude[0], 0.0001f)
        assertEquals(0.70710677f, attitude[3], 0.0001f)
        assertNull(OsmoMotionReader.parseAttitude(sample(fixed(1, Float.NaN))))
        assertNull(OsmoMotionReader.parseAttitude(sample(fixed(1, 0f))))
        assertNull(OsmoMotionReader.parseAttitude(byteArrayOf(0)))
    }
    @Test fun `reject mismatched timelines edits and duplicate composition tables`() {
        fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload
        fun scalar(id: Int, value: Int) = varint(id * 8) + varint(value)
        val lens = fixed(1, 1000f) + fixed(2, 1000f) + fixed(3, 1920f) + fixed(4, 1920f) +
            fixed(10, 3840f) + fixed(11, 3840f) + fixed(24, 8f) + message(28, fixed(1, 1f))
        val video = scalar(1, 2048) + scalar(2, 1024)
        val stream = message(2, message(3, video) + message(6, message(1, lens) + message(2, lens)))
        val pose = message(3, message(2, message(9, fixed(4, 1f))))
        val first = stream + pose; val second = pose
        val ftyp = box("ftyp", byteArrayOf(0, 0, 0, 0))
        val firstOffset = ftyp.size + 8
        fun file(delta: Int = 1000, edit: Int = 2000, compositions: Int = 0): ByteArray {
            fun track(kind: String, interval: Int): ByteArray {
                val stsd = box("stsd", int(0) + int(1) + box(kind, byteArrayOf()))
                val stts = box("stts", int(0) + int(1) + int(2) + int(interval))
                val stsc = box("stsc", int(0) + int(1) + int(1) + int(1) + int(1))
                val stsz = box("stsz", int(0) + int(0) + int(2) + int(first.size) + int(second.size))
                val stco = box("stco", int(0) + int(2) + int(firstOffset) + int(firstOffset + first.size))
                val ctts = (0 until compositions).fold(byteArrayOf()) { acc, _ -> acc + box("ctts", int(0) + int(0)) }
                val mdhd = box("mdhd", int(0) + int(0) + int(0) + int(25000))
                val edts = box("edts", box("elst", int(0) + int(1) + int(edit) + int(0) + int(65536)))
                val stbl = box("stbl", stsd + stts + stsc + stsz + stco + ctts)
                return box("trak", edts + box("mdia", mdhd + box("minf", stbl)))
            }
            val mvhd = box("mvhd", int(0) + int(0) + int(0) + int(25000))
            return ftyp + box("mdat", first + second) + box("moov", mvhd + track("avc1", 1000) + track("djmd", delta))
        }
        val temp = File.createTempFile("osmo-motion", ".lrf")
        fun read(bytes: ByteArray): OsmoMotionTrack? {
            temp.writeBytes(bytes)
            return FileChannel.open(temp.toPath(), StandardOpenOption.READ).use { OsmoMotionReader.read(it, 0, it.size(), PanoramaVideoFormat.OSMO_LRF) }
        }
        try {
            assertEquals(listOf(0L, 40000L), read(file())!!.frames.map { it.presentationTimeUs })
            assertNull(read(file(delta = 999)))
            assertNull(read(file(edit = 1000)))
            assertNull(read(file(compositions = 2)))
            assertNull(read(file().copyOf(18)))
        } finally { temp.delete() }
    }
    @Test fun `LRF and OSV motion remain available beyond fifty thousand frames`() {
        val count = 50_001
        fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload
        fun scalar(id: Int, value: Int) = varint(id * 8) + varint(value)
        fun fixture(format: PanoramaVideoFormat): ByteArray {
            val lens = fixed(1, 1000f) + fixed(2, 1000f) + fixed(3, 1920f) + fixed(4, 1920f) +
                fixed(10, 3840f) + fixed(11, 3840f) + fixed(24, 8f) + message(28, fixed(1, 1f))
            val dimensions = if (format == PanoramaVideoFormat.OSMO_LRF) 2048 to 1024 else 3840 to 3840
            val video = scalar(1, dimensions.first) + scalar(2, dimensions.second)
            val stream = message(2, message(3, video) + message(6, message(1, lens) + message(2, lens)))
            // Reusing this valid sample offset also exercises metadata totals above the former 64 MiB limit.
            val pose = message(3, message(2, message(9, fixed(4, 1f)))) + message(31, ByteArray(1400))
            assertTrue(pose.size.toLong() * count > 64L * 1024 * 1024)
            val first = stream + pose
            val ftyp = box("ftyp", byteArrayOf(0, 0, 0, 0))
            val firstOffset = ftyp.size + 8
            fun track(kind: String): ByteArray {
                val sizes = ByteBuffer.allocate(12 + count * 4).order(ByteOrder.BIG_ENDIAN)
                sizes.putInt(0).putInt(0).putInt(count).putInt(first.size)
                repeat(count - 1) { sizes.putInt(pose.size) }
                val wide = format == PanoramaVideoFormat.OSMO_OSV
                val offsets = ByteBuffer.allocate(8 + count * (if (wide) 8 else 4)).order(ByteOrder.BIG_ENDIAN)
                offsets.putInt(0).putInt(count)
                fun putOffset(value: Int) { if (wide) offsets.putLong(value.toLong()) else offsets.putInt(value) }
                putOffset(firstOffset)
                repeat(count - 1) { putOffset(firstOffset + first.size) }
                val stsd = box("stsd", int(0) + int(1) + box(kind, byteArrayOf()))
                val stts = box("stts", int(0) + int(1) + int(count) + int(1000))
                val stsc = box("stsc", int(0) + int(1) + int(1) + int(1) + int(1))
                val stbl = box("stbl", stsd + stts + stsc + box("stsz", sizes.array()) + box(if (wide) "co64" else "stco", offsets.array()))
                val mdhd = box("mdhd", int(0) + int(0) + int(0) + int(25000))
                val edts = box("edts", box("elst", int(0) + int(1) + int(count * 1000) + int(0) + int(65536)))
                return box("trak", edts + box("mdia", mdhd + box("minf", stbl)))
            }
            val mvhd = box("mvhd", int(0) + int(0) + int(0) + int(25000))
            val kind = if (format == PanoramaVideoFormat.OSMO_LRF) "avc1" else "hvc1"
            val videoTracks = track(kind) + if (format == PanoramaVideoFormat.OSMO_OSV) track(kind) else byteArrayOf()
            return ftyp + box("mdat", first + pose) + box("moov", mvhd + videoTracks + track("djmd"))
        }
        val temp = File.createTempFile("long-osmo-motion", ".bin")
        try {
            for (format in listOf(PanoramaVideoFormat.OSMO_LRF, PanoramaVideoFormat.OSMO_OSV)) {
                temp.writeBytes(fixture(format))
                val track = FileChannel.open(temp.toPath(), StandardOpenOption.READ).use {
                    OsmoMotionReader.read(it, 0, it.size(), format)
                }
                assertNotNull("long $format motion", track)
                assertEquals(count, track!!.frames.size)
                assertEquals(50_000L * 40_000L, track.frames.last().presentationTimeUs)
                assertNotNull(com.okuyuki.protocol.OsmoStabilization(track).correctionAt(track.frames.last().presentationTimeUs))
                var checks = 0
                FileChannel.open(temp.toPath(), StandardOpenOption.READ).use {
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        OsmoMotionReader.read(it, 0, it.size(), format) {
                            if (++checks == 3) throw java.util.concurrent.CancellationException()
                        }
                    }
                }
            }
        } finally { temp.delete() }
    }
    private fun sample(variable: String, format: PanoramaVideoFormat, count: Int, intervalUs: Long) {
        val path = System.getenv(variable)
        assumeTrue(!path.isNullOrBlank() && File(path!!).isFile)
        FileChannel.open(File(path!!).toPath(), StandardOpenOption.READ).use { channel ->
            val track = OsmoMotionReader.read(channel, 0, channel.size(), format)
            assertNotNull("validated sample track is required", track)
            assertEquals(count, track!!.frames.size)
            assertEquals(track, OsmoMotionCodec.decode(OsmoMotionCodec.encode(track)))
            assertEquals(intervalUs, track.frameIntervalUs)
            assertEquals(0L, track.frames.first().presentationTimeUs)
            assertEquals((count - 1) * intervalUs, track.frames.last().presentationTimeUs)
            assertTrue(track.frames.all { frame -> frame.storedAttitude.size == 4 && frame.storedAttitude.all(Float::isFinite) })
        }
    }
    @Test fun `real LRF has aligned camera attitudes`() = sample("OKUYUKI_LRF_SAMPLE", PanoramaVideoFormat.OSMO_LRF, 1677, 40000)
    @Test fun `real OSV has aligned camera attitudes`() = sample("OKUYUKI_OSV_SAMPLE", PanoramaVideoFormat.OSMO_OSV, 3353, 20000)
}
