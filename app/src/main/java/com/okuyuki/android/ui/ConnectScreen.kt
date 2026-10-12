package com.okuyuki.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.OkuyukiApplication
import com.okuyuki.android.host.HostState
import com.okuyuki.android.tv.TvRemote

/** Turns the phone's waiting on and off, opens Okuyuki TV on a TV, and explains how Quest finds the phone. */
@Composable
fun ConnectScreen(app: OkuyukiApplication, host: HostState, requestPhotos: () -> Unit, toggleHost: (Boolean) -> Unit) {
    val allowed = app.library.hasPermission()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HostCard(host, allowed, requestPhotos, toggleHost)
        val error = host.error
        // Shown while waiting too: without discovery the phone still works, through the address below.
        if (error != null) Notice(error, tone = Tone.ERROR)
        TvPanel(app.tvRemote, host)
        QuestPanel(host)
        if (host.running && host.addresses.isNotEmpty()) AddressPanel(host.addresses)
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Icon(Glyphs.Lock, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text("写真はクラウドを通らず、同じ Wi-Fi の中だけで送られます。Quest やテレビに一時的に置かれた写真は、終了すると消えます。" +
                "接続のない状態が 10 分続くと、自動でオフになります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HostCard(host: HostState, allowed: Boolean, requestPhotos: () -> Unit, toggleHost: (Boolean) -> Unit) {
    val extras = LocalExtras.current
    val colors = MaterialTheme.colorScheme
    val tv = host.tvClients > 0
    val quest = host.clients > 0
    val (title, body) = when {
        !allowed -> "写真へのアクセスが必要です" to "Quest やテレビに写真を見せるには、先にこのスマホの写真へのアクセスを許可してください。"
        tv && quest -> "Quest とテレビに接続中" to "Quest では Quest の中で、テレビではリモコンかこのスマホで写真を選べます。"
        tv -> "テレビに接続中" to "写真はテレビのリモコンでも、このスマホのライブラリからでも選べます。"
        quest -> "Quest に接続中" to "写真は Quest の中で選んでください。"
        host.running && host.port == 0 -> "準備しています…" to "Wi-Fi で待ち受けを始めています。"
        host.running -> "待機中" to "Quest やテレビで Okuyuki を開き、このスマホを選んでください。"
        else -> "オフ" to "オンにすると、同じ Wi-Fi の Quest やテレビから、このスマホの写真と動画を見られます。"
    }
    val (container, content) = when {
        tv || quest -> extras.successContainer to extras.onSuccessContainer
        host.running -> colors.primaryContainer to colors.onPrimaryContainer
        else -> extras.panel to colors.onSurface
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container, contentColor = content) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(host.running, enabled = allowed, role = Role.Switch, onValueChange = toggleHost),
                verticalAlignment = Alignment.CenterVertically) {
                GlyphBubble(if (tv || quest) Glyphs.Check else if (host.running) Glyphs.Wifi else Glyphs.Power, size = 44.dp,
                    container = content.copy(alpha = 0.1f), tint = content)
                Spacer(Modifier.width(14.dp))
                Text("接続を待つ", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = host.running, onCheckedChange = null, enabled = allowed)
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f))
            if (!allowed) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = requestPhotos) { Text("写真へのアクセスを許可") }
            }
        }
    }
}

@Composable
private fun TvPanel(remote: TvRemote, host: HostState) {
    val tvs by remote.tvs.collectAsStateWithLifecycle()
    val searching by remote.searching.collectAsStateWithLifecycle()
    val busy by remote.busy.collectAsStateWithLifecycle()
    val message by remote.message.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val ready = host.running && host.port > 0
    // Looks for TVs once the phone is ready, so the list is filled when the person gets here.
    LaunchedEffect(ready) { if (ready && remote.tvs.value.isEmpty()) remote.search() }
    Panel {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("テレビで見る", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = remote::search, enabled = !searching) {
                if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Glyphs.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (searching) "探しています" else "テレビを探す")
            }
        }
        Text(if (ready) "LG のテレビで Okuyuki TV を開き、このスマホにつなぎます。初めてのテレビでは、テレビの画面で「許可」を選んでください。"
            else "「接続を待つ」をオンにすると、ここからテレビの Okuyuki TV を開けます。",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        tvs.forEach { tv ->
            ListRow(tv.name, glyph = Glyphs.Tv, subtitle = tv.address) {
                Button(onClick = { remote.open(tv) }, enabled = ready && !busy) { Text("開く") }
            }
        }
        if (tvs.isEmpty() && !searching) Text("見つかったテレビがここに並びます。テレビの電源を入れ、スマホと同じ Wi-Fi につないでください。",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Notice(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), busy = busy) }
        TextButton(onClick = { manual = !manual }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("IP アドレスで開く")
            Spacer(Modifier.width(4.dp))
            Icon(Glyphs.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp).rotate(if (manual) 180f else 0f))
        }
        AnimatedVisibility(manual) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(address, { address = it }, Modifier.weight(1f), label = { Text("テレビの IP アドレス") },
                    placeholder = { Text("192.168.1.20") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (ready && !busy && address.isNotBlank()) remote.openAddress(address) }))
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { remote.openAddress(address) }, enabled = ready && !busy && address.isNotBlank()) { Text("開く") }
            }
        }
    }
}

@Composable
private fun QuestPanel(host: HostState) {
    Panel {
        Text("Quest で見る", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphBubble(Glyphs.Headset)
            Spacer(Modifier.width(14.dp))
            Text(when {
                host.clients > 0 -> "Quest に接続しています。写真は Quest の中で選んでください。"
                host.running -> "Quest で Okuyuki を開くと、近くのスマホとしてこのスマホが出てきます。選ぶとつながります。"
                else -> "「接続を待つ」をオンにすると、Quest からこのスマホが見えるようになります。"
            }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AddressPanel(addresses: List<String>) {
    Panel {
        Text("自動で見つからないとき", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), style = MaterialTheme.typography.titleMedium)
        Text("Quest やテレビの Okuyuki で、このアドレスを入力してください。", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                addresses.forEach { Text(it, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
