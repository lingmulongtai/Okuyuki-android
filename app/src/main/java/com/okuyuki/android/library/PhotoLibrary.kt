package com.okuyuki.android.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.okuyuki.stereo.StereoProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream

enum class LibraryState { CHECKING, PERMISSION_REQUIRED, SCANNING, READY, LIMITED, ERROR }

class PhotoLibrary(private val context: Context, val dao: LibraryDao, private val scope: CoroutineScope) {
    private val scanning = Mutex()
    private var rescan: Job? = null
    private val _state = MutableStateFlow(LibraryState.CHECKING)
    val state = _state.asStateFlow()
    /** 3D photos, for Quest and the phone's own grid. */
    val photos = dao.observePhotos()
    /** Every indexed photo and video, newest first. */
    val media = dao.observeAll()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after a scan that added, changed or removed something. */
    val changes = _changes.asSharedFlow()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    init {
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun hasPermission() = MEDIA_PERMISSIONS.any(::granted)

    fun hasImagePermission() = granted(Manifest.permission.READ_MEDIA_IMAGES) ||
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    /** A cached index or conversion is never evidence that a selected item is still accessible. */
    suspend fun canRead(media: IndexedMedia): Boolean = withContext(Dispatchers.IO) {
        val fullPermission = granted(if (media.isVideo) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES)
        if (!fullPermission && !granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return@withContext false
        try {
            context.contentResolver.openFileDescriptor(uri(media), "r")?.use { true } ?: false
        } catch (_: SecurityException) { false }
        catch (_: java.io.IOException) { false }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        rescan?.cancel()
        _state.value = LibraryState.CHECKING
        rescan = scope.launch {
            delay(300)
            scanning.withLock {
                if (!hasPermission()) {
                    _state.value = LibraryState.PERMISSION_REQUIRED
                    return@withLock
                }
                _state.value = LibraryState.SCANNING
                try {
                    if (scan()) _changes.tryEmit(Unit)
                    val limited = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                    _state.value = if (limited) LibraryState.LIMITED else LibraryState.READY
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecurityException) {
                    _state.value = LibraryState.PERMISSION_REQUIRED
                }
                catch (_: Exception) {
                    _state.value = LibraryState.ERROR
                }
            }
        }
    }

    /** Brings the index up to date with MediaStore. Returns whether anything changed. */
    private suspend fun scan(): Boolean = withContext(Dispatchers.IO) {
        val previous = dao.all().associateBy { it.id }
        val seen = HashSet<Long>()
        val changed = ArrayList<IndexedMedia>()
        var saved = 0
        suspend fun flush() {
            if (changed.isEmpty()) return
            dao.saveAll(changed.toList()); saved += changed.size; changed.clear()
        }
        val photoFields = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.ORIENTATION)
        val photos = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoFields,
            "${MediaStore.Images.Media.MIME_TYPE} IN (${PHOTO_TYPES.joinToString { "?" }})", PHOTO_TYPES.toTypedArray())
        photos.use { cursor ->
            while (cursor.moveToNext()) {
                ensureActive()
                val id = cursor.getLong(0); seen += id
                val modified = cursor.getLong(2)
                if (previous[id]?.modified == modified) continue
                val mime = cursor.getString(7) ?: "image/jpeg"
                val (stereo, hdr) = inspectPhoto(id, mime)
                changed += IndexedMedia(id, modified, stereo, cursor.getString(1) ?: "写真",
                    cursor.getLong(3).takeIf { it > 0 } ?: (modified * 1000), cursor.getInt(4), cursor.getInt(5), cursor.getLong(6),
                    IndexedMedia.PHOTO, mime, null, hdr, cursor.getInt(8))
                if (changed.size >= 200) flush()
            }
        }
        flush()
        val videoFields = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.ORIENTATION,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.COLOR_TRANSFER)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoFields, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                ensureActive()
                val id = cursor.getLong(0); seen += id
                val modified = cursor.getLong(2)
                if (previous[id]?.modified == modified) continue
                val hdr = when (cursor.getInt(10)) {
                    MediaFormat.COLOR_TRANSFER_ST2084 -> "hdr10"
                    MediaFormat.COLOR_TRANSFER_HLG -> "hlg"
                    else -> "none"
                }
                changed += IndexedMedia(id, modified, false, cursor.getString(1) ?: "動画",
                    cursor.getLong(3).takeIf { it > 0 } ?: (modified * 1000), cursor.getInt(4), cursor.getInt(5), cursor.getLong(6),
                    IndexedMedia.VIDEO, cursor.getString(7) ?: "video/mp4", cursor.getLong(9).takeIf { it > 0 }, hdr, cursor.getInt(8))
                if (changed.size >= 200) flush()
            }
        }
        flush()
        val gone = previous.keys - seen
        gone.chunked(500).forEach { dao.delete(it) }
        saved > 0 || gone.isNotEmpty()
    }

    private fun query(uri: Uri, fields: Array<String>, selection: String?, arguments: Array<String>?): Cursor =
        context.contentResolver.query(uri, fields, selection, arguments, null) ?: throw IllegalStateException("MediaStore unavailable")

    /** Whether the photo is a 3D HEIC, and whether it carries an HDR gain map. */
    private fun inspectPhoto(id: Long, mime: String): Pair<Boolean, String> = try {
        context.contentResolver.openFileDescriptor(uri(id), "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val head = input.readNBytes(HdrMarkers.HEAD_BYTES)
                val hdr = if (HdrMarkers.hasGainMap(head)) "ultrahdr" else "none"
                val stereo = mime in HEIF_TYPES && StereoProbe.inspect(input.channel) != null
                stereo to hdr
            }
        } ?: (false to "none")
    } catch (_: Exception) { false to "none" }

    fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    fun uri(media: IndexedMedia): Uri = ContentUris.withAppendedId(
        if (media.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, media.id)

    companion object {
        val MEDIA_PERMISSIONS = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        private val HEIF_TYPES = setOf("image/heic", "image/heif")
        /** Photo formats the phone can decode for a TV. */
        private val PHOTO_TYPES = listOf("image/jpeg", "image/heic", "image/heif", "image/png", "image/webp", "image/avif")
    }
}

/** Finds the gain map metadata of Ultra HDR (Adobe `hdrgm`) and ISO 21496-1 photos near the start of a file. */
object HdrMarkers {
    const val HEAD_BYTES = 256 * 1024
    private val markers = listOf("http://ns.adobe.com/hdr-gain-map/", "hdrgm:Version", "urn:iso:std:iso:ts:21496:-1")
        .map { it.toByteArray(Charsets.US_ASCII) }

    fun hasGainMap(head: ByteArray): Boolean = markers.any { indexOf(head, it) >= 0 }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (start in 0..data.size - pattern.size) {
            for (i in pattern.indices) if (data[start + i] != pattern[i]) continue@outer
            return start
        }
        return -1
    }
}
