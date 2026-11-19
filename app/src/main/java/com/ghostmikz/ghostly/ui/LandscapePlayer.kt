// Ported from BitChord's LandscapePlayer (https://github.com/kushagrasinghx/BitChord, GPLv3).
// Ghostly has no lyrics, so the right column turns between two pages, the
// player and the queue, where BitChord's turns between three.
package com.ghostmikz.ghostly.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The widest the two columns are allowed to get between them, centred in
 * whatever is left over: past a certain measure the extra width is only more
 * empty backdrop between the cover and the controls.
 */
private val LANDSCAPE_PLAYER_MAX_WIDTH = 1100.dp

/**
 * Below this height the landscape player tightens its right column — smaller
 * transport, shorter gaps — so a phone on its side fits the credits, the
 * scrubber, the transport and the volume bar between its system bars.
 */
private val LANDSCAPE_COMPACT_HEIGHT = 440.dp

/** Side margin of the columns: the portrait player's, or tighter on a phone-height window. */
private val PLAYER_GUTTER = 28.dp
private val LANDSCAPE_GUTTER_COMPACT = 20.dp

/**
 * How the right column hands over between the player and the queue. The left
 * column never moves, so a crossfade is the whole transition — the same page
 * being turned, not a panel being opened.
 */
private const val LANDSCAPE_PANE_FADE_IN_MS = 220
private const val LANDSCAPE_PANE_FADE_IN_DELAY_MS = 90
private const val LANDSCAPE_PANE_FADE_OUT_MS = 140

/** Room above the columns for the sheet's drag handle. */
private val LANDSCAPE_HANDLE_STRIP = 24.dp

/**
 * The player in a landscape window — a phone on its side, or a tablet.
 *
 * Two columns of equal width. The left one is the same in every state: the
 * cover, and under it the shuffle / repeat / queue row. The right one is one
 * thing at a time: the credits and transport, or the queue. Nothing moves
 * between columns, so opening the queue is only the right column's page
 * being turned.
 *
 * Purely the arrangement: every slot is built by NowPlayingScreen, from the
 * same state the portrait player reads, so rotating mid-song carries the open
 * queue and the playback straight across. Kept inside the safe-drawing insets,
 * since a phone on its side has its system bars and camera cutout down the
 * short edges, while the backdrop behind still runs underneath them.
 */
@Composable
internal fun LandscapePlayerLayout(
    showQueue: Boolean,
    onClose: () -> Unit,
    /** Fades the chrome with the sheet while the cover flies to the mini player. */
    fadeRest: Modifier,
    /** The cover; handed a modifier that already fixes its square size. */
    cover: @Composable (Modifier) -> Unit,
    /** The row under the cover. */
    actions: @Composable () -> Unit,
    /** Credits and transport. The flag is a phone-height window. */
    mainPane: @Composable (compact: Boolean) -> Unit,
    queuePane: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxHeight < LANDSCAPE_COMPACT_HEIGHT
        val gutter = if (compact) LANDSCAPE_GUTTER_COMPACT else PLAYER_GUTTER

        Row(
            modifier = Modifier
                .widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                .fillMaxSize()
                .padding(top = LANDSCAPE_HANDLE_STRIP, bottom = if (compact) 8.dp else 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The cover is square and takes whichever of the two axes runs out
                // first once the row below has had its height. Measured, not
                // estimated, so the row under it can never be pushed off the bottom.
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    cover(Modifier.size(minOf(maxWidth, maxHeight)))
                }
                Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
                Box(fadeRest) { actions() }
            }

            AnimatedContent(
                targetState = showQueue,
                transitionSpec = {
                    fadeIn(tween(LANDSCAPE_PANE_FADE_IN_MS, delayMillis = LANDSCAPE_PANE_FADE_IN_DELAY_MS)) togetherWith
                        fadeOut(tween(LANDSCAPE_PANE_FADE_OUT_MS))
                },
                contentAlignment = Alignment.Center,
                modifier = fadeRest.weight(1f).fillMaxHeight(),
                label = "landscapePane",
            ) { queue ->
                if (queue) {
                    Box(Modifier.fillMaxSize().padding(horizontal = gutter)) { queuePane() }
                } else {
                    Box(Modifier.fillMaxSize().padding(horizontal = gutter), contentAlignment = Alignment.Center) {
                        mainPane(compact)
                    }
                }
            }
        }

        // The sheet closes from a downward drag anywhere, as in portrait; the
        // handle is the promise of it, and a tap on it closes too.
        Box(
            fadeRest
                .align(Alignment.TopCenter)
                .offset(y = 8.dp)
                .width(38.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.70f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        )
    }
}
