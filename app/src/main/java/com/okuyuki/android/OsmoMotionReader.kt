package com.okuyuki.android

import com.okuyuki.protocol.PanoramaVideoFormat
import com.okuyuki.protocol.OsmoMotionFrame
import com.okuyuki.protocol.OsmoMotionTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/** Strictly accepts the simple, aligned sample tables verified in Osmo 360 files. */
internal object OsmoMotionReader {
    private const val MAX_MOOV = 64 * 1024 * 1024
    private const val MAX_SAMPLE = 1024 * 1024
    private const val MAX_SAMPLES = 50000
    private const val MAX_METADATA = 64L * 1024 * 1024
    private data class Track(val kind: String, val timescale: Long, val delta: Long,
        val count: Int, val editDuration: Long, val offsets: LongArray, val sizes: IntArray)
    private val reader = OsmoCalibrationReader

    fun read(channel: FileChannel, start: Long, length: Long, format: PanoramaVideoFormat): OsmoMotionTrack? = try {
        require(format == PanoramaVideoFormat.OSMO_LRF || format == PanoramaVideoFormat.OSMO_OSV)
        require(start >= 0 && length >= 8 && start <= channel.size() - length)
        fun readAt(offset: Long, size: Int): ByteArray {
            require(offset >= 0 && size >= 0 && offset <= length - size)
            val buffer = ByteBuffer.allocate(size)
            channel.position(Math.addExact(start, offset))
            while (buffer.hasRemaining()) require(channel.read(buffer) > 0)
            return buffer.array()
        }
        var position = 0L
        var moov: ByteArray? = null
        while (position <= length - 8) {
            val h = ByteBuffer.wrap(readAt(position, 8)).order(ByteOrder.BIG_ENDIAN)
            val small = h.int.toLong() and 0xffffffffL
            val type = ByteArray(4).also { h.get(it) }.toString(Charsets.ISO_8859_1)
            val header = if (small == 1L) 16 else 8
            val size = when (small) {
                0L -> length - position
                1L -> ByteBuffer.wrap(readAt(position + 8, 8)).order(ByteOrder.BIG_ENDIAN).long
                else -> small
            }
            require(size >= header && size <= length - position)
            if (type == "moov") {
                require(size - header <= MAX_MOOV)
                moov = readAt(position + header, (size - header).toInt()); break
            }
            position += size
        }
        val data = requireNotNull(moov)
        fun children(box: OsmoCalibrationReader.Box) = reader.boxes(data, box.content, box.end)
        fun one(boxes: List<OsmoCalibrationReader.Box>, type: String) = boxes.singleOrNull { it.type == type }
        fun number(box: OsmoCalibrationReader.Box, relative: Int) = reader.u32(data, box.content + relative, box.end)
        fun parseTrack(box: OsmoCalibrationReader.Box): Track? {
            val top = children(box)
            val media = one(top, "mdia") ?: return null
            val mediaChildren = children(media)
            val minf = one(mediaChildren, "minf") ?: return null
            val stbl = one(children(minf), "stbl") ?: return null
            val entries = children(stbl)
            val stsd = one(entries, "stsd") ?: return null
            if (stsd.end - stsd.content < 16) return null
            val formats = reader.boxes(data, stsd.content + 8, stsd.end).map { it.type }
            val videoType = if (format == PanoramaVideoFormat.OSMO_LRF) "avc1" else "hvc1"
            val kind = when {
                formats.size == 1 && formats[0] == "djmd" -> "djmd"
                formats.size == 1 && formats[0] == videoType -> "video"
                else -> return null
            }
            val mdhd = requireNotNull(one(mediaChildren, "mdhd"))
            require((data[mdhd.content].toInt() and 255) == 0)
            val timescale = number(mdhd, 12); require(timescale in 1..1000000000L)
            require(entries.none { it.type == "ctts" })
            val edts = requireNotNull(one(top, "edts")); val elst = requireNotNull(one(children(edts), "elst"))
            require(number(elst, 0) == 0L && number(elst, 4) == 1L && number(elst, 12) == 0L && number(elst, 16) == 65536L)
            val duration = number(elst, 8)
            val stts = requireNotNull(one(entries, "stts")); require(number(stts, 4) == 1L)
            val count = number(stts, 8); val delta = number(stts, 12)
            require(count in 1..MAX_SAMPLES.toLong() && delta in 1..timescale)
            val stsc = requireNotNull(one(entries, "stsc"))
            require(number(stsc, 4) == 1L && number(stsc, 8) == 1L && number(stsc, 12) == 1L && number(stsc, 16) == 1L)
            val stsz = requireNotNull(one(entries, "stsz")); require(number(stsz, 8) == count)
            val defaultSize = number(stsz, 4)
            val chunks = entries.singleOrNull { it.type == "stco" || it.type == "co64" } ?: error("missing chunks")
            require(number(chunks, 4) == count)
            val offsets = LongArray(if (kind == "djmd") count.toInt() else 0)
            val sizes = IntArray(offsets.size)
            var total = 0L
            for (i in offsets.indices) {
                val size = if (defaultSize == 0L) number(stsz, 12 + i * 4) else defaultSize
                require(size in 1..MAX_SAMPLE.toLong())
                val offset = if (chunks.type == "stco") number(chunks, 8 + i * 4)
                    else reader.u64(data, chunks.content + 8 + i * 8, chunks.end)
                require(offset <= length - size)
                total += size; require(total <= MAX_METADATA)
                offsets[i] = offset; sizes[i] = size.toInt()
            }
            return Track(kind, timescale, delta, count.toInt(), duration, offsets, sizes)
        }
        val root = reader.boxes(data, 0, data.size)
        val mvhd = requireNotNull(one(root, "mvhd"))
        require(number(mvhd, 0) == 0L)
        val movieTimescale = number(mvhd, 12); require(movieTimescale in 1..1000000000L)
        val trackBoxes = root.filter { it.type == "trak" }
        require(trackBoxes.size <= 16)
        val tracks = trackBoxes.mapNotNull { parseTrack(it) }
        val videos = tracks.filter { it.kind == "video" }
        require(videos.size == if (format == PanoramaVideoFormat.OSMO_LRF) 1 else 2)
        val video = videos.first()
        fun aligned(track: Track) = track.timescale == video.timescale && track.delta == video.delta &&
            track.count == video.count && track.editDuration == video.editDuration
        require(videos.all(::aligned))
        val mediaTicks = Math.multiplyExact(video.count.toLong(), video.delta)
        require(Math.multiplyExact(video.editDuration, video.timescale) == Math.multiplyExact(mediaTicks, movieTimescale))
        for (metadata in tracks.filter { it.kind == "djmd" && aligned(it) }) {
            val firstSample = readAt(metadata.offsets[0], metadata.sizes[0])
            if (reader.parseProduct(firstSample, format) == null) continue
            val frames = ArrayList<OsmoMotionFrame>(metadata.count)
            var valid = true
            for (i in 0 until metadata.count) {
                val attitude = parseAttitude(if (i == 0) firstSample else readAt(metadata.offsets[i], metadata.sizes[i]))
                if (attitude == null) { valid = false; break }
                val time = Math.multiplyExact(Math.multiplyExact(i.toLong(), metadata.delta), 1000000L) / metadata.timescale
                frames += OsmoMotionFrame(time, attitude)
            }
            if (valid) return OsmoMotionTrack(frames, metadata.delta * 1000000L / metadata.timescale)
        }
        null
    } catch (_: Exception) { null }

    internal fun parseAttitude(sample: ByteArray): List<Float>? = try {
        val frame = requireNotNull(reader.message(reader.fields(sample), 3))
        val camera = requireNotNull(reader.message(frame, 2))
        val attitude = requireNotNull(reader.message(camera, 9))
        val raw = FloatArray(4) { reader.float(attitude, it + 1, 0f) }
        val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        require(norm in 0.5f..1.5f)
        raw.map { it / norm }
    } catch (_: Exception) { null }
}
