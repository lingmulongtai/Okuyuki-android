package com.okuyuki.android

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.host.HostRuntime
import com.okuyuki.android.host.SelectedPanoramaVideo
import com.okuyuki.android.host.acceptsPanoramaShare
import com.okuyuki.protocol.PanoramaVideoShare
import com.okuyuki.protocol.PanoramaVideoFormat
import com.okuyuki.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun VideoShareButton(uri: Uri, format: PanoramaVideoFormat, enabled: Boolean = true) {
    val context = LocalContext.current
    var sharing by remember(uri) { mutableStateOf(false) }
    var shareFailed by remember(uri) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val host by HostRuntime.state.collectAsStateWithLifecycle()
    val shared by HostRuntime.panoramaVideo.collectAsStateWithLifecycle()
    Button(enabled = host.running && !sharing && enabled, onClick = {
        val sessionId = HostRuntime.state.value.sessionId
        sharing = true
        scope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
                    try {
                        val length = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                        }
                        if (length == null || length !in 1..Protocol.MAX_PANORAMA_VIDEO_BYTES) return@withContext null
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
                            asset.createInputStream().use { input ->
                                input.channel.position(asset.startOffset)
                                val calibration = if (format != PanoramaVideoFormat.EQUIRECTANGULAR_MP4) {
                                    OsmoCalibrationReader.read(input.channel, asset.startOffset, length, format)?.toShareCalibration()
                                } else null
                                length to calibration
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                if (selected == null || !acceptsPanoramaShare(sessionId, HostRuntime.state.value)) {
                    shareFailed = true
                } else {
                    val id = UUID.randomUUID().toString().replace("-", "")
                    HostRuntime.panoramaVideo.value = SelectedPanoramaVideo(PanoramaVideoShare(id, selected.first, format, selected.second), uri)
                    shareFailed = false
                }
            } finally { sharing = false }
        }
    }) { Text(stringResource(R.string.panorama_share)) }
    if (!host.running) Text(stringResource(R.string.panorama_share_requires_host))
    if (shareFailed) Text(stringResource(R.string.panorama_video_share_error), color = MaterialTheme.colorScheme.error)
    if (shared?.uri == uri) Text(stringResource(R.string.panorama_video_shared))
}
