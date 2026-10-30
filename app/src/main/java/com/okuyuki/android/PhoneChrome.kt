package com.okuyuki.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PhoneHeader(title: String, status: String, onStatusClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), Color.Transparent)))
                .statusBarsPadding().padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable(onClick = onStatusClick)) {
                Text(status, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun PhoneNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(stringResource(R.string.library), stringResource(R.string.connect_tab), stringResource(R.string.settings))
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, shadowElevation = 8.dp) {
            Row(Modifier.selectableGroup().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { index, label ->
                    val active = index == selected
                    Row(
                        Modifier.semantics(mergeDescendants = true) { contentDescription = label }
                            .clip(CircleShape)
                            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape)
                            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NavigationGlyph(index, if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (active) Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationGlyph(index: Int, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val stroke = 1.8.dp.toPx()
        when (index) {
            0 -> {
                drawRoundRect(tint, Offset(w * .09f, w * .15f), Size(w * .82f, w * .70f), CornerRadius(w * .12f), style = Stroke(stroke))
                drawCircle(tint, w * .08f, Offset(w * .35f, w * .36f))
                drawLine(tint, Offset(w * .22f, w * .72f), Offset(w * .46f, w * .48f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(w * .46f, w * .48f), Offset(w * .78f, w * .72f), stroke, StrokeCap.Round)
            }
            1 -> {
                drawRoundRect(tint, Offset(w * .08f, w * .28f), Size(w * .84f, w * .45f), CornerRadius(w * .20f), style = Stroke(stroke))
                drawCircle(tint, w * .08f, Offset(w * .35f, w * .5f))
                drawCircle(tint, w * .08f, Offset(w * .65f, w * .5f))
            }
            else -> {
                drawCircle(tint, w * .36f, center = Offset(w * .5f, w * .5f), style = Stroke(stroke))
                drawCircle(tint, w * .12f, center = Offset(w * .5f, w * .5f), style = Stroke(stroke))
                for (n in 0 until 8) {
                    val angle = n * Math.PI / 4
                    val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat()
                    drawLine(tint, Offset(w * (.5f + .36f * dx), w * (.5f + .36f * dy)), Offset(w * (.5f + .46f * dx), w * (.5f + .46f * dy)), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
