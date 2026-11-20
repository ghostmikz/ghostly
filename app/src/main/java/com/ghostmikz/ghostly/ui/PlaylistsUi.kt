package com.ghostmikz.ghostly.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostmikz.ghostly.data.Library
import com.ghostmikz.ghostly.data.Playlist
import com.ghostmikz.ghostly.data.Playlists
import com.ghostmikz.ghostly.data.Song
import com.ghostmikz.ghostly.ui.icons.BitChordIcons
import kotlinx.coroutines.launch

// Playlists, after BitChord's local playlists and its PlaylistPickerSheet
// (https://github.com/kushagrasinghx/BitChord, GPLv3).

fun Playlist.songsIn(library: Library): List<Song> {
    val byId = library.songs.associateBy { it.id }
    return songIds.mapNotNull { byId[it] }
}

/** The cover a playlist shows: its first song with art, or a symbol for Liked / an empty one. */
@Composable
fun PlaylistCover(playlist: Playlist, library: Library, modifier: Modifier, corner: Dp = 10.dp) {
    val first = remember(playlist, library) { playlist.songsIn(library).firstOrNull() }
    when {
        playlist.isLiked -> SymbolCover(BitChordIcons.StarFilled, modifier, corner)
        first == null -> SymbolCover(BitChordIcons.MusicNote, modifier, corner)
        else -> AlbumArt(first, modifier, corner = corner, px = 400)
    }
}

/** The Library's Playlists tab: a "New playlist" tile, then Liked, then the user's playlists. */
@Composable
fun PlaylistTile(playlist: Playlist?, library: Library, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().bounceClick(scaleDown = 0.95f, onClick = onClick)) {
        if (playlist == null) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(GhostlyColors.Card), Alignment.Center) {
                Icon(BitChordIcons.Plus, null, tint = GhostlyColors.Text, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("New playlist", color = GhostlyColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("On this phone", color = GhostlyColors.TextSecondary, fontSize = 13.sp)
        } else {
            PlaylistCover(playlist, library, Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(8.dp))
            Text(playlist.title, color = GhostlyColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(countText(playlist.songsIn(library).size, "song"), color = GhostlyColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

/** A playlist's page: the same layout as an album, with rename / delete (not for Liked). */
@Composable
fun PlaylistPage(playlist: Playlist, library: Library, currentId: Long?, actions: LibraryActions, bottomPad: Dp) {
    val songs = remember(playlist, library) { playlist.songsIn(library) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reordering by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (reordering) {
        ReorderPlaylistPage(
            playlist = playlist,
            songs = songs,
            onSave = { Playlists.setOrder(playlist.id, it.map { s -> s.id }); reordering = false },
            onClose = { reordering = false },
            contentPadding = PaddingValues(bottom = bottomPad),
        )
        return
    }
    CollectionScreen(
        playlist.title, if (playlist.isLiked) "Songs you liked" else "Playlist",
        { m, c -> PlaylistCover(playlist, library, m, c) },
        songs, currentId, actions.copy(removeFromPlaylist = { s -> Playlists.remove(playlist.id, s.id) }), bottomPad,
        emptyText = if (playlist.isLiked) "Tap the star on the player to like a song." else "Add songs from any song's menu.",
        extra = if (playlist.isLiked) null else ({
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallPill("Rename") { renaming = true }
                if (songs.size > 1) SmallPill("Reorder songs") { reordering = true }
                SmallPill("Delete") { deleting = true }
            }
        }),
    )
    if (renaming) NameDialog("Rename playlist", playlist.title, "Save", { renaming = false }) { Playlists.rename(playlist.id, it) }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, containerColor = GhostlyColors.Card,
        title = { Text("Delete \"${playlist.title}\"?", color = GhostlyColors.Text) },
        text = { Text("The songs stay on your phone.", color = GhostlyColors.TextSecondary) },
        confirmButton = { TextButton(onClick = { deleting = false; actions.back(); Playlists.delete(playlist.id) }) { Text("Delete", color = GhostlyColors.Accent) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel", color = GhostlyColors.Text) } },
    )
}

@Composable
private fun SmallPill(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(GhostlyColors.Card).bounceClick(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp)) {
        Text(text, color = GhostlyColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = GhostlyColors.Card,
        title = { Text(title, color = GhostlyColors.Text) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GhostlyColors.Text, cursorColor = GhostlyColors.Text))
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name); onDismiss() }) { Text(confirm, color = GhostlyColors.Text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = GhostlyColors.TextSecondary) } },
    )
}

/**
 * Where a song goes (BitChord's PlaylistPickerSheet): playlists are ticked rather than
 * picked, the tick at the top adds to all of them at once, and "New playlist" swaps to a
 * form in the same sheet that creates the playlist with the song already in it.
 * [song] null = opened from the Library to make a playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(song: Song?, library: Library, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val all by Playlists.playlists.collectAsState()
    val startCreating = song == null
    var creating by remember { mutableStateOf(startCreating) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val closeAfter: (() -> Unit) -> Unit = { action ->
        if (!closing) {
            closing = true
            action()
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        }
    }
    fun notice(text: String) = QueueNotice.show(text)

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, shape = RectangleShape,
        containerColor = GhostlyColors.Background, dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler { if (creating && !startCreating) creating = false else closeAfter {} }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (creating) {
                var name by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                val create = {
                    closeAfter {
                        Playlists.create(name, listOfNotNull(song?.id))
                        if (song != null) notice("Added to playlist")
                    }
                }
                SheetTopBar(
                    if (startCreating) Icons.Rounded.Close else Icons.Rounded.ChevronLeft,
                    { if (startCreating) closeAfter {} else creating = false },
                    confirmEnabled = name.isNotBlank(), onConfirm = create,
                ) { Text("New playlist", color = GhostlyColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) }
                Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                    if (name.isEmpty()) Text("Playlist name", color = GhostlyColors.TextSecondary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    BasicTextField(
                        name, { name = it }, singleLine = true,
                        textStyle = TextStyle(color = GhostlyColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Inter),
                        cursorBrush = SolidColor(GhostlyColors.Text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) create() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Text("Saved on this phone.", color = GhostlyColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 22.dp))
            } else {
                SheetTopBar(
                    Icons.Rounded.Close, { closeAfter {} },
                    confirmEnabled = selected.isNotEmpty(),
                    onConfirm = {
                        closeAfter {
                            val r = Playlists.add(selected, listOfNotNull(song?.id))
                            notice(when {
                                r.added > 1 -> "Added to ${r.added} playlists"
                                r.added == 1 -> "Added to playlist"
                                r.alreadyThere > 0 -> "Already in that playlist"
                                else -> "Couldn't add"
                            })
                        }
                    },
                ) { Spacer(Modifier.weight(1f)) }
                if (song != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArt(song, Modifier.size(52.dp), corner = 7.dp)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(song.title, color = GhostlyColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = GhostlyColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.Outline)
                }
                Text("ADD TO PLAYLIST", color = GhostlyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp))
                Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)).background(GhostlyColors.Card), Alignment.Center) {
                        Icon(BitChordIcons.Plus, null, tint = GhostlyColors.Text, modifier = Modifier.size(22.dp))
                    }
                    Text("New playlist", color = GhostlyColors.Text, fontSize = 17.sp, modifier = Modifier.padding(start = 16.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 0.5.dp, color = GhostlyColors.Outline)
                LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                    items(all, key = { it.id }) { p ->
                        val ticked = p.id in selected
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = if (ticked) selected - p.id else selected + p.id }
                                .padding(horizontal = 22.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistCover(p, library, Modifier.size(44.dp), corner = 7.dp)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(p.title, color = GhostlyColors.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(countText(p.songIds.size, "song"), color = GhostlyColors.TextSecondary, fontSize = 14.sp)
                            }
                            Icon(
                                if (ticked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                                tint = if (ticked) GhostlyColors.Text else GhostlyColors.TextSecondary, modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Way out on the left, tick on the right (BitChord's SheetTopBar). */
@Composable
private fun SheetTopBar(
    leading: androidx.compose.ui.graphics.vector.ImageVector, onLeading: () -> Unit,
    confirmEnabled: Boolean, onConfirm: () -> Unit, title: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLeading) { Icon(leading, null, tint = GhostlyColors.Text) }
        title()
        IconButton(onClick = onConfirm, enabled = confirmEnabled) {
            Icon(Icons.Rounded.Check, "Done", tint = if (confirmEnabled) GhostlyColors.Text else GhostlyColors.TextSecondary.copy(alpha = 0.4f))
        }
    }
}

