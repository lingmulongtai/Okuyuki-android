package com.okuyuki.android

import com.okuyuki.protocol.OsmoStabilization

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface CalibrationLoad {
    data object Loading : CalibrationLoad
    data class Ready(val calibration: OsmoCalibration?, val stabilization: OsmoStabilization?) : CalibrationLoad
}

@Composable
fun OsmoProxyPreview(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var calibrationResult by remember(uri) { mutableStateOf<CalibrationLoad>(CalibrationLoad.Loading) }
    LaunchedEffect(uri) {
        val result = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
                    asset.createInputStream().use { input ->
                        val available = input.channel.size() - asset.startOffset
                        val length = if (asset.declaredLength >= 0) asset.declaredLength else available
                        val calibration = OsmoCalibrationReader.read(input.channel, asset.startOffset, length)
                        val motion = OsmoMotionReader.read(input.channel, asset.startOffset, length, com.okuyuki.protocol.PanoramaVideoFormat.OSMO_LRF)
                        calibration to motion?.let(::OsmoStabilization)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        calibrationResult = CalibrationLoad.Ready(result?.first, result?.second)
    }
    val result = calibrationResult
    if (result !is CalibrationLoad.Ready) {
        Dialog(onDismissRequest = onClose) { Surface { CircularProgressIndicator(Modifier.padding(24.dp)) } }
        return
    }
    val calibration = result.calibration
    val stabilization = result.stabilization
    var stabilizationEnabled by remember(uri) { mutableStateOf(false) }
    var stabilizationUnavailable by remember(uri) { mutableStateOf(false) }
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
    val view = remember(player, calibration, stabilization) { DualFisheyeView(context, player, calibration, stabilization,
        onStabilizationUnavailable = { stabilizationEnabled = false; stabilizationUnavailable = true }) { failed = true; player.pause() } }
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
                Text(stringResource(if (calibration == null) R.string.osmo_approximate else R.string.osmo_calibrated), style = MaterialTheme.typography.bodySmall)
                if (stabilization != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = stabilizationEnabled, onCheckedChange = { stabilizationEnabled = it; stabilizationUnavailable = false; view.setStabilizationEnabled(it) })
                        Text(stringResource(R.string.osmo_stabilization_experimental))
                    }
                    if (stabilizationEnabled) Text(stringResource(R.string.osmo_stabilization_note), style = MaterialTheme.typography.bodySmall)
                    if (stabilizationUnavailable) Text(stringResource(R.string.osmo_stabilization_unavailable), style = MaterialTheme.typography.bodySmall)
                }
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(280.dp))
                if (failed) Text(stringResource(R.string.osmo_error), color = MaterialTheme.colorScheme.error)
                Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                    onValueChange = { seeking = true; position = it.toLong() },
                    onValueChangeFinished = { view.resetStabilizationClock(); player.seekTo(position); seeking = false },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0)
                VideoShareButton(uri, com.okuyuki.protocol.PanoramaVideoFormat.OSMO_LRF)
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
