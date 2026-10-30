package com.okuyuki.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.okuyuki.android.host.MediaFile
import com.okuyuki.android.host.TvMediaSource
import com.okuyuki.android.library.IndexedMedia
import com.okuyuki.android.library.LibraryCursor
import com.okuyuki.android.library.PhotoLibrary
import com.okuyuki.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's photos and videos for TVs: thumbnails and 1080p pictures for the grid and the first look,
 * 4K stills made with Media3 Transformer, and videos as they are or converted when the TV cannot play them.
 * Converted files stay in the app's cache, at most [CACHE_BYTES], oldest removed first.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class AndroidTvMedia(
    private val context: Context,
    private val library: PhotoLibrary,
    private val events: MutableSharedFlow<Event>,
    private val scope: CoroutineScope,
) : TvMediaSource {
    private val dao get() = library.dao
    private val resolver = context.contentResolver
    private val decoding = Semaphore(2)
    private val exporting = Mutex()
    private val displays = object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val tracks = ConcurrentHashMap<String, TvPlan.Track>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val directory = File(context.cacheDir, "tv").apply {
        mkdirs()
        listFiles { file -> file.name.endsWith(".part") || file.name.startsWith("input-") }?.forEach { it.delete() }
    }
    private val exportThread = HandlerThread("okuyuki-tv-export").apply { start() }
    private val exportHandler = Handler(exportThread.looper)
    private val exportDispatcher = exportHandler.asCoroutineDispatcher()

    /** A still or video being made. Finished ones live on disk, with their [Prepared] next to them. */
    private class Job(val estimateMillis: Long) {
        val startedAt = System.currentTimeMillis()
        @Volatile var failed = false
        @Volatile var reported = 0.0
        fun progress(): Double = if (reported > 0) reported.coerceAtMost(0.99)
            else ((System.currentTimeMillis() - startedAt).toDouble() / estimateMillis).coerceAtMost(0.95)
    }

    fun close() {
        exportThread.quitSafely()
    }

    private suspend fun find(id: String): IndexedMedia? {
        if (!library.hasPermission()) return null
        val media = id.toLongOrNull()?.let { dao.findAny(it) } ?: return null
        return media.takeIf { library.canRead(it) }
    }

    override suspend fun page(filter: String, cursor: String?): ItemPage? {
        val after = if (cursor == null) LibraryCursor.START else LibraryCursor.decode(cursor) ?: return null
        if (!library.hasPermission()) return ItemPage(emptyList(), null, 0)
        val kind = when (filter) { "photo" -> IndexedMedia.PHOTO; "video" -> IndexedMedia.VIDEO; else -> null }
        val stereo = filter == "stereo"
        val visible = dao.all().filter { (kind == null || it.kind == kind) && (!stereo || it.stereo) && library.canRead(it) }
            .sortedWith(compareByDescending<IndexedMedia> { it.takenAt }.thenByDescending { it.id })
        val rows = visible.filter { it.takenAt < after.takenAt || (it.takenAt == after.takenAt && it.id < after.id) }
            .take(Protocol.TV_PAGE_SIZE + 1)
        val items = rows.take(Protocol.TV_PAGE_SIZE)
        val next = items.lastOrNull()?.takeIf { rows.size > items.size }?.let { LibraryCursor(it.takenAt, it.id).encode() }
        return ItemPage(items.map { it.item() }, next, visible.size)
    }

    override suspend fun item(id: String): Item? = find(id)?.item()

    override suspend fun thumbnail(id: String): ByteArray? {
        val media = find(id) ?: return null
        return decoding.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { jpeg(fit(resolver.loadThumbnail(library.uri(media), Size(THUMB, THUMB), null), THUMB, THUMB), 85) }.getOrNull()
            }
        }
    }

    override suspend fun display(id: String): ByteArray? {
        val media = find(id) ?: return null
        val key = "${media.id}:${media.modified}"
        displays.get(key)?.let { return it.takeIf { library.canRead(media) } }
        val bytes = decoding.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = if (media.isVideo) videoFrame(media, DISPLAY_WIDTH, DISPLAY_HEIGHT)
                        else decodePhoto(media, DISPLAY_WIDTH, DISPLAY_HEIGHT, keepGainmap = false)
                    jpeg(bitmap, 90)
                }.onFailure { Log.w(TAG, "Display picture failed", it) }.getOrNull()
            }
        } ?: return null
        if (!library.canRead(media)) return null
        displays.put(key, bytes)
        return bytes
    }

    override suspend fun prepare(id: String, target: String, capabilities: Capabilities?): Prepared? {
        val media = find(id) ?: return null
        if (target == "video") {
            if (!media.isVideo) return Prepared("unsupported")
            val track = track(media) ?: return Prepared("failed")
            if (TvPlan.playsOriginal(media.mime, track, capabilities)) {
                val item = media.item()
                return Prepared("ready", width = item.width, height = item.height, hdr = media.hdr, mime = media.mime, progress = 1.0)
            }
            val conversion = TvPlan.conversion(track, media.hdr, capabilities) ?: return Prepared("unsupported")
            val estimate = ((media.durationMs ?: 60_000) * 2).coerceAtLeast(10_000)
            return preparation(videoName(media, conversion), id, target, estimate) { out, progress -> convert(media, conversion, out, progress) }
        }
        if (media.isVideo) return Prepared("unsupported")
        val encoder = TvPlan.encoder(capabilities) ?: return Prepared("unsupported")
        val plane = TvPlan.plane(capabilities)
        val hdr = TvPlan.hdrStills(capabilities)
        return preparation(stillName(media, plane, encoder, hdr), id, target, STILL_ESTIMATE_MS) { out, progress ->
            still(media, plane, encoder, hdr, out, progress)
        }
    }

    override suspend fun file(id: String, target: String, capabilities: Capabilities?): MediaFile? {
        val media = find(id) ?: return null
        val name = if (target == "video") {
            if (!media.isVideo) return null
            val track = track(media) ?: return null
            if (TvPlan.playsOriginal(media.mime, track, capabilities)) return original(media)
            videoName(media, TvPlan.conversion(track, media.hdr, capabilities) ?: return null)
        } else {
            if (media.isVideo) return null
            stillName(media, TvPlan.plane(capabilities), TvPlan.encoder(capabilities) ?: return null, TvPlan.hdrStills(capabilities))
        }
        val out = File(directory, "$name.mp4")
        val result = finished(out) ?: return null
        out.setLastModified(System.currentTimeMillis())
        return MediaFile(out.length(), result.mime ?: "video/mp4") { out.inputStream() }
    }

    private fun stillName(media: IndexedMedia, plane: TvPlan.Plane, encoder: String, hdr: Boolean) =
        "still-${media.id}-${media.modified}-${plane.width}x${plane.height}-${codecName(encoder)}${if (hdr) "-hdr" else ""}"

    private fun videoName(media: IndexedMedia, conversion: TvPlan.Conversion) =
        "video-${media.id}-${media.modified}-${conversion.shortSide ?: "full"}-${codecName(conversion.sampleMime)}${if (conversion.keepHdr) "-hdr" else ""}"

    private fun codecName(sampleMime: String) = if (sampleMime == TvPlan.HEVC) "hevc" else "avc"

    private fun original(media: IndexedMedia): MediaFile {
        val uri = library.uri(media)
        val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 } ?: media.sizeBytes
        return MediaFile(size, media.mime) { resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()) }
    }

    /** The finished result for [out], if both the file and its description are on disk. */
    private fun finished(out: File): Prepared? {
        val description = File(out.path + ".json")
        if (!out.exists() || !description.exists()) return null
        return runCatching { Protocol.json.decodeFromString<Prepared>(description.readText()) }.getOrNull()
    }

    /** Starts a job once and reports on it; the TV asks again until it is ready or a `prepared` event arrives. */
    private fun preparation(name: String, itemId: String, target: String, estimateMillis: Long,
                            work: suspend (File, (Double) -> Unit) -> Prepared): Prepared {
        val out = File(directory, "$name.mp4")
        finished(out)?.let { out.setLastModified(System.currentTimeMillis()); return it }
        val job = jobs.computeIfAbsent(name) { Job(estimateMillis).also { start(name, it, out, itemId, target, work) } }
        if (job.failed) {
            jobs.remove(name, job)
            return Prepared("failed")
        }
        return Prepared("preparing", progress = job.progress())
    }

    private fun start(name: String, job: Job, out: File, itemId: String, target: String,
                      work: suspend (File, (Double) -> Unit) -> Prepared) {
        scope.launch(Dispatchers.IO) {
            try {
                val result = exporting.withLock {
                    trim()
                    work(out) { job.reported = it }
                }
                val description = File(out.path + ".json")
                File(description.path + ".part").apply { writeText(Protocol.json.encodeToString(result)) }.renameTo(description)
                jobs.remove(name, job)
                events.tryEmit(Event("prepared", itemId, target, "ready"))
            } catch (cancelled: CancellationException) {
                jobs.remove(name, job)
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Preparing $target for $itemId failed", error)
                job.failed = true
                events.tryEmit(Event("prepared", itemId, target, "failed"))
            }
        }
    }

    /** A one-frame-per-second video of the photo, fitted into the TV's video plane with black around it. */
    private suspend fun still(media: IndexedMedia, plane: TvPlan.Plane, encoder: String, hdrWanted: Boolean,
                              out: File, progress: (Double) -> Unit): Prepared {
        val input = File(directory, "input-${media.id}.jpg")
        try {
            val hasGainmap = decoding.withPermit {
                withContext(Dispatchers.IO) {
                    val bitmap = decodePhoto(media, plane.width, plane.height, keepGainmap = hdrWanted)
                    try {
                        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }
                        bitmap.hasGainmap()
                    } finally { bitmap.recycle() }
                }
            }
            fun composition(hdr: Boolean): Composition {
                val item = MediaItem.Builder().setUri(Uri.fromFile(input)).setMimeType(MimeTypes.IMAGE_JPEG)
                    .setImageDurationMs(STILL_DURATION_MS).build()
                val edited = EditedMediaItem.Builder(item).setFrameRate(1)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(plane.width, plane.height, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                return Composition.Builder(EditedMediaItemSequence.withVideoFrom(listOf(edited)))
                    .experimentalSetRetainHdrFromUltraHdrImage(hdr).build()
            }
            val result = try {
                export(composition(hasGainmap), out, encoder, STILL_BITRATE, progress)
            } catch (error: ExportException) {
                // Not every phone encodes HDR; the photo's SDR base still makes a sharp 4K still.
                if (!hasGainmap) throw error
                Log.w(TAG, "HDR still failed, making an SDR one", error)
                export(composition(false), out, encoder, STILL_BITRATE, progress)
            }
            return describe(result, encoder, plane.width, plane.height)
        } finally {
            input.delete()
        }
    }

    private suspend fun convert(media: IndexedMedia, conversion: TvPlan.Conversion, out: File, progress: (Double) -> Unit): Prepared {
        fun composition(keepHdr: Boolean): Composition {
            val effects = conversion.shortSide?.let { Effects(emptyList(), listOf(Presentation.createForShortSide(it))) } ?: Effects(emptyList(), emptyList())
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(library.uri(media))).setEffects(effects).build()
            // A video without sound gets a silent track, which every TV player accepts.
            return Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited)))
                .setHdrMode(if (keepHdr) Composition.HDR_MODE_KEEP_HDR else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
        }
        val result = try {
            export(composition(conversion.keepHdr), out, conversion.sampleMime, VIDEO_BITRATE, progress)
        } catch (error: ExportException) {
            if (!conversion.keepHdr) throw error
            Log.w(TAG, "HDR conversion failed, converting to SDR", error)
            export(composition(false), out, conversion.sampleMime, VIDEO_BITRATE, progress)
        }
        val item = media.item()
        return describe(result, conversion.sampleMime, item.width, item.height)
    }

    private fun describe(result: ExportResult, encoder: String, width: Int, height: Int): Prepared {
        val transfer = result.colorInfo?.colorTransfer
        val hdr = when (transfer) { C.COLOR_TRANSFER_HLG -> "hlg"; C.COLOR_TRANSFER_ST2084 -> "hdr10"; else -> "none" }
        return Prepared("ready", width = result.width.takeIf { it > 0 } ?: width, height = result.height.takeIf { it > 0 } ?: height,
            hdr = hdr, mime = "video/mp4", codecs = TvPlan.codecs(result.videoMimeType ?: encoder, tenBit = hdr != "none"), progress = 1.0)
    }

    /** Runs one Transformer export on its own looper thread and moves the file into place when it is complete. */
    private suspend fun export(composition: Composition, out: File, sampleMime: String, bitrate: Int, progress: (Double) -> Unit): ExportResult {
        val partial = File(out.path + ".part")
        partial.delete()
        val result = withContext(exportDispatcher) {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    .setLooper(exportThread.looper)
                    .setVideoMimeType(sampleMime)
                    .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(true)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(exportResult)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100.0)
                        exportHandler.postDelayed(this, 500)
                    }
                }
                continuation.invokeOnCancellation {
                    exportHandler.post { transformer.cancel(); partial.delete() }
                    exportHandler.removeCallbacks(poll)
                }
                transformer.start(composition, partial.path)
                exportHandler.postDelayed(poll, 500)
            }
        }
        check(partial.renameTo(out)) { "Could not keep the export" }
        return result
    }

    /** Removes the least recently used conversions until the cache is under its limit. */
    private fun trim() {
        val files = directory.listFiles { file -> file.name.endsWith(".mp4") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= CACHE_BYTES) break
            total -= file.length()
            file.delete()
            File(file.path + ".json").delete()
        }
    }

    private suspend fun track(media: IndexedMedia): TvPlan.Track? {
        val key = "${media.id}:${media.modified}"
        tracks[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, library.uri(media), null)
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@withContext null
                fun int(name: String) = if (format.containsKey(name)) format.getInteger(name) else null
                val bitrate = int(MediaFormat.KEY_BIT_RATE)?.toLong()
                    ?: media.durationMs?.takeIf { it > 0 }?.let { media.sizeBytes * 8000 / it }
                TvPlan.Track(format.getString(MediaFormat.KEY_MIME), int(MediaFormat.KEY_PROFILE),
                    int(MediaFormat.KEY_WIDTH) ?: media.width, int(MediaFormat.KEY_HEIGHT) ?: media.height, bitrate)
            } catch (error: Exception) {
                Log.w(TAG, "Reading the video failed", error)
                null
            } finally {
                extractor.release()
            }
        }?.also { tracks[key] = it }
    }

    /** Decodes a photo into sRGB, rotated, scaled to fit [maxWidth] x [maxHeight]. */
    private fun decodePhoto(media: IndexedMedia, maxWidth: Int, maxHeight: Int, keepGainmap: Boolean): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, library.uri(media))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSampleSize(TvPlan.sampleSize(info.size.width, info.size.height, maxWidth, maxHeight))
        }
        if (!keepGainmap && decoded.hasGainmap()) decoded.gainmap = null
        return fit(decoded, maxWidth, maxHeight)
    }

    private fun videoFrame(media: IndexedMedia, maxWidth: Int, maxHeight: Int): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, library.uri(media))
            val at = ((media.durationMs ?: 0) * 1000 / 3).coerceAtMost(1_000_000)
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            val frame = retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxWidth, maxHeight, params)
                ?: error("No frame")
            return fit(frame, maxWidth, maxHeight)
        } finally {
            retriever.release()
        }
    }

    /** Scales [bitmap] down to fit, recycling the original when a copy is made. */
    private fun fit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val size = TvPlan.fit(bitmap.width, bitmap.height, maxWidth, maxHeight)
        if (size.width == bitmap.width && size.height == bitmap.height) return bitmap
        return bitmap.scale(size.width, size.height).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val source = if (bitmap.colorSpace == null || bitmap.colorSpace == srgb) bitmap else {
            createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, false, srgb).also {
                Canvas(it).drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                bitmap.recycle()
            }
        }
        try {
            return ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            source.recycle()
        }
    }

    companion object {
        private const val TAG = "OkuyukiTv"
        private const val THUMB = 480
        private const val DISPLAY_WIDTH = 1920
        private const val DISPLAY_HEIGHT = 1080
        /** Two frames at one per second, like the mock host's stills, so the TV can play and pause on the first. */
        private const val STILL_DURATION_MS = 2000L
        private const val STILL_ESTIMATE_MS = 4000L
        private const val STILL_BITRATE = 40_000_000
        private const val VIDEO_BITRATE = 40_000_000
        const val CACHE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
