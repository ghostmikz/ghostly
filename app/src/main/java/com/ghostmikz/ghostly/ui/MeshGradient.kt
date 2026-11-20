// Ported from BitChord's MeshGradient.kt (https://github.com/kushagrasinghx/BitChord, GPLv3).
// BitChord reads the cover through Coil by URL; Ghostly reads the local cover
// through its ArtCache by a Replay art reference ("songId:albumId").
package com.ghostmikz.ghostly.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.data.stats.artSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ghostly's own colours for when there is no cover to read: a cold night
 * (deep indigo, icy cyan, soft lavender, midnight blue) where BitChord uses
 * purple, pink, blue and peach.
 */
private val FallbackColors = listOf(
    Color(0xFF2E2A8C),
    Color(0xFF5FD4E8),
    Color(0xFFA48BE0),
    Color(0xFF16305E),
)

/** The four mesh colours, wrapped so the backdrop can skip recomposition. */
@Immutable
data class MeshPalette(val colors: List<Color>)

/**
 * The Apple Music "Now Playing" backdrop: four luminous colour blobs sampled
 * from the album art, drawn as soft radial gradients and blurred into a mesh.
 * Colour changes crossfade over ~1.4s instead of snapping.
 *
 * The blobs drift when there is a reason to — [trackKey] changing — and then
 * come to rest.
 */
@Composable
fun MeshGradientBackground(
    palette: MeshPalette,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    driftMillis: Int = 8_000,
    continuous: Boolean = false,
    blurRadius: Dp = 64.dp,
    animated: Boolean = true,
    colorFadeMillis: Int = 1400,
    paused: Boolean = false,
) {
    val reduceAnimation by Prefs.reduceAnimation.state.collectAsState()

    val tuned = (palette.colors.ifEmpty { FallbackColors } + FallbackColors)
        .take(4)
        .map { it.tuned() }

    val colorSpec: AnimationSpec<Color> = if (reduceAnimation || !animated) snap() else tween(colorFadeMillis)
    val animatedColors = tuned.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "meshColor$index").value
    }
    val baseColor by animateColorAsState(tuned.first().dimmed(), colorSpec, label = "meshBase")

    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey, reduceAnimation, continuous, animated, paused) {
        when {
            !animated || reduceAnimation -> phase.snapTo(0f)
            paused -> Unit
            continuous -> while (isActive) {
                phase.animateTo(
                    targetValue = phase.value + (2 * PI).toFloat(),
                    animationSpec = tween(driftMillis * 4, easing = LinearEasing),
                )
            }
            else -> phase.animateTo(
                targetValue = phase.value + DRIFT_RADIANS,
                animationSpec = tween(driftMillis, easing = FastOutSlowInEasing),
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            .background(baseColor)
            .blur(blurRadius),
    ) {
        val anchors = listOf(
            Offset(0.20f, 0.25f),
            Offset(0.80f, 0.20f),
            Offset(0.75f, 0.80f),
            Offset(0.25f, 0.75f),
        )
        val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
        val drift = phase.value

        animatedColors.forEachIndexed { index, color ->
            val anchor = anchors[index]
            val center = Offset(
                x = (anchor.x + 0.16f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                y = (anchor.y + 0.16f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                        val t = i / FADE_CURVE_STOPS.toFloat()
                        t to color.copy(alpha = BLOB_ALPHA * (1f - smootherStep(t)))
                    },
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.10f),
                    Color.Black.copy(alpha = 0.38f),
                ),
            ),
        )
    }
}

private const val BLOB_ALPHA = 0.85f

/**
 * How far the blobs travel in one settle. A shade under half a turn: enough
 * that the backdrop visibly reacts to a track change, short of a full orbit
 * that would land the blobs back where they started.
 */
private const val DRIFT_RADIANS = (PI * 0.45f).toFloat()

/** How many stops a gradient following [smootherStep] is cut into. */
private const val FADE_CURVE_STOPS = 16

private fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * The mesh colours of the local cover behind [ref], or BitChord's stock
 * purple/pink/blue/peach until (or unless) there is one.
 */
@Composable
fun rememberArtworkColors(ref: String?): MeshPalette {
    val context = LocalContext.current
    // Started from the colours already worked out for this cover, if any: a card
    // built again (a Library tab switch, coming back from Home) would otherwise
    // show the fallback for a moment before its own colours came back.
    var palette by remember(ref) { mutableStateOf(ref?.let(ArtworkColorCache::get) ?: MeshPalette(FallbackColors)) }

    LaunchedEffect(ref) {
        if (ref == null || ArtworkColorCache.get(ref) != null) return@LaunchedEffect
        val song = artSong(ref) ?: return@LaunchedEffect
        // Palette quality is fine at thumbnail size, and it's fast.
        val art = ArtCache.load(context, song, 128) ?: return@LaunchedEffect
        val bitmap = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val found = MeshPalette(withContext(Dispatchers.Default) { paletteOf(bitmap) })
        ArtworkColorCache.put(ref, found)
        palette = found
    }
    return palette
}

/** Covers' mesh colours, once worked out, for as long as the app runs. Small: a few colours each. */
internal object ArtworkColorCache {
    private val cache = android.util.LruCache<String, MeshPalette>(64)
    fun get(ref: String): MeshPalette? = cache.get(ref)
    fun put(ref: String, palette: MeshPalette) { cache.put(ref, palette) }
    fun clear() = cache.evictAll()
}

/** [this] with every colour turned [degrees] around the wheel, tone untouched. */
fun MeshPalette.rotated(degrees: Float): MeshPalette {
    if (degrees == 0f) return this
    return MeshPalette(
        colors.map { color ->
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color.toArgb(), hsl)
            hsl[0] = (hsl[0] + degrees) % 360f
            Color(ColorUtils.HSLToColor(hsl))
        },
    )
}

/**
 * Four mesh colours drawn from the artwork. The whole swatch list is read, and
 * any shortfall is derived from the art's own colours rather than borrowed.
 */
private fun paletteOf(bitmap: Bitmap): List<Color> {
    fun swatchesOf(clearFilters: Boolean): List<Color> {
        val builder = Palette.from(bitmap).maximumColorCount(24)
        if (clearFilters) builder.clearFilters()
        return builder.generate().swatches
            .sortedByDescending { it.population }
            .map { Color(it.rgb) }
    }

    val found = swatchesOf(clearFilters = false).ifEmpty {
        // The default filter discards near-black and near-white, which on a
        // monochrome sleeve can be everything there is.
        swatchesOf(clearFilters = true)
    }

    val distinct = found.distinctEnough()
    return when {
        distinct.isEmpty() -> FallbackColors
        distinct.size >= 4 -> distinct.take(4)
        else -> distinct.expandedToFour()
    }
}

/** Drop near-duplicates, so the four blobs don't collapse into one wash. */
private fun List<Color>.distinctEnough(): List<Color> {
    val kept = mutableListOf<Color>()
    forEach { color -> if (kept.none { it.isCloseTo(color) }) kept += color }
    return kept
}

private fun Color.isCloseTo(other: Color): Boolean {
    val a = hsl()
    val b = other.hsl()
    val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
    return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
}

/** Fill the empty slots off the art itself, fanning hue and lightness out. */
private fun List<Color>.expandedToFour(): List<Color> {
    val out = toMutableList()
    var step = 1
    while (out.size < 4) {
        out += this[(out.size - size) % size].shifted(24f * step, 0.12f * step)
        step++
    }
    return out
}

private fun Color.shifted(hue: Float, lightness: Float): Color {
    val hsl = hsl()
    hsl[0] = (hsl[0] + hue) % 360f
    hsl[2] = (hsl[2] + lightness).coerceIn(0.2f, 0.7f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.hsl(): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

/** Boost saturation and clamp lightness so any artwork yields a rich, non-muddy mesh. */
private fun Color.tuned(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.12f
    return Color(ColorUtils.HSLToColor(hsl))
}
