package com.ghostmikz.tune.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghostmikz.tune.data.Album
import com.ghostmikz.tune.data.Artist
import com.ghostmikz.tune.data.Library
import com.ghostmikz.tune.data.Song
import kotlinx.coroutines.launch

/** What the song rows can ask the app to do. */
class LibraryActions(
    val play: (List<Song>, Int, Boolean) -> Unit,
    val playNext: (Song) -> Unit,
    val openAlbum: (Long) -> Unit,
    val openArtist: (String) -> Unit,
    val openFolder: (String) -> Unit,
)

private val TABS = listOf("Songs", "Albums", "Artists", "Folders")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    library: Library,
    loading: Boolean,
    currentId: Long?,
    actions: LibraryActions,
    onSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val pager = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search your music") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Library", fontWeight = FontWeight.Bold)
                }
            },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search")
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
            },
        )

        when {
            loading && library.songs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            library.songs.isEmpty() -> EmptyState("No music found", "Put audio files on your phone (for example in the Music folder) and rescan from Settings.")
            searching && query.isNotBlank() -> SearchResults(library, query.trim(), currentId, actions, contentPadding)
            else -> {
                PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp, divider = {}) {
                    TABS.forEachIndexed { i, t ->
                        Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(t) })
                    }
                }
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    when (page) {
                        0 -> SongList(library.songs, currentId, actions, contentPadding, header = { PlayShuffleRow(library.songs, actions) })
                        1 -> AlbumGrid(library.albums, actions, contentPadding)
                        2 -> ArtistList(library.artists, actions, contentPadding)
                        else -> FolderList(library.songs, actions, contentPadding)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PlayShuffleRow(songs: List<Song>, actions: LibraryActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { actions.play(songs, 0, false) }, Modifier.weight(1f)) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play")
        }
        FilledTonalButton(onClick = { actions.play(songs, 0, true) }, Modifier.weight(1f)) {
            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Shuffle")
        }
    }
}

@Composable
fun SongList(
    songs: List<Song>,
    currentId: Long?,
    actions: LibraryActions,
    contentPadding: PaddingValues,
    showTrackNumbers: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (header != null) item { header() }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            SongRow(s, s.id == currentId, actions, showTrackNumbers) { actions.play(songs, i, false) }
        }
    }
}

@Composable
fun SongRow(song: Song, playing: Boolean, actions: LibraryActions, showTrackNumber: Boolean = false, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            if (showTrackNumber) {
                Box(Modifier.width(32.dp), Alignment.Center) {
                    if (playing) Icon(Icons.Rounded.GraphicEq, null, tint = accent)
                    else Text(if (song.track % 1000 > 0) "${song.track % 1000}" else "-", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                AlbumArt(song, Modifier.size(48.dp))
            }
        },
        headlineContent = {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) accent else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = {
            Text("${song.artist}  -  ${formatTime(song.durationMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                        onClick = { menu = false; actions.playNext(song) })
                    DropdownMenuItem(text = { Text("Go to album") }, leadingIcon = { Icon(Icons.Rounded.Album, null) },
                        onClick = { menu = false; actions.openAlbum(song.albumId) })
                    DropdownMenuItem(text = { Text("Go to artist") }, leadingIcon = { Icon(Icons.Rounded.Person, null) },
                        onClick = { menu = false; actions.openArtist(song.artist) })
                }
            }
        },
    )
}

@Composable
fun AlbumGrid(albums: List<Album>, actions: LibraryActions, contentPadding: PaddingValues, header: (@Composable () -> Unit)? = null) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(albums, key = { it.id }) { a ->
            Column(Modifier.clickable { actions.openAlbum(a.id) }) {
                AlbumArt(a.songs.first(), Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp, px = 400)
                Spacer(Modifier.height(6.dp))
                Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ArtistList(artists: List<Artist>, actions: LibraryActions, contentPadding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(artists, key = { it.name }) { a ->
            ListItem(
                modifier = Modifier.clickable { actions.openArtist(a.name) },
                leadingContent = { AlbumArt(a.songs.first(), Modifier.size(52.dp), corner = 26.dp) },
                headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(countText(a.albums.size, "album") + ", " + countText(a.songs.size, "song")) },
            )
        }
    }
}

@Composable
private fun FolderList(songs: List<Song>, actions: LibraryActions, contentPadding: PaddingValues) {
    val folders = remember(songs) { songs.groupingBy { it.folder }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(folders.entries.toList(), key = { it.key }) { (path, count) ->
            ListItem(
                modifier = Modifier.clickable { actions.openFolder(path) },
                leadingContent = { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(path.substringAfterLast('/').ifBlank { "Internal storage" }) },
                supportingContent = { Text("$path  -  " + countText(count, "song"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun SearchResults(library: Library, q: String, currentId: Long?, actions: LibraryActions, contentPadding: PaddingValues) {
    val songs = remember(library, q) {
        library.songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
    }
    val albums = remember(library, q) { library.albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) } }
    val artists = remember(library, q) { library.artists.filter { it.name.contains(q, true) } }
    if (songs.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
        EmptyState("Nothing found", "No songs, albums or artists match \"$q\".")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (artists.isNotEmpty()) {
            item { SectionTitle("Artists") }
            items(artists.take(5), key = { "ar" + it.name }) { a ->
                ListItem(
                    modifier = Modifier.clickable { actions.openArtist(a.name) },
                    leadingContent = { AlbumArt(a.songs.first(), Modifier.size(44.dp), corner = 22.dp) },
                    headlineContent = { Text(a.name) },
                )
            }
        }
        if (albums.isNotEmpty()) {
            item { SectionTitle("Albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.id }) { a ->
                        Column(Modifier.width(120.dp).clickable { actions.openAlbum(a.id) }) {
                            AlbumArt(a.songs.first(), Modifier.size(120.dp), corner = 10.dp, px = 400)
                            Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            itemsIndexed(songs, key = { _, s -> "s" + s.id }) { i, s ->
                SongRow(s, s.id == currentId, actions) { actions.play(songs, i, false) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

// ---------- Detail screens ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
        content()
    }
}

@Composable
fun AlbumScreen(album: Album, currentId: Long?, actions: LibraryActions, contentPadding: PaddingValues, onBack: () -> Unit) {
    DetailScaffold(album.title, onBack) {
        SongList(album.songs, currentId, actions, contentPadding, showTrackNumbers = true, header = {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumArt(album.songs.first(), Modifier.fillMaxWidth(0.62f).aspectRatio(1f), corner = 16.dp, px = 800)
                Spacer(Modifier.height(16.dp))
                Text(album.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Text(
                    album.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { actions.openArtist(album.artist) }.padding(4.dp),
                )
                val total = album.songs.sumOf { it.durationMs }
                Text(
                    listOfNotNull(album.year.takeIf { it > 0 }?.toString(), countText(album.songs.size, "song"), formatLong(total)).joinToString("  -  "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlayShuffleRow(album.songs, actions)
            }
        })
    }
}

@Composable
fun ArtistScreen(artist: Artist, currentId: Long?, actions: LibraryActions, contentPadding: PaddingValues, onBack: () -> Unit) {
    DetailScaffold(artist.name, onBack) {
        SongList(artist.songs, currentId, actions, contentPadding, header = {
            Column {
                PlayShuffleRow(artist.songs, actions)
                if (artist.albums.isNotEmpty()) {
                    SectionTitle("Albums")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artist.albums, key = { it.id }) { a ->
                            Column(Modifier.width(140.dp).clickable { actions.openAlbum(a.id) }) {
                                AlbumArt(a.songs.first(), Modifier.size(140.dp), corner = 12.dp, px = 400)
                                Spacer(Modifier.height(4.dp))
                                Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (a.year > 0) Text("${a.year}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                SectionTitle("Songs")
            }
        })
    }
}

@Composable
fun FolderScreen(path: String, songs: List<Song>, currentId: Long?, actions: LibraryActions, contentPadding: PaddingValues, onBack: () -> Unit) {
    DetailScaffold(path.substringAfterLast('/').ifBlank { "Internal storage" }, onBack) {
        SongList(songs, currentId, actions, contentPadding, header = { PlayShuffleRow(songs, actions) })
    }
}

// ---------- helpers ----------

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatLong(ms: Long): String {
    val min = ms / 60000
    return if (min >= 60) "${min / 60} hr ${min % 60} min" else "$min min"
}

fun countText(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
