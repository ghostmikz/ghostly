package com.ghostmikz.tune.ui

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.ghostmikz.tune.R
import com.ghostmikz.tune.data.History
import com.ghostmikz.tune.ui.icons.BitChordIcons
import kotlinx.coroutines.delay

private val Dim = Color.White.copy(alpha = 0.55f)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(state: PlayerState, vm: PlayerViewModel, actions: LibraryActions, onClose: () -> Unit) {
    val song = state.current
    val art = rememberArt(song, 1000)
    val colors = remember(art) { art?.artColors() }
    val top by animateColorAsState(colors?.top ?: TuneColors.Player, tween(600), label = "top")
    val bottom by animateColorAsState(colors?.bottom ?: TuneColors.Player, tween(600), label = "bottom")
    val favs by History.favorites.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    // Apple-style: the cover sits a bit smaller while paused.
    val coverScale by animateFloatAsState(if (state.isPlaying) 1f else 0.84f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cover")

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag > 220) onClose() }) { _, d -> drag += d }
            },
    ) {
        if (art != null && Build.VERSION.SDK_INT >= 31) {
            Image(art.asImageBitmap(), null, Modifier.fillMaxSize().blur(90.dp), contentScale = ContentScale.Crop, alpha = 0.5f)
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
            // handle + where it is playing from
            Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(38.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                Spacer(Modifier.height(10.dp))
                Text(if (state.from.isNotEmpty()) "Playing from ${state.from}" else "Now Playing",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }

            Spacer(Modifier.weight(0.5f))
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).scale(coverScale)
                    .shadow(if (state.isPlaying) 30.dp else 10.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFF0C0C0C)),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(BitChordIcons.MusicNote, null, tint = Color(0xFF5A5A5E), modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.weight(0.5f))

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
                RoundGlassButton(if (fav) BitChordIcons.StarFilled else BitChordIcons.Star, "Favorite") {
                    song?.let { History.toggleFavorite(it.id) }
                }
                Spacer(Modifier.width(12.dp))
                Box {
                    RoundGlassButton(Icons.Rounded.MoreHoriz, "More") { showMenu = true }
                    DropdownMenu(showMenu, { showMenu = false }, containerColor = TuneColors.Card, shape = RoundedCornerShape(14.dp)) {
                        if (song != null) SongMenuItems(song, actions.copyClosing(onClose)) { showMenu = false }
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

            Spacer(Modifier.height(18.dp))
            SeekBar(state, vm)

            // transport
            Row(
                Modifier.fillMaxWidth().padding(vertical = 22.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                Transport(R.drawable.ic_player_previous, "Previous", 54.dp) { vm.previous() }
                Transport(if (state.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play, "Play or pause", 72.dp) { vm.togglePlay() }
                Transport(R.drawable.ic_player_next, "Next", 54.dp) { vm.next() }
            }

            VolumeBar()

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
                BottomIcon(BitChordIcons.Queue, "Queue", false) { showQueue = true }
            }
        }
    }

    if (showQueue) QueueSheet(state, vm) { showQueue = false }
}

/** Same actions, but anything that leaves the player first closes it. */
private fun LibraryActions.copyClosing(close: () -> Unit) = LibraryActions(
    play = play, playNext = playNext,
    openAlbum = { close(); openAlbum(it) }, openArtist = { close(); openArtist(it) }, openFolder = { close(); openFolder(it) },
    openFavorites = openFavorites, openRecent = openRecent, openSettings = openSettings, back = back,
)

@Composable
private fun RoundGlassButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Transport(icon: Int, description: String, size: Dp, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, label = "press")
    Icon(
        painterResource(icon), description, tint = Color.White,
        modifier = Modifier.size(size).scale(scale).pointerInput(onClick) {
            detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() })
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
private fun SeekBar(state: PlayerState, vm: PlayerViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val value = dragging ?: (state.positionMs.toFloat() / duration)
    ThinBar(value, { dragging = it }, { dragging?.let { vm.seekTo((it * duration).toLong()) }; dragging = null })
    Row(Modifier.fillMaxWidth()) {
        val pos = (value * duration).toLong()
        Text(formatTime(pos), color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
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
    // Follow the hardware volume buttons too.
    LaunchedEffect(Unit) {
        while (true) {
            if (!touching) vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
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
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(it * max), 0)
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = TuneColors.Card, contentColor = Color.White) {
        Text("Playing Next", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        val list = rememberLazyListState(initialFirstVisibleItemIndex = state.queueIndex.coerceAtLeast(0))
        LazyColumn(state = list, modifier = Modifier.fillMaxHeight(0.75f)) {
            itemsIndexed(state.queue) { i, s ->
                val now = i == state.queueIndex
                Row(
                    Modifier.fillMaxWidth().clickable { vm.playQueueItem(i) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(s, Modifier.size(48.dp), corner = 6.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(s.title, color = Color.White, fontSize = 16.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = TuneColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (now) EqBars(Modifier.size(18.dp))
                }
            }
        }
    }
}
