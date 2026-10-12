package com.okuyuki.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okuyuki.android.OkuyukiApplication
import com.okuyuki.android.host.HostState
import com.okuyuki.android.library.IndexedMedia
import kotlinx.coroutines.launch

/** A photo or video up close, with what it is and where it can be shown. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaSheet(app: OkuyukiApplication, media: IndexedMedia, host: HostState, dismiss: () -> Unit, showOnTv: () -> Unit, goConnect: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val item = media.item()
    val showing = host.viewingId == media.id.toString() && host.clients + host.tvClients > 0
    // At most about half the window, so the details and the button show without scrolling on a phone held sideways.
    val preview = windowSize().height.let { if (it > 0.dp) minOf(400.dp, it * 0.45f) else 400.dp }
    fun close(then: () -> Unit = {}) { scope.launch { sheet.hide() }.invokeOnCompletion { dismiss(); then() } }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = LocalExtras.current.panel,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            val ratio = (item.width.toFloat() / item.height.coerceAtLeast(1)).coerceIn(0.5f, 2.4f)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.heightIn(max = preview).aspectRatio(ratio).clip(MaterialTheme.shapes.medium)) {
                    Thumbnail(app, media, Modifier.fillMaxSize(), placeholder = 40.dp, sizePx = 1440)
                    if (media.isVideo) MediaBadge(durationLabel(media.durationMs), Modifier.align(Alignment.BottomEnd).padding(10.dp), glyph = Glyphs.Play)
                    if (showing) MediaBadge("表示中", Modifier.align(Alignment.BottomStart).padding(10.dp),
                        container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(media.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${dateTimeLabel(media.takenAt)} · ${item.width}\u00A0×\u00A0${item.height}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (media.stereo) FactChip("3D", emphasized = true)
                FactChip(resolutionLabel(media), emphasized = media.isVideo && maxOf(item.width, item.height) >= 3840)
                HDR_LABELS[media.hdr]?.let { FactChip(it, emphasized = true) }
                FactChip(formatLabel(media.mime))
                FactChip(sizeLabel(media.sizeBytes))
            }
            Spacer(Modifier.height(22.dp))
            if (host.tvClients > 0) {
                Button(onClick = { showOnTv(); close() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Glyphs.Tv, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (showing) "もう一度テレビで表示" else "テレビで表示")
                }
            } else Hint(Glyphs.Tv, "テレビにつなぐと、ここからテレビに映せます。", action = "つなぐ", onAction = { close(goConnect) })
            if (media.stereo) {
                Spacer(Modifier.height(10.dp))
                Hint(Glyphs.Headset, if (host.clients > 0) "Quest の一覧でこの写真を選ぶと、立体で見られます。"
                    else "Quest で Okuyuki を開くと、この写真を立体で見られます。")
            }
        }
    }
}

@Composable
private fun Hint(glyph: ImageVector, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
        .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphBubble(glyph, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
