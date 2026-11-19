// Ported from BitChord's ScrobbleManager (https://github.com/kushagrasinghx/BitChord, GPLv3).
// BitChord's Song carries its length as "M:SS" text; a local file's length is
// known exactly, so [ScrobbleTrack] carries milliseconds instead.
package com.ghostmikz.ghostly.lastfm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

/** What the scrobbler needs to know about the track that is playing. */
data class ScrobbleTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
)

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    var useNowPlaying = true
    var usePrimaryArtistOnly = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun onSongStart(
        song: ScrobbleTrack?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(song, durationMs)
        if (useNowPlaying) {
            updateNowPlaying(song)
        }
    }

    fun onSongResume(song: ScrobbleTrack) {
        resumeScrobbleTimer(song)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
    }

    private fun startScrobbleTimer(
        song: ScrobbleTrack,
        durationMs: Long? = null,
    ) {
        scrobbleJob?.cancel()
        val resolvedDurationSeconds = durationMs?.toInt()?.div(1000)
            ?: song.durationSeconds()
            ?: return

        if (resolvedDurationSeconds <= minSongDuration) return

        val thresholdMs = (resolvedDurationSeconds * 1000L * scrobbleDelayPercent).roundToLong()
        scrobbleRemainingMillis = min(thresholdMs, scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSong(song, resolvedDurationSeconds)
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(song, resolvedDurationSeconds)
                scrobbleJob = null
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(song: ScrobbleTrack) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                val durationSeconds = song.durationSeconds() ?: 0
                scrobbleSong(song, durationSeconds)
                scrobbleJob = null
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun scrobbleSong(song: ScrobbleTrack, durationSeconds: Int) {
        val scrobbleArtist = song.artist.forScrobble()
        scope.launch {
            LastFM
                .scrobble(
                    artist = scrobbleArtist,
                    track = song.title,
                    duration = durationSeconds,
                    timestamp = songStartedAt,
                    album = song.album,
                ).onSuccess {
                    Log.d(TAG, "Scrobbled: ${song.title} by ${song.artist}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.e(TAG, "Failed to scrobble: ${song.title}", throwable)
                }
        }
    }

    private fun updateNowPlaying(song: ScrobbleTrack) {
        val scrobbleArtist = song.artist.forScrobble()
        scope.launch {
            LastFM
                .updateNowPlaying(
                    artist = scrobbleArtist,
                    track = song.title,
                    album = song.album,
                    duration = song.durationSeconds(),
                ).onSuccess {
                    Log.d(TAG, "Updated now playing: ${song.title}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.e(TAG, "Failed to update now playing: ${song.title}", throwable)
                }
        }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: ScrobbleTrack?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(song, durationMs)
            } else {
                onSongResume(song)
            }
        } else {
            onSongPause()
        }
    }

    private fun ScrobbleTrack.durationSeconds(): Int? = (durationMs / 1000).toInt().takeIf { it > 0 }

    private fun String.forScrobble(): String = if (usePrimaryArtistOnly) primaryArtist() else this

    companion object {
        private const val TAG = "ScrobbleManager"
    }
}
