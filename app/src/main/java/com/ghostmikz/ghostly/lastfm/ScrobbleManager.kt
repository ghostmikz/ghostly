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

/** Where scrobbles go: Last.fm, or a stand-in in the tests. */
interface ScrobbleSender {
    suspend fun scrobble(artist: String, track: String, timestamp: Long, album: String?, duration: Int): Result<*>
    suspend fun nowPlaying(artist: String, track: String, album: String?, duration: Int?): Result<*>
}

object LastFmSender : ScrobbleSender {
    /** Saved first, then sent with anything still waiting ([ScrobbleQueue]), so no scrobble is lost offline. */
    override suspend fun scrobble(artist: String, track: String, timestamp: Long, album: String?, duration: Int): Result<*> {
        ScrobbleQueue.add(ScrobbleQueue.Pending(artist, track, album, duration, timestamp))
        ScrobbleQueue.flush()
        return Result.success(Unit)
    }

    override suspend fun nowPlaying(artist: String, track: String, album: String?, duration: Int?) =
        LastFM.updateNowPlaying(artist = artist, track = track, album = album, duration = duration)
}

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
    private val sender: ScrobbleSender = LastFmSender,
    private val clock: () -> Long = System::currentTimeMillis,
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
        songStartedAt = clock() / 1000
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
            markScrobbled()
            return
        }
        scrobbleTimerStartedAt = clock()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(song, resolvedDurationSeconds)
                markScrobbled()
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = clock() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(song: ScrobbleTrack) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = clock()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                val durationSeconds = song.durationSeconds() ?: 0
                scrobbleSong(song, durationSeconds)
                markScrobbled()
            }
    }

    /** Nothing left to time: a resume or a settings change must not scrobble it again. */
    private fun markScrobbled() {
        scrobbleJob = null
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun scrobbleSong(song: ScrobbleTrack, durationSeconds: Int) {
        val scrobbleArtist = song.artist.forScrobble()
        scope.launch {
            val startedAt = songStartedAt
            sender
                .scrobble(
                    artist = scrobbleArtist,
                    track = song.title,
                    timestamp = startedAt,
                    album = song.album,
                    duration = durationSeconds,
                ).onSuccess {
                    Log.d(TAG, "Scrobble saved: ${song.title} by ${song.artist}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.e(TAG, "Failed to scrobble: ${song.title}", throwable)
                }
        }
    }

    private fun updateNowPlaying(song: ScrobbleTrack) {
        val scrobbleArtist = song.artist.forScrobble()
        scope.launch {
            sender
                .nowPlaying(
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
