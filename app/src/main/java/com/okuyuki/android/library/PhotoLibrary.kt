package com.okuyuki.android.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.okuyuki.stereo.StereoProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream

enum class LibraryState { CHECKING, PERMISSION_REQUIRED, SCANNING, READY, LIMITED, ERROR }

class PhotoLibrary(private val context: Context, val dao: LibraryDao, private val scope: CoroutineScope) {
    private val scanning = Mutex()
    private var rescan: Job? = null
    private val _status = MutableStateFlow("写真へのアクセスを確認しています…")
    val status = _status.asStateFlow()
    private val _state = MutableStateFlow(LibraryState.CHECKING)
    val state = _state.asStateFlow()
    val photos = dao.observePhotos()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    init { context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer) }

    fun hasPermission() = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun refresh() {
        rescan?.cancel()
        _state.value = LibraryState.CHECKING
        _status.value = "写真へのアクセスを確認しています…"
        rescan = scope.launch {
            delay(300)
            scanning.withLock {
                if (!hasPermission()) {
                    _status.value = "写真へのアクセスを許可してください"
                    _state.value = LibraryState.PERMISSION_REQUIRED
                    return@withLock
                }
                _status.value = "3D写真を確認しています…"
                _state.value = LibraryState.SCANNING
                try {
                    scan()
                    val limited = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                    _status.value = if (limited) "許可した写真のみ表示中 · すべて見るには写真の許可を変更" else "ギャラリーの3D写真を表示中"
                    _state.value = if (limited) LibraryState.LIMITED else LibraryState.READY
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecurityException) {
                    _status.value = "写真の許可が変更されました。再度許可してください"
                    _state.value = LibraryState.PERMISSION_REQUIRED
                }
                catch (_: Exception) {
                    _status.value = "写真を読み取れませんでした。もう一度更新してください"
                    _state.value = LibraryState.ERROR
                }
            }
        }
    }

    private suspend fun scan() = withContext(Dispatchers.IO) {
        val previous = dao.all().associateBy { it.id }
        val seen = mutableSetOf<Long>()
        val fields = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE)
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fields,
            "${MediaStore.Images.Media.MIME_TYPE} IN (?, ?)", arrayOf("image/heic", "image/heif"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                ensureActive()
                val id = cursor.getLong(0); seen += id
                val modified = cursor.getLong(2)
                if (previous[id]?.modified == modified) continue
                val stereo = try {
                    context.contentResolver.openFileDescriptor(uri(id), "r")?.use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { StereoProbe.inspect(it.channel) != null }
                    } ?: false
                } catch (_: Exception) { false }
                dao.save(IndexedPhoto(id, modified, stereo, cursor.getString(1) ?: "3D写真",
                    cursor.getLong(3).takeIf { it > 0 } ?: modified * 1000,
                    cursor.getInt(4), cursor.getInt(5), cursor.getLong(6)))
            }
        } ?: throw IllegalStateException("MediaStore unavailable")
        (previous.keys - seen).chunked(500).forEach { dao.delete(it) }
    }

    fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}
