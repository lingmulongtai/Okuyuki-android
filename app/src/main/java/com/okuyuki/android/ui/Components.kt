package com.okuyuki.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A white card on the page; the building block of the connect and settings screens. */
@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = LocalExtras.current.panel, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

/** A small heading above a group of panels. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A glyph in a soft round backdrop, for list rows and empty states. */
@Composable
fun GlyphBubble(glyph: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.secondaryContainer, tint: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Box(modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(glyph, contentDescription = null, modifier = Modifier.size(size * 0.5f), tint = tint)
    }
}

enum class Tone { INFO, ERROR }

/** A short message with an optional action, for states that need attention but not a dialog. [busy] shows a spinner. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.INFO, busy: Boolean = false, action: String? = null,
    onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val (container, content, accent) = when (tone) {
        Tone.INFO -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, colors.primary)
        Tone.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, colors.error)
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
            else Icon(Glyphs.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = accent)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = content)) { Text(action) }
        }
    }
}

/** A centered explanation for an empty screen, with the one thing to do about it. */
@Composable
fun EmptyState(glyph: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GlyphBubble(glyph, size = 72.dp, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) { Text(action) }
        }
    }
}

/** Two or three choices in a rounded track; the chosen one is lifted. Each choice can carry a count. */
@Composable
fun Segments(options: List<Pair<String, Int?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = CircleShape, color = colors.surfaceContainerHigh) {
        Row(Modifier.padding(3.dp)) {
            options.forEachIndexed { index, (label, count) ->
                val on = index == selected
                Surface(onClick = { onSelect(index) }, modifier = Modifier.weight(1f).semantics { role = Role.Tab; this.selected = on },
                    shape = CircleShape, color = if (on) LocalExtras.current.raised else Color.Transparent, shadowElevation = if (on) 1.dp else 0.dp) {
                    Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.onSurface else colors.onSurfaceVariant)
                        if (count != null) {
                            Spacer(Modifier.width(6.dp))
                            Text("$count", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** A short label on top of a photo, such as 3D, HDR or a video's length. */
@Composable
fun MediaBadge(text: String, modifier: Modifier = Modifier, glyph: ImageVector? = null, container: Color = Color.Black.copy(alpha = 0.55f),
    content: Color = Color.White) {
    Row(modifier.background(container, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { Icon(glyph, contentDescription = null, modifier = Modifier.size(11.dp), tint = content); Spacer(Modifier.width(3.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/** A detail of a photo or video in the sheet, such as 4K or HLG. */
@Composable
fun FactChip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(text, modifier.background(if (emphasized) colors.primaryContainer else colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium,
        color = if (emphasized) colors.onPrimaryContainer else colors.onSurfaceVariant)
}

/** A row in a panel: a glyph, a title with an optional line under it, and something at the end such as a button. */
@Composable
fun ListRow(title: String, modifier: Modifier = Modifier, glyph: ImageVector? = null, subtitle: String? = null,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 56.dp).padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { GlyphBubble(glyph); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}
