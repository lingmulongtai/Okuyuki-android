package com.okuyuki.android.ui

import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.okuyuki.android.OkuyukiApplication
import com.okuyuki.android.library.IndexedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnails kept while the app runs, so scrolling back does not decode them again. */
private object ThumbnailCache : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
}

/** The gallery's own thumbnail of [media], about [sizePx] square; while it loads, a quiet placeholder with the kind of item. */
@Composable
fun Thumbnail(app: OkuyukiApplication, media: IndexedMedia, modifier: Modifier = Modifier, placeholder: Dp = 22.dp,
    contentScale: ContentScale = ContentScale.Crop, sizePx: Int = 512) {
    val key = "${media.id}:${media.modified}:$sizePx"
    val bitmap by produceState(ThumbnailCache.get(key), key) {
        value = ThumbnailCache.get(key)
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.loadThumbnail(app.library.uri(media), Size(sizePx, sizePx), null) }.getOrNull()
        }?.also { ThumbnailCache.put(key, it) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        else Icon(if (media.isVideo) Glyphs.Play else if (media.stereo) Glyphs.Cube else Glyphs.Photo, contentDescription = null,
            modifier = Modifier.size(placeholder), tint = MaterialTheme.colorScheme.outline)
    }
}
