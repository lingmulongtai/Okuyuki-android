package com.okuyuki.android

import android.net.Uri
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun OsmoVideoPreview(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failed by remember(uri) { mutableStateOf(false) }
    var wantPlaying by remember(uri) { mutableStateOf(true) }
    var syncReady by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    var seeking by remember(uri) { mutableStateOf(false) }
    val selected = remember(uri) { BooleanArray(2) }
    val expectedGroups = remember(uri) { arrayOfNulls<androidx.media3.common.TrackGroup>(2) }
    val expectedIndices = remember(uri) { IntArray(2) }
    val players = remember(uri) {
        List(2) { index ->
            ExoPlayer.Builder(context).build().apply {
                if (index == 1) trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                setMediaItem(MediaItem.fromUri(uri))
            }
        }
    }
    val view = remember(players) { DualTrackFisheyeView(context, players) { failed = true } }
    DisposableEffect(owner, players, view) {
        val listeners = players.mapIndexed { playerIndex, player ->
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failed = true; players.forEach { it.pause() } }
                override fun onTracksChanged(tracks: Tracks) {
                    val expected = expectedGroups[playerIndex]
                    if (expected != null) {
                        selected[playerIndex] = tracks.groups.any { it.mediaTrackGroup == expected && it.isTrackSelected(expectedIndices[playerIndex]) }
                        return
                    }
                    val candidates = tracks.groups.flatMapIndexed { groupIndex, group ->
                        if (group.type != C.TRACK_TYPE_VIDEO) emptyList() else (0 until group.length).map { index ->
                            val f = group.getTrackFormat(index)
                            OsmoTrack(groupIndex, index, f.sampleMimeType, f.width, f.height, group.isTrackSupported(index))
                        }
                    }
                    if (candidates.isEmpty()) return
                    val pair = osmoTracks(candidates)
                    if (pair == null) { failed = true; players.forEach { it.pause() }; return }
                    val item = pair[playerIndex]
                    val group = tracks.groups[item.group].mediaTrackGroup
                    expectedGroups[playerIndex] = group
                    expectedIndices[playerIndex] = item.index
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group, item.index)).build()
                }
            }.also { player.addListener(it) }
        }
        players.forEach { it.prepare() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onResume()
                Lifecycle.Event.ON_STOP -> { wantPlaying = false; players.forEach { it.pause() }; view.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onPause()
            view.close()
            players.forEachIndexed { index, player -> player.removeListener(listeners[index]); player.release() }
        }
    }
    LaunchedEffect(players, wantPlaying, failed) {
        while (true) {
            val first = players[0]; val second = players[1]
            duration = first.duration.coerceAtLeast(0L)
            if (!seeking) position = first.currentPosition.coerceAtLeast(0L)
            val prepared = selected.all { it } && players.all { it.playbackState == Player.STATE_READY }
            val drift = abs(first.currentPosition - second.currentPosition)
            if (prepared && drift > 150 && !seeking) second.seekTo(first.currentPosition)
            syncReady = prepared && drift <= 150 && !seeking && !failed
            view.setFrameVisibility(syncReady)
            if (!wantPlaying || !syncReady) players.forEach { if (it.playWhenReady) it.pause() }
            else players.forEach { if (!it.playWhenReady) it.play() }
            delay(100)
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.osv_preview), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.osv_experimental), style = MaterialTheme.typography.bodySmall)
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(280.dp))
                if (failed) Text(stringResource(R.string.osv_error), color = MaterialTheme.colorScheme.error)
                Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                    onValueChange = { seeking = true; position = it.toLong() },
                    onValueChangeFinished = { players.forEach { it.seekTo(position) }; seeking = false },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0 && !failed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { wantPlaying = !wantPlaying }, enabled = !failed) {
                        Text(stringResource(if (wantPlaying) R.string.pause else R.string.play))
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
