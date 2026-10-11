package com.okuyuki.android

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.host.*
import com.okuyuki.android.library.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val light = lightColorScheme(primary = Color(0xFF2747E6), background = Color(0xFFF4F5F8), surface = Color.White, onSurface = Color(0xFF141821))
private val dark = darkColorScheme(primary = Color(0xFF8FA3FF), background = Color(0xFF0D0F15), surface = Color(0xFF161922), onSurface = Color(0xFFECEEF3))

@Composable
fun PhoneApp(app: OkuyukiApplication, requestPhotos: () -> Unit, toggleHost: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<IndexedPhoto?>(null) }
    val photos by app.library.photos.collectAsStateWithLifecycle(emptyList())
    val status by app.library.status.collectAsStateWithLifecycle()
    val host by HostRuntime.state.collectAsStateWithLifecycle()
    val pending by HostRuntime.authority.pending.collectAsStateWithLifecycle()
    val peers by app.database.dao().observePeers().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { NavigationBar { listOf("ライブラリ", "Quest", "設定").forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("▦", "◎", "⚙")[index], fontSize = 24.sp) }, label = { Text(label) })
            } } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tab == 0) "Okuyuki" else if (tab == 1) "Quest" else "設定", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    Text(if (host.clients > 0) "● 接続中" else if (host.running) "● 待機中" else "オフ",
                        color = if (host.clients > 0) Color(0xFF12805A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { tab = 1 })
                }
                if (host.error != null) Text(host.error!!, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
                pending.forEach { request ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${request.name} と接続", style = MaterialTheme.typography.titleMedium)
                            Text(request.code, style = MaterialTheme.typography.displaySmall)
                            Text("Questに同じ番号が表示されていることを確認してください。")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { scope.launch {
                                    HostRuntime.authority.prepareApproval(request.requestId)?.let { peer ->
                                        app.database.dao().savePeer(PairedPeer(peer.clientId, peer.name, peer.tokenHash))
                                        HostRuntime.authority.completeApproval(request.requestId, peer)
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
                            Text("3D写真 · ${photos.size}枚", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { app.library.refresh() }) { Text("更新") }
                        }
                        Text(status, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        if (photos.isEmpty()) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Galaxyで撮った3D写真がここに並びます。写真はギャラリーに置いたまま読み取ります。")
                            Button(onClick = requestPhotos) { Text("写真へのアクセスを許可") }
                        }
                        else LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            photos.groupBy { date(it.takenAt) }.forEach { (day, group) ->
                                item(span = { GridItemSpan(3) }) { Text("$day · ${group.size}枚", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                                items(group, key = { it.id }) { photo ->
                                    Box(Modifier.aspectRatio(1f).clickable { detail = photo }) {
                                        PhoneThumbnail(app, photo, Modifier.fillMaxSize())
                                        if (host.viewingId == photo.id.toString()) Surface(Modifier.align(Alignment.BottomStart).padding(4.dp), color = MaterialTheme.colorScheme.primary) {
                                            Text("Questで表示中", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Questとつなぐ", style = MaterialTheme.typography.titleLarge); Text("同じWi-FiにいるQuestを待ちます") }
                            Switch(checked = host.running, onCheckedChange = toggleHost, enabled = app.library.hasPermission())
                        }
                        if (!app.library.hasPermission()) Button(onClick = requestPhotos) { Text("先に写真へのアクセスを許可") }
                        Text(if (host.clients > 0) "Quest 接続中 · 写真をQuestで選んでください" else if (host.running) "QuestでOkuyukiを開き、スマホを選んでください" else "オンにするとQuestから写真を見られます。")
                        if (host.addresses.isNotEmpty()) {
                            Text("自動で見つからない場合", style = MaterialTheme.typography.titleMedium)
                            Text("Questで以下のアドレスを入力してください。")
                            host.addresses.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
                        }
                        Text("見た写真はQuestに一時的に置かれ、終了時に消えます。クラウドは経由しません。")
                        Text("接続先がない状態が10分続くと、待機を自動でオフにします。", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Okuyuki ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                        Text("テーマは端末の設定に合わせます。")
                        Text("ペアリング済みのQuest", style = MaterialTheme.typography.titleMedium)
                        if (peers.isEmpty()) Text("まだ接続したQuestはありません")
                        peers.forEach { peer -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(peer.name, Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { app.database.dao().deletePeer(peer.clientId) } }) { Text("解除") }
                        } }
                        OutlinedButton(onClick = requestPhotos) { Text("写真へのアクセスを変更") }
                        Text("アルファ版 · 実機での検証は未完了です。信頼できる自宅のWi-Fiまたはスマホのテザリングで使用してください。")
                    }
                }
            }
        }
        detail?.let { photo -> AlertDialog(onDismissRequest = { detail = null }, confirmButton = { TextButton(onClick = { detail = null }) { Text("閉じる") } },
            title = { Text(photo.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneThumbnail(app, photo, Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat().coerceAtLeast(1f) / photo.height.coerceAtLeast(1)))
                Text("${photo.width} × ${photo.height} · HEIC · ${date(photo.takenAt)}")
                Text("Questの一覧からこの写真を選ぶと、立体で表示されます。")
            } }) }
    }
}

@Composable private fun PhoneThumbnail(app: OkuyukiApplication, photo: IndexedPhoto, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, photo.id, photo.modified) {
        value = withContext(Dispatchers.IO) { runCatching { app.contentResolver.loadThumbnail(app.library.uri(photo.id), Size(512, 512), null) }.getOrNull() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = photo.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("3D")
    }
}

private fun date(time: Long) = SimpleDateFormat("M月d日（E）", Locale.JAPANESE).format(Date(time))
