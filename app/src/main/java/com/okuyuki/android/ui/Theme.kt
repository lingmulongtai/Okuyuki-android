package com.okuyuki.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand colors, from the launcher icon: ink, a blue square and an ember square overlapping. */
object Brand {
    val Ink = Color(0xFF141821)
    val Blue = Color(0xFF2747E6)
    val Ember = Color(0xFFE8603F)
}

/**
 * Colors Material has no slot for. [panel] is the card color on the page background and [raised] the color of
 * something lifted above a panel, such as the selected part of a toggle.
 */
@Immutable
data class Extras(val panel: Color, val raised: Color, val success: Color, val successContainer: Color, val onSuccessContainer: Color)

private val LightExtras = Extras(panel = Color.White, raised = Color.White, success = Color(0xFF12805A),
    successContainer = Color(0xFFD9F2E6), onSuccessContainer = Color(0xFF00391F))
private val DarkExtras = Extras(panel = Color(0xFF161922), raised = Color(0xFF2A303E), success = Color(0xFF6FD6A6),
    successContainer = Color(0xFF103626), onSuccessContainer = Color(0xFFB9F0D5))

val LocalExtras = staticCompositionLocalOf { LightExtras }

private val LightColors = lightColorScheme(
    primary = Brand.Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE4FF), onPrimaryContainer = Color(0xFF0B1A66),
    secondary = Color(0xFF4B5470), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E7F3), onSecondaryContainer = Color(0xFF1A2033),
    tertiary = Color(0xFFC4472A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDED5), onTertiaryContainer = Color(0xFF3D0F02),
    background = Color(0xFFF4F5F9), onBackground = Brand.Ink,
    surface = Color(0xFFF4F5F9), onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFE4E7EF), onSurfaceVariant = Color(0xFF596074),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFF0F2F7), surfaceContainerHigh = Color(0xFFE9ECF3),
    surfaceContainerHighest = Color(0xFFE2E5EE),
    outline = Color(0xFF8D93A5), outlineVariant = Color(0xFFD7DBE5),
    inverseSurface = Color(0xFF1C2030), inverseOnSurface = Color(0xFFEEF0F6), inversePrimary = Color(0xFFA9B8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9B8FF), onPrimary = Color(0xFF0D1C73),
    primaryContainer = Color(0xFF2A3DB3), onPrimaryContainer = Color(0xFFE0E5FF),
    secondary = Color(0xFFBEC5DB), onSecondary = Color(0xFF283044),
    secondaryContainer = Color(0xFF2C3346), onSecondaryContainer = Color(0xFFDCE1F4),
    tertiary = Color(0xFFFFA68C), onTertiary = Color(0xFF561800),
    tertiaryContainer = Color(0xFF77301A), onTertiaryContainer = Color(0xFFFFDBD1),
    background = Color(0xFF0B0D12), onBackground = Color(0xFFE8EAF1),
    surface = Color(0xFF0B0D12), onSurface = Color(0xFFE8EAF1),
    surfaceVariant = Color(0xFF2A2F3D), onSurfaceVariant = Color(0xFFA9AEBF),
    surfaceContainerLowest = Color(0xFF07080C), surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Color(0xFF161A22), surfaceContainerHigh = Color(0xFF1D212B),
    surfaceContainerHighest = Color(0xFF252A36),
    outline = Color(0xFF737A8C), outlineVariant = Color(0xFF2D3240),
    inverseSurface = Color(0xFFE8EAF1), inverseOnSurface = Color(0xFF1A1D26), inversePrimary = Brand.Blue,
)

private val base = Typography()
private val OkuyukiType = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
)

private val OkuyukiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** Material with the app's colors, type and shapes. Text outside any surface, as in dialogs and sheets, gets the page's text color. */
@Composable
fun OkuyukiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = colors, typography = OkuyukiType, shapes = OkuyukiShapes) {
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}

/** The app's mark: two squares overlapping in depth, the shared part in ink. The launcher icon draws the same shapes. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val ink = if (isSystemInDarkTheme()) Color(0xFF0B0D12) else Brand.Ink
    Canvas(modifier.size(size)) {
        val full = this.size.minDimension
        val side = full * 0.64f
        val r = side * 0.16f
        val start = full - side
        drawRoundRect(Brand.Blue, Offset.Zero, Size(side, side), CornerRadius(r))
        drawRoundRect(Brand.Ember, Offset(start, start), Size(side, side), CornerRadius(r))
        // The overlap keeps the front square's corner at its top left and the back square's at its bottom right.
        drawPath(Path().apply {
            addRoundRect(RoundRect(start, start, side, side, topLeftCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r)))
        }, ink)
    }
}
