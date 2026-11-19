package com.ghostmikz.ghostly.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.data.History
import com.ghostmikz.ghostly.data.Library
import com.ghostmikz.ghostly.data.Song
import com.ghostmikz.ghostly.ui.icons.BitChordIcons
import java.time.LocalDate
import kotlin.random.Random

private data class Mix(val label: String, val title: String, val text: String, val color: Color, val songs: List<Song>, val shuffle: Boolean)

@Composable
fun HomeScreen(library: Library, actions: LibraryActions, bottomPad: Dp) {
    val stats by History.stats.collectAsState()
    val favIds by History.favorites.collectAsState()
    val songs = library.songs
    val byId = remember(songs) { songs.associateBy { it.id } }

    val recent = remember(stats, byId) {
        stats.entries.sortedByDescending { it.value.lastPlayed }.mapNotNull { byId[it.key] }.take(15)
    }
    val most = remember(stats, byId) {
        stats.entries.sortedByDescending { it.value.count }.mapNotNull { byId[it.key] }.take(25)
    }
    val added = remember(songs) { songs.sortedByDescending { it.dateAdded }.take(25) }
    val favorites = remember(favIds, byId) { favIds.mapNotNull { byId[it] } }
    // Changes once a day, so "Albums for You" feels fresh without jumping around on every visit.
    val dailyAlbums = remember(library.albums) {
        library.albums.shuffled(Random(LocalDate.now().toEpochDay())).take(12)
    }
    val dailyArtists = remember(library.artists) {
        library.artists.shuffled(Random(LocalDate.now().toEpochDay() + 7)).take(12)
    }

    val mixes = buildList {
        add(Mix("Mix for You", "Shuffle All", "Every song on this phone, in a random order.", Color(0xFF33AEF0), songs, true))
        if (most.size >= 3) add(Mix("Made from your plays", "On Repeat", "The songs you keep coming back to.", Color(0xFF8E5BF0), most, false))
        if (favorites.isNotEmpty()) add(Mix("Starred by you", "Favorites", "${countText(favorites.size, "song")} you starred.", GhostlyColors.Accent, favorites, true))
        add(Mix("Fresh on your phone", "Recently Added", "The newest files in your library.", Color(0xFF2FB874), added, false))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad)) {
        item {
            HeaderBar(null, actions)
            BigTitle("Listen Now")
        }
        if (songs.isEmpty()) {
            item {
                Text("No music on this phone yet. Copy some audio files (for example into the Music folder) and they show up here.",
                    color = GhostlyColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
            }
            return@LazyColumn
        }
        item {
            ShelfTitle("Top Picks for You")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(mixes, key = { it.title }) { m -> MixCard(m) { actions.play(m.songs, 0, m.shuffle, m.title) } }
            }
        }
        item {
            Shelf("Recently Played", recent, { "r" + it.id }) { s ->
                SquareCard(s, s.title, s.artist) { actions.play(recent, recent.indexOf(s), false, "Recently Played") }
            }
        }
        item {
            Shelf("Albums for You", dailyAlbums, { "a" + it.id }) { a ->
                SquareCard(a.songs.first(), a.title, a.artist) { actions.openAlbum(a.id) }
            }
        }
        item {
            Shelf("Recently Added", added.take(15), { "n" + it.id }) { s ->
                SquareCard(s, s.title, s.artist) { actions.play(added, added.indexOf(s), false, "Recently Added") }
            }
        }
        item {
            Shelf("Most Played", most.take(15), { "m" + it.id }) { s ->
                SquareCard(s, s.title, "${stats[s.id]?.count ?: 0} plays") { actions.play(most, most.indexOf(s), false, "Most Played") }
            }
        }
        item {
            Shelf("Artists", dailyArtists, { "ar" + it.name }) { a ->
                SquareCard(a.songs.first(), a.name, "", 130.dp, round = true) { actions.openArtist(a.name) }
            }
        }
    }
}

/** The big colored card at the top of Home (like BitChord's "Top Picks for You"). */
@Composable
private fun MixCard(mix: Mix, onClick: () -> Unit) {
    Box(
        Modifier.width(250.dp).height(320.dp).clip(RoundedCornerShape(20.dp)).background(mix.color).clickable(onClick = onClick),
    ) {
        // Three soft rings as decoration, like BitChord's mood cards.
        Canvas(Modifier.fillMaxWidth().height(210.dp).padding(top = 30.dp)) {
            val line = Stroke(width = 3.dp.toPx())
            val ring = Color.White.copy(alpha = 0.6f)
            val cx = size.width / 2
            listOf(Triple(0.38f, 0.16f, 0f), Triple(0.58f, 0.22f, 0.24f), Triple(0.72f, 0.28f, 0.52f)).forEach { (w, h, y) ->
                val rw = size.width * w
                val rh = size.height * h
                drawOval(ring, Offset(cx - rw / 2, size.height * y), Size(rw, rh), style = line)
            }
        }
        Icon(BitChordIcons.MusicNote, null, tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(22.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(mix.label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            Text(mix.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
            Text(mix.text, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 19.sp)
        }
    }
}
