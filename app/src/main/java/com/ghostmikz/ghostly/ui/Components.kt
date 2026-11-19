package com.ghostmikz.ghostly.ui

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

/** Pill-shaped segmented tabs (Songs / Artists / Albums / Folders). */
@Composable
fun SegmentedTabs(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    container: Color = GhostlyColors.Card,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(42.dp).clip(CircleShape)
            .background(container).padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val bg by animateColorAsState(if (i == selected) GhostlyColors.Selected else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bg).plainClick { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = GhostlyColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

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
            android.widget.Toast.makeText(context, "Playing next: ${song.title}", android.widget.Toast.LENGTH_SHORT).show()
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
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp),
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
                    Text(song.title, color = GhostlyColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium,
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
    item("Play next", BitChordIcons.Queue) { actions.playNext(song) }
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
