package com.ghostmikz.ghostly.ui.lyrics

import com.ghostmikz.ghostly.ui.keepScrollInList
import com.ghostmikz.ghostly.ui.fadingEdges
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostmikz.ghostly.lyrics.isGeniusSectionHeader
import com.ghostmikz.ghostly.lyrics.LyricAlignment
import com.ghostmikz.ghostly.lyrics.LyricLine
import com.ghostmikz.ghostly.ui.icons.BitChordIcons
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first


/**
 * Source/status caption, with the provider chooser hung off the caption itself.
 *
 * The provider's name is the one line here that names something the reader can
 * act on — it is a choice, not a fact — so it is the line that opens the
 * chooser, underlined the way the other actions are.
 *
 * The link is absent where it has nothing to open: a line that only says the
 * lookup is still running is a fact, and one that says there are no lyrics is a
 * finding rather than a choice.
 */
@Composable
internal fun LyricsStatusWithChange(
    status: String,
    /** Present when [status] names something the reader can switch away from. */
    onStatusClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier.additiveChrome(LocalPlayerChrome.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.titleMedium,
            color = chromeWhite(0.55f),
            textDecoration = if (onStatusClick != null) TextDecoration.Underline else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (onStatusClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.play(Haptic.Select)
                            onStatusClick()
                        }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

internal fun adjustedLyricsPosition(positionMs: Long, offsetMs: Int): Long =
    (positionMs - offsetMs.toLong()).coerceAtLeast(0L)

internal fun adjustedLyricsSeekTarget(lineTimeMs: Long, offsetMs: Int): Long =
    (lineTimeMs + offsetMs.toLong()).coerceAtLeast(0L)

/**
 * The current lyric, one line, directly above the scrubber — or the line
 * saying why there isn't one.
 */
@Composable
internal fun CurrentLyricStrip(
    lines: List<LyricLine>,
    trackKey: String,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    durationMs: Long,
    lyricsUnavailable: Boolean,
    loadingText: String,
    onClick: () -> Unit,
) {
    // Compute the active lyric text so we can determine RTL direction at the
    // strip level.  This lets us override the parent Column's forced
    // CenterHorizontally alignment and flush RTL lines to the right edge.
    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    var stripRtl by remember { mutableStateOf(false) }
    var activeText by remember { mutableStateOf("") }
    if (isSynced) {
        val clock = rememberLyricClock(trackKey, playhead, isPlaying)
        val index by remember(lines) {
            derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
        }
        val current = lines.getOrNull(index)
        val instrumental = current == null || current.isGap
        val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
        val intro = instrumental && firstSung >= 0 && index < firstSung
        val introLines = LYRICS_INTRO_LINES
        val introLine = remember(trackKey) { introLines.random() }
        activeText = when {
            intro -> introLine
            instrumental -> "Instrumental"
            else -> current.text
        }
        stripRtl = isRtl(activeText)
    }

    val align: Alignment = if (stripRtl) Alignment.CenterStart else Alignment.CenterStart

    CompositionLocalProvider(LocalLayoutDirection provides if (stripRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // The slider's touch target reaches ~13dp above the drawn bar, so
                // the strip reads as further off it than it is. Nudged down into
                // that dead space, the same way the timestamps below are pulled
                // back up into it.
                .offset(y = 6.dp),
        ) {
            if (lines.isNotEmpty()) {
                CurrentLyricLine(
                    lines = lines,
                    trackKey = trackKey,
                    playhead = playhead,
                    isPlaying = isPlaying,
                    durationMs = durationMs,
                    onClick = onClick,
                    modifier = Modifier
                        .align(align),
                )
            } else if (lyricsUnavailable) {
                LyricsUnavailableLine(
                    trackKey = trackKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(align),
                )
            } else {
                LyricsLoadingLine(
                    text = loadingText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(align),
                )
            }
        }
    }
}


/**
 * Stands in for the lyrics while the lookup is still out.
 *
 * Without it the panel had one empty state doing two jobs: a lookup that had
 * come back with nothing and a lookup that had not come back yet both said "No
 * lyrics for this track", so every track was declared to have none for as long
 * as it took to find out that it did.
 */
@Composable
internal fun LyricsSkeleton(modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "lyricsSkeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SKELETON_PERIOD_MS, easing = LinearEasing),
        ),
        label = "sweep",
    )
    BoxWithConstraints(
        // No gutter of its own: the list this stands in for bleeds out to the
        // panel's full width and puts the gutter back as content padding, so
        // the words land level with the panel's own edge and so does this.
        modifier.padding(top = 40.dp),
    ) {
        // Every bar sweeps against the width of the column rather than its own,
        // so one band crosses the whole page. Measured per bar, a short row
        // lights end to end in the time a long one takes to get halfway, and
        // the block reads as a row of separate things loading separately.
        val column = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(SKELETON_BLOCK_GAP)) {
            SKELETON_BLOCKS.forEach { rows ->
                Column(verticalArrangement = Arrangement.spacedBy(SKELETON_LEADING)) {
                    rows.forEach { fraction ->
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(SKELETON_BAR)
                                .clip(RoundedCornerShape(4.dp))
                                // Read in the draw block, not the body: a
                                // pageful of these would otherwise recompose on
                                // every frame, and all any of them needs per
                                // frame is a fresh gradient.
                                .drawWithCache {
                                    val full = column.toPx()
                                    val band = full * 0.45f
                                    val startX = -band + sweep.value * (full + band * 2)
                                    val brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.10f),
                                            Color.White.copy(alpha = 0.26f),
                                            Color.White.copy(alpha = 0.10f),
                                        ),
                                        startX = startX,
                                        endX = startX + band,
                                    )
                                    onDrawBehind { drawRect(brush) }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared phone-panel gesture: moving forward through content hides the half
 * player, while reversing brings it back. Only direct finger input counts, so
 * the lyrics auto-follow and the queue's current-track jump cannot move chrome.
 */
@Composable
internal fun rememberPlayerControlsOnScroll(
    enabled: Boolean = true,
    onReveal: () -> Unit,
    onHide: () -> Unit,
): NestedScrollConnection {
    val controlsSlopPx = with(LocalDensity.current) { CONTROLS_SCROLL_SLOP.toPx() }
    val revealControls by rememberUpdatedState(onReveal)
    val hideControls by rememberUpdatedState(onHide)
    return remember(enabled, controlsSlopPx) {
        object : NestedScrollConnection {
            private var travel = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (enabled && source == NestedScrollSource.UserInput && available.y != 0f) {
                    if (travel != 0f && (travel > 0f) != (available.y > 0f)) travel = 0f
                    travel += available.y
                    // Finger travelling up is the list going forward.
                    if (travel <= -controlsSlopPx) {
                        travel = 0f
                        hideControls()
                    } else if (travel >= controlsSlopPx) {
                        travel = 0f
                        revealControls()
                    }
                }
                return Offset.Zero
            }
        }
    }
}

/**
 * Apple Music's lyrics view: big tight type, the playing line crisp and
 * everything else falling out of focus the further it is from it. Blur needs
 * API 31+, so alpha carries the same hierarchy on older devices.
 *
 * Scrolling by hand clears the blur and suspends the auto-follow, so you can
 * read ahead; a couple of seconds after you stop it snaps back to the song.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsPanel(
    lines: List<LyricLine>,
    /**
     * Romanized or translated copies of [lines], index for index, drawn small
     * under each original line. Null shows the originals alone.
     */
    subLines: List<LyricLine>? = null,
    trackKey: String,
    playhead: LyricPlayhead,
    /** Whether a lookup for this track is still in flight. */
    looking: Boolean,
    isPlaying: Boolean,
    /** False while the panel is retained offscreen solely as a warm layout. */
    active: Boolean = true,
    onSeekToLine: (Long) -> Unit,
    controlsOpen: Boolean,
    onRevealControls: () -> Unit,
    onHideControls: () -> Unit,
    translationProgress: State<Float>? = null,
    /** Reports whether the lyric list is mid-scroll, so the player above it
     * can stand down its own swipe gestures for as long as it is. */
    onScrollingChange: (Boolean) -> Unit = {},
    /** Whether this platform can turn picked lines into a card at all. */
    canPick: Boolean = false,
    /** Whether the reader is choosing lines to put on a share card. */
    picking: Boolean = false,
    /** Which lines are chosen, as indices into [lines]. */
    picked: Set<Int> = emptySet(),
    /** Long-press on a line: start picking, or fold that line into the pick. */
    onPickLine: (Int) -> Unit = {},
    /** Tap while picking: add or drop that line. */
    onTogglePick: (Int) -> Unit = {},
    /**
     * Holds the sung line in the middle of the panel rather than at its top.
     * For the wide layouts — TV, landscape, tablet — where a column of lyrics
     * read from its top edge leaves most of the screen below the words.
     */
    centerFocus: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val panelPlaying = isPlaying && active
    val clock = rememberLyricClock(trackKey, playhead, panelPlaying)
    val subReveal = rememberSubLyricsReveal(subLines, trackKey)

    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    // Only a song that actually names a second voice is laid out as one. A
    // single-voice song has every line on the left already, so splitting the
    // panel into lanes for it would just be a narrower panel.
    val duet = remember(lines) { lines.any { it.alignment == LyricAlignment.End } }

    val activeRows by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) emptyList() else activeLyricRows(lines, clock.longValue)
        }
    }
    // The uppermost unfinished vocal owns the scroll anchor until its end,
    // even as later rows begin their own independent highlight animations.
    val scrollLine = activeRows.firstOrNull() ?: -1
    // Where the panel is heading, which is a beat ahead of where the singing
    // is. Movement that starts on the downbeat arrives after it — the line is
    // already being sung by the time it settles, and you read it late. Started
    // during the run-up instead, the words are under your eye when they land.
    //
    // Kept apart from [scrollLine] on purpose: this leads, and the sweep must
    // not. Everything lit by the clock still goes through the real one.
    val leadLine by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) {
                -1
            } else {
                val now = clock.longValue
                activeLyricRows(lines, now + scrollLead(lines, now)).firstOrNull() ?: -1
            }
        }
    }
    // What the stack arranges itself around. The outgoing line starts dimming
    // as the panel leaves it rather than when its last word ends, so the dim,
    // the blur and the movement are one gesture.
    val focusLine = if (leadLine >= 0) leadLine else scrollLine
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect(onScrollingChange)
    }
    // LayoutInfo changes on every scroll frame. Observe only height here so
    // the entire lyrics list is not recomposed for every scrolling pixel.
    val viewportHeight by remember(listState) {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    var browsingByDrag by remember { mutableStateOf(false) }
    // While picking lines, the panel must not auto-scroll — the reader needs
    // the list to stay put so they can tap the lines they want.
    val browsing = browsingByDrag || picking
    val onBottomHalfTap: () -> Unit = {
        // A pick has the whole panel to itself: the controls that a tap down
        // there would pull back in are the player's transport, and it must not
        // surface under somebody who is choosing lines.
        if (!picking && !listState.isScrollInProgress) {
            onRevealControls()
        }
    }

    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val lyricsBlur by PlayerSettings.lyricsBlur.collectAsStateWithLifecycle()
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    val glowing = !reduceAnimation && !reduceDynamicBlur && lyricsBlur &&
        renderEffectBlurSupported

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                // Suspends the panel's own following, and nothing more. Which
                // way the drag is going is what decides the controls now — see
                // [controlsOnScroll] — and hiding here as well meant a
                // scroll *up*, the gesture that is supposed to bring them back,
                // put them away first and then returned them.
                browsingByDrag = true
            }
        }
    }

    // Reading on hides the player; coming back up brings it out again.
    //
    // The direction is taken from the drag itself rather than from where the
    // list ends up, so it answers on the gesture rather than after it. Deltas
    // arrive a couple of pixels at a time, so they are accumulated and the
    // total is what crosses [CONTROLS_SCROLL_SLOP] — and the total resets the
    // moment the finger changes its mind, so a scroll that wanders does not
    // bank its way to the wrong answer.
    //
    // [NestedScrollSource.UserInput] is the whole guard against the panel
    // hiding the controls by itself: this list scrolls on its own every time a
    // line lands, and that is not somebody reading on.
    val controlsOnScroll = rememberPlayerControlsOnScroll(
        onReveal = { if (!picking) onRevealControls() },
        onHide = onHideControls,
    )

    val currentLine by rememberUpdatedState(focusLine)
    val activeOnScreen by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.index == currentLine }
        }
    }
    // Paused, there is no song to follow back to, so a hand scroll should sit
    // wherever it was left rather than snapping back on these timers.
    LaunchedEffect(browsingByDrag, activeOnScreen, listState.isScrollInProgress, panelPlaying) {
        if (panelPlaying && browsingByDrag && activeOnScreen && !listState.isScrollInProgress) {
            delay(600)
            browsingByDrag = false
        }
    }

    LaunchedEffect(browsingByDrag, listState.isScrollInProgress, panelPlaying) {
        if (panelPlaying && browsingByDrag && !listState.isScrollInProgress) {
            delay(5_000)
            browsingByDrag = false
        }
    }

    // The panel's own journey, published so each row can work out how far
    // behind it should be running. Held as a plain value plus a frame clock
    // rather than an animation per row: sixty rows each with their own
    // Animatable is sixty animations to start and stop on every handover.
    var run by remember(lines) { mutableStateOf(ScrollRun(0, 0f, LYRIC_SETTLE_MS)) }
    val since = remember(lines) { mutableFloatStateOf(0f) }
    LaunchedEffect(run.id) {
        if (run.id == 0) return@LaunchedEffect
        animate(
            initialValue = 0f,
            targetValue = run.spanMs,
            animationSpec = tween(run.spanMs.toInt(), easing = LinearEasing),
        ) { value, _ -> since.floatValue = value }
    }
    // Keyed to the track, not to [lines]: toggling the translation replaces
    // every line while the reader's place in the song is unchanged, and a reset
    // here would snap the panel back to the top mid-read.
    var placed by remember(trackKey) { mutableStateOf(false) }
    // Unsynced text has no line to hold anywhere, and centred it would only
    // open with half a screen of nothing above the first verse.
    val centered = centerFocus && isSynced
    // Where a row [size] tall has to sit, in the list's own item offsets, to be
    // the line being read: its top on the top padding, or its middle on the
    // middle of the viewport.
    val focusOffset: (Int) -> Int = { size ->
        if (!centered) {
            0
        } else {
            val info = listState.layoutInfo
            (info.viewportStartOffset + info.viewportEndOffset) / 2 - size / 2
        }
    }
    // Nothing resets [browsing] off [controlsOpen] any more. It used to, so
    // that tapping the controls back resumed following — but the controls now
    // also come back by scrolling up, and clearing the flag there handed the
    // panel straight back to the song mid-gesture, scrolling the reader away
    // from the line they had gone looking for. The two timers below end a
    // browse on their own terms.
    // A newer line replaces an unfinished automatic scroll. Only a user's
    // browsing gesture should suspend following, not our own animation.
    LaunchedEffect(focusLine, browsing, controlsOpen, active) {
        if (active && isSynced && !browsing &&
            focusLine >= 0 && focusLine in lines.indices
        ) {
            // Centred, the top padding is half the viewport, and it only exists
            // a frame after the viewport does. Placing against the frame before
            // it lands would leave the line that whole padding off centre.
            snapshotFlow { listState.layoutInfo }.first {
                it.viewportSize.height > 0 && (!centered || it.beforeContentPadding > 0)
            }
            // Keep the same anchor whether the playback controls are visible or hidden.
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusLine }
            when {
                !placed -> {
                    listState.scrollToItem(focusLine, scrollOffset = -focusOffset(0))
                    // The row's height is only known once it is laid out, and
                    // centring needs half of it.
                    if (centered) {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == focusLine }
                            ?.let { listState.scrollBy((it.offset - focusOffset(it.size)).toFloat()) }
                    }
                    placed = true
                }
                // Already on screen, which is the ordinary case of handing over
                // to the next line: its distance is known, so the move can be
                // given the run-up's own duration and curve instead of the
                // list's default spring.
                visible != null -> {
                    val span = scrollLead(lines, clock.longValue).toInt()
                    val distance = (visible.offset - focusOffset(visible.size)).toFloat()
                    run = ScrollRun(run.id + 1, distance, span)
                    // The same curve the rows catch up on. Two different
                    // curves and a row with no delay at all still trails the
                    // list it is sitting in, which is most of the way to
                    // looking like the panel cannot keep up with itself.
                    listState.animateScrollBy(
                        value = distance,
                        animationSpec = tween(durationMillis = span, easing = LYRIC_EASING),
                    )
                }
                // Somewhere off screen — after a seek, or a long instrumental
                // scrolled past. How far is not known without laying the rows
                // out, so this hands back to the list's own staged scroll.
                else -> {
                    listState.animateScrollToItem(focusLine, scrollOffset = -focusOffset(0))
                    // That lands the row's top on the middle; the last half
                    // line is settled once its height is known.
                    if (centered) {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == focusLine }
                            ?.let {
                                listState.animateScrollBy(
                                    value = (it.offset - focusOffset(it.size)).toFloat(),
                                    animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                )
                            }
                    }
                }
            }
        }
    }

    if (lines.isEmpty()) {
        val empty = modifier.revealLyricsControlsOnTap(!controlsOpen, onBottomHalfTap)
        // "None" is a finding, and it is only worth reporting once the lookup
        // has actually come back with it.
        if (looking) {
            LyricsSkeleton(empty)
        } else {
            Box(empty, contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics for this track",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .bleedHorizontally(PLAYER_GUTTER)
            .nestedScroll(controlsOnScroll)
            .nestedScroll(keepScroll)
            // Browsing leaves taps to each lyric row's seek action throughout
            // the list — and picking leaves them nothing at all: the gesture
            // below takes taps at the initial pass, so while a pick is open it
            // would swallow every choice before the row ever saw it.
            .revealLyricsControlsOnTap(!controlsOpen && !picking, onBottomHalfTap)
            .fadingEdges(),
        // Each row carries GLOW_ROOM of its own inset for the halo, so the
        // list hands that much back — otherwise the lines would sit a glow's
        // width further apart and further in than they used to.
        // Centred, half the viewport above the first line, so that it too can
        // be brought to the middle.
        contentPadding = PaddingValues(
            top = if (centered) {
                with(LocalDensity.current) { viewportHeight.toDp() } * 0.5f
            } else {
                40.dp - GLOW_ROOM
            },
            bottom = with(LocalDensity.current) { viewportHeight.toDp() } * 0.8f,
            start = PLAYER_GUTTER - GLOW_ROOM,
            end = PLAYER_GUTTER - GLOW_ROOM,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            if (!isSynced && isGeniusSectionHeader(line.text)) {
                val sectionTitle = line.text.removePrefix("[").removeSuffix("]").trim()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 6.dp else 24.dp, bottom = 8.dp)
                        .padding(horizontal = GLOW_ROOM),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 11.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = sectionTitle.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                letterSpacing = 1.3.sp,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                            ),
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
                return@itemsIndexed
            }

            if (!isSynced && line.isGap) {
                Spacer(Modifier.height(14.dp))
                return@itemsIndexed
            }

            // Off the line being sung, not off the line the panel is heading
            // for. Brightness is what says "these are the words right now", so
            // it cannot run ahead of them — on a source with no word timings
            // there is no sweep behind it to keep the sung line lit, and it
            // read as dim while it was still being sung.
            val offset = if (scrollLine < 0) 0 else index - scrollLine
            val distance = abs(offset)
            val isActive = isSynced && index in activeRows
            // Symmetric either side of the playing line, and shallow: the two
            // rows around it stay readable so you can follow back over what was
            // just sung as well as ahead, and everything past that recedes to
            // the same floor rather than fading to nothing.
            val falloff = if (centerFocus) LINE_FALLOFF_ALPHA else LINE_FALLOFF_ALPHA_PHONE
            val step = distance.coerceAtMost(falloff.lastIndex)
            val blur by animateDpAsState(
                targetValue = when {
                    !isSynced || reduceDynamicBlur || !lyricsBlur || browsing || isActive -> 0.dp
                    else -> LINE_FALLOFF_BLUR[step.coerceAtMost(LINE_FALLOFF_BLUR.lastIndex)]
                },
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricBlur",
            )
            val lineAlpha by animateFloatAsState(
                targetValue = when {
                    !isSynced -> 0.95f
                    isActive -> 1f
                    // Reading by hand is not following along: the stack flattens
                    // to one brightness so no row is being pointed at.
                    browsing -> BROWSING_ALPHA
                    else -> falloff[step]
                },
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricAlpha",
            )
            if (line.isGap) {
                // A break counts itself out rather than being marked: three
                // dots lighting in turn across the interlude, so a long one
                // reads as time running down instead of a symbol parked on
                // screen waiting for the singing to come back.
                val until = lines.getOrNull(index + 1)?.timeMs ?: line.endMs
                // How far through the break the song is, held at 0 before it and
                // 1 after: the dots read the clock only while it is running, so
                // a break that is not playing never redraws — see SweptLyricLine.
                val gapFill = remember(line, until, clock) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        val now = clock.longValue
                        when {
                            now <= line.timeMs -> 0f
                            now >= until -> 1f
                            else -> GAP_RUNNING
                        }
                    }
                }
                // The row itself opens and closes with the break, so the list
                // carries no dead space through the verses either side of it —
                // which is also what stops the panel scrolling past a hole to
                // reach the next line that is actually sung.
                val swell by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (isActive) 400 else 350,
                        easing = LYRIC_EASING,
                    ),
                    label = "gapSwell",
                )
                val instrumental = "Instrumental"
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .height((GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell)
                        .clipToBounds(),
                ) {
                Box(
                    modifier = Modifier
                        .blur(blur, BlurredEdgeTreatment.Unbounded)
                        .clip(RoundedCornerShape(10.dp))
                        // A gap is nothing to pick, so it only ever seeks — and
                        // not even that while a pick is open, where a stray tap
                        // in the silence would jump the song.
                        .clickable(enabled = isSynced && !picking) { onSeekToLine(line.timeMs) }
                        // Matches the inset every sung line carries, so the
                        // rhythm of the list doesn't break at a break.
                        .padding(GLOW_ROOM)
                        .size(
                            width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2,
                            height = GAP_DOT_SIZE,
                        )
                        .graphicsLayer {
                            val grow = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
                            scaleX = grow
                            scaleY = grow
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            alpha = lineAlpha * swell
                        }
                        .drawBehind {
                            // Read here rather than in composition: the fill
                            // moves every frame, and this way a break costs a
                            // redraw of three circles, not a recomposition.
                            val span = (until - line.timeMs).coerceAtLeast(1L)
                            val through = gapFill.value.takeUnless { it == GAP_RUNNING }
                                ?: ((clock.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                            val radius = GAP_DOT_SIZE.toPx() / 2f
                            val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                            repeat(GAP_DOTS) { dot ->
                                // Each dot owns its share of the break and
                                // fills across it, so they light left to right.
                                val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                                drawCircle(
                                    color = Color.White.copy(
                                        alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit,
                                    ),
                                    radius = radius,
                                    center = Offset(radius + dot * stride, size.height / 2f),
                                )
                            }
                        }
                        .semantics { contentDescription = instrumental },
                )
                }
            } else {
                val alignEnd = duet && line.alignment == LyricAlignment.End
                val style = if (isSynced) {
                    MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 34.sp,
                        lineHeight = 41.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                    )
                } else {
                    MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 30.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                    )
                }
                // The stack sits fractionally back and the playing line comes
                // forward to meet you, rather than the playing line swelling
                // past the others — a smaller move, and one that doesn't push
                // the type around the line it hands over to.
                //
                // Anchored to the left edge, so the words don't slide sideways
                // under the highlight; scaling about the centre would fight the
                // sweep. A row under a finger dips, the way a button does.
                // Behind the panel's focus, so the words close up to full
                // brightness as it leaves rather than when the last syllable
                // lands — the dim, the blur and the movement together.
                val sung = offset < 0
                // Rows behind the one being scrolled to are the ones that
                // fan out; the ones it is moving away from arrive together.
                val behind = if (run.delta >= 0f) index - focusLine else focusLine - index
                val staggerDelay = behind.coerceIn(0, STAGGER_STEPS) *
                    STAGGER_FRACTION * run.durationMs
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = when {
                        pressed -> PRESSED_SCALE
                        isActive -> 1f
                        else -> INACTIVE_SCALE
                    },
                    animationSpec = tween(
                        durationMillis = if (pressed) 120 else LYRIC_SETTLE_MS,
                        easing = LYRIC_EASING,
                    ),
                    label = "lyricScale",
                )
                // Apple's bloom on the line being sung. Fades in and out with
                // the line rather than switching, so a handover is one line's
                // light going down as the next one's comes up.
                val glow by animateFloatAsState(
                    targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                    animationSpec = tween(durationMillis = 420),
                    label = "lyricGlow",
                )
                // No width held back for the swell any more: nothing draws past
                // its own bounds now that the playing line tops out at 1, so the
                // text gets the full column and wraps where the panel does.
                val shape = Modifier
                    .fillMaxWidth()
                    // The lane the other voice sings in, kept clear. Applied
                    // before the layer below so the row scales about the edge
                    // it is actually written from.
                    .padding(
                        start = if (duet && alignEnd) DUET_LANE else 0.dp,
                        end = if (duet && !alignEnd) DUET_LANE else 0.dp,
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                        alpha = lineAlpha
                        // Held back against the list's own movement: the list
                        // has already taken this row part of the way, so giving
                        // back what it has not earned yet is what leaves it
                        // trailing. One curve for both, so a row with no delay
                        // sits exactly still against the list and the rows that
                        // do have one are the only thing that moves.
                        //
                        // Rows with nothing to catch up on never read the clock
                        // at all, so a handover only invalidates the handful of
                        // layers that are actually fanning out.
                        translationY = if (staggerDelay <= 0f) {
                            0f
                        } else {
                            val elapsed = since.floatValue
                            run.delta * (
                                LYRIC_EASING.transform(
                                    (elapsed / run.durationMs).coerceIn(0f, 1f),
                                ) - LYRIC_EASING.transform(
                                    ((elapsed - staggerDelay) / run.durationMs)
                                        .coerceIn(0f, 1f),
                                )
                                )
                        }
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    // The chosen lines are marked on the row itself rather
                    // than with a mark beside it: a lane down the side would
                    // have to be reserved for every line whether or not
                    // anybody was picking, and this panel is words from edge
                    // to edge. Everything else dims a little instead, so what
                    // is picked is read against what isn't.
                    .background(
                        when {
                            index in picked -> Color.White.copy(alpha = 0.16f)
                            picking -> Color.White.copy(alpha = 0.05f)
                            else -> Color.Transparent
                        },
                    )
                    // A combined click because long-press is the way in: a list
                    // of words gives no sign that pressing one does anything
                    // beyond seeking, so the gesture has to be discoverable
                    // from the header's link as well. Enabled only when there
                    // is something to do — seek when the source stamps its
                    // lines, choose while picking — so an unsynced row still
                    // passes taps through to the panel behind it.
                    .then(
                        if (canPick) {
                            Modifier.combinedClickable(
                                enabled = picking || isSynced,
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                onLongClick = { onPickLine(index) },
                                onClick = {
                                    if (picking) {
                                        onTogglePick(index)
                                    } else {
                                        onSeekToLine(line.timeMs)
                                    }
                                },
                            )
                        } else {
                            // Where a card cannot be made there is nothing to
                            // pick, and a long press that did nothing would be
                            // the only sign of it — so the row keeps the plain
                            // tap it has always had.
                            Modifier.clickable(
                                enabled = isSynced,
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                            ) { onSeekToLine(line.timeMs) }
                        },
                    )
                // Lead and answering vocal are one row: they are one line of
                // the song, they scale and dim together, and tapping either
                // seeks to the same place.
                val sub = subReveal.lines?.getOrNull(index)
                val subStyle = style.copy(
                    fontSize = SUB_LYRIC_FONT_SIZE,
                    lineHeight = SUB_LYRIC_LINE_HEIGHT,
                    fontWeight = FontWeight.Bold,
                )
                // The answering voice is only on screen while it is being sung,
                // the way Apple Music keeps it: it opens on its own clock, just
                // ahead of its first word, and folds away once its last one is
                // done — not with the lead, which it routinely enters halfway
                // through and outlasts. Left open on every row, a verse of
                // echoes read as two lyrics interleaved.
                //
                // A backing line with text but no timings of its own has no
                // clock to open on, so it borrows the lead's.
                //
                // A row the panel has already left waits for the scroll in
                // flight to land before it closes. Until then it can still be
                // the row the list measures from, and a row shrinking there drags
                // everything below it up with it: the line being scrolled to
                // overshoots by exactly what folded away. Once the move has
                // landed it is above the line the list hangs from, and closing
                // there only draws the rows above together.
                val backingOpen = line.background?.let { backing ->
                    val due by remember(backing, clock) {
                        derivedStateOf {
                            val now = clock.longValue
                            now >= backing.timeMs - BACKING_LEAD_MS && now < backing.endMs
                        }
                    }
                    val wanted = !isSynced || picking || if (backing.isWordSynced) {
                        due
                    } else {
                        isActive || index == focusLine
                    }
                    animateFloatAsState(
                        targetValue = if (wanted) 1f else 0f,
                        animationSpec = when {
                            reduceAnimation -> tween(0)
                            wanted -> tween(BACKING_OPEN_MS, easing = LYRIC_EASING)
                            else -> tween(
                                BACKING_CLOSE_MS,
                                delayMillis = if (index < focusLine) run.durationMs else 0,
                                easing = FastOutSlowInEasing,
                            )
                        },
                        label = "backingOpen",
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(modifier = shape) {
                    PanelVoice(
                        line = line,
                        clock = clock,
                        style = voiceStyle(style, line.text, alignEnd, duet),
                        isActive = isActive,
                        sung = sung,
                        synced = isSynced,
                        browsing = browsing,
                        glowAlpha = glow,
                        room = GLOW_ROOM,
                        alignEnd = alignEnd,
                        laneLocked = duet,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // A line the service handed back unchanged ("falling
                    // down" in a Korean song) gets no second copy of itself.
                    sub?.takeIf { it.text.differsFrom(line.text) }?.let { subLine ->
                        PanelVoice(
                            line = subLine,
                            clock = clock,
                            style = voiceStyle(subStyle, subLine.text, alignEnd, duet),
                            isActive = isActive,
                            sung = sung,
                            synced = isSynced,
                            browsing = browsing,
                            glowAlpha = 0f,
                            room = 0.dp,
                            alignEnd = alignEnd,
                            laneLocked = duet,
                            rise = false,
                            // Only the rows actually in front of the reader get the
                            // particle pass. Sixty rows' worth of glyph boxes is a
                            // layout walk per frame for text nobody is looking at.
                            translationProgress = translationProgress.takeIf {
                                if (isSynced) abs(index - focusLine) <= 1 else index < 4
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .revealBelow(subReveal.progress)
                                // Tucked up into the lead's glow inset so the
                                // pair reads as one line in two scripts.
                                .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                .offset(y = -SUB_LYRIC_TUCK)
                                .graphicsLayer { alpha = SUB_LYRIC_ALPHA },
                        )
                    }
                    line.background?.let { backing ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(backingOpen?.let { Modifier.revealBacking(it, alignEnd) } ?: Modifier),
                        ) {
                            PanelVoice(
                                line = backing.withoutBracketPunctuation(),
                                clock = clock,
                                style = voiceStyle(style.copy(
                                    fontSize = BACKING_FONT_SIZE,
                                    lineHeight = BACKING_LINE_HEIGHT,
                                ), backing.text, alignEnd, duet),
                                isActive = isActive,
                                sung = sung,
                                synced = isSynced,
                                browsing = browsing,
                                // No bloom on the second voice. The glow marks
                                // what is being sung *at you*; putting it on both
                                // makes the row read as two equal lines, which is
                                // the thing this split exists to stop.
                                glowAlpha = 0f,
                                room = 0.dp,
                                alignEnd = alignEnd,
                                laneLocked = duet,
                                rise = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // No top inset: the lead's own bottom room is
                                    // the gap, which leaves the two voices closer
                                    // to each other than to the rows either side.
                                    .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                    .graphicsLayer { alpha = BACKING_ALPHA },
                            )
                            sub?.background
                                ?.takeIf { it.text.differsFrom(backing.text) }
                                ?.let { subBacking ->
                                    PanelVoice(
                                        line = subBacking.withoutBracketPunctuation(),
                                        clock = clock,
                                        style = voiceStyle(subStyle.copy(
                                            fontSize = SUB_BACKING_FONT_SIZE,
                                            lineHeight = SUB_BACKING_LINE_HEIGHT,
                                        ), subBacking.text, alignEnd, duet),
                                        isActive = isActive,
                                        sung = sung,
                                        synced = isSynced,
                                        browsing = browsing,
                                        glowAlpha = 0f,
                                        room = 0.dp,
                                        alignEnd = alignEnd,
                                        laneLocked = duet,
                                        rise = false,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .revealBelow(subReveal.progress)
                                            .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                            .offset(y = -SUB_LYRIC_TUCK)
                                            .graphicsLayer { alpha = BACKING_ALPHA * SUB_LYRIC_ALPHA },
                                    )
                                }
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * One voice of a row in [LyricsPanel] — the lead, or the answering line drawn
 * under it.
 *
 * Both go through the same sweep. A backing vocal carries its own word
 * timings, so it lights up on its own clock rather than borrowing the lead's:
 * that is the whole point of splitting it out, and it is why the bracket no
 * longer gets cut off when the next line's stamp arrives mid-phrase.
 */
@Composable
internal fun PanelVoice(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    /** Whether the panel has already left this line behind. */
    sung: Boolean,
    /** Whether the source stamps its lines at all. */
    synced: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    room: Dp,
    /** Whether this line is one of the right-hand voice's; see [LyricAlignment]. */
    alignEnd: Boolean,
    /** When true, alignment is driven by lane (duet) not text direction. */
    laneLocked: Boolean = false,
    translationProgress: State<Float>? = null,
    /**
     * Whether words lift off the line as they are sung. Only the lead does:
     * on the small lines under it — the answering voice, a translation — the
     * movement competes with the line being read and makes the row look busy.
     */
    rise: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Vazirmatn for Persian/Arabic-script lines; Latin lines keep the player's font.
    val arabicScript = remember(line.text) { usesArabicScript(line.text) }
    val lyricStyle = if (arabicScript) {
        // BitChord sets Arabic script in its bundled Vazirmatn; Ghostly uses the system's.
        style
    } else style
    if (line.isWordSynced && !browsing) {
        // Every word-synced line goes through the sweep, not just the playing
        // one — a line that has already been sung is fully revealed and one
        // still to come is not, which falls out of the same arithmetic.
        //
        // Running it only on the active line meant swapping this composable
        // for a plain Text the instant a line handed over, and the two
        // disagreed about the brightness of the words: the tail of the line
        // popped up to meet the rest of it in a single frame. Animating the
        // tail instead lets a finished line close up as it dims away.
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = lyricStyle,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = glowAlpha,
            glowRoom = room,
            feather = isActive,
            rise = rise,
            alignEnd = alignEnd,
            laneLocked = laneLocked,
            translationProgress = translationProgress,
            color = Color.White,
        )
    } else if (line.isWordSynced) {
        // Browsing: keep the sweep so sung lines stay fully lit and unsung
        // ones stay dim, but skip the bloom — it is a playback flourish, not
        // a browsing aid.  Non-active lines get the same dim tail as when we
        // are not browsing; the active line stays at full brightness.
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = lyricStyle,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = 0f,
            glowRoom = room,
            rise = rise,
            alignEnd = alignEnd,
            laneLocked = laneLocked,
            translationProgress = translationProgress,
            color = Color.White,
        )
    } else {
        // No word timings, so there is no sweep to light the words as they are
        // sung: the line lights whole, the moment it starts.
        //
        // It still has to hold itself back until then. The parent's falloff
        // alone left a line not yet sung reading brighter here than the same
        // line does on a word-synced source, where the unsung words sit at
        // [UNSUNG_ALPHA] underneath it — the two have to agree about what "not
        // yet" looks like, or changing provider changes the panel rather than
        // the words. Lyrics with no timing at all are all "now", and stay lit.
        val lit by animateFloatAsState(
            targetValue = if (!synced || sung || isActive) 1f else UNSUNG_ALPHA,
            label = "lyricLit",
        )
        var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = line.text,
            style = lyricStyle,
            color = Color.White.copy(alpha = lit),
            onTextLayout = { layout = it },
            modifier = modifier.lyricParticles(layout, translationProgress, room).padding(room),
        )
    }
}

/**
 * What [LyricsPanel] draws under each line, and how far it has opened.
 *
 * One clock for the whole panel rather than an animation per row: a long song
 * is a hundred rows, and each would otherwise start, run and stop its own
 * Animatable on every toggle. [progress] is only ever read in layout and draw
 * (see [revealBelow]), so opening it costs a relayout of the rows on screen
 * and not one recomposition.
 */
internal class SubLyricsReveal(
    val progress: State<Float>,
    lines: State<List<LyricLine>?>,
) {
    /** Held through the collapse, so the words fold away rather than vanish. */
    val lines: List<LyricLine>? by lines
}

@Composable
internal fun rememberSubLyricsReveal(target: List<LyricLine>?, trackKey: String): SubLyricsReveal {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    // Keyed to the track: a new song arrives with nothing under it, and must
    // not fold the last song's translation away over its own opening lines.
    val shown = remember(trackKey) { mutableStateOf(target) }
    val progress = remember(trackKey) { Animatable(if (target != null) 1f else 0f) }
    LaunchedEffect(target, trackKey, reduceAnimation) {
        if (reduceAnimation) {
            shown.value = target
            progress.snapTo(if (target != null) 1f else 0f)
            return@LaunchedEffect
        }
        // Switching straight from romanized to translated closes the one
        // before opening the other, so two scripts never share the gap.
        if (shown.value != null && shown.value !== target && progress.value > 0f) {
            progress.animateTo(0f, tween(SUB_LYRIC_CLOSE_MS, easing = FastOutSlowInEasing))
        }
        if (target != null) {
            shown.value = target
            progress.animateTo(1f, tween(SUB_LYRIC_OPEN_MS, easing = LYRIC_EASING))
        } else {
            shown.value = null
        }
    }
    return remember(trackKey) { SubLyricsReveal(progress.asState(), shown) }
}

/**
 * Opens downward out of the line above: the height grows from nothing while
 * the words slide down from behind the original and fade up. Both read
 * [progress] outside composition, so only layout and draw run per frame.
 */
internal fun Modifier.revealBelow(progress: State<Float>): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val open = progress.value
        val height = (placeable.height * open).roundToInt()
        layout(placeable.width, height) {
            placeable.placeWithLayer(0, 0) {
                translationY = -placeable.height * (1f - open) * 0.6f
                alpha = open * open
            }
        }
    }

/**
 * Opens the answering voice under its lead as [progress] runs from 0 to 1.
 *
 * The room it takes opens with it, so the rows below make way rather than
 * jump; the words fade in a little ahead of the room being made, and rise the
 * last few pixels into place from a hair smaller — the same small arrival
 * am-lyrics gives its backing line. Scaled from the edge the line is written
 * from, so a right-hand duet line grows out of its own margin.
 *
 * Nothing is drawn at all once it is closed: the layer is at zero alpha, and
 * the sweep inside it is never asked for a frame.
 */
internal fun Modifier.revealBacking(progress: State<Float>, alignEnd: Boolean): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val open = progress.value
        val rise = BACKING_RISE.toPx()
        layout(placeable.width, (placeable.height * open).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                alpha = (open * BACKING_FADE_LEAD).coerceAtMost(1f)
                translationY = rise * (1f - open)
                val grow = BACKING_REST_SCALE + (1f - BACKING_REST_SCALE) * open
                scaleX = grow
                scaleY = grow
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
            }
        }
    }

internal fun String.differsFrom(original: String): Boolean =
    trim().lowercase(Locale.ROOT) != original.trim().lowercase(Locale.ROOT)

/**
 * The answering vocal without the parentheses every text-only source wraps it
 * in — see [withBackgroundVocals]. Apple Music draws its own equivalent line
 * bare, and the brackets were only ever there to mark the split before there
 * was a row of its own to draw it on.
 *
 * The LRC writer still gets the line with its brackets: that punctuation is
 * what the provider published, so a downloaded file keeps it. This is a
 * display-only trim, done here rather than in the data layer, and applied to
 * the words too, not just [LyricLine.text] — [SweptLyricLine] measures the
 * words against the text it draws, and a sweep reading "(echoed" against a
 * line reading "echoed" would search for a substring that is no longer there.
 */
internal fun LyricLine.withoutBracketPunctuation(): LyricLine = copy(
    text = text.stripParens(),
    words = words.mapNotNull { word -> word.withoutChars { it == '(' || it == ')' } },
)

internal fun String.stripParens(): String = replace("(", "").replace(")", "").trim()

/** The strip's fixed line box — about what Latin type at 16sp makes of itself. */
internal val STRIP_LINE_HEIGHT = 20.sp

/** How much of a line's own length it spends fading out at the end, clamped to this range. */
internal const val LYRIC_FADE_FRACTION = 0.28f
internal const val LYRIC_FADE_MIN_MS = 160f
internal const val LYRIC_FADE_MAX_MS = 700f

/**
 * The single lyric line above the scrubber.
 *
 * The line fades out as it nears its end, and the next one slides up in its
 * place — [AnimatedContent] carries the slide, respecting
 * [PlayerSettings.reduceAnimation].
 */
@Composable
internal fun CurrentLyricLine(
    lines: List<LyricLine>,
    trackKey: Any,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    // One line box whatever the script: left to the font, a Devanagari or
    // Gurmukhi line is taller than a Latin one and the credits and artwork
    // above this strip shift up and down as lines change language.
    val stripStyle = MaterialTheme.typography.titleMedium.copy(
        lineHeight = STRIP_LINE_HEIGHT,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    if (!isSynced) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = BitChordIcons.Instrumental,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Lyrics",
                style = stripStyle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
        return
    }

    val clock = rememberLyricClock(trackKey, playhead, isPlaying)

    val index by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
    }
    val current = lines.getOrNull(index)
    // Before the first line, and through instrumental breaks, show the note.
    val instrumental = current == null || current.isGap
    // Everything ahead of the first sung line is the intro — LRC files open on a
    // bare [00:00.00] gap, so that stretch is gap lines rather than nothing.
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
    val intro = instrumental && firstSung >= 0 && index < firstSung
    // The intro gets one of the slang lines; mid-song breaks stay plain.
    val introLines = LYRICS_INTRO_LINES
    // `stringArrayResource` may return a new array on every recomposition.
    // Keying this selection to that array made the intro copy change whenever
    // the playback clock recomposed the strip. Pick it once for this track.
    val introLine = remember(trackKey) { introLines.random() }
    // The strip is one line and switches the moment the next one is due, so
    // the answering vocal — where there is one — has nowhere to go: showing
    // it would mean either cutting it short when the next line arrives or
    // holding the strip back and leaving a gap before the next line's own
    // words appear. [LyricsPanel] has the room to draw it properly; here it
    // is simply left off, same as before this line had a bracket in it.
    val text = when {
        intro -> introLine
        instrumental -> "Instrumental"
        else -> current.text
    }

    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    // Content-based RTL: drive the row's layout direction from the active lyric's
    // own text direction so alignment and sweep geometry match what Compose would
    // produce for that paragraph.  LTR rows keep the chevron on the right; RTL
    // rows put it on the left (mirrored) and right-align the text.
    val stripRtl = isRtl(text)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (stripRtl) Arrangement.End else Arrangement.Start,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        if (instrumental) {
            Icon(
                imageVector = BitChordIcons.Instrumental,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        AnimatedContent(
            targetState = Triple(index, current, text),
            transitionSpec = {
                val duration = if (reduceAnimation) 0 else 340
                if (reduceAnimation) {
                    (fadeIn(snap()) togetherWith fadeOut(snap())).using(
                        SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> snap() })
                    )
                } else {
                    // The arriving line is not faded in: each line's own dynamic
                    // fade (see the content below) has already taken the old line
                    // out, so the new one slides straight up at full strength.
                    slideInVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> (height * 0.35f).toInt() }
                        .togetherWith(
                            fadeOut(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
                                slideOutVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> -(height * 0.35f).toInt() }
                        ).using(
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration, easing = FastOutSlowInEasing) })
                        )
                }
            },
            label = "currentLyricTransition",
            modifier = Modifier.weight(1f, fill = false),
        ) { (itemIndex, lineItem, lineText) ->
            val itemInstrumental = lineItem == null || lineItem.isGap
            val swept = lineItem?.takeIf { !itemInstrumental && it.isWordSynced }
            // Each line carries its own fade, read against its own end: the line
            // leaving the strip is already faded out and stays that way while it
            // slides off, and the one arriving is at full strength from its
            // first frame. A single fade on the row would hand the old line the
            // new line's alpha the instant the index changed.
            val fading = Modifier.graphicsLayer {
                if (itemInstrumental) return@graphicsLayer
                val start = lines.getOrNull(itemIndex)?.timeMs ?: 0L
                val end = lines.getOrNull(itemIndex + 1)?.timeMs
                    ?: durationMs.takeIf { it > start }
                    ?: (start + 4_000L)
                val fade = ((end - start) * LYRIC_FADE_FRACTION)
                    .coerceIn(LYRIC_FADE_MIN_MS, LYRIC_FADE_MAX_MS)
                val remaining = (end - clock.longValue).toFloat()
                alpha = 0.78f * (remaining / fade).coerceIn(0f, 1f)
            }
            if (swept != null) {
                SweptLyricLine(
                    line = swept,
                    clock = clock,
                    style = voiceStyle(stripStyle, text, alignEnd = false, laneLocked = false),
                    dimAlpha = UNSUNG_ALPHA_STRIP,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    rise = false,
                    modifier = fading,
                )
            } else {
                Text(
                    modifier = fading,
                    text = lineText,
                    style = voiceStyle(
                        stripStyle.copy(
                            color = if (itemInstrumental) Color.White.copy(alpha = 0.5f) else Color.White
                        ),
                        lineText,
                        alignEnd = false,
                        laneLocked = false,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!stripRtl) {
            Spacer(Modifier.width(6.dp))
            // Disclosure hint: this strip opens the full lyrics screen.
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        } else {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { scaleX = -1f },
            )
        }
    }
}

/**
 * Stands in for [CurrentLyricLine] once a lookup has come back empty — shown
 * for a few seconds so it registers, then left to fade rather than snapping
 * out or lingering for the rest of the track.
 */
@Composable
internal fun LyricsUnavailableLine(trackKey: Any, modifier: Modifier = Modifier) {
    var visible by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        delay(LYRICS_UNAVAILABLE_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.55f else 0f,
        animationSpec = tween(durationMillis = LYRICS_UNAVAILABLE_FADE_MS),
        label = "lyricsUnavailableAlpha",
    )
    Text(
        text = "Lyrics not available",
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

/** Stands in for [CurrentLyricLine] while a lookup is still in flight. */
@Composable
internal fun LyricsLoadingLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 4.dp),
    )
}
