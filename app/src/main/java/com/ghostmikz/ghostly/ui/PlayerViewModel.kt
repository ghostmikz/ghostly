package com.ghostmikz.ghostly.ui

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
        val queue = order.map { i -> c.getMediaItemAt(i).let { item -> ids[item.mediaId] ?: item.asSong() } }
        _state.value = PlayerState(
            current = c.currentMediaItem?.let { ids[it.mediaId] ?: it.asSong() },
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            queue = queue,
            queueIndex = order.indexOf(c.currentMediaItemIndex),
            from = from,
        )
    }

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false, from: String = "") {
        val c = controller ?: return
        if (songs.isEmpty()) return
        this.from = from
        c.shuffleModeEnabled = shuffle
        val first = if (shuffle) songs.indices.random() else start
        c.setMediaItems(songs.map { it.toMediaItem() }, first, 0)
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
    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun playNext(song: Song) = controller?.let { c ->
        if (c.mediaItemCount == 0) play(listOf(song), from = song.title) else c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
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
