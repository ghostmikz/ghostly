// Ported from BitChord's ReorderPlaylistPage (https://github.com/kushagrasinghx/BitChord, GPLv3).
package com.ghostmikz.ghostly.ui

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ghostmikz.ghostly.data.Playlist
import com.ghostmikz.ghostly.data.Song

/**
 * Rearranges one of the user's playlists: every entry with a handle to drag it
 * by, and nothing saved until Save.
 *
 * Drawn in place of the playlist page, rather than in a sheet over it — the
 * page is already the playlist, and a sheet's own drag-to-dismiss would be one
 * more gesture competing with the rows'. Back closes it, as the X does.
 *
 * Held locally and saved as one edit, rather than a move at a time while
 * dragging: a drag passes through a dozen positions on the way to the one it
 * was meant for.
 */
@Composable
fun ReorderPlaylistPage(
    playlist: Playlist,
    songs: List<Song>,
    onSave: (List<Song>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    BackHandler(onBack = onClose)
    // Kept across rotation and recreation as ids, and rebuilt from whichever song
    // list is current, so a reload of the library doesn't throw the edit away.
    var orderIds by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(songs.map { it.id }) }
    val order = remember(songs) {
        val byId = songs.associateBy { it.id }
        val kept = orderIds.mapNotNull { byId[it] }
        val added = songs.filterNot { s -> s.id in orderIds }
        mutableStateListOf<Song>().apply { addAll(kept + added) }
    }
    LaunchedEffect(order) {
        androidx.compose.runtime.snapshotFlow { order.map { it.id } }.collect { orderIds = it }
    }
    val changed = order.map { it.id } != songs.map { it.id }
    Column(modifier.fillMaxSize()) {
        TopBarSpace()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Back", tint = GhostlyColors.Text)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Reorder songs",
                    style = MaterialTheme.typography.titleLarge,
                    color = GhostlyColors.Text,
                )
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GhostlyColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The page's filled pill, as its Play is.
            Button(
                onClick = { onSave(order.toList()) },
                enabled = changed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhostlyColors.Text,
                    contentColor = GhostlyColors.Background,
                    disabledContainerColor = GhostlyColors.Text.copy(alpha = 0.16f),
                    disabledContentColor = GhostlyColors.TextSecondary,
                ),
            ) {
                Text("Save")
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = GhostlyColors.CardHigh)
        ReorderableSongList(order, enabled = true, contentPadding)
    }
}

/**
 * The drag itself, over a lazy list so a playlist of hundreds of tracks
 * doesn't compose all of them at once.
 *
 * The held row keeps two numbers: which entry it is ([held], by song id)
 * and how far it is drawn from its own slot ([offset]). Each time its centre
 * crosses into a neighbour the two trade places in [order], and the offset is
 * reduced by exactly the distance its slot just moved, so the row stays under
 * the finger rather than jumping with the swap.
 */
@Composable
private fun ReorderableSongList(
    order: MutableList<Song>,
    enabled: Boolean,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    var held by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edgeZone = with(density) { 56.dp.toPx() }
    val edgeSpeed = with(density) { 900.dp.toPx() }

    fun settle() {
        val key = held ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == key } ?: return
        val top = dragged.offset + offset
        val center = top + dragged.size / 2f
        // Songs only: the footer after the last row is a list item too, and
        // carrying a row onto it would move it to an index past the end.
        val target = items.firstOrNull { item ->
            item.key != key && item.index < order.size &&
                center >= item.offset && center <= item.offset + item.size
        }
        if (target != null) {
            val from = dragged.index
            val to = target.index
            if (from !in order.indices) return
            // LazyColumn holds its place by the key of the first visible row;
            // moving that row would drag the whole list along with it, so the
            // current position is pinned explicitly across the swap.
            val first = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            order.add(to, order.removeAt(from))
            if (from == first || to == first) listState.requestScrollToItem(first, firstOffset)
            offset -= (target.offset - dragged.offset).toFloat()
        }
        // Near either edge the list scrolls under the held row, so a track
        // can be carried further than one screen.
        val viewport = listState.layoutInfo
        autoScroll = when {
            top < viewport.viewportStartOffset + edgeZone && listState.canScrollBackward -> -edgeSpeed
            top + dragged.size > viewport.viewportEndOffset - edgeZone && listState.canScrollForward -> edgeSpeed
            else -> 0f
        }
    }

    fun end() {
        held = null
        offset = 0f
        autoScroll = 0f
    }

    LaunchedEffect(autoScroll != 0f) {
        if (autoScroll == 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (held != null && autoScroll != 0f) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
            previous = now
            val scrolled = listState.scrollBy(autoScroll * seconds)
            if (scrolled == 0f) break
            // The list moved and the finger didn't: the row's slot went with
            // the list, so the row is drawn that much further from it.
            offset += scrolled
            settle()
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        itemsIndexed(order, key = { _, song -> song.id.toString() }) { index, song ->
            val key = song.id.toString()
            val dragging = held == key
            if (dragging) {
                // Scrolled off while held, the row takes its gesture with it and no
                // end callback runs: end the drag here (as the queue's rows do).
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { end() } }
            }
            val start: () -> Unit = {
                held = key
                offset = 0f
            }
            val drag: (Float) -> Unit = { delta ->
                offset += delta
                settle()
            }
            ReorderRow(
                song = song,
                lifted = dragging,
                enabled = enabled,
                onDragStart = start,
                onDrag = drag,
                onDragEnd = ::end,
                onMoveUp = if (index > 0) ({ order.add(index - 1, order.removeAt(index)) }) else null,
                onMoveDown = if (index < order.lastIndex) ({ order.add(index + 1, order.removeAt(index)) }) else null,
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) offset else 0f }
                    .then(if (dragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)),
            )
        }
        item(key = "reorder-footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReorderRow(
    song: Song,
    lifted: Boolean,
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    /** Without a drag: TalkBack / switch access move the song one place. */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    // Holding anywhere on the row picks it up too, not only the handle — the
    // handle is a small target, and a long press is what lists elsewhere on
    // the phone teach people to try first.
    val gestures = Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
            onDrag = { change, amount ->
                change.consume()
                onDrag(amount.y)
            },
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Opaque when lifted: it is drawn over the rows it passes.
            .background(if (lifted) GhostlyColors.Card else Color.Transparent)
            .then(gestures)
            .padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song, Modifier.size(44.dp), corner = 7.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = GhostlyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = GhostlyColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = "Drag to reorder",
            tint = GhostlyColors.TextSecondary,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    customActions = listOfNotNull(
                        onMoveUp?.let { m -> CustomAccessibilityAction("Move up") { m(); true } },
                        onMoveDown?.let { m -> CustomAccessibilityAction("Move down") { m(); true } },
                    )
                }
                .padding(12.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.y)
                        },
                    )
                },
        )
    }
}
