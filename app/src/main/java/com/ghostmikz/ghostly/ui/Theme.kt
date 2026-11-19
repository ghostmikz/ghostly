package com.ghostmikz.ghostly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.R
import com.ghostmikz.ghostly.data.Prefs

// Colors and type scale follow BitChord's theme (GPLv3). BitChord ships Apple's SF Pro,
// which cannot be redistributed, so Ghostly uses Inter (OFL), which is very close.

/** One full set of colors. Ghostly has a dark and a light one, like BitChord. */
data class Palette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val card: Color,
    val cardHigh: Color,
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val divider: Color,
    val bar: Color,
    val miniBar: Color,
    val barBorder: Color,
    val selected: Color,
    val placeholderIcon: Color,
    val switchOff: Color,
)

val DarkPalette = Palette(
    isDark = true,
    background = Color.Black,
    surface = Color(0xFF0D0D0F),
    card = Color(0xFF1C1C1E),
    cardHigh = Color(0xFF2C2C2E),
    outline = Color(0xFF2C2C2E),
    text = Color.White,
    textSecondary = Color(0xFF8E8E93),
    divider = Color(0xFF242426),
    bar = Color(0xF0121214),
    miniBar = Color(0xF01A1A1C),
    barBorder = Color(0xFF2A2A2D),
    selected = Color(0xFF5A5A5E),
    placeholderIcon = Color(0xFF5A5A5E),
    switchOff = Color(0xFF39393D),
)

val LightPalette = Palette(
    isDark = false,
    background = Color.White,
    surface = Color(0xFFF7F7F9),
    card = Color(0xFFF2F2F7),
    cardHigh = Color(0xFFE5E5EA),
    outline = Color(0xFFE5E5EA),
    text = Color.Black,
    textSecondary = Color(0xFF6E6E73),
    divider = Color(0xFFDCDCE0),
    bar = Color(0xF2F7F7F9),
    miniBar = Color(0xF2F2F2F7),
    barBorder = Color(0xFFE5E5EA),
    selected = Color.White,
    placeholderIcon = Color(0xFFC7C7CC),
    switchOff = Color(0xFFE9E9EA),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Colors for the current theme. Read inside composables. */
object GhostlyColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
    val CardHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.cardHigh
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary
    val Divider: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.divider
    val Bar: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bar
    val MiniBar: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.miniBar
    val BarBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.barBorder
    val Selected: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.selected
    val PlaceholderIcon: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.placeholderIcon
    val SwitchOff: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.switchOff

    // Same in both themes.
    val Accent = Color(0xFFFA2D48)
    val Player = Color(0xFF141414)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.W400),
    Font(R.font.inter_medium, FontWeight.W500),
    Font(R.font.inter_semibold, FontWeight.W600),
    Font(R.font.inter_bold, FontWeight.W700),
    Font(R.font.inter_extrabold, FontWeight.W800),
)

private fun style(weight: Int, size: Int, spacing: Double) =
    TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size.sp, letterSpacing = spacing.sp)

private val GhostlyTypography = Typography(
    displayLarge = style(800, 34, -0.8),
    displayMedium = style(800, 32, -0.8),
    headlineLarge = style(800, 30, -0.7),
    headlineMedium = style(700, 22, -0.4),
    headlineSmall = style(700, 22, -0.4),
    titleLarge = style(700, 20, -0.3),
    titleMedium = style(600, 16, -0.2),
    titleSmall = style(600, 14, -0.1),
    bodyLarge = style(400, 16, 0.0),
    bodyMedium = style(400, 14, 0.0),
    bodySmall = style(400, 12, 0.0),
    labelLarge = style(600, 14, 0.0),
    labelMedium = style(600, 12, 0.0),
    labelSmall = style(600, 11, 0.0),
)

private fun Palette.scheme() = if (isDark) {
    darkColorScheme(
        primary = text, onPrimary = background, background = background, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = card, onSurfaceVariant = textSecondary,
        surfaceContainer = card, surfaceContainerHigh = card, surfaceContainerHighest = cardHigh, surfaceContainerLow = surface,
        outline = outline, outlineVariant = outline, secondaryContainer = cardHigh, onSecondaryContainer = text,
        error = GhostlyColors.Accent,
    )
} else {
    lightColorScheme(
        primary = text, onPrimary = background, background = background, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = card, onSurfaceVariant = textSecondary,
        surfaceContainer = card, surfaceContainerHigh = card, surfaceContainerHighest = cardHigh, surfaceContainerLow = surface,
        outline = outline, outlineVariant = outline, secondaryContainer = cardHigh, onSecondaryContainer = text,
        error = GhostlyColors.Accent,
    )
}

/** Is the app dark right now? Follows Settings > Appearance > Theme (System / Light / Dark). */
@Composable
fun isAppDark(): Boolean {
    val mode by Prefs.theme.collectAsState()
    return when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun GhostlyTheme(dark: Boolean = isAppDark(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme(), typography = GhostlyTypography, content = content)
    }
}
