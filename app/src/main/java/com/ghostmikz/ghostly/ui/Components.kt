package com.ghostmikz.ghostly.ui

import androidx.compose.animation.animateColorAsState
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
        Modifier.size(size).clip(CircleShape).background(GhostlyColors.Surface)
            .border(1.dp, GhostlyColors.Card, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

/** White "Play" pill next to a dark outlined "Shuffle" pill. */
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.weight(1f).height(50.dp).clip(CircleShape).background(Color.White).clickable(onClick = onPlay),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_player_play), null, tint = Color.Black, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play", color = Color.Black, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.weight(1f).height(50.dp).clip(CircleShape).background(GhostlyColors.Surface)
                .border(1.dp, GhostlyColors.Outline, CircleShape).clickable(onClick = onShuffle),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(BitChordIcons.Shuffle, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Shuffle", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Pill-shaped segmented tabs (Songs / Artists / Albums / Folders). */
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(42.dp).clip(CircleShape)
            .background(GhostlyColors.Card).padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val bg by animateColorAsState(if (i == selected) Color(0xFF5A5A5E) else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bg).plainClick { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

// ---------- titles ----------

@Composable
fun BigTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.displayLarge, color = Color.White,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun ShelfTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp,
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
                        EqBars(Modifier.size(20.dp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = GhostlyColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(formatTime(song.durationMs), color = GhostlyColors.TextSecondary, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 8.dp))
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false },
                        containerColor = GhostlyColors.Card, shape = RoundedCornerShape(14.dp)) { menu { open = false } }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF242426))
        }
    }
}

/** Square cover with two lines of text under it, for the sideways shelves. */
@Composable
fun SquareCard(song: Song?, title: String, subtitle: String, width: Dp = 160.dp, round: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(width).plainClick(onClick)) {
        AlbumArt(song, Modifier.size(width), corner = if (round) width / 2 else 10.dp, px = 400)
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
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

/** Plain row with an icon, label and chevron (Library's "Favorites", "Recently Played"). */
@Composable
fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 22.dp)) {
            Row(Modifier.padding(vertical = 16.dp, horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Icon(BitChordIcons.ChevronRight, null, tint = GhostlyColors.TextSecondary,
                    modifier = Modifier.padding(end = 16.dp).size(18.dp))
            }
            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF242426))
        }
    }
}

// ---------- floating bars ----------

data class TabItem(val label: String, val icon: ImageVector)

/** The floating rounded tab bar at the bottom. */
@Composable
fun FloatingTabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp).height(64.dp).clip(CircleShape)
            .background(Color(0xF0121214)).border(1.dp, Color(0xFF2A2A2D), CircleShape).padding(4.dp),
    ) {
        tabs.forEachIndexed { i, tab ->
            val on = i == selected
            val bg by animateColorAsState(if (on) GhostlyColors.CardHigh else Color.Transparent, label = "tab")
            val fg = if (on) Color.White else GhostlyColors.TextSecondary
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(bg).plainClick { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(25.dp))
                Spacer(Modifier.height(2.dp))
                Text(tab.label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Floating mini player that sits above the tab bar. */
@Composable
fun MiniPlayer(state: PlayerState, vm: PlayerViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp).height(64.dp).clip(CircleShape)
            .background(Color(0xF01A1A1C)).border(1.dp, Color(0xFF2A2A2D), CircleShape)
            .clickable(onClick = onOpen)
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -15) onOpen() } }
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song, Modifier.size(46.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = GhostlyColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            painterResource(if (state.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play),
            "Play or pause", tint = Color.White,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = vm::togglePlay).padding(4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            painterResource(R.drawable.ic_player_next), "Next", tint = Color.White,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable { vm.next() }.padding(4.dp),
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
        text = { Text(text, color = Color.White) },
        leadingIcon = { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
        onClick = { onDismiss(); run() },
    )
    item("Play next", BitChordIcons.Queue) { actions.playNext(song) }
    item(if (fav) "Remove from favorites" else "Add to favorites", if (fav) BitChordIcons.StarFilled else BitChordIcons.Star) {
        com.ghostmikz.ghostly.data.History.toggleFavorite(song.id)
    }
    item("Go to album", BitChordIcons.Library) { actions.openAlbum(song.albumId) }
    item("Go to artist", BitChordIcons.MusicNote) { actions.openArtist(song.artist) }
}

/** Three bouncing bars that mark the song that is playing. */
@Composable
fun EqBars(modifier: Modifier = Modifier, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "eq")
    val phase by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "eqPhase",
    )
    Canvas(modifier) {
        val bar = size.width / 5
        listOf(0f, 0.33f, 0.66f).forEachIndexed { i, offset ->
            val wave = kotlin.math.abs(kotlin.math.sin((phase + offset) * Math.PI)).toFloat()
            val h = size.height * (0.35f + 0.65f * wave)
            drawRoundRect(
                color, Offset(bar * (i * 2), size.height - h),
                Size(bar, h), CornerRadius(bar / 2, bar / 2),
            )
        }
    }
}
