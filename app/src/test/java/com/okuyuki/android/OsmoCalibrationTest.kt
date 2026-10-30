package com.okuyuki.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class OsmoCalibrationTest {
    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream(); var v = value
        while (v >= 128) { out.write((v.toInt() and 127) or 128); v = v ushr 7 }
        out.write(v.toInt()); return out.toByteArray()
    }
    private fun scalar(id: Int, value: Long) = varint((id * 8).toLong()) + varint(value)
    private fun message(id: Int, bytes: ByteArray) = varint((id * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes
    private fun fixed(id: Int, value: Float) = varint((id * 8 + 5).toLong()) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
    private fun product(): ByteArray {
        val q = fixed(1, 1f)
        val lens = fixed(1, 1000f) + fixed(2, 1000f) + fixed(3, 1920f) + fixed(4, 1920f) + fixed(10, 3840f) + fixed(11, 3840f) + fixed(24, 8f) + message(28, q)
        val video = scalar(1, 2048) + scalar(2, 1024)
        return message(2, message(3, video) + message(6, message(1, lens) + message(2, lens)))
    }
    @Test fun `decode bounded calibration and reject corrupt or incomplete data`() {
        val bytes = product()
        val result = OsmoCalibrationReader.parseProduct(bytes)
        assertNotNull(result)
        assertEquals(1000f, result!!.left.intrinsics[0], 0.001f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), result.left.quaternion, 0.001f)
        assertNull(OsmoCalibrationReader.parseProduct(bytes.copyOf(bytes.size - 1)))
        assertNull(OsmoCalibrationReader.parseProduct(byteArrayOf(0)))
        assertNull(OsmoCalibrationReader.parseProduct(message(2, message(3, scalar(1, 4096) + scalar(2, 2048)))))
    }
    @Test fun `sample table finds calibration and rejects out of bounds offsets`() {
        fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload
        val sample = product()
        val ftyp = box("ftyp", byteArrayOf(0, 0, 0, 0))
        fun file(offset: Int): ByteArray {
            val stsd = box("stsd", int(0) + int(1) + box("djmd", byteArrayOf()))
            val stco = box("stco", int(0) + int(1) + int(offset))
            val stsz = box("stsz", int(0) + int(0) + int(1) + int(sample.size))
            val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd + stco + stsz)))))
            return ftyp + box("mdat", sample) + moov
        }
        val temporary = File.createTempFile("osmo-calibration", ".mp4")
        try {
            temporary.writeBytes(file(ftyp.size + 8))
            FileChannel.open(temporary.toPath(), StandardOpenOption.READ).use { input -> assertNotNull(OsmoCalibrationReader.read(input, 0, input.size())) }
            temporary.writeBytes(file(Int.MAX_VALUE))
            FileChannel.open(temporary.toPath(), StandardOpenOption.READ).use { input -> assertNull(OsmoCalibrationReader.read(input, 0, input.size())) }
        } finally { temporary.delete() }
    }
    @Test fun `read actual user LRF when explicitly provided`() {
        val path = System.getenv("OKUYUKI_LRF_SAMPLE")
        assumeTrue(!path.isNullOrBlank() && File(path!!).isFile)
        FileChannel.open(File(path!!).toPath(), StandardOpenOption.READ).use { input ->
            val result = OsmoCalibrationReader.read(input, 0, input.size())
            assertNotNull("the real LRF must contain native Osmo calibration", result)
            assertTrue(result!!.left.intrinsics[0] > 0)
            assertTrue(result.right.intrinsics[0] > 0)
        }
    }
}
