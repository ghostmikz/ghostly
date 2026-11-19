package com.ghostmikz.tune.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

@Composable
fun MiniPlayer(state: PlayerState, vm: PlayerViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen)
            .pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag < -20) onOpen() } },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(song, Modifier.size(44.dp), corner = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = vm::togglePlay) {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause")
                }
                IconButton(onClick = { vm.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
            val p = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            LinearProgressIndicator(
                progress = { p.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(state: PlayerState, vm: PlayerViewModel, onClose: () -> Unit, onOpenArtist: (String) -> Unit) {
    val song = state.current
    val art = rememberArt(song, 1000)
    val colors = remember(art) { art?.artColors() ?: DefaultArtColors }
    val top by animateColorAsState(colors.top, tween(600), label = "top")
    val bottom by animateColorAsState(colors.bottom, tween(600), label = "bottom")
    val accent by animateColorAsState(colors.accent, tween(600), label = "accent")
    var showQueue by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = { if (dragTotal > 200) onClose() },
                ) { _, d -> dragTotal += d }
            },
    ) {
        // Soft blurred cover behind everything (Android 12+ only, blur is not available before).
        if (art != null && Build.VERSION.SDK_INT >= 31) {
            Image(
                art.asImageBitmap(), null,
                Modifier.fillMaxSize().blur(80.dp),
                contentScale = ContentScale.Crop, alpha = 0.45f,
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0xAA000000)))))
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = Color.White) }
                Text("Now playing", Modifier.weight(1f), color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = Color.White) }
            }

            Spacer(Modifier.weight(0.6f))
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .shadow(24.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else AlbumArt(song, Modifier.fillMaxSize(), corner = 20.dp)
            }
            Spacer(Modifier.weight(0.6f))

            Column(Modifier.fillMaxWidth()) {
                Text(
                    song?.title ?: "", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee(),
                )
                Text(
                    song?.artist ?: "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { song?.let { onOpenArtist(it.artist) } },
                )
            }

            Spacer(Modifier.height(16.dp))
            SeekBar(state, vm, accent)

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val off = Color.White.copy(alpha = 0.5f)
                IconButton(onClick = { vm.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) accent else off)
                }
                IconButton(onClick = { vm.previous() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(40.dp), tint = Color.White)
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable(onClick = vm::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause",
                        Modifier.size(44.dp), tint = bottom)
                }
                IconButton(onClick = { vm.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(40.dp), tint = Color.White)
                }
                IconButton(onClick = { vm.cycleRepeat() }) {
                    Icon(
                        if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                        tint = if (state.repeat == Player.REPEAT_MODE_OFF) off else accent,
                    )
                }
            }
            Spacer(Modifier.weight(0.4f))
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val list = rememberLazyListState(initialFirstVisibleItemIndex = state.queueIndex.coerceAtLeast(0))
            LazyColumn(state = list, modifier = Modifier.fillMaxHeight(0.7f)) {
                itemsIndexed(state.queue) { i, s ->
                    val now = i == state.queueIndex
                    ListItem(
                        modifier = Modifier.clickable { vm.playQueueItem(i) },
                        leadingContent = { AlbumArt(s, Modifier.size(44.dp)) },
                        headlineContent = {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { if (now) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: PlayerState, vm: PlayerViewModel, accent: Color) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { vm.seekTo((it * duration).toLong()) }; dragging = null },
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatTime((value * duration).toLong()), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(formatTime(state.durationMs), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
    }
}
