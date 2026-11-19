/*
 * The player backdrop from BitChord (https://github.com/kushagrasinghx/BitChord, GPLv3),
 * ArtworkMeshBackdrop.kt: the cover's own colours averaged into a coarse grid, flipped
 * and rotated so it does not read as a reflection, softened, and hung from the bottom
 * edge of the artwork. It stays still and crossfades when the song changes.
 * Ghostly reads its local cover bitmaps instead of BitChord's network artwork.
 */
package com.ghostmikz.ghostly.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

@Immutable
class ArtworkMesh internal constructor(internal val image: ImageBitmap)

/** Meshes already built, by album (one cover per album). */
private val meshCache = object : LinkedHashMap<Long, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<Long, ArtworkMesh>) = size > 64
}

/** The mesh for [art] (the song's cover), built off the main thread and cached per album. */
@Composable
fun rememberArtworkMesh(albumId: Long?, art: Bitmap?): ArtworkMesh? {
    val held = remember { mutableStateOf<ArtworkMesh?>(null) }
    val mesh by produceState(albumId?.let { meshCache[it] } ?: held.value, albumId, art) {
        if (albumId == null || art == null) return@produceState
        meshCache[albumId]?.let { value = it; return@produceState }
        val built = withContext(Dispatchers.Default) { meshOf(art, albumId.hashCode()) }
        if (built != null) { meshCache[albumId] = built; value = built }
    }
    LaunchedEffect(mesh) { if (mesh != null) held.value = mesh }
    return mesh
}

@Composable
fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    /**
     * How far down the surface the artwork's bottom edge sits. Zero for a
     * surface with no artwork over it, which puts the whole mesh on screen. Read
     * in the draw, so a seam that moves costs no composition.
     */
    seam: () -> Dp = { 0.dp },
    /** What the surface is before any artwork has been read. */
    fallback: Color = FallbackBackdrop,
) {
    val reduceAnimation = !motionOn()

    // The mesh on screen, and the one fading in over it. Two states rather than
    // one animated value because what crossfades here is a pair of bitmaps, not
    // a pair of colours: the outgoing one has to stay drawable until the fade
    // has finished with it.
    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(mesh) {
        val next = mesh ?: return@LaunchedEffect
        // A change arriving mid-fade. Whatever was fading in has been on screen
        // for a while and is what the new one should fade *from*; leaving it in
        // [incoming] would restart the fade from the mesh before it, and a clip
        // sending frames faster than the fade runs would never settle at all.
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect

        // Nothing to fade *from* on the first read, and nothing to fade at all
        // when the user has asked for less motion.
        if (current == null || reduceAnimation) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(MESH_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // A flat opaque floor: the player is a sheet with the app's own pages
            // behind it, and every pixel here has to be opaque.
            .background(fallback),
    ) {
        val seamY = seam().toPx().coerceIn(0f, size.height)
        shown?.let { drawMesh(it, seamY, alpha = 1f) }
        // Read here rather than in composition: an Animatable read inside a
        // draw lambda invalidates the drawing and leaves composition out of it.
        incoming?.let { drawMesh(it, seamY, alpha = fade.value) }

        // Enough of a scrim to keep white text off a bright sleeve, and no
        // more. The old backdrop needed a heavier one because it lightened
        // every colour it drew to a fixed band; these are the sleeve's own,
        // and a sleeve that ends dark should leave a dark screen.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.30f),
                ),
            ),
        )
    }
}

/**
 * Paints [mesh] over the whole surface: the grid from [seamY] down, and its
 * first row held from there up. Two draws of one small texture, both stretched
 * by the sampler.
 */
private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val image = mesh.image
    val width = size.width.roundToInt()

    // Above the seam: the sleeve's bottom edge, held. Skipped when the seam is
    // at the very top, where there is no such stretch to fill.
    if (seamY > 0.5f) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seamY.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }

    // From the seam down: the mesh itself, stretched over whatever is left.
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, seamY.roundToInt()),
        dstSize = IntSize(width, (size.height - seamY).roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

private val FallbackBackdrop = Color(0xFF121212)

/** What a cover is scaled to before it is averaged down (a whole multiple of MESH_GRID). */
private const val MESH_PX = 120

/**
 * How many cells across the mesh is.
 *
 * Coarse deliberately — see [ArtworkMesh]. Enough that a cover's layout
 * survives (where the bright part was, how much of the frame it took) and few
 * enough that nothing recognisable does.
 */
private const val MESH_GRID = 6

/**
 * How far the grid is interpolated on the CPU before the GPU stretches it.
 *
 * Bilinear filtering straight off a 6x6 texture is smooth but not *soft*: it is
 * piecewise-linear, and the eye finds the crease at every cell boundary when
 * they are a hundred and eighty pixels apart. Resampling to this first, with a
 * curve that flattens at each control point, leaves creases too small to see
 * before they are magnified. It costs about a thousand lerps, off the main
 * thread, and the control points come through untouched, so the artwork's
 * proportions are exactly what they were.
 */
private const val MESH_TEX = 32

/**
 * The mesh's blur, baked in rather than left to a `RenderEffect`: a full-screen
 * one is paid again on every frame the backdrop changes, and keeps a layer the
 * size of the screen the rest of the time. Three box passes of this radius make
 * a Gaussian of about 1.4 texels, the same softness on a phone as the 32dp live
 * blur this replaces, for the same clamped edges and so the same opaque ones.
 */
private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3

/**
 * How long the backdrop takes to change colour, whether that is a track skip or
 * the next frame of a clip playing over the sleeve.
 *
 * Long enough to read as the screen changing colour rather than cutting, and
 * comfortably inside the interval a clip's frames arrive on — see
 * `MESH_REFRESH_MS` in `NowPlayingScreen`. It has to finish before the next one
 * lands, or every fade is cut off partway and the backdrop never settles where
 * a frame actually put it.
 */
private const val MESH_FADE_MS = 900


/** Averages [art] into the mesh texture, flipped top to bottom and rotated sideways (BitChord's meshOf). */
private fun meshOf(art: Bitmap, seed: Int): ArtworkMesh? {
    val soft = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
    val source = Bitmap.createScaledBitmap(soft, MESH_PX, MESH_PX, true)
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) return null
    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)
    val cells = rows * cols
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)
    val line = IntArray(width)
    for (y in 0 until height) {
        source.getPixels(line, 0, width, 0, y, width, 1)
        val rowBase = ((height - 1 - y) * rows / height) * cols
        for (x in 0 until width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++
        }
    }
    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()).lifted()
    }
    val texels = grid.rotatedBelowSeam(cols, rows, seed).resampled(cols, rows, MESH_TEX)
    val scratch = IntArray(texels.size)
    repeat(MESH_BLUR_PASSES) {
        boxBlurPass(texels, scratch, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = true)
        boxBlurPass(scratch, texels, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = false)
    }
    return ArtworkMesh(Bitmap.createBitmap(texels, MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888).asImageBitmap())
}

private fun boxBlurPass(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val major = if (horizontal) width else height
    val minor = if (horizontal) height else width
    val window = radius * 2 + 1
    repeat(minor) { fixed ->
        var red = 0
        var green = 0
        var blue = 0
        fun pixel(at: Int): Int {
            val position = at.coerceIn(0, major - 1)
            return if (horizontal) source[fixed * width + position]
            else source[position * width + fixed]
        }
        for (offset in -radius..radius) {
            val color = pixel(offset)
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        repeat(major) { moving ->
            val index = if (horizontal) fixed * width + moving else moving * width + fixed
            target[index] = argb(
                (red + window / 2) / window,
                (green + window / 2) / window,
                (blue + window / 2) / window,
            )
            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)
            red += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            green += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

private fun IntArray.rotatedBelowSeam(cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return this
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = this[base + (src + shift) % cols]
        }
    }
    return out
}

/**
 * Smoothly resamples a [cols] x [rows] grid of control points up to a
 * [size] x [size] texture — see [MESH_TEX] for why this happens at all.
 */
private fun IntArray.resampled(cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(this[y0 * cols + x0], this[y0 * cols + x1], wx)
            val bottom = lerpArgb(this[y1 * cols + x0], this[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

/** Flat at both ends, so no cell boundary shows up as a crease once magnified. */
private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

/**
 * The one liberty taken with the artwork's colours.
 *
 * Saturation is nudged because averaging a block of pixels greys it — the mean
 * of a red stripe and the black around it is a dull maroon, and this puts back
 * roughly what the averaging took, not more. Lightness is only floored, off
 * pure black, which is not so much a colour choice as somewhere for the blur to
 * find an edge. Neither touches the *proportions*, which is the whole point of
 * this backdrop: a sleeve that is mostly black stays mostly black.
 */
private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f
