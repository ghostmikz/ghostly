package com.ghostmikz.ghostly.ui.lyrics

import androidx.compose.runtime.Composable

/**
 * What a touch *meant*, not what it should feel like — the shape of the buzz is
 * this file's business, so a screen never has to know what the motor under it
 * can do.
 *
 * Everything here is deliberately brief. The longest pattern is a three-beat
 * one under 65ms; a haptic that outlasts the finger stops reading as a response
 * to the tap and starts reading as the phone ringing.
 */
enum class Haptic {
    /**
     * The lightest single beat, for something that repeats while a finger is
     * still down — a drag crossing a tab boundary, say. Anything firmer becomes
     * a rattle once it fires ten times in a row.
     */
    Tick,

    /** A plain button press with no state behind it: More, Download, Menu. */
    Tap,

    /** A discrete choice landing: a tab, a filter pill, the end of a scrub. */
    Select,

    /** Switching something on — a light lead-in *rising* into a firm beat. */
    ToggleOn,

    /** Switching it back off — the same pair mirrored, so it falls away. */
    ToggleOff,

    /** Forward through the queue: an accelerating triplet. */
    SkipNext,

    /** Backward: [SkipNext] reversed, which is what makes the pair legible. */
    SkipPrevious,

    /** Playback starting — swells into the beat that lands. */
    Resume,

    /** Playback stopping — lands first, then releases. */
    Pause,

    /** Something growing to fill the screen, e.g. the mini player opening. */
    Expand,
}

/**
 * A handle on the device's motor, obtained with [rememberHaptics]. Ghostly plays
 * BitChord's haptics through the view's own feedback constants rather than its
 * hand-tuned vibration patterns.
 */
class Haptics internal constructor(private val view: android.view.View) {
    fun play(haptic: Haptic) {
        val constant = when (haptic) {
            Haptic.Tick -> android.view.HapticFeedbackConstants.CLOCK_TICK
            Haptic.ToggleOn, Haptic.ToggleOff, Haptic.Select -> android.view.HapticFeedbackConstants.CONTEXT_CLICK
            else -> android.view.HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }
}

/** `val haptics = rememberHaptics()`, then `haptics.play(Haptic.Select)`. */
@Composable
fun rememberHaptics(): Haptics {
    val view = androidx.compose.ui.platform.LocalView.current
    return androidx.compose.runtime.remember(view) { Haptics(view) }
}
