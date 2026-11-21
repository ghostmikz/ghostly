package com.ghostmikz.ghostly.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.Sort
import com.ghostmikz.ghostly.data.sortedForLibrary
import com.ghostmikz.ghostly.data.LocalMusicSort
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.data.Album
import com.ghostmikz.ghostly.data.Artist
import com.ghostmikz.ghostly.data.Library
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.data.Song
import com.ghostmikz.ghostly.ui.icons.BitChordIcons

/** Everything a screen can ask the app to do. */
data class LibraryActions(
    val play: (songs: List<Song>, start: Int, shuffle: Boolean, from: String) -> Unit,
    val playNext: (Song) -> Unit,
    val openAlbum: (Long) -> Unit,
    val openArtist: (String) -> Unit,
    val openFolder: (String) -> Unit,
    val openFavorites: () -> Unit,
    val openRecent: () -> Unit,
    val openSettings: () -> Unit,
    val back: () -> Unit,
    val openPlaylist: (String) -> Unit = {},
    /** Opens BitChord's "Add to playlist" sheet for a song (null = make a new playlist). */
    val addToPlaylist: (Song?) -> Unit = {},
    /** Set on a playlist's own page: the song menu then offers "Remove from playlist". */
    val removeFromPlaylist: ((Song) -> Unit)? = null,
)

private val LIB_TABS = listOf("Songs", "Artists", "Albums", "Playlists", "Folders")

/** Room at the top of a page for the pinned round buttons (drawn by MainActivity, like BitChord's). */
@Composable
fun TopBarSpace() = Spacer(Modifier.statusBarsPadding().height(68.dp))

/** The Library's Songs/Artists/Albums/... tab, remembered while the app runs. */
private val libraryTab = androidx.compose.runtime.mutableIntStateOf(0)

@Composable
fun LibraryScreen(
    library: Library,
    currentId: Long?,
    actions: LibraryActions,
    bottomPad: Dp,
    /** BitChord's way in to Replay: its row of headline cards at the head of the Library. */
    replay: @Composable () -> Unit = {},
) {
    // Kept for the whole app session (not just this composition), so the tab
    // is still the chosen one after visiting a page or the other tabs.
    var tab by libraryTab
    // BitChord's device-library sort; Play and Shuffle follow the order shown.
    val sort by Prefs.localSort.collectAsState()
    val allSongs = remember(library.songs, sort) { library.songs.sortedForLibrary(sort) }
    // BitChord's "search this folder" on the device library: the Songs tab narrowed as you type.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val songs = remember(allSongs, query) { allSongs.filter { it.matchesQuery(query) } }
    val searchAction = remember { listOf(PageActions.Action(BitChordIcons.Search, "Search this list") { searching = true }) }
    if (tab == 0) PageActionsFor(searchAction)
    // Held out here, above the per-tab lists, so the pill slides across a tab
    // change instead of starting over in the new list.
    val tabSlide = rememberSegmentSlide(tab)
    val header: @Composable () -> Unit = {
        Column {
            TopBarSpace()
            BigTitle("Library")
            Spacer(Modifier.height(8.dp))
            replay()
            NavRow(BitChordIcons.Star, "Liked", actions.openFavorites)
            NavRow(BitChordIcons.Clock, "Recently Played", actions.openRecent)
            Spacer(Modifier.height(20.dp))
            PlayShuffleButtons(
                onPlay = { actions.play(songs, 0, false, "Library") },
                onShuffle = { actions.play(songs, 0, true, "Library") },
            )
            Spacer(Modifier.height(18.dp))
            SegmentedTabs(LIB_TABS, tab, { tab = it }, slide = tabSlide)
            if (tab == 0) SortRow(sort, Prefs::setLocalSort) else Spacer(Modifier.height(8.dp))
            if (tab == 0 && searching) {
                ListSearchField(query, { query = it }, onClose = { searching = false; query = "" })
                if (songs.isEmpty()) {
                    Text("Nothing here matches \u201c$query\u201d", color = GhostlyColors.TextSecondary, fontSize = 16.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
                }
            }
        }
    }
    when (tab) {
        0 -> Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPad)) {
                item { header() }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                    Box(Modifier.animateItem()) {
                        SongRow(s, s.id == currentId, { actions.play(songs, i, false, "Library") }, { SongMenuItems(s, actions, it) }, onPlayNext = { actions.playNext(s) })
                    }
                }
            }
            // The header is row 0, so the songs start at 1.
            val playingRow = remember(songs, currentId) { songs.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }?.plus(1) }
            LocateTargetFor(listState, playingRow)
        }
        1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad)) {
            item { header() }
            items(library.artists, key = { it.name }) { a -> ArtistRow(a) { actions.openArtist(a.name) } }
        }
        2 -> LazyVerticalGrid(
            GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPad),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(library.albums, key = { it.id }) { a ->
                Box(Modifier.padding(horizontal = 8.dp)) { AlbumTile(a) { actions.openAlbum(a.id) } }
            }
        }
        3 -> {
            val all by com.ghostmikz.ghostly.data.Playlists.playlists.collectAsState()
            LazyVerticalGrid(
                GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPad),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
                item(key = "new") { Box(Modifier.padding(horizontal = 8.dp)) { PlaylistTile(null, library) { actions.addToPlaylist(null) } } }
                items(all, key = { it.id }) { p ->
                    Box(Modifier.padding(horizontal = 8.dp)) { PlaylistTile(p, library) { actions.openPlaylist(p.id) } }
                }
            }
        }
        else -> {
            val folders = remember(songs) { songs.groupingBy { it.folder }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad)) {
                item { header() }
                items(folders.entries.toList(), key = { it.key }) { (path, n) ->
                    IconRow(Icons.Rounded.Folder, folderName(path), "$path  ·  ${countText(n, "song")}") { actions.openFolder(path) }
                }
            }
        }
    }
}

@Composable
fun AlbumTile(album: Album, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().bounceClick(scaleDown = 0.95f, onClick = onClick)) {
        AlbumArt(album.songs.first(), Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp, px = 400)
        Spacer(Modifier.height(8.dp))
        Text(album.title, color = GhostlyColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, color = GhostlyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(artist.songs.first(), Modifier.size(52.dp), corner = 26.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(artist.name, color = GhostlyColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(countText(artist.albums.size, "album") + "  ·  " + countText(artist.songs.size, "song"),
                    color = GhostlyColors.TextSecondary, fontSize = 15.sp)
            }
            HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.Divider)
        }
    }
}

@Composable
fun IconRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(GhostlyColors.Card), Alignment.Center) {
            Icon(icon, null, tint = Color(0xFF636366), modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(title, color = GhostlyColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = GhostlyColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.Divider)
        }
    }
}

// ---------- collection pages (album, artist, folder, favorites, recently played) ----------

@Composable
fun CollectionScreen(
    title: String,
    subtitle: String,
    cover: @Composable (Modifier, Dp) -> Unit,
    songs: List<Song>,
    currentId: Long?,
    actions: LibraryActions,
    bottomPad: Dp,
    numbered: Boolean = false,
    emptyText: String = "Nothing here yet.",
    extra: (@Composable () -> Unit)? = null,
) {
    // BitChord's detail-page tools: search this list, and sort its tracks.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(SongSort.DEFAULT.name) }
    val sort = SongSort.valueOf(sortName)
    var sortMenu by remember { mutableStateOf(false) }
    val shown = remember(songs, query, sort) { songs.filter { it.matchesQuery(query) }.sortedForDetail(sort) }
    val pageActions = remember {
        listOf(
            PageActions.Action(BitChordIcons.Search, "Search this list") { searching = true },
            PageActions.Action(Icons.AutoMirrored.Rounded.Sort, "Sort") { sortMenu = true },
        )
    }
    if (songs.isNotEmpty()) PageActionsFor(pageActions)
    Box(Modifier.fillMaxSize()) {
    // Where the sort menu drops from: under the sort button in the top bar.
    Box(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 76.dp)) {
        SongSortMenu(sortMenu, sort, onSelect = { sortName = it.name }, onDismiss = { sortMenu = false })
    }
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPad)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TopBarSpace()
                // "Full-screen album/playlist cover" runs the cover edge to edge.
                if (Prefs.fullAlbumCover.state.collectAsState().value) cover(Modifier.fillMaxWidth().aspectRatio(1f), 0.dp)
                else cover(Modifier.fillMaxWidth(0.76f).aspectRatio(1f), 12.dp)
                Spacer(Modifier.height(22.dp))
                Text(title, color = GhostlyColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = GhostlyColors.TextSecondary, fontSize = 22.sp, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${countText(songs.size, "Track")}  ·  ${formatShort(songs.sumOf { it.durationMs })}",
                    color = GhostlyColors.TextSecondary, fontSize = 15.sp,
                )
                Spacer(Modifier.height(22.dp))
                if (songs.isNotEmpty()) {
                    PlayShuffleButtons(
                        onPlay = { actions.play(shown, 0, false, title) },
                        onShuffle = { actions.play(shown, 0, true, title) },
                    )
                }
                extra?.invoke()
                Spacer(Modifier.height(14.dp))
            }
        }
        if (searching) item(key = "search") {
            ListSearchField(query, { query = it }, onClose = { searching = false; query = "" })
        }
        if (songs.isEmpty()) item {
            Text(emptyText, color = GhostlyColors.TextSecondary, fontSize = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
        } else if (shown.isEmpty()) item {
            Text("Nothing here matches \u201c$query\u201d", color = GhostlyColors.TextSecondary, fontSize = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
        itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
            Box(Modifier.animateItem()) {
                SongRow(s, s.id == currentId, { actions.play(shown, i, false, title) }, { SongMenuItems(s, actions, it) },
                    trackNumber = if (numbered) s.track % 1000 else null, onPlayNext = { actions.playNext(s) })
            }
        }
    }
    // The header is row 0, then the search field when it is open.
    val playingRow = remember(shown, currentId, searching) {
        shown.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }?.plus(if (searching) 2 else 1)
    }
    LocateTargetFor(listState, playingRow)
    }
}

/** A dark rounded card with a big grey symbol, for collections without a cover (folders, favorites). */
@Composable
fun SymbolCover(icon: ImageVector, modifier: Modifier, corner: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(GhostlyColors.Card), Alignment.Center) {
        Icon(icon, null, tint = GhostlyColors.PlaceholderIcon, modifier = Modifier.fillMaxSize(0.3f))
    }
}

@Composable
fun AlbumPage(album: Album, currentId: Long?, actions: LibraryActions, bottomPad: Dp) {
    CollectionScreen(
        album.title,
        album.artist + if (album.year > 0) "  ·  ${album.year}" else "",
        { m, c -> AlbumArt(album.songs.first(), m, corner = c, px = 900) },
        album.songs, currentId, actions, bottomPad, numbered = true,
    )
}

@Composable
fun ArtistPage(artist: Artist, currentId: Long?, actions: LibraryActions, bottomPad: Dp) {
    CollectionScreen(
        artist.name, "Artist",
        { m, c -> AlbumArt(artist.songs.first(), m, corner = if (c > 0.dp) 400.dp else 0.dp, px = 900) },
        artist.songs, currentId, actions, bottomPad,
        extra = {
            if (artist.albums.size > 1 || artist.albums.firstOrNull()?.title != "Unknown album") {
                Column(Modifier.fillMaxWidth()) {
                    Shelf("Albums", artist.albums, { it.id }) { a ->
                        SquareCard(a.songs.first(), a.title, if (a.year > 0) "${a.year}" else "", 150.dp) { actions.openAlbum(a.id) }
                    }
                    ShelfTitle("Songs")
                }
            }
        },
    )
}

@Composable
fun FolderPage(path: String, songs: List<Song>, currentId: Long?, actions: LibraryActions, bottomPad: Dp) {
    // A device folder takes the same sort as the library, as in BitChord.
    val sort by Prefs.localSort.collectAsState()
    val sorted = remember(songs, sort) { songs.sortedForLibrary(sort) }
    CollectionScreen(folderName(path), path, { m, c -> SymbolCover(Icons.Rounded.Folder, m, c) }, sorted, currentId, actions, bottomPad)
}

/**
 * The Songs tab's sort: the order it is in now, opening BitChord's sort menu
 * (each order, a tick against the one in force).
 */
@Composable
private fun SortRow(sort: LocalMusicSort, onSort: (LocalMusicSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sort.label, color = GhostlyColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Rounded.Sort, "Sort music", tint = GhostlyColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = GhostlyColors.Card, shape = RoundedCornerShape(14.dp)) {
            LocalMusicSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, color = GhostlyColors.Text) },
                    trailingIcon = if (option == sort) {
                        { Icon(Icons.Rounded.Check, null, tint = GhostlyColors.Text) }
                    } else null,
                    onClick = { onSort(option); open = false },
                )
            }
        }
    }
}

// ---------- helpers ----------

fun folderName(path: String) = path.substringAfterLast('/').ifBlank { "Internal storage" }

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatShort(ms: Long): String {
    val min = ms / 60000
    return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
}

fun countText(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
