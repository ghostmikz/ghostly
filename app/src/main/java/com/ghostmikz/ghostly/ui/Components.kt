package com.ghostmikz.ghostly.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.layout.layout
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.CompositionLocalProvider
import com.ghostmikz.ghostly.ui.backdrop.backdrops.rememberLayerBackdrop
import com.ghostmikz.ghostly.ui.backdrop.backdrops.layerBackdrop
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.R
import com.ghostmikz.ghostly.data.Song
import com.ghostmikz.ghostly.ui.icons.BitChordIcons

private fun Modifier.plainClick(onClick: () -> Unit) =
    clickable(interactionSource = null, indication = null, onClick = onClick)

// ---------- buttons ----------

/** Dark round button used in screen headers (back, settings, ...). */
@Composable
fun CircleButton(icon: ImageVector, description: String, size: Dp = 48.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).bounceClick(scaleDown = 0.9f, onClick = onClick).clip(CircleShape).background(GhostlyColors.Surface)
            .border(1.dp, GhostlyColors.Card, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = GhostlyColors.Text, modifier = Modifier.size(size * 0.5f)) }
}

/** White "Play" pill next to a dark outlined "Shuffle" pill. */
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.weight(1f).height(50.dp).bounceClick(onClick = onPlay).clip(CircleShape).background(GhostlyColors.Text),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_player_play), null, tint = GhostlyColors.Background, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play", color = GhostlyColors.Background, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.weight(1f).height(50.dp).bounceClick(onClick = onShuffle).clip(CircleShape).background(GhostlyColors.Surface)
                .border(1.dp, GhostlyColors.Outline, CircleShape),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(BitChordIcons.Shuffle, null, tint = GhostlyColors.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Shuffle", color = GhostlyColors.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Pill-shaped segmented tabs (Songs / Artists / Albums / Folders, and Settings'
 * two- and three-way choices). The selected segment is a pill that slides
 * between the others, as BitChord's PillSegmentedControl.
 */
@Composable
fun SegmentedTabs(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    container: Color = GhostlyColors.Card,
    /**
     * The pill's position from [rememberSegmentSlide], for tabs that are rebuilt
     * when the selection changes (the Library's, which sit inside each tab's own
     * list): kept outside, the slide survives the rebuild.
     */
    slide: androidx.compose.runtime.State<Float>? = null,
) {
    if (labels.isEmpty()) return
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val own = rememberSegmentSlide(selected)
    val pill = slide ?: own
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(42.dp).clip(CircleShape)
            .background(container).padding(4.dp),
    ) {
        val segment = constraints.maxWidth.toFloat() / labels.size
        Box(
            Modifier
                .matchParentSize()
                // Read here rather than in composition, so the slide relays this
                // box out and recomposes nothing.
                .layout { measurable, c ->
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(segment.roundToInt(), c.maxHeight))
                    layout(c.maxWidth, c.maxHeight) { placeable.place((pill.value * segment).roundToInt(), 0) }
                }
                .clip(CircleShape)
                .background(GhostlyColors.Selected),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).plainClick {
                        if (i != selected) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    // Shrinks to fit rather than being cut off: five labels share a
                    // narrow phone's width, more so with large text.
                    androidx.compose.foundation.text.BasicText(
                        label,
                        style = androidx.compose.ui.text.TextStyle(color = GhostlyColors.Text, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** Where the selected pill of [SegmentedTabs] is, sliding on BitChord's spring (or snapping with Reduce animation). */
@Composable
fun rememberSegmentSlide(selected: Int): androidx.compose.runtime.State<Float> {
    val reduceAnimation by com.ghostmikz.ghostly.data.Prefs.reduceAnimation.state.collectAsState()
    val motion: androidx.compose.animation.core.AnimationSpec<Float> =
        if (reduceAnimation) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.spring(PILL_DAMPING, androidx.compose.animation.core.Spring.StiffnessMediumLow)
    return androidx.compose.animation.core.animateFloatAsState(selected.coerceAtLeast(0).toFloat(), motion, label = "segmentStart")
}

/** BitChord's pill spring: a little give, no wobble. */
private const val PILL_DAMPING = 0.8f

// ---------- titles ----------

@Composable
fun BigTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.displayLarge, color = GhostlyColors.Text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun ShelfTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = GhostlyColors.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp))
}

// ---------- rows and cards ----------

/** One song: cover, title, artist, length and a menu. Thin divider under the text like BitChord. */
@Composable
fun SongRow(
    song: Song,
    playing: Boolean,
    onClick: () -> Unit,
    menu: @Composable (onDismiss: () -> Unit) -> Unit,
    trackNumber: Int? = null,
    onPlayNext: (() -> Unit)? = null,
) {
    val swipe by com.ghostmikz.ghostly.data.Prefs.swipePlayNext.state.collectAsState()
    if (onPlayNext == null || !swipe) {
        SongRowContent(song, playing, onClick, menu, trackNumber)
        return
    }
    // "Play next on swipe": drag the row to the right to queue it next; it springs back.
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.StartToEnd) {
            onPlayNext()
            QueueNotice.show("Song will play next")
        }
        false
    })
    SwipeToDismissBox(
        state, enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(Modifier.fillMaxSize().background(GhostlyColors.CardHigh).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(BitChordIcons.Queue, null, tint = GhostlyColors.Text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Play Next", color = GhostlyColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { Box(Modifier.background(GhostlyColors.Background)) { SongRowContent(song, playing, onClick, menu, trackNumber) } }
}

@Composable
private fun SongRowContent(
    song: Song,
    playing: Boolean,
    onClick: () -> Unit,
    menu: @Composable (onDismiss: () -> Unit) -> Unit,
    trackNumber: Int?,
) {
    var open by remember { mutableStateOf(false) }
    // BitChord's album rows: the playing one fades to the accent, with a faint
    // wash of it behind. Its library lists leave the playing row plain, as here.
    val tinted = trackNumber != null && playing
    val titleColor by animateColorAsState(if (tinted) GhostlyColors.Accent else GhostlyColors.Text, label = "song row title")
    val rowBackground by animateColorAsState(
        if (tinted) GhostlyColors.Accent.copy(alpha = 0.14f) else Color.Transparent, label = "song row background",
    )
    Row(
        Modifier.fillMaxWidth().drawBehind { drawRect(rowBackground) }.clickable(onClick = onClick).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (playing) EqBars(Modifier.size(16.dp))
                else Text(if (trackNumber > 0) "$trackNumber" else "-", color = GhostlyColors.TextSecondary, fontSize = 16.sp)
            }
        } else {
            Box(Modifier.size(52.dp)) {
                AlbumArt(song, Modifier.fillMaxSize(), corner = 6.dp)
                if (playing) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)), Alignment.Center) {
                        EqBars(Modifier.size(20.dp), Color.White)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = titleColor, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = GhostlyColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(formatTime(song.durationMs), color = GhostlyColors.TextSecondary, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 8.dp))
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = GhostlyColors.Text) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false },
                        containerColor = GhostlyColors.Card, shape = RoundedCornerShape(14.dp)) { menu { open = false } }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.Divider)
        }
    }
}

/** Square cover with two lines of text under it, for the sideways shelves. */
@Composable
fun SquareCard(song: Song?, title: String, subtitle: String, width: Dp = 160.dp, round: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(width).bounceClick(scaleDown = 0.95f, onClick = onClick)) {
        AlbumArt(song, Modifier.size(width), corner = if (round) width / 2 else 10.dp, px = 400)
        Spacer(Modifier.height(8.dp))
        Text(title, color = GhostlyColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = GhostlyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier)
        }
    }
}

@Composable
fun <T> Shelf(title: String, items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    ShelfTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = key) { card(it) }
    }
}

/** Plain row with an icon, label and chevron (Library's "Liked", "Recently Played"). */
@Composable
fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = GhostlyColors.Text, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 22.dp)) {
            Row(Modifier.padding(vertical = 16.dp, horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = GhostlyColors.Text, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Icon(BitChordIcons.ChevronRight, null, tint = GhostlyColors.TextSecondary,
                    modifier = Modifier.padding(end = 16.dp).size(18.dp))
            }
            HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.Divider)
        }
    }
}

// ---------- floating bars ----------

data class TabItem(val label: String, val icon: ImageVector)

/**
 * The default floating tab bar: BitChord's FloatingBottomBar. A frosted bar whose
 * pill rides a spring (damping 0.72, stiffness 320) and stretches while it is
 * behind where it is going; the chosen icon grows 8% on the same spring.
 */
@Composable
fun FloatingTabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val motion = motionOn()
    val tick = rememberTick()
    val density = LocalDensity.current
    val spec: AnimationSpec<Float> = if (motion) spring(dampingRatio = 0.72f, stiffness = 320f) else snap()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 6.dp.toPx() }
    val n = tabs.size
    val tabWidthPx = if (rowSize.width > 0) (rowSize.width - gapPx * (n - 1)) / n else 0f
    val stepPx = if (rowSize.width > 0) (rowSize.width + gapPx) / n else 0f
    val target = if (stepPx > 0f) selected * stepPx + dragOffset else 0f
    val offset by animateFloatAsState(target, spec, label = "pillOffset")
    // how much of a tab's stride the pill is still behind; the stretch follows only this
    val lag = if (stepPx > 0f) (kotlin.math.abs(target - offset) / stepPx).coerceIn(0f, 1f) else 0f
    LaunchedEffect(selected) { dragOffset = 0f }
    val currentStep by rememberUpdatedState(stepPx)
    val currentSelected by rememberUpdatedState(selected)
    val choose by rememberUpdatedState(onSelect)

    Box(
        modifier.fillMaxWidth().clip(CircleShape).frosted(CircleShape, GhostlyColors.Surface)
            .border(GLASS_EDGE_WIDTH, if (LocalPalette.current.isDark) GLASS_EDGE_COLOR else GhostlyColors.BarBorder, CircleShape)
            .padding(4.dp),
    ) {
        if (tabWidthPx > 0f) {
            Box(
                Modifier.width(with(density) { tabWidthPx.toDp() }).height(with(density) { rowSize.height.toDp() })
                    .graphicsLayer {
                        translationX = offset
                        scaleX = 1f + lag * 0.16f
                        scaleY = 1f - lag * 0.16f * 0.5f
                    }
                    .clip(CircleShape).background(GhostlyColors.Text.copy(alpha = 0.14f)),
            )
        }
        Row(
            Modifier.fillMaxWidth().onSizeChanged { rowSize = it }
                .pointerInput(Unit) {
                    var total = 0f
                    var start = currentSelected
                    var lastTab = currentSelected
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f; start = currentSelected; lastTab = start },
                        onDragCancel = { dragOffset = 0f },
                        onDragEnd = {
                            val step = currentStep
                            if (step > 0f) {
                                val i = (start + total / step).coerceIn(0f, (n - 1).toFloat()).roundToInt()
                                if (i != currentSelected) choose(i)
                            }
                            dragOffset = 0f
                        },
                    ) { _, delta ->
                        val step = currentStep
                        if (step <= 0f) return@detectHorizontalDragGestures
                        total += delta
                        val at = (start + total / step).coerceIn(0f, (n - 1).toFloat())
                        dragOffset = at * step - currentSelected * step
                        if (at.roundToInt() != lastTab) { lastTab = at.roundToInt(); tick() }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, tab ->
                val on = i == selected
                val scale by animateFloatAsState(if (on) 1.08f else 1f, spec, label = "tabScale")
                val tint by animateColorAsState(if (on) GhostlyColors.Text else GhostlyColors.TextSecondary, tween(200), label = "tabTint")
                Column(
                    Modifier.weight(1f).clip(CircleShape).plainClick { if (!on) tick(); onSelect(i) }.padding(vertical = 6.5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(28.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                    Spacer(Modifier.height(1.dp))
                    Text(tab.label, color = tint, fontSize = 10.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The Liquid Glass tab bar: BitChord's FloatingTabBar. The bar is real glass, and the
 * selection pill (PillMotion.kt) lifts into a lens that refracts the bar and its tabs
 * while it travels, squashing with its acceleration, then lands flat.
 */
@Composable
fun GlassTabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val motion = motionOn()
    val tick = rememberTick()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pill = remember { GlassPillMotion(scope, selected) }
    pill.reduceMotion = !motion
    LaunchedEffect(selected) { pill.animateTo(selected) }
    val choose by rememberUpdatedState(onSelect)
    val reduceBlur by com.ghostmikz.ghostly.data.Prefs.reduceBlur.state.collectAsState()
    // The lens samples the recorded page; without it the flat pill carries the motion alone.
    val glassPill = isGlassSupported() && !reduceBlur && LocalAppBackdrop.current != null
    val barGlass = rememberLayerBackdrop()
    val tabRow = rememberLayerBackdrop()
    val content = glassContentColor()
    val spec: AnimationSpec<Float> = if (motion) spring(dampingRatio = 0.72f, stiffness = 320f) else snap()

    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val stepPx = if (rowSize.width > 0) rowSize.width.toFloat() / tabs.size else 0f
    pill.stepDp = stepPx / density.density
    val padPx = with(density) { 4.dp.toPx() }
    val rowH = rowSize.height.toFloat()
    val restSize = Size(stepPx, rowH)
    // lifted: as tall as the whole bar plus BitChord's 9dp, keeping the tab's proportions
    val liftedH = rowH + padPx * 2 + with(density) { PILL_GROW_HEIGHT.toPx() }
    val liftedSize = if (rowH > 0f) Size(liftedH * stepPx / rowH, liftedH) else restSize

    // Unclipped: the lifted lens stands past the bar's edges, so it is drawn over the bar.
    Box(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalGlassExport provides barGlass) {
            Box(Modifier.fillMaxWidth().height(64.dp).clip(CircleShape).liquidGlass(CircleShape).padding(4.dp)) {
                if (stepPx > 0f && (!glassPill || pill.isFlat)) {
                    FlatSelectionPill(
                        glassIndicatorColor(),
                        Modifier.matchParentSize().pillPlacement(
                            center = { Offset(pill.position * stepPx + stepPx / 2f, rowH / 2f) },
                            size = { pill.liveSize(restSize, liftedSize) },
                        ),
                    )
                }
                Row(
                    Modifier.fillMaxSize().onSizeChanged { rowSize = it }.layerBackdrop(tabRow)
                        .pointerInput(tabs.size) {
                            var last = -1
                            detectHorizontalDragGestures(
                                onDragStart = { o -> pill.startDrag(); last = (o.x / (size.width / tabs.size)).toInt() },
                                onDragEnd = {
                                    val i = pill.position.roundToInt().coerceIn(0, tabs.lastIndex)
                                    pill.release(i); choose(i)
                                },
                                onDragCancel = { pill.release(pill.index) },
                            ) { change, _ ->
                                val step = size.width.toFloat() / tabs.size
                                pill.dragTo((change.position.x / step - 0.5f).coerceIn(0f, tabs.lastIndex.toFloat()))
                                val over = (change.position.x / step).toInt().coerceIn(0, tabs.lastIndex)
                                if (over != last) { last = over; tick() }
                            }
                        },
                ) {
                    tabs.forEachIndexed { i, tab ->
                        val on = i == selected
                        val scale by animateFloatAsState(if (on) 1.08f else 1f, spec, label = "glassTabScale")
                        val fg by animateColorAsState(if (on) content else content.copy(alpha = 0.65f), tween(200), label = "glassTabFg")
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                                .plainClick { if (i != selected) tick(); onSelect(i) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                            Spacer(Modifier.height(1.dp))
                            Text(tab.label, color = fg, fontSize = 10.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (glassPill && stepPx > 0f && !pill.isFlat) {
            GlassSelectionPill(
                motion = pill, fillColor = glassIndicatorColor(), barGlass = barGlass, tabRow = tabRow,
                modifier = Modifier.matchParentSize().pillPlacement(
                    center = { Offset(pill.position * stepPx + stepPx / 2f + padPx, rowH / 2f + padPx) },
                    size = { pill.liveSize(restSize, liftedSize) },
                ),
            )
        }
    }
}

/** The tab bar shrunk to one round button while scrolling down; tap it to bring the bar back. */
@Composable
fun TabBubble(tab: TabItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(64.dp).clip(CircleShape).liquidGlass(CircleShape).bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(tab.icon, tab.label, tint = glassContentColor(), modifier = Modifier.size(26.dp)) }
}

/** Floating mini player that sits above the tab bar. */
@Composable
fun MiniPlayer(
    state: PlayerState, vm: PlayerViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier,
    sidePadding: Dp = 18.dp, dragModifier: Modifier = Modifier,
    /** Where the cover is, so the player's cover can fly to and from it (BitChord's PlayerDock). */
    onArtPlaced: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    /** True while the player's own cover is in flight over this one. */
    hideArt: () -> Boolean = { false },
) {
    val song = state.current ?: return
    Row(
        modifier.fillMaxWidth().padding(horizontal = sidePadding).height(64.dp).clip(CircleShape)
            .background(GhostlyColors.MiniBar).border(1.dp, GhostlyColors.BarBorder, CircleShape)
            .miniTrackSwipe(onNext = { vm.next() }, onPrevious = { vm.previous() })
            .then(dragModifier)
            .bounceClick(scaleDown = 0.97f, haptic = false, onClick = onOpen)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            song,
            Modifier.size(46.dp).onGloballyPositioned(onArtPlaced).graphicsLayer { alpha = if (hideArt()) 0f else 1f },
            corner = 8.dp,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = GhostlyColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = GhostlyColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            painterResource(if (state.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play),
            "Play or pause", tint = GhostlyColors.Text,
            modifier = Modifier.size(44.dp).bounceClick(scaleDown = 0.8f) { vm.togglePlay() }.padding(4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            painterResource(R.drawable.ic_player_next), "Next", tint = GhostlyColors.Text,
            modifier = Modifier.size(44.dp).bounceClick(scaleDown = 0.8f) { vm.next() }.padding(4.dp),
        )
    }
}

// ---------- menu items shared by song rows ----------

@Composable
fun SongMenuItems(song: Song, actions: LibraryActions, onDismiss: () -> Unit) {
    val favs by com.ghostmikz.ghostly.data.History.favorites.collectAsState()
    val fav = song.id in favs
    @Composable
    fun item(text: String, icon: ImageVector, run: () -> Unit) = DropdownMenuItem(
        text = { Text(text, color = GhostlyColors.Text) },
        leadingIcon = { Icon(icon, null, tint = GhostlyColors.Text, modifier = Modifier.size(20.dp)) },
        onClick = { onDismiss(); run() },
    )
    item("Play next", BitChordIcons.Queue) { actions.playNext(song); QueueNotice.show("Song will play next") }
    item("Add to playlist", BitChordIcons.Plus) { actions.addToPlaylist(song) }
    actions.removeFromPlaylist?.let { remove -> item("Remove from playlist", BitChordIcons.Library) { remove(song) } }
    item(if (fav) "Remove from Liked" else "Like", if (fav) BitChordIcons.StarFilled else BitChordIcons.Star) {
        com.ghostmikz.ghostly.data.History.toggleFavorite(song.id)
    }
    item("Go to album", BitChordIcons.Library) { actions.openAlbum(song.albumId) }
    item("Go to artist", BitChordIcons.MusicNote) { actions.openArtist(song.artist) }
}

/** Is music playing right now? Lets the equalizer bars stand still while paused. */
val LocalIsPlaying = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Three bouncing bars that mark the current song; they freeze while paused. */
@Composable
fun EqBars(modifier: Modifier = Modifier, color: Color = GhostlyColors.Text) {
    val moving = LocalIsPlaying.current
    // Only ticks while playing: paused bars are still, so nothing should keep
    // drawing frames for them. Read in the draw block, so it redraws, not recomposes.
    val phase = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(moving) {
        while (moving) {
            phase.snapTo(0f)
            phase.animateTo(1f, tween(900, easing = LinearEasing))
        }
    }
    Canvas(modifier) {
        val bar = size.width / 5
        listOf(0f, 0.33f, 0.66f).forEachIndexed { i, offset ->
            val wave = if (moving) kotlin.math.abs(kotlin.math.sin((phase.value + offset) * Math.PI)).toFloat() else 0.25f + 0.25f * i
            val h = size.height * (0.35f + 0.65f * wave)
            drawRoundRect(
                color, Offset(bar * (i * 2), size.height - h),
                Size(bar, h), CornerRadius(bar / 2, bar / 2),
            )
        }
    }
}

// ---------- the floor under the floating bars ----------

/** The strip the tab bar alone needs, above the gesture inset (BitChord). */
private val FADE_HEIGHT = 180.dp

/** Taller once the mini player is stacked on top of the tab bar. */
private val FADE_HEIGHT_WITH_MINI_PLAYER = 254.dp

/**
 * How many colour stops the ramp is cut into: a two-stop gradient is the wrong
 * curve, and across a strip this tall it bands visibly on an 8-bit display.
 */
private const val FADE_STOPS = 16

/**
 * BitChord's BottomFadeScrim: the page's own colour, faded in from nothing at
 * the top to solid at the very bottom, under the floating bars. It grows with
 * the mini player rather than snapping ahead of it.
 */
@Composable
fun BottomFadeScrim(modifier: Modifier = Modifier, withMiniPlayer: Boolean, inset: androidx.compose.ui.unit.Dp) {
    val height by androidx.compose.animation.core.animateDpAsState(
        inset + if (withMiniPlayer) FADE_HEIGHT_WITH_MINI_PLAYER else FADE_HEIGHT,
        // Matches the beat the mini player takes to appear.
        androidx.compose.animation.core.tween(220),
        label = "bottomScrimHeight",
    )
    val pageColor = GhostlyColors.Background
    val brush = remember(pageColor) {
        // A cubic ease-in rather than a straight ramp: the alpha holds under a few
        // percent for the first half, so the eye can't find where the layer starts.
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colorStops = Array(FADE_STOPS) { i ->
                val t = i / (FADE_STOPS - 1f)
                t to pageColor.copy(alpha = androidx.compose.animation.core.EaseInCubic.transform(t))
            },
        )
    }
    Box(modifier.fillMaxWidth().height(height).background(brush))
}

// ---------- queue notices ----------

/**
 * BitChord's QueueActionNotice: a brief confirmation ("Song will play next")
 * shown just above the mini player. Each one has its own id, so repeating the
 * same action restarts its lifetime.
 */
object QueueNotice {
    data class Notice(val id: Int, val message: String)

    private val _current = kotlinx.coroutines.flow.MutableStateFlow<Notice?>(null)
    val current: kotlinx.coroutines.flow.StateFlow<Notice?> = _current
    private var nextId = 0

    fun show(message: String) {
        nextId += 1
        _current.value = Notice(nextId, message)
    }

    internal fun dismiss(id: Int) {
        if (_current.value?.id == id) _current.value = null
    }
}

/** Where [QueueNotice]s appear: fades and slides up, gone after three seconds (BitChord). */
@Composable
fun QueueNoticeHost(modifier: Modifier = Modifier) {
    val notice by QueueNotice.current.collectAsState()
    val reduceAnimation by com.ghostmikz.ghostly.data.Prefs.reduceAnimation.state.collectAsState()
    LaunchedEffect(notice?.id) {
        val shown = notice ?: return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        QueueNotice.dismiss(shown.id)
    }
    androidx.compose.animation.AnimatedContent(
        targetState = notice,
        transitionSpec = {
            if (reduceAnimation) {
                androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            } else {
                (androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 }) togetherWith
                    (androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 })
            }
        },
        contentKey = { it?.id },
        label = "queueActionNotice",
        modifier = modifier.fillMaxWidth(),
    ) { current ->
        if (current != null) {
            androidx.compose.material3.Surface(
                color = Color(0xFF282828),
                contentColor = Color.White,
                shape = RoundedCornerShape(5.dp),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            ) {
                Text(current.message, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
    }
}

// ---------- switch ----------

private val SwitchTrackWidth = 64.dp
private val SwitchTrackHeight = 28.dp
private val SwitchThumbWidth = 40.dp
private val SwitchThumbHeight = 24.dp
private val SwitchThumbPadding = 2.dp

/**
 * BitChord's IosSwitch: a wide green capsule and a white pill of a thumb, which
 * stretches a little toward where it is going while pressed. It buzzes when
 * [checked] flips rather than when it is tapped, since the row around it toggles
 * too. A null [onCheckedChange] makes it a read-out; the row owns the tap.
 */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = GhostlyColors.Background.luminance() < 0.5f
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val position by androidx.compose.animation.core.animateFloatAsState(
        if (checked) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 700f), label = "iosSwitchThumb",
    )
    val stretch by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 1.18f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 900f), label = "iosSwitchStretch",
    )
    val track by animateColorAsState(
        when {
            checked && dark -> Color(0xFF30D158)
            checked -> Color(0xFF34C759)
            else -> Color(0xFF8E8E93).copy(alpha = if (dark) 0.36f else 0.22f)
        },
        label = "iosSwitchTrack",
    )
    val ltr = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Ltr
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Skips the first run, so a switch scrolling into view stays quiet.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(checked) {
        if (settled) haptics.performHapticFeedback(
            if (checked) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
            else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff,
        )
        settled = true
    }
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                if (onCheckedChange != null) {
                    Modifier.minimumInteractiveComponentSize().toggleable(
                        value = checked, enabled = enabled, interactionSource = interaction, indication = null,
                        role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange,
                    )
                } else {
                    Modifier
                },
            )
            .size(SwitchTrackWidth, SwitchTrackHeight)
            .clip(CircleShape)
            .background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val travel = (SwitchTrackWidth - SwitchThumbWidth - SwitchThumbPadding * 2).toPx()
                    val x = SwitchThumbPadding.toPx() + travel * position
                    translationX = if (ltr) x else -x
                    // Grows from the edge it is leaving, so the stretch reads as
                    // reaching toward the other side.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (checked == ltr) 1f else 0f, 0.5f)
                    scaleX = stretch
                }
                .size(SwitchThumbWidth, SwitchThumbHeight)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

// ---------- locate the playing song ----------

/** The song list on screen, and its row that is playing, for [LocatePlayingButton]. */
object LocateTarget {
    class Target(val listState: androidx.compose.foundation.lazy.LazyListState, val row: Int?)

    internal var current by mutableStateOf<Target?>(null)
}

/**
 * Tells [LocatePlayingButton] about this song list while it is on screen. The
 * button itself is drawn over the page, not in it, so it can be glass like the
 * other floating buttons: glass samples the page, and can't sample itself.
 */
@Composable
fun LocateTargetFor(listState: androidx.compose.foundation.lazy.LazyListState, playingRow: Int?) {
    val target = remember(listState, playingRow) { LocateTarget.Target(listState, playingRow) }
    androidx.compose.runtime.DisposableEffect(target) {
        LocateTarget.current = target
        onDispose { if (LocateTarget.current === target) LocateTarget.current = null }
    }
}

/**
 * A round button in the corner that scrolls the song list on screen to the song
 * that is playing. It only shows while that song is in the list but out of
 * sight, and goes away again once it is on screen.
 */
@Composable
fun LocatePlayingButton(modifier: Modifier = Modifier) {
    val target = LocateTarget.current
    val listState = target?.listState
    val targetIndex = target?.row
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduceAnimation by com.ghostmikz.ghostly.data.Prefs.reduceAnimation.state.collectAsState()
    val offScreen by remember(listState, targetIndex) {
        androidx.compose.runtime.derivedStateOf {
            val state = listState ?: return@derivedStateOf false
            val index = targetIndex ?: return@derivedStateOf false
            val info = state.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == index }
            // Counted as out of sight until the whole row clears the bars below.
            row == null || row.offset < info.viewportStartOffset || row.offset + row.size > info.viewportEndOffset - info.afterContentPadding
        }
    }
    val glass = LocalLiquidGlassEnabled.current
    androidx.compose.animation.AnimatedVisibility(
        visible = offScreen,
        modifier = modifier,
        enter = if (reduceAnimation) androidx.compose.animation.EnterTransition.None
        else androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
        exit = if (reduceAnimation) androidx.compose.animation.ExitTransition.None
        else androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.6f),
    ) {
        // The same material as the floating back and settings buttons.
        Box(
            Modifier.size(48.dp)
                .bounceClick(scaleDown = 0.88f) {
                    val state = listState ?: return@bounceClick
                    val index = targetIndex ?: return@bounceClick
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    scope.launch {
                        // Lands the row about a third of the way down, not hard
                        // against the top under the floating buttons.
                        val lead = -state.layoutInfo.viewportSize.height / 3
                        if (reduceAnimation) state.scrollToItem(index, lead) else state.animateScrollToItem(index, lead)
                    }
                }
                .clip(CircleShape)
                .then(if (glass) Modifier.liquidGlass(CircleShape) else Modifier.frosted(CircleShape, GhostlyColors.Surface))
                .border(1.dp, GhostlyColors.Card, CircleShape)
                .semantics { contentDescription = "Show the playing song" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MyLocation, null, tint = if (glass) glassContentColor() else GhostlyColors.Text, modifier = Modifier.size(24.dp))
        }
    }
}


// ---------- mini player track swipe ----------

/** How far a sideways swipe on the mini player has to go to change the song (BitChord). */
private val TRACK_SWIPE_THRESHOLD = 72.dp

/**
 * BitChord's mini player swipe: sideways through the queue, a left swipe to the
 * next song and a right one back, matching the full player. Decided once, by the
 * way the finger is heading as it passes the touch slop: upwards is left alone
 * for the pull into the player, and a lift inside the slop is the bar's tap.
 * The song changes when the finger lifts, so one long swipe never skips twice.
 */
@Composable
private fun Modifier.miniTrackSwipe(onNext: () -> Unit, onPrevious: () -> Unit): Modifier {
    val currentNext by androidx.compose.runtime.rememberUpdatedState(onNext)
    val currentPrevious by androidx.compose.runtime.rememberUpdatedState(onPrevious)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    return pointerInput(Unit) {
        val threshold = TRACK_SWIPE_THRESHOLD.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointer = down.id
            var sideways: androidx.compose.ui.input.pointer.PointerInputChange? = null
            while (sideways == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                val moved = change.position - down.position
                if (moved.getDistance() < viewConfiguration.touchSlop) continue
                // Not sideways: the pull up (or nothing) has it.
                if (kotlin.math.abs(moved.x) < kotlin.math.abs(moved.y)) return@awaitEachGesture
                change.consume()
                sideways = change
            }
            var dragged = sideways.position.x - down.position.x
            val finished = drag(pointer) { change ->
                dragged += change.position.x - change.previousPosition.x
                change.consume()
            }
            if (!finished) return@awaitEachGesture
            when {
                dragged <= -threshold -> { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); currentNext() }
                dragged >= threshold -> { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); currentPrevious() }
            }
        }
    }
}

// ---------- page buttons in the top bar ----------

/**
 * The round buttons a page asks for in the top bar, beside Settings (BitChord's
 * detail-page actions: search this list, sort). Drawn over the page by the app,
 * like the back and Settings buttons, so they share their glass.
 */
object PageActions {
    class Action(val icon: ImageVector, val description: String, val onClick: () -> Unit)

    internal var current by mutableStateOf<List<Action>>(emptyList())
}

/** Puts [actions] in the top bar while this page is on screen. */
@Composable
fun PageActionsFor(actions: List<PageActions.Action>) {
    androidx.compose.runtime.DisposableEffect(actions) {
        PageActions.current = actions
        onDispose { if (PageActions.current === actions) PageActions.current = emptyList() }
    }
}

// ---------- search this list ----------

/**
 * BitChord's DetailSearchField: a pill under a page's header that narrows its
 * list. Opened by a button, so it comes up focused; the cross clears the text,
 * or closes the field once there is nothing left to clear.
 */
@Composable
fun ListSearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp).height(46.dp)
            .clip(CircleShape).background(GhostlyColors.Card.copy(alpha = 0.6f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BitChordIcons.Search, null, tint = GhostlyColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search this list", color = GhostlyColors.TextSecondary, fontSize = 17.sp, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onQueryChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = GhostlyColors.Text, fontSize = 17.sp, fontFamily = Inter),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(GhostlyColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape).clickable { if (query.isEmpty()) onClose() else onQueryChange("") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, if (query.isEmpty()) "Close search" else "Clear search",
                tint = GhostlyColors.TextSecondary, modifier = Modifier.size(17.dp))
        }
    }
}

/** Whether [song] answers a "search this list" query: title, artist or album. */
fun Song.matchesQuery(query: String): Boolean = query.isBlank() ||
    title.contains(query, ignoreCase = true) || artist.contains(query, ignoreCase = true) || album.contains(query, ignoreCase = true)

// ---------- sort a page's tracks ----------

/** BitChord's SongSort for a page's track list: its own order, by title, or by when each song was added. */
enum class SongSort(val label: String) {
    DEFAULT("Default"),
    TITLE_ASC("Title (A to Z)"),
    TITLE_DESC("Title (Z to A)"),
    DATE_ADDED_ASC("Date added"),
    DATE_ADDED_DESC("Date added"),
}

fun List<Song>.sortedForDetail(sort: SongSort): List<Song> = when (sort) {
    SongSort.DEFAULT -> this
    SongSort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    SongSort.TITLE_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
    // Ties keep the page's own order.
    SongSort.DATE_ADDED_ASC -> withIndex().sortedWith(compareBy<IndexedValue<Song>> { it.value.dateAdded }.thenBy { it.index }).map { it.value }
    SongSort.DATE_ADDED_DESC -> withIndex().sortedWith(compareByDescending<IndexedValue<Song>> { it.value.dateAdded }.thenBy { it.index }).map { it.value }
}

/**
 * BitChord's track-list sort menu: Date added as one row whose arrow shows the
 * direction (up for newest first) and flips, animated, on a second tap; then
 * the other orders with a tick on the one in force.
 */
@Composable
fun SongSortMenu(expanded: Boolean, selected: SongSort, onSelect: (SongSort) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded, onDismiss, containerColor = GhostlyColors.Card, shape = RoundedCornerShape(14.dp)) {
        val dateActive = selected == SongSort.DATE_ADDED_ASC || selected == SongSort.DATE_ADDED_DESC
        val arrowRotation by androidx.compose.animation.core.animateFloatAsState(
            if (selected == SongSort.DATE_ADDED_ASC) 180f else 0f, androidx.compose.animation.core.tween(200), label = "dateAddedArrow",
        )
        DropdownMenuItem(
            text = { Text("Date added", color = GhostlyColors.Text) },
            trailingIcon = if (dateActive) {
                { Icon(Icons.Rounded.ArrowUpward, null, tint = GhostlyColors.Accent, modifier = Modifier.rotate(arrowRotation)) }
            } else null,
            onClick = {
                // First tap: newest first. Again: flip the direction.
                onSelect(if (selected == SongSort.DATE_ADDED_DESC) SongSort.DATE_ADDED_ASC else SongSort.DATE_ADDED_DESC)
            },
        )
        SongSort.entries.filter { it != SongSort.DATE_ADDED_ASC && it != SongSort.DATE_ADDED_DESC }.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label, color = GhostlyColors.Text) },
                trailingIcon = if (option == selected) {
                    { Icon(Icons.Rounded.Check, null, tint = GhostlyColors.Accent) }
                } else null,
                onClick = { onSelect(option); onDismiss() },
            )
        }
    }
}
