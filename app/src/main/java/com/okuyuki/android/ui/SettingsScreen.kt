package com.okuyuki.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.BuildConfig
import com.okuyuki.android.OkuyukiApplication
import com.okuyuki.android.host.HostRuntime
import com.okuyuki.android.host.TV
import com.okuyuki.android.refreshAuthority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Devices the phone trusts, TVs it can open, photo access and the app's details. */
@Composable
fun SettingsScreen(app: OkuyukiApplication, requestPhotos: () -> Unit, openAppSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val peersFlow = remember { app.database.dao().observePeers() }
    val peers by peersFlow.collectAsStateWithLifecycle(emptyList())
    val tvs by app.tvRemote.tvs.collectAsStateWithLifecycle()
    val scan by app.library.state.collectAsStateWithLifecycle()
    val allowed = app.library.hasPermission()
    var licenses by rememberSaveable { mutableStateOf(false) }
    if (licenses) LicensesDialog(app) { licenses = false }
    Page(8.dp, Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SectionTitle("つないだ機器")
        Panel {
            if (peers.isEmpty()) Quiet("まだありません。Quest やテレビからの接続を許可すると、ここに並びます。")
            peers.sortedBy { it.name }.forEach { peer ->
                ListRow(peer.name, glyph = if (peer.kind == TV) Glyphs.Tv else Glyphs.Headset, subtitle = if (peer.kind == TV) "テレビ" else "Quest") {
                    TextButton(onClick = { scope.launch { HostRuntime.pairingMutex.withLock {
                        app.database.dao().deletePeer(peer.clientId); refreshAuthority(app.database.dao())
                    } } }) { Text("解除") }
                }
            }
        }
        if (peers.isNotEmpty()) Caption("解除した機器が次につなぐときは、もう一度番号の確認が必要です。")
        SectionTitle("Okuyuki TV を開いたテレビ")
        Panel {
            if (tvs.isEmpty()) Quiet("まだありません。「つなぐ」でテレビの Okuyuki TV を開くと、ここに並びます。")
            tvs.forEach { tv ->
                ListRow(tv.name, glyph = Glyphs.Tv, subtitle = tv.address) { TextButton(onClick = { app.tvRemote.forget(tv) }) { Text("削除") } }
            }
        }
        if (tvs.isNotEmpty()) Caption("削除したテレビを次に開くときは、テレビの画面でもう一度「許可」を選びます。")
        SectionTitle("写真")
        Panel {
            ListRow("写真へのアクセス", glyph = Glyphs.Photo, subtitle = when {
                !allowed -> "許可していません"
                scan.limited -> "選んだ写真と動画だけ"
                else -> "すべての写真と動画"
            }, onClick = { if (allowed && !scan.limited) openAppSettings() else requestPhotos() }) {
                Icon(Glyphs.ChevronRight, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle("このアプリについて")
        Panel {
            ListRow("バージョン", subtitle = BuildConfig.VERSION_NAME)
            ListRow("オープンソース", subtitle = "3D 写真の読み取りに libheif と libde265（LGPL-3.0）を使っています",
                onClick = { licenses = true }) {
                Icon(Glyphs.ChevronRight, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Notice("アルファ版です。実機での確認はこれからです。自宅の Wi-Fi やスマホのテザリングなど、信頼できるネットワークで使ってください。")
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Caption(text: String) {
    Text(text, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The LGPL texts of the HEIF libraries the app carries, as the license asks. */
@Composable
private fun LicensesDialog(app: OkuyukiApplication, close: () -> Unit) {
    val paragraphs by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            listOf("libheif" to "1.23.6", "libde265" to "1.1.3").flatMap { (name, version) ->
                listOf("$name $version") + app.assets.open("licenses/$name-COPYING.txt").bufferedReader().use { it.readText() }
                    .split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("オープンソースライセンス", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = close) { Icon(Glyphs.Close, contentDescription = "閉じる") }
                }
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("libheif と libde265 を改変せずに使っています。対応するソースは GitHub のリリースに添えています。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(paragraphs) { paragraph ->
                        if (paragraph.startsWith("libheif ") || paragraph.startsWith("libde265 ")) {
                            Text(paragraph, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        } else Text(paragraph, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
