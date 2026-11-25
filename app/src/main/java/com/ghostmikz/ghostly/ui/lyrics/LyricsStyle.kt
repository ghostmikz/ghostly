package com.ghostmikz.ghostly.ui.lyrics

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.lyrics.LyricLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first


/** Returns true if any character in [text] belongs to a BMP RTL script. */
internal fun isRtl(text: String): Boolean {
    var rtlCount = 0
    var ltrCount = 0
    for (ch in text) {
        val code = ch.code
        // Hebrew presentation forms
        if (code in 0xFB1D..0xFB4F) return true
        val dir = Character.getDirectionality(ch)
        when (dir) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtlCount++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltrCount++
        }
    }
    return rtlCount > ltrCount
}

/** Returns the text direction for [text]: LTR by default, RTL when detected. */
internal fun textDirectionFor(text: String): androidx.compose.ui.text.style.TextDirection {
    return if (isRtl(text)) androidx.compose.ui.text.style.TextDirection.Rtl
    else androidx.compose.ui.text.style.TextDirection.Ltr
}

/** Whether [text] carries Arabic-script letters (Persian, Arabic, Urdu…). */
internal fun usesArabicScript(text: String): Boolean = text.any { ch ->
    val code = ch.code
    code in 0x0600..0x06FF || code in 0x0750..0x077F ||
        code in 0x08A0..0x08FF || code in 0xFB50..0xFDFF ||
        code in 0xFE70..0xFEFF
}

/** Whether this text should align to the right end of its box. */
internal fun alignsRight(text: String, alignEnd: Boolean, laneLocked: Boolean): Boolean =
    if (laneLocked) alignEnd else isRtl(text)

/** Compute per-voice text style with absolute physical alignment based on the voice's own text direction. */
internal fun voiceStyle(
    base: TextStyle,
    text: String,
    alignEnd: Boolean,
    laneLocked: Boolean,
): TextStyle = base.copy(
    textAlign = if (alignsRight(text, alignEnd, laneLocked)) TextAlign.Right else TextAlign.Left,
    textDirection = if (isRtl(text)) androidx.compose.ui.text.style.TextDirection.Rtl else androidx.compose.ui.text.style.TextDirection.Ltr,
)

/**
 * How far back the part of the playing line that hasn't been sung yet is held.
 *
 * The strip above the scrubber gets less of a gap than the full panel: it is
 * one line of small type with nothing around it to compare against, and taking
 * it as far down as the panel does left the words ahead of the highlight hard
 * to read at a glance.
 */
internal const val UNSUNG_ALPHA = 0.45f
internal const val UNSUNG_ALPHA_STRIP = 0.55f

/**
 * The bloom behind the line being sung, at its very strongest.
 *
 * Kept well under half strength: the halo is drawn from the same white as the
 * text, so at full alpha it stops reading as light and starts reading as a
 * second, badly printed copy of the words. What is actually drawn is this
 * scaled by each letter's own bloom, so only a properly carried note ever sees
 * the whole of it.
 *
 * The bloom used to be a band of light trailing the sweep's leading edge across
 * every line, which is a lamp being dragged along under the words: a shape that
 * belongs to the highlight rather than to the singing, present on patter and
 * held notes alike. It is now attached to the letters of the held words
 * themselves — see [LyricLine.growingWords][com.music.bitchord.data.lyrics.LyricLine.growingWords]
 * — so a line of quick syllables has no glow at all and a carried note lights
 * up letter by letter, which is where the light was always meant to come from.
 */
internal const val GLOW_ALPHA = 0.62f

/**
 * How far the bloom spreads off a letter. Tight, because it is a letter's worth
 * of light now rather than a word's: a wide radius on something this small is
 * a smudge behind the text instead of a glow coming off it.
 */
internal val GLOW_RADIUS = 6.dp

/**
 * Room reserved inside each copy of a line for the halo to spread into.
 *
 * A blur is computed on its layer's own bitmap, so a halo with nowhere to go
 * inside those bounds is a halo with a hard edge — which is what cropped the
 * bloom to the line's box. Every copy carries the same inset so they still lay
 * out identically, and the list gives the width back by taking it off its own
 * padding and row spacing.
 */
internal val GLOW_ROOM = 10.dp

/**
 * How the answering vocal is drawn: smaller than the lead and a shade behind
 * it, the way Apple Music hangs a backing line under the one it answers.
 *
 * Small enough to be read as a second voice at a glance and no smaller —
 * these are the words of the song, not a caption.
 */
internal val BACKING_FONT_SIZE = 23.sp
internal val BACKING_LINE_HEIGHT = 29.sp
internal const val BACKING_ALPHA = 0.72f

/**
 * How the answering voice comes and goes; see [revealBacking]. am-lyrics opens
 * its backing line over about 400 ms and folds it away over 450.
 */
internal const val BACKING_OPEN_MS = 400

/** Where a lyric line is in its own animation; see [SweptLyricLine]. */
internal const val PHASE_BEFORE = 0
internal const val PHASE_MOVING = 1
internal const val PHASE_AFTER = 2

/** An instrumental break that is playing, so its fill is read off the clock. */
internal const val GAP_RUNNING = -1f

/**
 * How far ahead of its first word the answering voice starts to open: far
 * enough that its words have faded in by the time the sweep reaches them.
 */
internal const val BACKING_LEAD_MS = 250L
internal const val BACKING_CLOSE_MS = 450
internal val BACKING_RISE = 6.dp
internal const val BACKING_REST_SCALE = 0.94f

/** The words reach full opacity when the room is this far open (1 / 1.6). */
internal const val BACKING_FADE_LEAD = 1.6f

/**
 * The romanization or translation hung under each line — caption-sized, the
 * way Apple Music prints pronunciation under the lyric, and tucked up into the
 * lead's glow inset so the two read as one line rather than two rows.
 */
internal val SUB_LYRIC_FONT_SIZE = 20.sp
internal val SUB_LYRIC_LINE_HEIGHT = 25.sp
internal val SUB_BACKING_FONT_SIZE = 16.sp
internal val SUB_BACKING_LINE_HEIGHT = 21.sp
internal const val SUB_LYRIC_ALPHA = 0.85f
internal val SUB_LYRIC_TUCK = 6.dp
internal const val SUB_LYRIC_OPEN_MS = 460
internal const val SUB_LYRIC_CLOSE_MS = 260

/**
 * How far the sweep's leading edge fades out instead of ending on a cut.
 *
 * A hard boundary is legible as a boundary: the eye reads a bar travelling
 * across the words rather than the words themselves lighting up as they are
 * sung. Feathering it over roughly a character and a half is what turns the
 * cut back into a wavefront.
 */
internal val WIPE_FEATHER = 30.dp

/**
 * How far the word being sung lifts off the line.
 *
 * Two pixels, and it has to be about two: enough that the eye catches the
 * words moving under the sweep, little enough that nothing appears to come
 * loose from the line it belongs to.
 */
internal val WORD_RISE = 2.dp

/**
 * How much further up a row is opened when it holds a word being animated
 * letter by letter, in multiples of [WORD_RISE].
 *
 * A letter that swells has to be given the room above the line it grew out of
 * or the top of it is shaved off by the band it is drawn in. Covers the lift
 * and the swell together, which is why it is well over the one rise an
 * ordinary word needs.
 */
internal const val GROW_HEADROOM = 3f

/**
 * The lane kept clear on the far side of a duet line.
 *
 * Only ever applied to a song that actually has two voices laid out. Without
 * it a long right-hand line reaches all the way back across the panel and the
 * split stops reading as a split at all; with it, each voice keeps its own
 * column even when only one of them is singing.
 */
internal val DUET_LANE = 44.dp

/**
 * How tall a break stands while it is playing.
 *
 * Nothing when it is not: an interlude that held its row open all through the
 * verse either side of it left a hole in the list, and the panel scrolled past
 * empty space to get to the next thing sung. It opens as the singing stops and
 * closes again as it comes back, so the list only carries a break while there
 * is one.
 */
internal val GAP_ROW_HEIGHT = 40.dp
internal val GAP_ROW_SPACING = 16.dp

/**
 * How the stack falls away either side of the line being sung.
 *
 * Indexed by distance from it. Far subtler than a linear ramp: the two lines
 * around the playing one stay legible so you can read ahead and behind, and
 * only past that does the panel let go. The last entry stands for everything
 * further out, which is most of the list.
 */
internal val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
internal val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

/**
 * The same falloff for a phone, where the sung line is read from the top and
 * the whole stack lies below it. [LINE_FALLOFF_ALPHA] was set on a centred
 * column with only a few rows either side; down a tall phone column it reads as
 * one flat brightness, so this keeps going for a row longer and steeper. Its
 * last entry is still a floor, not zero — the bottom of the screen stays
 * readable at a glance.
 */
internal val LINE_FALLOFF_ALPHA_PHONE = floatArrayOf(1f, 0.74f, 0.6f, 0.48f, 0.4f, 0.34f)

/**
 * The shape of the page the lyrics are going to fill.
 *
 * One entry per line of the song, and one number per row that line wraps to.
 * That wrapping is the whole point: at this size a line of a song is rarely one
 * row, so the rows that wrap run nearly the full column and only the last one
 * of each is short. A ladder of evenly spaced bars of assorted lengths is what
 * a loading list looks like — text is blocks with ragged bottoms.
 */
internal val SKELETON_BLOCKS = listOf(
    floatArrayOf(0.97f, 0.54f),
    floatArrayOf(0.92f, 0.99f, 0.41f),
    floatArrayOf(0.68f),
    floatArrayOf(0.95f, 0.73f),
    floatArrayOf(0.89f, 0.96f, 0.37f),
)

/**
 * Set to the panel's own metrics: a bar stands the cap height of the 34sp the
 * lines are drawn in, rows of one line sit a line-height apart, and lines are a
 * row's own padding further apart again than that.
 */
internal val SKELETON_BAR = 26.dp
internal val SKELETON_LEADING = 15.dp
internal val SKELETON_BLOCK_GAP = 35.dp
internal const val SKELETON_PERIOD_MS = 1_400

/** What a line reads at while the list is being scrolled by hand. */
internal const val BROWSING_ALPHA = 0.8f

/** The playing line sits at 1; the rest sit fractionally back from it. */
internal const val INACTIVE_SCALE = 0.98f

/** A line under a finger dips, the way a button does. */
internal const val PRESSED_SCALE = 0.96f

/**
 * The break between verses, counted out rather than marked.
 *
 * Sized off the same 34sp the lines are set in, so a break sits in the list at
 * the weight of the words either side of it. [GAP_DOT_REST] is what an unlit
 * dot still shows: enough to say how many are coming, not enough to be read as
 * already counted.
 */
internal const val GAP_DOTS = 3
internal val GAP_DOT_SIZE = 13.dp
internal val GAP_DOT_GAP = 5.dp
internal const val GAP_DOT_REST = 0.25f
internal const val GAP_REST_SCALE = 0.76f

/** How long the panel takes to settle on a new line, and how far ahead it starts. */
internal const val SCROLL_LEAD_MIN_MS = 350L
internal const val SCROLL_LEAD_MAX_MS = 500L

/**
 * The curve every handover runs on: away quickly, in slowly and softly.
 *
 * One curve for the lot — dimming, blurring, scaling and the scroll — so a
 * line handing over reads as a single movement rather than four that happen to
 * start together.
 */
internal val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
internal const val LYRIC_SETTLE_MS = 400

/**
 * How the rows fan out as the panel moves between lines.
 *
 * They do not travel as a block. Each row after the one being scrolled to sets
 * off slightly later than the row before it, up to a few rows back, so the
 * spacing opens as the panel leaves and closes as it arrives. A block of text
 * sliding rigidly is a list being scrolled; the same lines arriving one behind
 * another is the panel handing over.
 *
 * Deliberately under half of what the renderer this came from uses. Its lines
 * carry the whole scroll themselves, so a long delay only means arriving late;
 * here the list has already moved underneath them, and the same delay reads as
 * the rows being dragged rather than following.
 */
internal const val STAGGER_STEPS = 3
internal const val STAGGER_FRACTION = 0.06f

/** One handover: how far the panel is going, and how long it is taking. */
internal class ScrollRun(val id: Int, val delta: Float, val durationMs: Int) {
    /** The last row to arrive does so this long after the panel sets off. */
    val spanMs: Float get() = durationMs * (1f + STAGGER_FRACTION * STAGGER_STEPS)
}

/**
 * How long before a line lands the panel starts moving to it — and how long
 * the move then takes, which is the same number.
 *
 * It is the run-up: the silence between the last word of the line being sung
 * and the first of the next. Bounded either side, because that silence is a
 * held breath in one song and half a verse in another, and neither the snap
 * nor the drift is what you want to be reading against.
 */
internal fun scrollLead(lines: List<LyricLine>, positionMs: Long): Long {
    val current = lines.indexOfLast { it.timeMs <= positionMs }
    // Before the first line's own timestamp there is no current line to
    // measure a run-up from. [indexOfLast] answers -1 there, and the guard
    // below does not catch it: `current + 1` is 0, which is a perfectly real
    // line, so the elvis never fires and `lines[current]` indexes at -1.
    //
    // Only reachable while the playhead is genuinely before the first lyric —
    // a track paused at 0:00 whose words start a few seconds in, which is
    // every track that opens on an intro.
    if (current < 0) return SCROLL_LEAD_MIN_MS
    val next = lines.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
    val gap = next.timeMs - lines[current].endMs
    return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
}

/**
 * How far a finger has to carry the lyric list before the player below it
 * gets out of the way — or comes back.
 *
 * Roughly a line of body text. Below that a scroll is a nudge to see one more
 * line rather than a decision to go reading, and answering every nudge put the
 * controls in and out on the same gesture.
 */
internal val CONTROLS_SCROLL_SLOP = 20.dp

internal const val LYRICS_UNAVAILABLE_HOLD_MS = 5_000L
internal const val LYRICS_UNAVAILABLE_FADE_MS = 900
internal sealed interface LyricsTranslationUiState {
    data object Idle : LyricsTranslationUiState
    data object Loading : LyricsTranslationUiState
    data class Ready(val lines: List<LyricLine>) : LyricsTranslationUiState
    data object SameLanguage : LyricsTranslationUiState
}

internal enum class LyricsDisplayMode { Original, Romanized, Translated }

internal const val TRANSLATION_MOTION_MS = 540
internal const val PARTICLES_PER_VOICE = 18

internal data class TranslationParticle(
    val anchor: Offset,
    val drift: Offset,
    val radius: Float,
    val delay: Float,
)
