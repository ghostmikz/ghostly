package com.ghostmikz.tune.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ghostmikz.tune.data.Library
import com.ghostmikz.tune.data.MusicRepository
import com.ghostmikz.tune.data.Song
import com.ghostmikz.tune.playback.PlaybackService
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
            _library.value = runCatching { MusicRepository.load(getApplication()) }.getOrDefault(Library())
            byId = _library.value.songs.associateBy { it.id.toString() }
            _loading.value = false
            refresh()
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val ids = byId
        val queue = (0 until c.mediaItemCount).mapNotNull { ids[c.getMediaItemAt(it).mediaId] }
        _state.value = PlayerState(
            current = c.currentMediaItem?.mediaId?.let { ids[it] },
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            queue = queue,
            queueIndex = c.currentMediaItemIndex,
        )
    }

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
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
    fun playQueueItem(index: Int) = controller?.seekTo(index, 0)
    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun playNext(song: Song) = controller?.let { c ->
        if (c.mediaItemCount == 0) play(listOf(song)) else c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
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
