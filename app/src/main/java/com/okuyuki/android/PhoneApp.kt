package com.okuyuki.android

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.host.*
import com.okuyuki.android.library.*
import com.okuyuki.android.tv.TvRemote
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val light = lightColorScheme(primary = Color(0xFF2747E6), background = Color(0xFFF4F5F8), surface = Color.White, onSurface = Color(0xFF141821))
private val dark = darkColorScheme(primary = Color(0xFF8FA3FF), background = Color(0xFF0D0F15), surface = Color(0xFF161922), onSurface = Color(0xFFECEEF3))

@Composable
fun PhoneApp(app: OkuyukiApplication, requestPhotos: () -> Unit, toggleHost: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<IndexedMedia?>(null) }
    val photos by app.library.photos.collectAsStateWithLifecycle(emptyList())
    val everything by app.library.media.collectAsStateWithLifecycle(emptyList())
    val status by app.library.status.collectAsStateWithLifecycle()
    val host by HostRuntime.state.collectAsStateWithLifecycle()
    val pending by HostRuntime.authority.pending.collectAsStateWithLifecycle()
    val peersFlow = remember { app.database.dao().observePeers() }
    val peers by peersFlow.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val shown = if (showAll) everything else photos
    val days = remember(shown) { shown.groupBy { date(it.takenAt) } }
    val unit = if (showAll) "件" else "枚"
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { NavigationBar { listOf("ライブラリ", "つなぐ", "設定").forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("▦", "◎", "⚙")[index], fontSize = 24.sp) }, label = { Text(label) })
            } } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(listOf("Okuyuki", "つなぐ", "設定")[tab], style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    val connected = host.clients + host.tvClients > 0
                    Text(if (connected) "● 接続中" else if (host.running) "● 待機中" else "オフ",
                        color = if (connected) Color(0xFF12805A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { tab = 1 })
                }
                if (host.error != null) Text(host.error!!, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
                pending.forEach { request ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${request.name} と接続", style = MaterialTheme.typography.titleMedium)
                            Text(request.code, style = MaterialTheme.typography.displaySmall)
                            Text(if (request.kind == TV) "テレビに同じ番号が表示されていることを確認してください。" else "Questに同じ番号が表示されていることを確認してください。")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { scope.launch {
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
                                } }) { Text("許可") }
                                TextButton(onClick = { HostRuntime.authority.reject(request.requestId) }) { Text("拒否") }
                            }
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (showAll) "写真と動画 · ${shown.size}件" else "3D写真 · ${shown.size}枚", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { app.library.refresh() }) { Text("更新") }
                        }
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("3D写真") })
                            FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("すべて") })
                        }
                        Text(status, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        if (shown.isEmpty()) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (showAll) "ギャラリーの写真と動画がここに並びます。テレビではこれをすべて見られます。"
                                else "Galaxyで撮った3D写真がここに並びます。写真はギャラリーに置いたまま読み取ります。")
                            Button(onClick = requestPhotos) { Text("写真へのアクセスを許可") }
                        }
                        else LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            days.forEach { (day, group) ->
                                item(span = { GridItemSpan(3) }) { Text("$day · ${group.size}$unit", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                                items(group, key = { it.id }) { media ->
                                    Box(Modifier.aspectRatio(1f).clickable { detail = media }) {
                                        PhoneThumbnail(app, media, Modifier.fillMaxSize())
                                        if (media.isVideo) Text("▶ ${duration(media.durationMs)}", Modifier.align(Alignment.BottomEnd)
                                            .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                                        if (host.viewingId == media.id.toString()) Surface(Modifier.align(Alignment.BottomStart).padding(4.dp), color = MaterialTheme.colorScheme.primary) {
                                            Text("表示中", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Quest・テレビとつなぐ", style = MaterialTheme.typography.titleLarge); Text("同じWi-Fiの機器を待ちます") }
                            Switch(checked = host.running, onCheckedChange = toggleHost, enabled = app.library.hasPermission())
                        }
                        if (!app.library.hasPermission()) Button(onClick = requestPhotos) { Text("先に写真へのアクセスを許可") }
                        Text(when {
                            host.clients > 0 && host.tvClients > 0 -> "Quest とテレビが接続中"
                            host.tvClients > 0 -> "テレビ接続中 · 写真はリモコンで選べます"
                            host.clients > 0 -> "Quest 接続中 · 写真をQuestで選んでください"
                            host.running -> "QuestやテレビでOkuyukiを開き、このスマホを選んでください"
                            else -> "オンにするとQuestやテレビから写真を見られます。"
                        })
                        TvSection(app.tvRemote, host)
                        if (host.addresses.isNotEmpty()) {
                            Text("自動で見つからない場合", style = MaterialTheme.typography.titleMedium)
                            Text("Questやテレビで以下のアドレスを入力してください。")
                            host.addresses.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
                        }
                        Text("見た写真はQuestやテレビに一時的に置かれ、終了時に消えます。クラウドは経由しません。")
                        Text("接続先がない状態が10分続くと、待機を自動でオフにします。", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val tvs by app.tvRemote.tvs.collectAsStateWithLifecycle()
                        Text("Okuyuki ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                        Text("テーマは端末の設定に合わせます。")
                        Text("ペアリング済みの機器", style = MaterialTheme.typography.titleMedium)
                        if (peers.isEmpty()) Text("まだ接続した機器はありません")
                        peers.forEach { peer -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${if (peer.kind == TV) "テレビ" else "Quest"} · ${peer.name}", Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { HostRuntime.pairingMutex.withLock {
                                app.database.dao().deletePeer(peer.clientId); refreshAuthority(app.database.dao())
                            } } }) { Text("解除") }
                        } }
                        Text("Okuyuki TV を開いたテレビ", style = MaterialTheme.typography.titleMedium)
                        if (tvs.isEmpty()) Text("「つなぐ」でテレビを開くと、ここに並びます")
                        tvs.forEach { tv -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tv.name, Modifier.weight(1f))
                            TextButton(onClick = { app.tvRemote.forget(tv) }) { Text("解除") }
                        } }
                        OutlinedButton(onClick = requestPhotos) { Text("写真へのアクセスを変更") }
                        Text("アルファ版 · 実機での検証は未完了です。信頼できる自宅のWi-Fiまたはスマホのテザリングで使用してください。")
                    }
                }
            }
        }
        detail?.let { media ->
            val item = media.item()
            AlertDialog(onDismissRequest = { detail = null }, confirmButton = { TextButton(onClick = { detail = null }) { Text("閉じる") } },
                dismissButton = if (host.tvClients > 0) { { TextButton(onClick = { HostRuntime.showOnTv(media.id); detail = null }) { Text("テレビで表示") } } } else null,
                title = { Text(media.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhoneThumbnail(app, media, Modifier.fillMaxWidth().aspectRatio(item.width.toFloat().coerceAtLeast(1f) / item.height.coerceAtLeast(1)))
                    Text(listOfNotNull("${item.width} × ${item.height}", media.mime.substringAfter('/').uppercase(),
                        media.durationMs?.takeIf { media.isVideo }?.let(::duration), HDR_LABELS[media.hdr], date(media.takenAt)).joinToString(" · "))
                    Text(when {
                        host.tvClients > 0 -> "「テレビで表示」で、つないでいるテレビに映します。"
                        media.stereo -> "Questの一覧からこの写真を選ぶと、立体で表示されます。"
                        else -> "「つなぐ」でテレビの Okuyuki TV を開くと、テレビで見られます。"
                    })
                } })
        }
    }
}

@Composable private fun TvSection(remote: TvRemote, host: HostState) {
    val tvs by remote.tvs.collectAsStateWithLifecycle()
    val searching by remote.searching.collectAsStateWithLifecycle()
    val busy by remote.busy.collectAsStateWithLifecycle()
    val message by remote.message.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    val ready = host.running && host.port > 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("テレビで見る", style = MaterialTheme.typography.titleMedium)
            Text(if (ready) "LG のテレビで Okuyuki TV を開き、このスマホにつなぎます。初めてのテレビでは、テレビの画面で許可を選んでください。"
                else "上のスイッチをオンにすると、ここからテレビの Okuyuki TV を開けます。")
            tvs.forEach { tv -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(tv.name); Text(tv.address, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { remote.open(tv) }, enabled = ready && !busy) { Text("開く") }
            } }
            OutlinedButton(onClick = remote::search, enabled = !searching) { Text(if (searching) "探しています…" else "テレビを探す") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, Modifier.weight(1f), label = { Text("テレビの IP アドレス") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                TextButton(onClick = { remote.openAddress(address) }, enabled = ready && !busy && address.isNotBlank()) { Text("開く") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable private fun PhoneThumbnail(app: OkuyukiApplication, media: IndexedMedia, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, media.id, media.modified) {
        value = withContext(Dispatchers.IO) { runCatching { app.contentResolver.loadThumbnail(app.library.uri(media), Size(512, 512), null) }.getOrNull() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = media.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (media.isVideo) "▶" else if (media.stereo) "3D" else "")
    }
}

private val HDR_LABELS = mapOf("ultrahdr" to "Ultra HDR", "hdr10" to "HDR10", "hdr10plus" to "HDR10+", "hlg" to "HLG")

private fun date(time: Long) = SimpleDateFormat("M月d日（E）", Locale.JAPANESE).format(Date(time))

private fun duration(ms: Long?): String {
    val seconds = ((ms ?: 0) / 1000).toInt()
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

private suspend fun refreshAuthority(dao: LibraryDao) {
    HostRuntime.authority.setPeers(dao.peers().map { ApprovedPeer(it.clientId, it.name, it.tokenHash, it.kind) })
}
