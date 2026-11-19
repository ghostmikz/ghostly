/*
 * Adapted from BitChord's LiquidGlass.kt (https://github.com/kushagrasinghx/BitChord, GPLv3),
 * which itself adapts EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass (GPLv3).
 * The glass rendering is Kyant0/backdrop (Apache-2.0), vendored in ui/backdrop.
 */
package com.ghostmikz.ghostly.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.ui.backdrop.Backdrop
import com.ghostmikz.ghostly.ui.backdrop.backdrops.LayerBackdrop
import com.ghostmikz.ghostly.ui.backdrop.drawBackdrop
import com.ghostmikz.ghostly.ui.backdrop.effects.blur
import com.ghostmikz.ghostly.ui.backdrop.effects.colorControls
import com.ghostmikz.ghostly.ui.backdrop.effects.lens
import com.ghostmikz.ghostly.ui.backdrop.highlight.Highlight
import com.ghostmikz.ghostly.ui.backdrop.shadow.Shadow

/** Whether the Liquid Glass nav bar is on (Settings > Appearance > Liquid Glass). */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/** The recorded page that glass and frosted surfaces sample. Null when nothing is recorded. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Where a [liquidGlass] surface also records the glass it draws, so the
 * selection pill's lens above it can refract that surface.
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

/** The blur pipeline needs RenderEffect on a RenderNode: Android 12 (API 31). */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

// BitChord's (Apple-matched) values.
private const val VIBRANCY = 1f
private const val BLUR_RADIUS_DP = 8f
private const val LENS_HEIGHT = 0.5f
private const val LENS_AMOUNT = 0.5f
private const val LENS_MAX_DP = 48f
private const val SURFACE_OPACITY = 0.4f
private const val GLASS_RESOLUTION_SCALE = 0.33f
private const val FROST_BLUR_RADIUS_DP = 16f
private const val FROST_RESOLUTION_SCALE = 0.25f

internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/** Icon and label colour on glass: pure black or white, whichever the theme is not. */
@Composable
fun glassContentColor(): Color = if (LocalPalette.current.isDark) Color.White else Color.Black

/** The selected tab's pill on glass: a white lift in both themes. */
@Composable
fun glassIndicatorColor(): Color =
    if (LocalPalette.current.isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.5f)

/**
 * Liquid glass: the recorded page, saturated, blurred and bent by a lens, with a
 * theme tint, rim and shadow. Solid under "Reduce dynamic blur"; frosted without the
 * lens under "Better Performance for Glass". Unchanged on phones older than Android 12.
 */
@Composable
fun Modifier.liquidGlass(shape: CornerBasedShape, surfaceTint: Color? = null): Modifier {
    if (!isGlassSupported()) return this
    val reduceBlur by Prefs.reduceBlur.state.collectAsState()
    if (reduceBlur) {
        return background(GhostlyColors.Surface, shape).border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val backdrop = LocalAppBackdrop.current
        ?: return background(GhostlyColors.Surface, shape).border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    val performance by Prefs.glassPerformance.state.collectAsState()
    val exported = LocalGlassExport.current
    val density = LocalDensity.current
    val scale = if (performance) FROST_RESOLUTION_SCALE else GLASS_RESOLUTION_SCALE
    val blurDp = if (performance) FROST_BLUR_RADIUS_DP else BLUR_RADIUS_DP
    val blurPx = with(density) { blurDp.dp.toPx() } * scale
    val lensHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * scale
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * scale
    val themeTint = if (LocalPalette.current.isDark) Color(0xFF121212) else Color(0xFFFAFAFA)
    val tint = surfaceTint ?: themeTint.copy(alpha = SURFACE_OPACITY)

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + 0.5f * VIBRANCY)
            blur(blurPx)
            if (!performance && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = { drawRect(color = tint, size = size) },
        exportedBackdrop = exported,
        backdropScale = scale,
    )
}

/**
 * The default (non-glass) bar's frosted look, standing in for BitChord's Haze
 * "regular" material: a soft blur of the page under a mostly opaque theme fill.
 * Solid under "Reduce dynamic blur" and on phones older than Android 12.
 */
@Composable
fun Modifier.frosted(shape: CornerBasedShape, fill: Color): Modifier {
    val reduceBlur by Prefs.reduceBlur.state.collectAsState()
    val backdrop = LocalAppBackdrop.current
    if (!isGlassSupported() || reduceBlur || backdrop == null) return background(fill, shape)
    val density = LocalDensity.current
    val blurPx = with(density) { 24.dp.toPx() } * FROST_RESOLUTION_SCALE
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(blurPx) },
        highlight = null,
        shadow = null,
        onDrawSurface = { drawRect(color = fill.copy(alpha = 0.72f), size = size) },
        backdropScale = FROST_RESOLUTION_SCALE,
    )
}
