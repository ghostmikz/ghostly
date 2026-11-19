/*
 * The Replay's artwork plumbing. BitChord (https://github.com/kushagrasinghx/BitChord, GPLv3)
 * loads covers by URL with Coil and builds its mesh from a palette; Ghostly reads the local
 * covers through its own ArtCache and builds the player's ArtworkMesh from them. The hue
 * turn is BitChord's MeshPalette.rotated, applied to the cover before the mesh is built.
 */
package com.ghostmikz.ghostly.ui.replay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.ColorUtils
import com.ghostmikz.ghostly.data.stats.artSong
import com.ghostmikz.ghostly.ui.ArtCache
import com.ghostmikz.ghostly.ui.ArtworkMesh
import com.ghostmikz.ghostly.ui.rememberArtworkMesh

/** A cover being read: the bitmap once there is one, and whether the read has finished. */
@Immutable
internal class Cover(val bitmap: Bitmap?, val settled: Boolean)

/** The local cover behind [ref] (see [com.ghostmikz.ghostly.data.stats.artRef]), at [px]. */
@Composable
internal fun rememberCover(ref: String?, px: Int): Cover {
    val context = LocalContext.current
    val song = remember(ref) { artSong(ref) }
    val initial = remember(ref, px) {
        val cached = song?.let { ArtCache.peek(it, px) }
        Cover(cached, song == null || cached != null)
    }
    val cover by produceState(initial, ref, px) {
        if (song == null || value.bitmap != null) return@produceState
        value = Cover(ArtCache.load(context, song, px), true)
    }
    return cover
}

/**
 * A cover, cropped to [shape] — or, where the file has none, the lettered
 * stand-in the lists use, so a missing sleeve is a deliberate-looking tile
 * rather than a hole. Nothing at all while the cover is still being read.
 */
@Composable
internal fun ReplayCover(
    ref: String?,
    fallbackText: String,
    size: Dp,
    shape: Shape,
    px: Int,
    modifier: Modifier = Modifier,
) {
    val cover = rememberCover(ref, px)
    val image = cover.bitmap
    when {
        image != null -> Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
        !cover.settled -> Box(modifier.size(size))
        fallbackText.isNotBlank() -> Box(modifier) { InitialTile(fallbackText, size, shape) }
        else -> Box(modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.10f)))
    }
}

/**
 * The player's mesh, built from the cover behind [ref] with every colour turned
 * [hue] degrees around the wheel — see [storyHue].
 */
@Composable
internal fun rememberReplayMesh(ref: String?, hue: Float = 0f): ArtworkMesh? {
    val cover = rememberCover(ref, MESH_ART_PX)
    val albumId = remember(ref) { artSong(ref)?.albumId }
    val source = remember(cover.bitmap, hue) {
        cover.bitmap?.let { if (hue == 0f) it else it.hueRotated(hue) }
    }
    // A turned mesh is a different mesh from the player's, so it is cached
    // under a key no real album id can have.
    val key = albumId?.let { if (hue == 0f) it else Long.MIN_VALUE / 2 + it * 400 + hue.toLong() }
    return rememberArtworkMesh(key, source)
}

/** [this] with every colour turned [degrees] around the wheel, tone untouched. */
internal fun Bitmap.hueRotated(degrees: Float): Bitmap {
    val soft = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val small = Bitmap.createScaledBitmap(soft, HUE_PX, HUE_PX, true)
    val pixels = IntArray(HUE_PX * HUE_PX)
    small.getPixels(pixels, 0, HUE_PX, 0, 0, HUE_PX, HUE_PX)
    val hsl = FloatArray(3)
    for (i in pixels.indices) {
        ColorUtils.colorToHSL(pixels[i], hsl)
        hsl[0] = (hsl[0] + degrees) % 360f
        pixels[i] = ColorUtils.HSLToColor(hsl)
    }
    return Bitmap.createBitmap(pixels, HUE_PX, HUE_PX, Bitmap.Config.ARGB_8888)
}

/** The covers a mesh is read from; the mesh only ever averages them down. */
private const val MESH_ART_PX = 192
private const val HUE_PX = 48

/** Rows, story cards and the poster, as BitChord sizes them. */
internal const val ROW_ART_PX = 192
internal const val CARD_ART_PX = 400
internal const val HEADER_ART_PX = 600
