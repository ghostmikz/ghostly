package com.ghostmikz.ghostly.ui

import com.ghostmikz.ghostly.playback.queueTier
import com.ghostmikz.ghostly.playback.queueEntryId
import com.ghostmikz.ghostly.playback.asQueueEntry
import com.ghostmikz.ghostly.playback.QueueTier
import com.ghostmikz.ghostly.playback.QueueShuffle
import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
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
    /** Whether Next / Previous have a track to go to (repeat-all included). */
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var controller: MediaController? = null
    /** Kept so [onCleared] can release a controller that is still connecting. */
    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()
    /** Whether the app is on screen; the position is only polled while it is. */
    private val visible = MutableStateFlow(true)
    fun setVisible(on: Boolean) { visible.value = on }

    /**
     * BitChord's playhead for the lyrics: the position with the moment it was
     * read, and a count of deliberate jumps (a seek, a skip, a track change) so
     * the lyrics can tell a real jump from a late reading.
     */
    val playhead = com.ghostmikz.ghostly.ui.lyrics.PlaybackPosition()
    private var playheadItem = -1
    private var byId: Map<String, Song> = emptyMap()
    private var from = ""

    init {
        viewModelScope.launch {
            val c = controllerFuture.await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            // Shuffle is BitChord's queue edit, not the player's mode: follow its state.
            launch { QueueShuffle.enabled.collect { refresh() } }
            refresh()
            while (isActive) {
                if (!visible.value) {
                    visible.first { it }
                    refresh()
                }
                if (c.isPlaying) {
                    _state.value = _state.value.copy(positionMs = c.currentPosition)
                    playhead.report(c.currentPosition)
                }
                delay(500)
            }
        }
    }

    /**
     * Files added, removed or retagged on the phone: the library is read again once
     * the media scanner has settled, and only while the app is on screen.
     */
    private var watchingMedia = false
    private var mediaReload: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mediaReload?.cancel()
            mediaReload = viewModelScope.launch {
                delay(MEDIA_SETTLE_MS)
                visible.first { it }
                loadLibrary()
            }
        }
    }

    fun loadLibrary() {
        if (!watchingMedia) {
            watchingMedia = runCatching {
                getApplication<Application>().contentResolver.registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver,
                )
            }.isSuccess
        }
        viewModelScope.launch {
            _loading.value = true
            ArtCache.forgetMissing()
            _library.value = runCatching { MusicRepository.load(getApplication(), com.ghostmikz.ghostly.data.Prefs.filterNonMusic.value) }.getOrDefault(Library())
            byId = _library.value.songs.associateBy { it.id.toString() }
            _loading.value = false
            refresh()
            // Playlists and history follow songs whose ids changed (a restore, a rescan).
            com.ghostmikz.ghostly.data.SongKeys.reconcile(getApplication<Application>())
        }
    }

    private fun refresh() {
        val c = controller ?: return
        // A new track (repeat-one starting over included) is a jump for the lyrics.
        if (c.currentMediaItemIndex != playheadItem || c.currentPosition < playhead.positionMs - 1_500) {
            playheadItem = c.currentMediaItemIndex
            playhead.seeks++
        }
        playhead.report(c.currentPosition)
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
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
        )
    }

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false, from: String = "") {
        val c = controller ?: return
        if (songs.isEmpty()) return
        this.from = from
        // BitChord: the player's own shuffle is never used (the session refuses it).
        // Shuffle is the queue itself rearranged, so what the queue shows is what plays.
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

    /**
     * "Play <something> on Ghostly" from the Assistant or a car: the songs whose
     * title, artist or album hold every word, else (an empty or unmatched query)
     * the whole library shuffled. Waits for the player and library on a cold start.
     */
    fun playFromSearch(query: String?) {
        viewModelScope.launch {
            val songs = withTimeoutOrNull(SEARCH_WAIT_MS) {
                controllerFuture.await()
                while (controller == null) delay(50)
                _library.first { it.songs.isNotEmpty() }.songs
            } ?: return@launch
            val words = query.orEmpty().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val found = if (words.isEmpty()) emptyList() else songs.filter { s ->
                val text = "${s.title} ${s.artist} ${s.album}".lowercase(Locale.ROOT)
                words.all { it in text }
            }
            if (found.isNotEmpty()) play(found, from = "Search") else play(songs, shuffle = true)
        }
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
        playhead.seeks++
        playhead.report(ms)
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
        MediaController.releaseFuture(controllerFuture)
        if (watchingMedia) getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
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

/** The scanner reports a copied album file by file; wait for it to finish. */
private const val MEDIA_SETTLE_MS = 3_000L

/** How long a voice request waits for the player and library before giving up. */
private const val SEARCH_WAIT_MS = 15_000L
