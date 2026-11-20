package com.ghostmikz.ghostly.ui

import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.ghostmikz.ghostly.R
import com.ghostmikz.ghostly.data.History
import com.ghostmikz.ghostly.data.Prefs
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ghostmikz.ghostly.ui.icons.BitChordIcons
import kotlinx.coroutines.delay

private val Dim = Color.White.copy(alpha = 0.55f)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: PlayerState, vm: PlayerViewModel, actions: LibraryActions, onClose: () -> Unit,
    dragModifier: Modifier = Modifier,
    /** 1 fully open, 0 down on the mini player (BitChord's PlayerDock.openFraction). */
    openFraction: () -> Float = { 1f },
    /** The mini player's cover in root coordinates, for the cover to fly to; null = plain slide. */
    miniArt: () -> androidx.compose.ui.geometry.Rect? = { null },
    /** How far below fully open the player sits right now, in pixels. */
    sheetOffsetPx: () -> Float = { 0f },
    /** How far the sheet travels from fully open to closed, in pixels. */
    travelPx: () -> Float = { 1f },
) {
    val song = state.current
    var coverCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    // Everything but the cover fades with how open the player is; the cover flies instead.
    val fadeRest = Modifier.graphicsLayer { alpha = if (miniArt() != null) openFraction() else 1f }
    // The sharp cover, with the mini player's small one standing in until it has loaded,
    // so the cover in flight is never the empty placeholder.
    val art = rememberArt(song, 1000) ?: rememberArt(song, 192)
    val reduceAnim by Prefs.reduceAnimation.state.collectAsState()
    val reduceBlur by Prefs.reduceBlur.state.collectAsState()
    val noBlend by Prefs.noBlend.state.collectAsState()
    val fullCover by Prefs.fullCover.state.collectAsState()
    val hideVolume by Prefs.hideVolume.state.collectAsState()
    val hideStatus by Prefs.hideStatusBar.state.collectAsState()
    val hideSongStatus by Prefs.hideSongStatus.state.collectAsState()
    val nerdStats by Prefs.nerdStats.state.collectAsState()
    // BitChord's backdrop: the cover's own colours as a still mesh hung from the cover's bottom edge.
    val mesh = rememberArtworkMesh(song?.albumId, art)
    var seamPx by remember { mutableFloatStateOf(0f) }
    var playerCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    val favs by History.favorites.collectAsState()
    // BitChord's landscapePlayerAvailable: a window wider than tall, and wide enough
    // for two columns, gets the two-column player (a phone on its side, a tablet).
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val sideways = config.screenWidthDp > config.screenHeightDp && config.screenWidthDp >= 560
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    // Apple-style: the cover sits a bit smaller while paused.
    val coverScale by animateFloatAsState(
        if (state.isPlaying || reduceAnim) 1f else 0.84f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cover",
    )
    // "Hide status bar in player"
    val view = LocalView.current
    DisposableEffect(hideStatus) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hideStatus) controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    Box(
        // Dragging down anywhere moves the whole player with the finger (handled by the caller).
        Modifier.fillMaxSize()
            .drawBehind { drawRect(GhostlyColors.Player, alpha = if (miniArt() != null) openFraction() else 1f) }
            .onGloballyPositioned { playerCoords = it }.then(dragModifier),
    ) {
        // "Disable blend effects" leaves the plain dark player.
        if (!noBlend) Box(fadeRest) { ArtworkMeshBackdrop(mesh, seam = { with(density) { seamPx.toDp() } }) }

        // ---- the pieces, shared by the portrait and landscape players ----
            // "Full-screen cover art" runs the cover edge to edge with square corners.
            val restCorner = if (fullCover) 0.dp else 10.dp
            val coverShape = remember(restCorner) {
                object : androidx.compose.ui.graphics.Shape {
                    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
                        RoundedCornerShape(androidx.compose.ui.unit.lerp(8.dp, restCorner, if (miniArt() != null) openFraction() else 1f))
                            .createOutline(size, layoutDirection, density)
                }
            }
        val cover: @Composable (Modifier) -> Unit = { sizing ->
            Box(
                sizing
                    .onGloballyPositioned { coverCoords = it }
                    // BitChord's dock: the cover is *laid out* (not scaled) between the mini player's
                    // cover and this spot, so its corners morph cleanly. It rides only part of the
                    // sheet's travel, so it lands on the mini cover with its speed run down to nothing.
                    .layout { measurable, constraints ->
                        val w = constraints.maxWidth
                        val h = constraints.maxHeight
                        val mini = miniArt()
                        val here = coverCoords?.takeIf { it.isAttached }?.positionInRoot()
                        val t = openFraction()
                        if (mini == null || here == null || t >= 1f || w <= 0) {
                            val pl = measurable.measure(constraints)
                            return@layout layout(w, h) { pl.place(0, 0) }
                        }
                        val offset = sheetOffsetPx()
                        val travel = travelPx().coerceAtLeast(1f)
                        val sleeveOpen = Rect(here.x, here.y - offset, here.x + w, here.y - offset + h)
                        val ride = ((mini.top - sleeveOpen.top) / travel).coerceIn(0f, 1f)
                        val target = lerp(mini, sleeveOpen.translate(0f, offset * ride), t)
                        val pl = measurable.measure(
                            Constraints.fixed(target.width.roundToInt().coerceAtLeast(1), target.height.roundToInt().coerceAtLeast(1)),
                        )
                        layout(w, h) { pl.place((target.left - here.x).roundToInt(), (target.top - here.y).roundToInt()) }
                    }
                    .scale(coverScale)
                    .shadow(if (state.isPlaying && !fullCover) 30.dp else 0.dp, coverShape)
                    .clip(coverShape).background(Color(0xFF0C0C0C))
                    .onGloballyPositioned { c -> playerCoords?.let { seamPx = it.localBoundingBoxOf(c).bottom } },
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(BitChordIcons.MusicNote, null, tint = Color(0xFF5A5A5E), modifier = Modifier.size(72.dp))
            }
        }
        val credits: @Composable () -> Unit = {
            // title, artist, star, more
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(song?.artist ?: "", color = Dim, fontSize = 24.sp, letterSpacing = (-0.4).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { song?.let { onClose(); actions.openArtist(it.artist) } })
                }
                val fav = song != null && song.id in favs
                RoundGlassButton(if (fav) BitChordIcons.StarFilled else BitChordIcons.Star, if (fav) "Remove from Liked" else "Like") {
                    song?.let { History.toggleFavorite(it.id) }
                }
                Spacer(Modifier.width(12.dp))
                Box {
                    RoundGlassButton(Icons.Rounded.MoreHoriz, "More") { showMenu = true }
                    DropdownMenu(showMenu, { showMenu = false }, containerColor = GhostlyColors.Card, shape = RoundedCornerShape(14.dp)) {
                        if (song != null) SongMenuItems(song, actions.copyClosing(onClose)) { showMenu = false }
                        // The player's own (BitChord): about what is playing now.
                        val status = sleepTimerStatus()
                        DropdownMenuItem(
                            text = { Text("Sleep timer", color = GhostlyColors.Text) },
                            leadingIcon = { Icon(Icons.Rounded.Bedtime, null, tint = GhostlyColors.Text, modifier = Modifier.size(20.dp)) },
                            trailingIcon = status?.let { { Text(it, color = GhostlyColors.TextSecondary, fontSize = 14.sp) } },
                            onClick = { showMenu = false; showSleep = true },
                        )
                    }
                }
            }

            // album line, like BitChord's "AC/DC - Back In Black >"
            if (song != null && song.album != "Unknown album") {
                Row(
                    Modifier.padding(top = 18.dp).clickable { onClose(); actions.openAlbum(song.albumId) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(song.album, color = Color.White.copy(alpha = 0.8f), fontSize = 19.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(BitChordIcons.ChevronRight, null, tint = Dim, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                }
            }

        }
        val transportRow: @Composable (Boolean) -> Unit = { compact ->
            // transport
            Row(
                Modifier.fillMaxWidth().padding(vertical = if (compact) 6.dp else 22.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                Transport(R.drawable.ic_player_previous, "Previous", if (compact) 44.dp else 54.dp) { vm.previous() }
                Transport(if (state.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play, "Play or pause", if (compact) 58.dp else 72.dp) { vm.togglePlay() }
                Transport(R.drawable.ic_player_next, "Next", if (compact) 44.dp else 54.dp) { vm.next() }
            }

        }
        val bottomRow: @Composable () -> Unit = {
            // bottom row: shuffle, repeat, queue
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomIcon(BitChordIcons.Shuffle, "Shuffle", state.shuffle) { vm.toggleShuffle() }
                Box(contentAlignment = Alignment.Center) {
                    BottomIcon(BitChordIcons.Repeat, "Repeat", state.repeat != Player.REPEAT_MODE_OFF) { vm.cycleRepeat() }
                    if (state.repeat == Player.REPEAT_MODE_ONE) {
                        Text("1", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 4.dp)
                                .clip(CircleShape).background(Color.White).padding(horizontal = 4.dp))
                    }
                }
                BottomIcon(BitChordIcons.Queue, "Queue", sideways && showQueue) { showQueue = !showQueue }
            }
        }

        if (sideways) {
            LandscapePlayerLayout(
                showQueue = showQueue,
                onClose = onClose,
                fadeRest = fadeRest,
                cover = cover,
                actions = bottomRow,
                mainPane = { compact ->
                    Column(Modifier.fillMaxWidth()) {
                        if (!hideSongStatus) {
                            Text(if (state.from.isNotEmpty()) "Playing from ${state.from}" else "Now Playing",
                                color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                        }
                        credits()
                        Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
                        SeekBar(state, vm, if (nerdStats) song else null)
                        transportRow(compact)
                        if (!hideVolume) VolumeBar()
                    }
                },
                queuePane = { PlayerQueue(state, vm, Modifier.fillMaxSize().padding(top = 8.dp), padding = 0.dp) },
            )
        } else Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // handle + where it is playing from
            Column(fadeRest.fillMaxWidth().padding(horizontal = 28.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(38.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                Spacer(Modifier.height(10.dp))
                if (!hideSongStatus) {
                    Text(if (state.from.isNotEmpty()) "Playing from ${state.from}" else "Now Playing",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }

            Spacer(Modifier.weight(0.5f))
            // Everything under the cover (title, seek bar, transport, volume, bottom
            // row) needs about 480dp, more with large text. On a short screen the
            // cover gives way instead of pushing the bottom row off the screen.
            val coverMax = (config.screenHeightDp - 300 - 180 * config.fontScale).coerceAtLeast(140f).dp
            cover(
                Modifier.align(Alignment.CenterHorizontally)
                    .then(if (fullCover) Modifier else Modifier.padding(horizontal = 28.dp))
                    .widthIn(max = coverMax)
                    .fillMaxWidth().aspectRatio(1f),
            )
            Spacer(Modifier.weight(0.5f))

            Column(fadeRest.padding(horizontal = 28.dp)) {
                credits()
                Spacer(Modifier.height(18.dp))
                SeekBar(state, vm, if (nerdStats) song else null)
                transportRow(false)
                if (!hideVolume) VolumeBar()
                bottomRow()
            }
        }
    }

    if (showQueue && !sideways) QueueSheet(state, vm) { showQueue = false }
    if (showSleep) SleepTimerSheet { showSleep = false }
}

/** Same actions, but anything that leaves the player first closes it. */
private fun LibraryActions.copyClosing(close: () -> Unit) = copy(
    openAlbum = { close(); openAlbum(it) }, openArtist = { close(); openArtist(it) }, openFolder = { close(); openFolder(it) },
    removeFromPlaylist = null,
)

@Composable
private fun RoundGlassButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).bounceClick(scaleDown = 0.88f, onClick = onClick).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Transport(icon: Int, description: String, size: Dp, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val motion = motionOn()
    val tick = rememberTick()
    val scale by animateFloatAsState(if (pressed && motion) 0.82f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    Icon(
        painterResource(icon), description, tint = Color.White,
        modifier = Modifier.size(size).scale(scale)
            .semantics { role = Role.Button; onClick { onClick(); true } }
            .pointerInput(onClick) {
            detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { tick(); onClick() })
        },
    )
}

@Composable
private fun BottomIcon(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Icon(
        icon, description, tint = if (active) Color.White else Dim,
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).padding(9.dp),
    )
}

/** Thin rounded bar that grows a little while you drag it (BitChord's ThinSlider look). */
@Composable
private fun ThinBar(value: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf(false) }
    val height by animateDpAsState(if (active) 12.dp else 7.dp, label = "bar")
    var width by remember { mutableFloatStateOf(1f) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Box(
        modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    active = true
                    change((down.position.x / width).coerceIn(0f, 1f))
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        change((c.position.x / width).coerceIn(0f, 1f))
                        c.consume()
                    }
                    active = false
                    done()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            width = size.width
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = r)
            drawRoundRect(Color.White.copy(alpha = if (active) 1f else 0.85f), Offset.Zero,
                Size(size.width * value.coerceIn(0f, 1f), size.height), r)
        }
    }
}

@Composable
private fun SeekBar(state: PlayerState, vm: PlayerViewModel, statsFor: com.ghostmikz.ghostly.data.Song?) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val value = dragging ?: (state.positionMs.toFloat() / duration)
    ThinBar(value, { dragging = it }, { dragging?.let { vm.seekTo((it * duration).toLong()) }; dragging = null })
    Row(Modifier.fillMaxWidth()) {
        val pos = (value * duration).toLong()
        Text(formatTime(pos), color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (statsFor != null) {
            Text(rememberStats(statsFor), color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.weight(1f))
        }
        Text("-" + formatTime((state.durationMs - pos).coerceAtLeast(0)), color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun VolumeBar() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    var touching by remember { mutableStateOf(false) }
    // On direct USB output the DAC's own attenuator is the volume (BitChord):
    // the music stream no longer reaches it, so the phone's volume would do nothing.
    val dac by com.ghostmikz.ghostly.playback.bitperfect.DacVolume.level.collectAsState()
    // Follow the hardware volume buttons too.
    LaunchedEffect(dac) {
        while (true) {
            if (!touching) vol = dac ?: (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
            delay(400)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = Dim, modifier = Modifier.size(22.dp))
        ThinBar(
            vol,
            {
                touching = true
                vol = it
                if (dac != null) {
                    com.ghostmikz.ghostly.playback.bitperfect.DacVolume.set(it)
                } else {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(it * max), 0)
                }
            },
            { touching = false },
            Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Dim, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(state: PlayerState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GhostlyColors.Card, contentColor = Color.White) {
        PlayerQueue(state, vm, Modifier.fillMaxHeight(0.75f))
    }
}

/** BitChord's queue, wired to this player: shared by the portrait sheet and the landscape column. */
@Composable
private fun PlayerQueue(state: PlayerState, vm: PlayerViewModel, modifier: Modifier, padding: androidx.compose.ui.unit.Dp = 20.dp) {
    InlineQueue(
        queue = state.queue,
        tiers = state.queueTiers,
        entryIds = state.queueEntryIds,
        currentIndex = state.queueIndex,
        contextTitle = state.from.takeIf { it.isNotBlank() },
        onJumpTo = vm::playQueueItem,
        onRemove = vm::removeQueueItem,
        onMove = vm::moveQueueItem,
        onClear = vm::clearUserQueue,
        modifier = modifier,
        contentPadding = padding,
    )
}

/** "Stats for nerds": format, bitrate and sample rate, read from the file itself. */
@Composable
private fun rememberStats(song: com.ghostmikz.ghostly.data.Song): String {
    val context = LocalContext.current
    var text by remember(song.id) { mutableStateOf("") }
    LaunchedEffect(song.id) {
        text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(context, song.uri)
                    val mime = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
                    val kbps = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000)
                    val hz = if (Build.VERSION.SDK_INT >= 31) {
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                    } else null
                    val format = when {
                        "mpeg" in mime -> "MP3"; "flac" in mime -> "FLAC"; "mp4" in mime || "aac" in mime -> "AAC"
                        "ogg" in mime || "vorbis" in mime -> "OGG"; "opus" in mime -> "OPUS"; "wav" in mime -> "WAV"
                        else -> mime.substringAfter('/').uppercase()
                    }
                    listOfNotNull(format, kbps?.let { "$it kbps" }, hz?.let { String.format(java.util.Locale.US, "%.1f kHz", it / 1000f) }).joinToString(" · ")
                } finally { r.release() }
            }.getOrDefault("")
        }
    }
    return text
}

// ---------- Sleep timer (BitChord's SleepTimerPicker, https://github.com/kushagrasinghx/BitChord, GPLv3) ----------

/** Live "m:ss" until the sleep timer fires, or null when none is running. */
@Composable
fun sleepTimerCountdown(): String? {
    val deadline by com.ghostmikz.ghostly.playback.SleepTimer.deadline.collectAsState()
    val remaining by produceState<Long?>(com.ghostmikz.ghostly.playback.SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = com.ghostmikz.ghostly.playback.SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining?.let { val s = it / 1000; "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60) }
}

/** What the menu row shows next to "Sleep timer". */
@Composable
fun sleepTimerStatus(): String? {
    val afterTrack by com.ghostmikz.ghostly.playback.SleepTimer.afterTrack.collectAsState()
    return sleepTimerCountdown() ?: "After this song".takeIf { afterTrack }
}

private fun sleepDuration(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val timer = com.ghostmikz.ghostly.playback.SleepTimer
    val chosen by timer.minutes.collectAsState()
    val afterTrack by timer.afterTrack.collectAsState()
    val countdown = sleepTimerCountdown()
    // A running timer no preset matches is a custom one: the row ticks and names it.
    val custom = chosen?.takeIf { it !in timer.PRESETS }
    var pickingCustom by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GhostlyColors.Card, contentColor = Color.White) {
        if (pickingCustom) {
            var minutes by remember { mutableIntStateOf(custom ?: 90) }
            SleepHeader("Custom", "Pause playback after a while") { pickingCustom = false }
            HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.CardHigh)
            Text(sleepDuration(minutes), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { minutes -= timer.STEP_MINUTES }, enabled = minutes > timer.STEP_MINUTES) { Text("−", color = Color.White, fontSize = 24.sp) }
                ThinBar(timer.fractionOf(minutes), { minutes = timer.minutesAt(it) }, {}, Modifier.weight(1f))
                TextButton(onClick = { minutes += timer.STEP_MINUTES }, enabled = minutes < timer.MAX_MINUTES) { Text("+", color = Color.White, fontSize = 24.sp) }
            }
            Box(Modifier.fillMaxWidth().padding(20.dp).height(50.dp).clip(CircleShape).background(Color.White)
                .bounceClick { timer.start(minutes); onDismiss() }, Alignment.Center) {
                Text("Start timer", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            SleepHeader("Sleep timer", when {
                countdown != null -> "$countdown until playback pauses"
                afterTrack -> "Pausing when this song ends"
                else -> "Pause playback after a while"
            }, onDismiss)
            HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.CardHigh)
            // Finishing the song is the one people reach for at the end of a listen, so it leads.
            SleepOption("After this song", afterTrack) { timer.startAfterTrack(); onDismiss() }
            timer.PRESETS.forEach { m ->
                SleepOption(if (m == 60) "1 hour" else "$m minutes", m == chosen) { timer.start(m); onDismiss() }
            }
            SleepOption(custom?.let { "Custom · " + sleepDuration(it) } ?: "Custom", custom != null) { pickingCustom = true }
            if (chosen != null || afterTrack) {
                SleepOption("Turn off timer", false, icon = Icons.Rounded.Close) { timer.cancel(); onDismiss() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SleepHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronLeft, "Back", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
}

/** A choice row; the tick leads, in the icon column, as a menu marks its choice. */
@Composable
private fun SleepOption(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp)) {
            val i = icon ?: if (selected) Icons.Rounded.Check else null
            if (i != null) Icon(i, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text(label, color = Color.White, fontSize = 17.sp)
    }
}
