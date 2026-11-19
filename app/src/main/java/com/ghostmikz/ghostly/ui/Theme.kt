package com.ghostmikz.ghostly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.R

// Colors and type scale follow BitChord's theme (GPLv3). BitChord ships Apple's SF Pro,
// which cannot be redistributed, so Ghostly uses Inter (OFL), which is very close.

object GhostlyColors {
    val Background = Color.Black
    val Surface = Color(0xFF0D0D0F)
    val Card = Color(0xFF1C1C1E)
    val CardHigh = Color(0xFF2C2C2E)
    val Outline = Color(0xFF2C2C2E)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFF8E8E93)
    val Accent = Color(0xFFFA2D48)
    val Player = Color(0xFF141414)
}

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = GhostlyColors.Background,
    onBackground = Color.White,
    surface = GhostlyColors.Surface,
    onSurface = Color.White,
    surfaceVariant = GhostlyColors.Card,
    onSurfaceVariant = GhostlyColors.TextSecondary,
    surfaceContainer = GhostlyColors.Card,
    surfaceContainerHigh = GhostlyColors.Card,
    surfaceContainerHighest = GhostlyColors.CardHigh,
    surfaceContainerLow = GhostlyColors.Surface,
    outline = GhostlyColors.Outline,
    outlineVariant = GhostlyColors.Outline,
    secondaryContainer = GhostlyColors.CardHigh,
    onSecondaryContainer = Color.White,
    error = GhostlyColors.Accent,
)

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

@Composable
fun GhostlyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = GhostlyTypography, content = content)
}
