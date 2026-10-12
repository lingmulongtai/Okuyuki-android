package com.okuyuki.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
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
    val tv = request.kind == TV
    val device = if (tv) "テレビ" else "Quest"
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = LocalExtras.current.panel, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphBubble(if (tv) Glyphs.Tv else Glyphs.Headset, size = 56.dp, container = MaterialTheme.colorScheme.primaryContainer,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(16.dp))
                Text(named(device, "がつなごうとしています"), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
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
                Spacer(Modifier.height(20.dp))
                Text(named(device, "に同じ番号が出ていれば許可してください。違う番号なら拒否してください。"), style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = approve, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("許可する") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = reject, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("拒否する") }
                if (others > 0) {
                    Spacer(Modifier.height(14.dp))
                    Text("ほかに $others 件の確認があります", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
