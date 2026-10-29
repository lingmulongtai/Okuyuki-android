package com.okuyuki.android

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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

@Composable
fun OsmoProxyPreview(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failed by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    var seeking by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true
        }
    }
    val view = remember(player) { DualFisheyeView(context, player) { failed = true; player.pause() } }
    DisposableEffect(owner, player, view) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val ratio = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                if (ratio !in 1.9f..2.1f) { failed = true; player.pause() }
            }
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
            view.close()
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.coerceAtLeast(0L)
            if (!seeking) position = player.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.osmo_preview), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.osmo_approximate), style = MaterialTheme.typography.bodySmall)
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(280.dp))
                if (failed) Text(stringResource(R.string.osmo_error), color = MaterialTheme.colorScheme.error)
                Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                    onValueChange = { seeking = true; position = it.toLong() },
                    onValueChangeFinished = { player.seekTo(position); seeking = false },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (playing) player.pause() else player.play() }, enabled = !failed) {
                        Text(stringResource(if (playing) R.string.pause else R.string.play))
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
