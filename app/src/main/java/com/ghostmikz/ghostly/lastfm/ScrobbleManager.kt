// Adapted from BitChord's ScrobbleManager (https://github.com/kushagrasinghx/BitChord), GPLv3.
package com.ghostmikz.ghostly.lastfm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

data class ScrobbleTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
)

/**
 * Last.fm rules: a track counts once it played for half its length or 4 minutes,
 * whichever comes first, and only if it is longer than 30 seconds.
 */
class ScrobbleManager(
    private val scope: CoroutineScope,
    private val scrobbleOn: () -> Boolean,
    private val nowPlayingOn: () -> Boolean,
) {
    private var job: Job? = null
    private var remainingMs = 0L
    private var timerStartedAt = 0L
    private var songStartedAt = 0L
    private var current: ScrobbleTrack? = null

    fun onTrackChanged(track: ScrobbleTrack?, isPlaying: Boolean) {
        stop()
        current = track
        if (track != null && isPlaying) start(track)
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        val track = current ?: return
        when {
            isPlaying && songStartedAt == 0L -> start(track)
            isPlaying -> resume(track)
            else -> pause()
        }
    }

    fun destroy() = stop()

    private fun start(track: ScrobbleTrack) {
        songStartedAt = System.currentTimeMillis() / 1000
        if (nowPlayingOn()) send("now playing") { LastFM.updateNowPlaying(track.artist.primaryArtist(), track.title, track.album, duration = seconds(track)) }
        val secs = seconds(track)
        if (secs <= MIN_SONG_SECONDS) { remainingMs = 0; return }
        remainingMs = min((secs * 1000L * 0.5f).roundToLong(), MAX_DELAY_SECONDS * 1000L)
        resume(track)
    }

    private fun resume(track: ScrobbleTrack) {
        if (remainingMs <= 0) return
        job?.cancel()
        timerStartedAt = System.currentTimeMillis()
        job = scope.launch {
            delay(remainingMs)
            remainingMs = 0
            timerStartedAt = 0
            val startedAt = songStartedAt
            send("scrobble") { LastFM.scrobble(track.artist.primaryArtist(), track.title, startedAt, track.album, duration = seconds(track)) }
        }
    }

    private fun pause() {
        job?.cancel()
        job = null
        if (timerStartedAt != 0L) {
            remainingMs = (remainingMs - (System.currentTimeMillis() - timerStartedAt)).coerceAtLeast(0)
            timerStartedAt = 0
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        remainingMs = 0
        timerStartedAt = 0
        songStartedAt = 0
    }

    private fun seconds(t: ScrobbleTrack) = (t.durationMs / 1000).toInt()

    private fun send(what: String, call: suspend () -> Result<String>) {
        if (!LastFM.isInitialized() || LastFM.sessionKey.isNullOrBlank() || !scrobbleOn()) return
        scope.launch {
            call().onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Last.fm $what failed: ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ScrobbleManager"
        private const val MIN_SONG_SECONDS = 30
        private const val MAX_DELAY_SECONDS = 240
    }
}
