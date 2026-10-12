package com.okuyuki.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.OkuyukiApplication
import com.okuyuki.android.host.HostState
import com.okuyuki.android.library.IndexedMedia
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The phone's grid: 3D photos for Quest, or every photo and video for TVs, by day.
 * [photos] and [everything] are null until the index has been read once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: OkuyukiApplication, photos: List<IndexedMedia>?, everything: List<IndexedMedia>?, host: HostState,
    requestPhotos: () -> Unit, open: (IndexedMedia) -> Unit) {
    // The chosen filter is kept across launches; people who use the TV mostly want everything.
    val prefs = remember { app.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var showAll by rememberSaveable { mutableStateOf(prefs.getBoolean("showAll", false)) }
    fun choose(all: Boolean) { showAll = all; prefs.edit { putBoolean("showAll", all) } }
    val scan by app.library.state.collectAsStateWithLifecycle()
    val allowed = app.library.hasPermission()
    val shown = if (showAll) everything else photos
    var pulling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        if (allowed) {
            Segments(listOf("3D写真" to photos?.size, "すべて" to everything?.size), if (showAll) 1 else 0, { choose(it == 1) },
                Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp).fillMaxWidth())
            when {
                scan.failed -> Notice("写真を読み取れませんでした。下に引くと読み直します。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), Tone.ERROR)
                scan.limited -> Notice("選んだ写真と動画だけを表示しています", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    action = "選び直す", onAction = requestPhotos)
            }
        }
        PullToRefreshBox(isRefreshing = pulling, modifier = Modifier.weight(1f), onRefresh = {
            scope.launch { pulling = true; try { app.library.refresh().join() } finally { pulling = false } }
        }) {
            val loading = shown == null || shown.isEmpty() && !scan.failed && (scan.scanning || !scan.allowed)
            when {
                !allowed -> Scrolling { EmptyState(Glyphs.Photo, "写真へのアクセスを許可してください",
                    "ギャラリーの写真と動画を、このスマホの中で読み取ります。写真がスマホの外に出るのは、Quest やテレビで見るときだけです。",
                    action = "写真へのアクセスを許可", onAction = requestPhotos) }
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("写真と動画を読み込んでいます…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                shown.isEmpty() && !showAll -> Scrolling { EmptyState(Glyphs.Cube, "3D写真はまだありません",
                    "Galaxy で撮った 3D 写真がここに並び、Quest で立体的に見られます。テレビでは、ふつうの写真と動画も見られます。",
                    action = "すべての写真と動画を見る", onAction = { choose(true) }) }
                shown.isEmpty() -> Scrolling { EmptyState(Glyphs.Photo, "写真と動画がありません", "ギャラリーに写真や動画が増えると、ここに並びます。") }
                else -> MediaGrid(app, shown, unit = if (showAll) "件" else "枚", mark3d = showAll, viewingId = host.viewingId, open = open)
            }
            val viewing = everything?.firstOrNull { it.id.toString() == host.viewingId }
            if (viewing != null && host.clients + host.tvClients > 0) {
                NowShowing(app, viewing, where = when {
                    host.tvClients > 0 && host.clients > 0 -> "Quest・テレビで表示中"
                    host.tvClients > 0 -> "テレビで表示中"
                    else -> "Quest で表示中"
                }, onClick = { open(viewing) }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Scrolling(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
}

@Composable
private fun MediaGrid(app: OkuyukiApplication, items: List<IndexedMedia>, unit: String, mark3d: Boolean, viewingId: String?, open: (IndexedMedia) -> Unit) {
    val days = remember(items) { items.groupBy { localDay(it.takenAt) } }
    val today = remember { LocalDate.now() }
    val state = rememberLazyGridState()
    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = if (viewingId != null) 96.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        days.forEach { (day, group) ->
            stickyHeader(key = "day:$day", contentType = "day") {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 4.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom) {
                    Text(dayLabel(day, today), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Text("${group.size}$unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(group, key = { it.id }, contentType = { "media" }) { media ->
                Tile(app, media, mark3d, showing = viewingId == media.id.toString()) { open(media) }
            }
        }
    }
}

@Composable
private fun Tile(app: OkuyukiApplication, media: IndexedMedia, mark3d: Boolean, showing: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val label = buildString {
        append(if (media.isVideo) "動画 " else if (media.stereo) "3D写真 " else "写真 ")
        append(media.name)
        if (media.isVideo) append("、${durationLabel(media.durationMs)}")
        if (showing) append("、表示中")
    }
    Box(Modifier.aspectRatio(1f).clip(shape).clickable(onClick = onClick).semantics { contentDescription = label }) {
        Thumbnail(app, media, Modifier.fillMaxSize())
        if (media.isVideo) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.4f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
        Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (mark3d && media.stereo) MediaBadge("3D")
            if (media.hdr != "none") MediaBadge("HDR")
        }
        if (media.isVideo) MediaBadge(durationLabel(media.durationMs), Modifier.align(Alignment.BottomEnd).padding(5.dp),
            glyph = Glyphs.Play, container = Color.Transparent)
        if (showing) {
            Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.tertiary, shape))
            MediaBadge("表示中", Modifier.align(Alignment.BottomStart).padding(6.dp), container = MaterialTheme.colorScheme.tertiary,
                content = MaterialTheme.colorScheme.onTertiary)
        }
    }
}

/** What the TV or Quest shows right now, kept at hand above the grid. */
@Composable
private fun NowShowing(app: OkuyukiApplication, media: IndexedMedia, where: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium, color = colors.inverseSurface, contentColor = colors.inverseOnSurface, shadowElevation = 8.dp) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(app, media, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), placeholder = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(where, style = MaterialTheme.typography.labelMedium, color = colors.inversePrimary)
                Text(media.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Glyphs.ChevronRight, contentDescription = null, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        }
    }
}
