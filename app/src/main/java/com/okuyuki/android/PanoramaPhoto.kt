package com.okuyuki.android

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.host.HostRuntime
import com.okuyuki.android.host.SelectedPanoramaPhoto
import com.okuyuki.android.host.acceptsPanoramaShare
import com.okuyuki.protocol.PanoramaPhotoShare
import com.okuyuki.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.UUID

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Failed : PhotoLoad
    data class Ready(val holder: PhotoBitmap) : PhotoLoad
}

private class PhotoBitmap(val bitmap: Bitmap) {
    private var references = 1
    @Synchronized fun retain() { check(references > 0); references++ }
    @Synchronized fun release() {
        check(references > 0)
        references--
        if (references == 0) bitmap.recycle()
    }
}

@Composable
fun PanoramaPhoto(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host by HostRuntime.state.collectAsStateWithLifecycle()
    val sharedPhoto by HostRuntime.panoramaPhoto.collectAsStateWithLifecycle()
    var shareMessage by remember(uri) { mutableStateOf<Int?>(null) }
    var sharing by remember(uri) { mutableStateOf(false) }
    val result by produceState<PhotoLoad>(PhotoLoad.Loading, uri) {
        value = PhotoLoad.Loading
        var holder: PhotoBitmap? = null
        try {
            withContext(Dispatchers.IO) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0 && width.toDouble() / height in 1.9..2.1)
                    val scale = minOf(1.0, 4096.0 / width, 4096.0 / height)
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                holder = PhotoBitmap(bitmap)
            }
            value = PhotoLoad.Ready(checkNotNull(holder))
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = PhotoLoad.Failed }
        finally {
            holder?.release()
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.panorama_photo), style = MaterialTheme.typography.titleLarge)
                when (val state = result) {
                    PhotoLoad.Loading -> CircularProgressIndicator()
                    PhotoLoad.Failed -> Text(stringResource(R.string.panorama_photo_error), color = MaterialTheme.colorScheme.error)
                    is PhotoLoad.Ready -> {
                        PhotoSurface(state.holder)
                        Button(enabled = host.running && !sharing, onClick = {
                            val sessionId = HostRuntime.state.value.sessionId
                            sharing = true
                            scope.launch {
                                try {
                                    val size = withContext(Dispatchers.IO) {
                                        try {
                                            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                                                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                                            }
                                        } catch (_: Exception) { null }
                                    }
                                    if (!acceptsPanoramaShare(sessionId, HostRuntime.state.value) || size == null || size !in 1..Protocol.MAX_PANORAMA_PHOTO_BYTES) {
                                        shareMessage = R.string.panorama_share_error
                                    } else {
                                        val id = UUID.randomUUID().toString().replace("-", "")
                                        HostRuntime.panoramaPhoto.value = SelectedPanoramaPhoto(PanoramaPhotoShare(id, size), uri)
                                        shareMessage = null
                                    }
                                } finally { sharing = false }
                            }
                        }) { Text(stringResource(R.string.panorama_share)) }
                        if (!host.running) Text(stringResource(R.string.panorama_share_requires_host), style = MaterialTheme.typography.bodySmall)
                        if (host.running && sharedPhoto?.uri == uri) Text(stringResource(R.string.panorama_shared), style = MaterialTheme.typography.bodySmall)
                        shareMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun PhotoSurface(holder: PhotoBitmap) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failed by remember(holder) { mutableStateOf(false) }
    val view = remember(holder, owner) { PanoramaPhotoView(context, holder.bitmap) { failed = true } }
    DisposableEffect(owner, view) {
        holder.retain()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onResume()
                Lifecycle.Event.ON_STOP -> view.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onPause()
            view.close()
            holder.release()
        }
    }
    if (failed) Text(stringResource(R.string.panorama_photo_error), color = MaterialTheme.colorScheme.error)
    else AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(320.dp))
}
