package com.okuyuki.android

import android.net.Uri
import android.view.LayoutInflater
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
import com.okuyuki.android.host.SelectedPanoramaVideo
import com.okuyuki.android.host.acceptsPanoramaShare
import com.okuyuki.protocol.PanoramaVideoShare
import com.okuyuki.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PanoramaVideo(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failed by remember(uri) { mutableStateOf(false) }
    var sharing by remember(uri) { mutableStateOf(false) }
    var shareFailed by remember(uri) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val host by HostRuntime.state.collectAsStateWithLifecycle()
    val shared by HostRuntime.panoramaVideo.collectAsStateWithLifecycle()
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    val view = remember(player) {
        (LayoutInflater.from(context).inflate(R.layout.panorama_player, null) as PlayerView).apply { this.player = player }
    }
    DisposableEffect(owner, player, view) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onResume()
                Lifecycle.Event.ON_STOP -> { player.pause(); view.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onPause()
            view.player = null
            player.removeListener(listener)
            player.release()
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.panorama_video), style = MaterialTheme.typography.titleLarge)
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(320.dp))
                if (failed) Text(stringResource(R.string.panorama_video_error), color = MaterialTheme.colorScheme.error)
                Button(enabled = host.running && !sharing && !failed, onClick = {
                    val sessionId = HostRuntime.state.value.sessionId
                    sharing = true
                    scope.launch {
                        try {
                            val size = withContext(Dispatchers.IO) {
                                try {
                                    val length = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                                    }
                                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
                                        asset.createInputStream().use { it.channel.position(asset.startOffset) }
                                    } ?: return@withContext null
                                    length
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            }
                            if (size == null || size !in 1..Protocol.MAX_PANORAMA_VIDEO_BYTES || !acceptsPanoramaShare(sessionId, HostRuntime.state.value)) {
                                shareFailed = true
                            } else {
                                val id = UUID.randomUUID().toString().replace("-", "")
                                HostRuntime.panoramaVideo.value = SelectedPanoramaVideo(PanoramaVideoShare(id, size), uri)
                                shareFailed = false
                            }
                        } finally { sharing = false }
                    }
                }) { Text(stringResource(R.string.panorama_share)) }
                if (!host.running) Text(stringResource(R.string.panorama_share_requires_host))
                if (shareFailed) Text(stringResource(R.string.panorama_video_share_error), color = MaterialTheme.colorScheme.error)
                if (shared?.uri == uri) Text(stringResource(R.string.panorama_video_shared))
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
