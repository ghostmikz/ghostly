package com.ghostmikz.ghostly.ui

import com.ghostmikz.ghostly.playback.queueTier
import com.ghostmikz.ghostly.playback.queueEntryId
import com.ghostmikz.ghostly.playback.asQueueEntry
import com.ghostmikz.ghostly.playback.QueueTier
import com.ghostmikz.ghostly.playback.QueueShuffle
import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ghostmikz.ghostly.data.Library
import com.ghostmikz.ghostly.data.MusicRepository
import com.ghostmikz.ghostly.data.Song
import com.ghostmikz.ghostly.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    /** Per queue row: whether it was queued by hand ("Play next") or came with the list. */
    val queueTiers: List<QueueTier> = emptyList(),
    /** Per queue row: its entry id, so a row keeps its identity through a reorder. */
    val queueEntryIds: List<String?> = emptyList(),
    val queueIndex: Int = 0,
    val from: String = "",
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var controller: MediaController? = null
    private var byId: Map<String, Song> = emptyMap()
    private var from = ""

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            // Shuffle is BitChord's queue edit, not the player's mode: follow its state.
            launch { QueueShuffle.enabled.collect { refresh() } }
            refresh()
            while (isActive) {
                if (c.isPlaying) _state.value = _state.value.copy(positionMs = c.currentPosition)
                delay(500)
            }
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _loading.value = true
            ArtCache.forgetMissing()
            _library.value = runCatching { MusicRepository.load(getApplication(), com.ghostmikz.ghostly.data.Prefs.filterNonMusic.value) }.getOrDefault(Library())
            byId = _library.value.songs.associateBy { it.id.toString() }
            _loading.value = false
            refresh()
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val ids = byId
        // The queue in the order it will actually play (the shuffle order when
        // shuffle is on), every item kept: one the library doesn't list (filtered
        // out, or removed since) is drawn from the item's own tags, so a row's
        // position always maps back to the right player index.
        val order = playOrder(c)
        queueOrder = order
        val items = order.map { c.getMediaItemAt(it) }
        val queue = items.map { item -> ids[item.mediaId] ?: item.asSong() }
        _state.value = PlayerState(
            current = c.currentMediaItem?.let { ids[it.mediaId] ?: it.asSong() },
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = QueueShuffle.enabled.value,
            repeat = c.repeatMode,
            queue = queue,
            queueTiers = items.map { it.queueTier },
            queueEntryIds = items.map { it.queueEntryId },
            queueIndex = order.indexOf(c.currentMediaItemIndex),
            from = from,
        )
    }

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false, from: String = "") {
        val c = controller ?: return
        if (songs.isEmpty()) return
        this.from = from
        // BitChord: the player's own shuffle is never used. Shuffle is the queue
        // itself rearranged, so what the queue shows is what plays, in order.
        c.shuffleModeEnabled = false
        val items = songs.map { it.toMediaItem().asQueueEntry(QueueTier.CONTEXT) }
        QueueShuffle.clearCanonicalContext()
        if (shuffle) {
            QueueShuffle.enableForNextQueue()
            c.setMediaItems(QueueShuffle.startingOrder(items, songs.indices.random()), 0, 0)
        } else {
            QueueShuffle.setEnabled(false)
            QueueShuffle.setCanonicalContext(items)
            c.setMediaItems(items, start, 0)
        }
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _state.value = _state.value.copy(positionMs = ms)
    }
    /** [index] is a row of [PlayerState.queue]; the player's own index comes from the play order. */
    fun playQueueItem(index: Int) = controller?.let { c ->
        queueOrder.getOrNull(index)?.takeIf { it < c.mediaItemCount }?.let { c.seekTo(it, 0) }
    }

    private var queueOrder: List<Int> = emptyList()

    // BitChord's queue edits: the player's own move/remove, and Clear takes only
    // the songs queued by hand (QueueCoordinator.clearUserQueue).
    fun removeQueueItem(index: Int) = controller?.let { c ->
        queueOrder.getOrNull(index)?.takeIf { it < c.mediaItemCount }?.let { c.removeMediaItem(it) }
    }

    fun moveQueueItem(from: Int, to: Int) = controller?.let { c ->
        val a = queueOrder.getOrNull(from) ?: return@let
        val b = queueOrder.getOrNull(to) ?: return@let
        if (a < c.mediaItemCount && b < c.mediaItemCount) c.moveMediaItem(a, b)
    }

    fun clearUserQueue() = controller?.let { c ->
        val user = ((c.currentMediaItemIndex + 1) until c.mediaItemCount)
            .filter { c.getMediaItemAt(it).queueTier == QueueTier.USER_QUEUE }
        // Removed as runs, from the end, so earlier indices stay valid.
        var end = user.size
        while (end > 0) {
            var start = end - 1
            while (start > 0 && user[start - 1] == user[start] - 1) start--
            c.removeMediaItems(user[start], user[end - 1] + 1)
            end = start
        }
    }

    private fun playOrder(c: Player): List<Int> {
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        if (!c.shuffleModeEnabled) return (0 until timeline.windowCount).toList()
        val out = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(true)
        while (i != androidx.media3.common.C.INDEX_UNSET && out.size < timeline.windowCount) {
            out += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }
    fun toggleShuffle() = controller?.let { QueueShuffle.toggle(it) }
    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    /**
     * BitChord's "Play next": straight after the current track, at the head of
     * the user's own queue, tagged so a later shuffle leaves it where it is.
     */
    fun playNext(song: Song) = controller?.let { c ->
        if (c.mediaItemCount == 0) play(listOf(song), from = song.title)
        else c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem().asQueueEntry(QueueTier.USER_QUEUE))
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(albumArtUri)
            .setTrackNumber(track % 1000)
            .setDurationMs(durationMs)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build(),
    )
    .build()

/** A queue item the library doesn't list, drawn from its own tags. */
private fun MediaItem.asSong(): Song {
    val m = mediaMetadata
    return Song(
        id = mediaId.toLongOrNull() ?: -1L,
        title = m.title?.toString().orEmpty(),
        artist = m.artist?.toString() ?: "Unknown artist",
        album = m.albumTitle?.toString() ?: "Unknown album",
        albumId = m.artworkUri?.lastPathSegment?.toLongOrNull() ?: -1L,
        durationMs = m.durationMs ?: 0L,
        track = 0, year = 0, dateAdded = 0, folder = "",
    )
}
