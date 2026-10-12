package com.okuyuki.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.host.*
import com.okuyuki.android.library.*
import com.okuyuki.android.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private enum class Tab(val label: String, val title: String, val glyph: ImageVector) {
    LIBRARY("ライブラリ", "Okuyuki", Glyphs.Library), CONNECT("つなぐ", "つなぐ", Glyphs.Cast), SETTINGS("設定", "設定", Glyphs.Tune)
}

@Composable
fun PhoneApp(app: OkuyukiApplication, requestPhotos: () -> Unit, toggleHost: (Boolean) -> Unit, openAppSettings: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val photos by app.library.photos.collectAsStateWithLifecycle(null as List<IndexedMedia>?)
    val everything by app.library.media.collectAsStateWithLifecycle(null as List<IndexedMedia>?)
    val host by HostRuntime.state.collectAsStateWithLifecycle()
    val pending by HostRuntime.authority.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BackHandler(tab != Tab.LIBRARY) { tab = Tab.LIBRARY }
    OkuyukiTheme {
        val extras = LocalExtras.current
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = { Header(tab, host) { tab = Tab.CONNECT } },
            bottomBar = {
                val line = MaterialTheme.colorScheme.outlineVariant
                NavigationBar(Modifier.drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
                    containerColor = extras.panel, tonalElevation = 0.dp) {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(selected = tab == entry, onClick = { tab = entry }, label = { Text(entry.label) },
                            icon = { Icon(entry.glyph, contentDescription = null) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface))
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (tab) {
                    Tab.LIBRARY -> LibraryScreen(app, photos, everything, host, requestPhotos, open = { detailId = it.id })
                    Tab.CONNECT -> ConnectScreen(app, host, requestPhotos, toggleHost)
                    Tab.SETTINGS -> SettingsScreen(app, requestPhotos, openAppSettings)
                }
            }
        }
        everything?.firstOrNull { it.id == detailId }?.let { media ->
            MediaSheet(app, media, host, dismiss = { detailId = null }, showOnTv = { HostRuntime.showOnTv(media.id) },
                goConnect = { detailId = null; tab = Tab.CONNECT })
        }
        pending.firstOrNull()?.let { request ->
            PairingDialog(request, others = pending.size - 1,
                approve = { scope.launch { approve(app, request) } },
                reject = { HostRuntime.authority.reject(request.requestId) })
        }
    }
}

@Composable
private fun Header(tab: Tab, host: HostState, openConnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (tab == Tab.LIBRARY) { BrandMark(26.dp); Spacer(Modifier.width(10.dp)) }
        // The title keeps its line; with very large text the status gives way and ends in "…".
        Text(tab.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { StatusPill(host, openConnect) }
    }
}

/** Where the phone stands with Quest and TVs, at a glance. Opens "つなぐ". */
@Composable
private fun StatusPill(host: HostState, onClick: () -> Unit) {
    val extras = LocalExtras.current
    val colors = MaterialTheme.colorScheme
    val (label, dot, container) = when {
        host.tvClients > 0 && host.clients > 0 -> Triple("Quest・テレビに接続中", extras.success, extras.successContainer)
        host.tvClients > 0 -> Triple("テレビに接続中", extras.success, extras.successContainer)
        host.clients > 0 -> Triple("Quest に接続中", extras.success, extras.successContainer)
        host.running -> Triple("待機中", colors.primary, colors.primaryContainer)
        else -> Triple("オフ", colors.outline, colors.surfaceContainerHigh)
    }
    Surface(onClick = onClick, shape = CircleShape, color = container) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            val waiting = host.running && host.clients + host.tvClients == 0
            val pulse = if (waiting) rememberInfiniteTransition(label = "waiting")
                .animateFloat(1f, 0.35f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "dot").value else 1f
            Box(Modifier.size(8.dp).alpha(pulse).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (host.running) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Saves the peer before the device receives its token, and rolls back if the request ended meanwhile. */
private suspend fun approve(app: OkuyukiApplication, request: PendingPeer) {
    HostRuntime.pairingMutex.withLock {
        HostRuntime.authority.prepareApproval(request.requestId)?.let { peer ->
            val dao = app.database.dao()
            val previous = dao.peers().firstOrNull { it.clientId == peer.clientId }
            dao.savePeer(PairedPeer(peer.clientId, peer.name, peer.tokenHash, peer.kind))
            refreshAuthority(dao)
            if (!HostRuntime.authority.completeApproval(request.requestId, peer)) {
                if (previous == null) dao.deletePeer(peer.clientId) else dao.savePeer(previous)
                refreshAuthority(dao)
            }
        }
    }
}

suspend fun refreshAuthority(dao: LibraryDao) {
    HostRuntime.authority.setPeers(dao.peers().map { ApprovedPeer(it.clientId, it.name, it.tokenHash, it.kind) })
}
