package com.okuyuki.android

import com.okuyuki.protocol.PanoramaVideoFormat
import com.okuyuki.protocol.PanoramaLensCalibration
import com.okuyuki.protocol.PanoramaStereoCalibration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/** Validated Osmo 360 native-refine lens calibration in source sensor pixels. */
internal data class OsmoLens(val intrinsics: FloatArray, val distortion: FloatArray, val quaternion: FloatArray)
internal data class OsmoCalibration(val left: OsmoLens, val right: OsmoLens)

internal fun OsmoCalibration.toShareCalibration(): PanoramaStereoCalibration {
    fun OsmoLens.toShared() = PanoramaLensCalibration(intrinsics.toList(), distortion.toList(), quaternion.toList())
    return PanoramaStereoCalibration(left.toShared(), right.toShared()).also { require(it.isValid()) }
}

/** Bounded MP4/djmd reader. Returns null when the mode or metadata is not understood. */
internal object OsmoCalibrationReader {
    private const val MAX_MOOV = 64 * 1024 * 1024
    private const val MAX_SAMPLE = 1024 * 1024
    internal data class Box(val type: String, val content: Int, val end: Int)

    fun read(channel: FileChannel, start: Long, length: Long, format: PanoramaVideoFormat = PanoramaVideoFormat.OSMO_LRF): OsmoCalibration? = runCatching {
        require(start >= 0 && length in 8..channel.size() && start <= channel.size() - length)
        fun readAt(offset: Long, size: Int): ByteArray {
            require(offset >= 0 && size >= 0 && offset <= length - size)
            val out = ByteBuffer.allocate(size)
            channel.position(Math.addExact(start, offset))
            while (out.hasRemaining()) require(channel.read(out) > 0)
            return out.array()
        }
        var offset = 0L
        var moov: ByteArray? = null
        while (offset <= length - 8) {
            val header = ByteBuffer.wrap(readAt(offset, 8)).order(ByteOrder.BIG_ENDIAN)
            val small = header.int.toLong() and 0xffffffffL
            val type = ByteArray(4).also { header.get(it) }.toString(Charsets.ISO_8859_1)
            val headerSize = if (small == 1L) 16 else 8
            val size = when (small) {
                0L -> length - offset
                1L -> ByteBuffer.wrap(readAt(offset + 8, 8)).order(ByteOrder.BIG_ENDIAN).long
                else -> small
            }
            require(size >= headerSize && size <= length - offset)
            if (type == "moov") {
                require(size - headerSize <= MAX_MOOV)
                moov = readAt(offset + headerSize, (size - headerSize).toInt())
                break
            }
            offset += size
        }
        val data = requireNotNull(moov)
        val root = boxes(data, 0, data.size)
        for (track in root.filter { it.type == "trak" }) {
            val media = boxes(data, track.content, track.end).firstOrNull { it.type == "mdia" } ?: continue
            val minf = boxes(data, media.content, media.end).firstOrNull { it.type == "minf" } ?: continue
            val stbl = boxes(data, minf.content, minf.end).firstOrNull { it.type == "stbl" } ?: continue
            val entries = boxes(data, stbl.content, stbl.end)
            val stsd = entries.firstOrNull { it.type == "stsd" } ?: continue
            if (stsd.end - stsd.content < 16) continue
            val descriptions = boxes(data, stsd.content + 8, stsd.end)
            if (descriptions.none { it.type == "djmd" }) continue
            val table = entries.firstOrNull { it.type == "stco" || it.type == "co64" } ?: continue
            val sizes = entries.firstOrNull { it.type == "stsz" } ?: continue
            val sampleOffset = if (table.type == "stco") u32(data, table.content + 8, table.end) else u64(data, table.content + 8, table.end)
            if (u32(data, table.content + 4, table.end) < 1) continue
            val defaultSize = u32(data, sizes.content + 4, sizes.end)
            if (u32(data, sizes.content + 8, sizes.end) < 1) continue
            val sampleSize = if (defaultSize == 0L) u32(data, sizes.content + 12, sizes.end) else defaultSize
            if (sampleSize !in 1..MAX_SAMPLE.toLong()) continue
            val candidate = runCatching { parseProduct(readAt(sampleOffset, sampleSize.toInt()), format) }.getOrNull()
            if (candidate != null) return candidate
        }
        null
    }.getOrNull()

    internal fun boxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val out = mutableListOf<Box>(); var at = start
        while (at < end) {
            require(end - at >= 8 && out.size < 100000)
            val small = u32(data, at, end)
            val type = String(data, at + 4, 4, Charsets.ISO_8859_1)
            val header = if (small == 1L) 16 else 8
            val size = when (small) { 0L -> (end - at).toLong(); 1L -> u64(data, at + 8, end); else -> small }
            require(size >= header && size <= end - at)
            out += Box(type, at + header, at + size.toInt()); at += size.toInt()
        }
        return out
    }
    internal fun u32(data: ByteArray, at: Int, end: Int): Long {
        require(at >= 0 && at <= end - 4)
        return ByteBuffer.wrap(data, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
    }
    internal fun u64(data: ByteArray, at: Int, end: Int): Long {
        require(at >= 0 && at <= end - 8)
        return ByteBuffer.wrap(data, at, 8).order(ByteOrder.BIG_ENDIAN).long.also { require(it >= 0) }
    }
    internal data class Field(val number: Int, val wire: Int, val bytes: ByteArray)
    internal fun fields(data: ByteArray): List<Field> {
        var pos = 0
        fun varint(): Long {
            var out = 0L; var shift = 0
            while (shift < 64) {
                require(pos < data.size)
                val b = data[pos++].toInt() and 255
                if (shift == 63) require(b <= 1)
                out = out or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return out
                shift += 7
            }
            error("invalid varint")
        }
        val out = mutableListOf<Field>()
        while (pos < data.size) {
            require(out.size < 100000)
            val tag = varint(); val number = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            require(number > 0)
            val bytes = when (wire) {
                0 -> { val before = pos; varint(); data.copyOfRange(before, pos) }
                1 -> { require(pos <= data.size - 8); data.copyOfRange(pos, pos + 8).also { pos += 8 } }
                2 -> { val size = varint(); require(size in 0..(data.size - pos).toLong()); data.copyOfRange(pos, pos + size.toInt()).also { pos += size.toInt() } }
                5 -> { require(pos <= data.size - 4); data.copyOfRange(pos, pos + 4).also { pos += 4 } }
                else -> error("unsupported wire type")
            }
            out += Field(number, wire, bytes)
        }
        return out
    }
    internal fun message(list: List<Field>, id: Int): List<Field>? = list.firstOrNull { it.number == id && it.wire == 2 }?.let { fields(it.bytes) }
    private fun scalar(list: List<Field>, id: Int): Long? {
        val bytes = list.firstOrNull { it.number == id && it.wire == 0 }?.bytes ?: return null
        var out = 0L; bytes.forEachIndexed { i, byte -> out = out or ((byte.toLong() and 127) shl (7 * i)) }; return out
    }
    internal fun float(list: List<Field>, id: Int, default: Float? = null): Float {
        val field = list.firstOrNull { it.number == id && it.wire == 5 } ?: return requireNotNull(default)
        return ByteBuffer.wrap(field.bytes).order(ByteOrder.LITTLE_ENDIAN).float.also { require(it.isFinite()) }
    }
    internal fun parseProduct(sample: ByteArray, format: PanoramaVideoFormat = PanoramaVideoFormat.OSMO_LRF): OsmoCalibration? = runCatching {
        val stream = requireNotNull(message(fields(sample), 2))
        val video = requireNotNull(message(stream, 3))
        val dimensions = when (format) {
            PanoramaVideoFormat.OSMO_LRF -> 2048L to 1024L
            PanoramaVideoFormat.OSMO_OSV -> 3840L to 3840L
            else -> error("unsupported projection")
        }
        require(scalar(video, 1) == dimensions.first && scalar(video, 2) == dimensions.second)
        val mode = message(stream, 7)?.let { scalar(it, 1) } ?: 0L
        require(mode == 0L)
        val lenses = requireNotNull(message(stream, 6))
        fun lens(index: Int): OsmoLens {
            val data = requireNotNull(message(lenses, index))
            val width = float(data, 10); val height = float(data, 11)
            require(width == 3840f && height == 3840f && float(data, 24) == 8f)
            val intrinsics = floatArrayOf(float(data, 1), float(data, 2), float(data, 3), float(data, 4))
            require(intrinsics[0] in 1f..width && intrinsics[1] in 1f..height && intrinsics[2] in 0f..width && intrinsics[3] in 0f..height)
            val distortion = floatArrayOf(float(data, 5, 0f), float(data, 6, 0f), float(data, 7, 0f), float(data, 8, 0f), float(data, 15, 0f))
            require(distortion.all { kotlin.math.abs(it) <= 10f })
            val q = requireNotNull(message(data, 28))
            val quaternion = FloatArray(4) { float(q, it + 1, 0f) }
            val norm = sqrt(quaternion.sumOf { (it * it).toDouble() }).toFloat()
            require(norm in 0.5f..1.5f)
            for (i in quaternion.indices) quaternion[i] /= norm
            return OsmoLens(intrinsics, distortion, quaternion)
        }
        OsmoCalibration(lens(1), lens(2))
    }.getOrNull()
}
