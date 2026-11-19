package com.ghostmikz.ghostly.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.ghostmikz.ghostly.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ArtCache {
    /**
     * An eighth of what the system gives this app (the usual Android figure):
     * about 32 MB on a 256 MB phone, 16 MB on an old 128 MB one, instead of a
     * fixed 64 MB that a low-memory phone can't spare.
     */
    private var maxBytes = 32 * 1024 * 1024
    private val cache by lazy {
        object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }

    /** Sizes the cache from the phone's memory; called before the first cover is read. */
    fun sizeFor(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        maxBytes = (am.memoryClass * 1024 * 1024 / 8).coerceIn(8 * 1024 * 1024, 64 * 1024 * 1024)
    }
    private val missing = HashSet<String>()

    fun peek(song: Song, px: Int): Bitmap? = cache.get(key(song, px))

    suspend fun load(context: Context, song: Song, px: Int): Bitmap? {
        val key = key(song, px)
        cache.get(key)?.let { return it }
        synchronized(missing) { if (key in missing) return null }
        val bmp = withContext(Dispatchers.IO) { decode(context, song, px) }
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing += key }
        return bmp
    }

    /** A rescan may have found covers that were missing; let them be tried again. */
    fun forgetMissing() = synchronized(missing) { missing.clear() }

    fun clear() {
        cache.evictAll()
        synchronized(missing) { missing.clear() }
    }

    // By album where there is one (one cover, many songs); by song where there
    // isn't, or every song without an album would share the first one's picture.
    private fun key(song: Song, px: Int) = if (song.albumId > 0) "${song.albumId}:$px" else "s${song.id}:$px"

    private fun decode(context: Context, song: Song, px: Int): Bitmap? {
        val cr = context.contentResolver
        // Android 10+: embedded art from the file itself, falls back to folder art.
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { return cr.loadThumbnail(song.uri, Size(px, px), null) }
        }
        return runCatching {
            // Bounds first, then a power-of-two sample size, so a 3000px folder
            // cover is never decoded whole (36 MB) just to draw a 192px row.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(song.albumArtUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            cr.openInputStream(song.albumArtUri)?.use { input ->
                val full = BitmapFactory.decodeStream(input, null, opts) ?: return null
                if (full.width <= px) full
                else Bitmap.createScaledBitmap(full, px, px * full.height / full.width, true).also { if (it !== full) full.recycle() }
            }
        }.getOrNull()
    }
}

@Composable
fun rememberArt(song: Song?, px: Int): Bitmap? {
    val context = LocalContext.current
    var bmp by remember(song?.albumId, px) { mutableStateOf(song?.let { ArtCache.peek(it, px) }) }
    LaunchedEffect(song?.albumId, px) {
        if (song != null && bmp == null) bmp = ArtCache.load(context, song, px)
    }
    return bmp
}

@Composable
fun AlbumArt(song: Song?, modifier: Modifier = Modifier, corner: Dp = 8.dp, px: Int = 192) {
    val bmp = rememberArt(song, px)
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(GhostlyColors.Card),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(com.ghostmikz.ghostly.ui.icons.BitChordIcons.MusicNote, null, Modifier.fillMaxWidth(0.34f).fillMaxHeight(0.34f), tint = GhostlyColors.PlaceholderIcon)
        }
    }
}

data class ArtColors(val top: Color, val bottom: Color, val accent: Color)

val DefaultArtColors = ArtColors(Color(0xFF3A3450), Color(0xFF121018), Color(0xFFF2B8D6))

fun Bitmap.artColors(): ArtColors {
    val p = Palette.from(this).maximumColorCount(16).generate()
    val dominant = p.getDominantColor(0xFF3A3450.toInt())
    val dark = p.getDarkMutedColor(p.getDarkVibrantColor(dominant))
    val accent = p.getLightVibrantColor(p.getVibrantColor(p.getLightMutedColor(0xFFF2B8D6.toInt())))
    return ArtColors(
        top = Color(dominant).darken(0.55f),
        bottom = Color(dark).darken(0.25f),
        accent = Color(accent),
    )
}

private fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)
