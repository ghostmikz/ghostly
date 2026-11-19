package com.ghostmikz.ghostly.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import com.ghostmikz.ghostly.data.Prefs

// Shared motion helpers. Everything here goes still when Settings > Appearance >
// "Reduce animation" is on, like BitChord.

/** True unless the user turned on "Reduce animation". */
@Composable
fun motionOn(): Boolean = !Prefs.reduceAnimation.state.collectAsState().value

/** A light tick, the kind BitChord plays on taps. */
@Composable
fun rememberTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
}

/**
 * Tap with a small press-down bounce and a haptic tick. Replaces clickable() on
 * buttons and cards; the ripple is left out on purpose (BitChord uses none).
 */
fun Modifier.bounceClick(scaleDown: Float = 0.94f, haptic: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val motion = motionOn()
    val tick = rememberTick()
    val click by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed && motion) scaleDown else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                onTap = { if (haptic) tick(); click() },
            )
        }
}

/** Springs used across the app; snap instantly when motion is reduced. */
fun <T> ghostSpring(motion: Boolean, damping: Float = 0.8f, stiffness: Float = 380f) =
    if (motion) spring<T>(dampingRatio = damping, stiffness = stiffness) else spring(stiffness = 100_000f)
