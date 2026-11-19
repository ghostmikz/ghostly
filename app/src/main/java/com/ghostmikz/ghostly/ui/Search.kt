package com.ghostmikz.ghostly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.data.Library
import com.ghostmikz.ghostly.ui.icons.BitChordIcons

@Composable
fun SearchScreen(library: Library, currentId: Long?, actions: LibraryActions, bottomPad: Dp) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val songs = remember(library, q) {
        if (q.isEmpty()) emptyList()
        else library.songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
    }
    val albums = remember(library, q) { if (q.isEmpty()) emptyList() else library.albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) } }
    val artists = remember(library, q) { if (q.isEmpty()) emptyList() else library.artists.filter { it.name.contains(q, true) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad)) {
        item {
            HeaderBar(null, actions)
            BigTitle("Search")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp)
                    .clip(RoundedCornerShape(14.dp)).background(GhostlyColors.Card).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BitChordIcons.Search, null, tint = GhostlyColors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Artists, Songs, Albums", color = GhostlyColors.TextSecondary, fontSize = 17.sp)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontFamily = Inter),
                        cursorBrush = SolidColor(GhostlyColors.Accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Rounded.Cancel, "Clear", tint = GhostlyColors.TextSecondary,
                        modifier = Modifier.size(22.dp).clickable { query = "" })
                }
            }
        }
        if (q.isNotEmpty() && songs.isEmpty() && albums.isEmpty() && artists.isEmpty()) item {
            Text("No results for \"$q\".", color = GhostlyColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
        }
        if (artists.isNotEmpty()) {
            item { ShelfTitle("Artists") }
            items(artists.take(5), key = { "ar" + it.name }) { a -> ArtistRow(a) { actions.openArtist(a.name) } }
        }
        if (albums.isNotEmpty()) item {
            Shelf("Albums", albums, { "al" + it.id }) { a -> SquareCard(a.songs.first(), a.title, a.artist, 150.dp) { actions.openAlbum(a.id) } }
        }
        if (songs.isNotEmpty()) {
            item { ShelfTitle("Songs") }
            itemsIndexed(songs, key = { _, s -> "s" + s.id }) { i, s ->
                SongRow(s, s.id == currentId, { actions.play(songs, i, false, "Search") }, { SongMenuItems(s, actions, it) })
            }
        }
    }
}

