// What BitChord's lyrics panel reads from the rest of its player, answered for
// Ghostly: its settings, the Android halves of BitChord's platform helpers, and
// the online translation and romanization, which a local player doesn't have.
package com.ghostmikz.ghostly.ui.lyrics

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.lyrics.LyricLine
import com.ghostmikz.ghostly.ui.QueueNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The player's side margin (BitChord's PLAYER_GUTTER). */
internal val PLAYER_GUTTER = 28.dp

/** BitChord's PlayerSettings, as far as the lyrics read it. */
internal object PlayerSettings {
    /** A per-device nudge to the lyric timing; Ghostly has no picker for it yet. */
    val lyricsOffsetMs: StateFlow<Int> = MutableStateFlow(0)
    val reduceDynamicBlur: StateFlow<Boolean> get() = Prefs.reduceBlur.state
    /** BitChord blurs the lines away from the sung one; on unless blur is reduced. */
    val lyricsBlur: StateFlow<Boolean> = MutableStateFlow(true)
    val reduceAnimation: StateFlow<Boolean> get() = Prefs.reduceAnimation.state
    /** Blank follows the app's language; translation is unavailable in Ghostly anyway. */
    val translationLanguage: StateFlow<String> = MutableStateFlow("")
}

sealed interface LyricsTranslationResult {
    data class Translated(val lines: List<LyricLine>) : LyricsTranslationResult
    data class SameLanguage(val language: String?) : LyricsTranslationResult
    data object Unavailable : LyricsTranslationResult
}

sealed interface LyricsRomanizationResult {
    data class Romanized(val lines: List<LyricLine>) : LyricsRomanizationResult
    data object AlreadyRomanized : LyricsRomanizationResult
    data object Unavailable : LyricsRomanizationResult
}

/** BitChord's PlayerPlatform.host: messages go to Ghostly's queue notice; translating needs a server Ghostly doesn't use. */
internal object PlayerPlatform {
    object host {
        fun showMessage(message: String) = QueueNotice.show(message)
        @Suppress("UNUSED_PARAMETER")
        suspend fun translateLyrics(trackId: String, lines: List<LyricLine>, targetLanguageTag: String): LyricsTranslationResult =
            LyricsTranslationResult.Unavailable
        @Suppress("UNUSED_PARAMETER")
        suspend fun romanizeLyrics(trackId: String, lines: List<LyricLine>, targetLanguageTag: String): LyricsRomanizationResult =
            LyricsRomanizationResult.Unavailable
    }
}

// RenderEffect, API 31+; `Modifier.blur` is a no-op below it.
internal val renderEffectBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
internal fun appLanguageTag(): String = androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0).toLanguageTag()

internal fun DrawScope.clipShiftedDown(left: Float, top: Float, right: Float, bottom: Float, dy: Float, block: DrawScope.() -> Unit) {
    clipRect(left = left, top = top, right = right, bottom = bottom) { translate(top = dy) { block() } }
}

/** Widened past the player's gutters on both sides, so a row can run to the screen's edges (BitChord). */
internal fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-(placeable.width - width) / 2, 0) }
}

/** BitChord's lyrics_intro_lines: shown while the run-up to the first sung line plays. */
internal val LYRICS_INTRO_LINES = listOf(
    "The beat is landing", "The song is starting", "Warming up", "Setting the mood", "Bass first, words later", "Wait for it",
    "Feel that build", "Just the groove for now", "The hook is on the way", "Let it breathe", "Cue the vocals", "First notes in",
)
