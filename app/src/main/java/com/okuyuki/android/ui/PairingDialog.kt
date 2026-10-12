package com.okuyuki.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.okuyuki.android.host.PendingPeer
import com.okuyuki.android.host.TV

/**
 * A Quest or TV asks to connect. The same four digits show on the device, so a stranger on the Wi-Fi can't pass as it.
 * The dialog stays until the person decides; [others] counts requests waiting behind this one.
 */
@Composable
fun PairingDialog(request: PendingPeer, others: Int, approve: () -> Unit, reject: () -> Unit) {
    val device = if (request.kind == TV) "テレビ" else "Quest"
    // A phone held sideways is too short for one column, so the code and the buttons sit side by side there.
    val window = windowSize()
    val sideways = window.width > window.height && window.height < 480.dp
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = if (sideways) 680.dp else 420.dp), shape = MaterialTheme.shapes.extraLarge,
            color = LocalExtras.current.panel, contentColor = MaterialTheme.colorScheme.onSurface) {
            // Scrolls when the text is set very large, so both buttons stay within reach.
            val content = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp)
            if (sideways) Row(content, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Request(request, device) }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Decision(device, others, approve, reject) }
            } else Column(content, horizontalAlignment = Alignment.CenterHorizontally) {
                Request(request, device)
                Spacer(Modifier.height(20.dp))
                Decision(device, others, approve, reject)
            }
        }
    }
}

/** Who is asking, and the code it shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Request(request: PendingPeer, device: String) {
    GlyphBubble(if (request.kind == TV) Glyphs.Tv else Glyphs.Headset, size = 56.dp, container = MaterialTheme.colorScheme.primaryContainer,
        tint = MaterialTheme.colorScheme.onPrimaryContainer)
    Spacer(Modifier.height(16.dp))
    // Two pieces, so a narrow dialog or large text breaks the title after "が" rather than inside the verb.
    FlowRow(Modifier.semantics(mergeDescendants = true) { heading() }, horizontalArrangement = Arrangement.Center) {
        Text(named(device, "が"), style = MaterialTheme.typography.titleLarge)
        Text("つなごうとしています", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(4.dp))
    Text(request.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(22.dp))
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "接続番号 ${request.code.toList().joinToString(" ")}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        request.code.forEach { digit ->
            Box(Modifier.size(width = 54.dp, height = 66.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) {
                Text("$digit", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** What to compare, and the two answers. */
@Composable
private fun Decision(device: String, others: Int, approve: () -> Unit, reject: () -> Unit) {
    Text(named(device, "に同じ番号が出ていれば許可してください。\n違う番号なら拒否してください。"), style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Button(onClick = approve, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("許可する") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = reject, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("拒否する") }
    if (others > 0) {
        Spacer(Modifier.height(14.dp))
        Text("ほかに $others 件の確認があります", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
