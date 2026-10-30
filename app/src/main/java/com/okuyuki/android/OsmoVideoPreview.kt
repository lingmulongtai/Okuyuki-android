package com.okuyuki.android

import android.net.Uri
import android.os.SystemClock
import com.okuyuki.protocol.OsmoStabilization
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

@Composable
fun OsmoVideoPreview(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var loaded by remember(uri) { mutableStateOf(false) }
    var calibration by remember(uri) { mutableStateOf<OsmoCalibration?>(null) }
    var stabilization by remember(uri) { mutableStateOf<OsmoStabilization?>(null) }
    LaunchedEffect(uri) {
        val result = withContext(Dispatchers.IO) {
            val workContext = coroutineContext
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
                    asset.createInputStream().use { input ->
                        val available = input.channel.size() - asset.startOffset
                        val length = if (asset.declaredLength >= 0) asset.declaredLength else available
                        val calibration = OsmoCalibrationReader.read(input.channel, asset.startOffset, length, com.okuyuki.protocol.PanoramaVideoFormat.OSMO_OSV)
                        val motion = OsmoMotionReader.read(input.channel, asset.startOffset, length, com.okuyuki.protocol.PanoramaVideoFormat.OSMO_OSV) { workContext.ensureActive() }
                        calibration to motion?.let { OsmoStabilization(it) { workContext.ensureActive() } }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        calibration = result?.first
        stabilization = result?.second
        loaded = true
    }
    if (!loaded) {
        Dialog(onDismissRequest = onClose) { Surface { CircularProgressIndicator(Modifier.padding(24.dp)) } }
        return
    }
    var failed by remember(uri) { mutableStateOf(false) }
    var wantPlaying by remember(uri) { mutableStateOf(true) }
    var syncReady by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    var seeking by remember(uri) { mutableStateOf(false) }
    var resettingSeek by remember(uri) { mutableStateOf(false) }
    var pausePending by remember(uri) { mutableStateOf(false) }
    var active by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var ended by remember(uri) { mutableStateOf(false) }
    var stabilizationEnabled by remember(uri) { mutableStateOf(false) }
    var stabilizationUnavailable by remember(uri) { mutableStateOf(false) }
    val selected = remember(uri) { BooleanArray(2) }
    val expectedGroups = remember(uri) { arrayOfNulls<androidx.media3.common.TrackGroup>(2) }
    val expectedIndices = remember(uri) { IntArray(2) }
    val players = remember(uri) {
        List(2) { index ->
            ExoPlayer.Builder(context).build().apply {
                val parameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                if (index == 1) parameters.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                trackSelectionParameters = parameters.build()
                setMediaItem(MediaItem.fromUri(uri))
            }
        }
    }
    val view = remember(players, calibration, stabilization) { DualTrackFisheyeView(context, players, calibration, stabilization,
        onStabilizationUnavailable = { stabilizationEnabled = false; stabilizationUnavailable = true }) { failed = true } }
    LaunchedEffect(view, stabilizationEnabled) { view.setStabilizationEnabled(stabilizationEnabled) }
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
                        .setOverrideForType(TrackSelectionOverride(group, item.index))
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                    selected[playerIndex] = player.currentTracks.groups.any { it.mediaTrackGroup == group && it.isTrackSelected(item.index) }
                }
            }.also { player.addListener(it) }
        }
        players.forEach { it.prepare() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { active = true; view.onResume() }
                Lifecycle.Event.ON_STOP -> { active = false; pausePending = false; view.resumeFrames(); wantPlaying = false; players.forEach { it.pause() }; view.onPause() }
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
        var selectionSince: Long? = null
        var finalSince: Long? = null
        var pauseSince: Long? = null
        var lastCount = view.frameCount()
        var progressAt = SystemClock.elapsedRealtime()
        while (true) {
            val first = players[0]; val second = players[1]
            val now = SystemClock.elapsedRealtime()
            if (!active) {
                selectionSince = null; finalSince = null; pauseSince = null; progressAt = now
                delay(100); continue
            }
            if (pausePending) {
                val started = pauseSince ?: now.also { pauseSince = it }
                if (view.isFrozen() || now - started >= 3_000) {
                    if (!view.isFrozen()) view.resumeFrames()
                    pausePending = false; wantPlaying = false
                }
            } else pauseSince = null
            duration = first.duration.coerceAtLeast(0L)
            if (!seeking) position = first.currentPosition.coerceAtLeast(0L)
            val selectedBoth = selected.all { it }
            if (wantPlaying && !failed && !seeking && !selectedBoth) {
                val started = selectionSince ?: now.also { selectionSince = it }
                if (now - started >= 15_000) failed = true
            } else selectionSince = null
            val endedCount = players.count { it.playbackState == Player.STATE_ENDED }
            if (endedCount == 2 && view.isFrozen()) { view.resumeFrames(); pausePending = false }
            val prepared = selectedBoth && players.all { it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_ENDED }
            val drift = second.currentPosition - first.currentPosition
            if (prepared && endedCount == 0) {
                val speed = when { drift > 40 -> 0.95f; drift < -40 -> 1.05f; else -> 1f }
                if (second.playbackParameters.speed != speed) second.setPlaybackSpeed(speed)
            }
            syncReady = selectedBoth && (prepared || endedCount > 0) && !seeking && !failed
            view.setFrameVisibility(syncReady)
            if (endedCount == 2 && !seeking && !failed) {
                if (view.finalFramesPresented()) {
                    ended = true; wantPlaying = false; finalSince = null
                } else {
                    val started = finalSince ?: now.also { finalSince = it }
                    if (now - started >= 8_000) failed = true
                }
            } else finalSince = null
            if (!wantPlaying || !selectedBoth || seeking || failed || (!prepared && endedCount == 0)) {
                players.forEach { if (it.playWhenReady) it.pause() }
            } else players.filter { it.playbackState != Player.STATE_ENDED }.forEach { if (!it.playWhenReady) it.play() }
            val count = view.frameCount()
            if (count != lastCount || !wantPlaying || (!prepared && endedCount == 0) || seeking || endedCount == 2) progressAt = now
            else if (now - progressAt >= 8_000) failed = true
            lastCount = count
            delay(100)
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.osv_preview), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (calibration != null) R.string.osv_calibrated else R.string.osv_experimental), style = MaterialTheme.typography.bodySmall)
                if (stabilization != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = stabilizationEnabled, onCheckedChange = { stabilizationEnabled = it; stabilizationUnavailable = false })
                        Text(stringResource(R.string.osmo_stabilization_experimental))
                    }
                    if (stabilizationUnavailable) Text(stringResource(R.string.osmo_stabilization_unavailable))
                }
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(280.dp))
                if (failed) Text(stringResource(R.string.osv_error), color = MaterialTheme.colorScheme.error)
                Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                    onValueChange = { seeking = true; position = it.toLong() },
                    onValueChangeFinished = {
                        val target = position.coerceAtMost((duration - 100L).coerceAtLeast(0L))
                        ended = false; resettingSeek = true; pausePending = false
                        players.forEach { it.pause() }
                        view.setFrameVisibility(false)
                        view.resetForSeek { players.forEach { it.seekTo(target) }; seeking = false; resettingSeek = false }
                    },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0 && !failed && !resettingSeek && !pausePending)
                VideoShareButton(uri, com.okuyuki.protocol.PanoramaVideoFormat.OSMO_OSV)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (ended) {
                            ended = false; seeking = true; resettingSeek = true; position = 0L
                            view.setFrameVisibility(false)
                            view.resetForSeek { players.forEach { it.seekTo(0L) }; seeking = false; resettingSeek = false; wantPlaying = true }
                        } else if (wantPlaying) {
                            pausePending = true; view.freezeAtPair()
                        } else {
                            view.resumeFrames(); wantPlaying = true
                        }
                    }, enabled = !failed && !resettingSeek && !pausePending) {
                        Text(stringResource(if (wantPlaying) R.string.pause else R.string.play))
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
