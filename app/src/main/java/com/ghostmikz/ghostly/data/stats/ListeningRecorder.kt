/*
 * Ported from BitChord's ListeningRecorder (https://github.com/kushagrasinghx/BitChord, GPLv3).
 * BitChord fills in a track's missing album and artist with a YouTube Music lookup; Ghostly's
 * files already carry those, so the one thing looked up here is the file's genre tag, from
 * MediaStore on the device. No network.
 */
package com.ghostmikz.ghostly.data.stats

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import com.ghostmikz.ghostly.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Turns the player's ticking into the numbers [ListeningStats] keeps.
 *
 * ## Why it samples rather than measures
 *
 * The tempting implementation reads the track's position when it ends and calls
 * that the listening. It is wrong in every direction that matters: a track
 * skipped at 0:20 reports twenty seconds it did play, a track seeked back over
 * reports less than it played, a track left paused for an hour reports the same
 * as one played through, and a track the process is killed under reports
 * nothing at all.
 *
 * So this is fed from the service's progress sampler, which only ticks while
 * audio is actually coming out, and each tick contributes the *wall-clock* time
 * since the last one. Seeking cannot inflate it because position is never
 * consulted. Pausing cannot inflate it because no tick happens. The gap either
 * side of a pause is capped at [MAX_STEP_MS], which is what stops an hour on the
 * lock screen from arriving as an hour of listening on the first tick after
 * resume.
 *
 * The cost is that the few seconds before the first tick of each track are not
 * counted. That is a systematic undercount of at most one sample interval per
 * track, and it is the right way to be wrong: every alternative overcounts, and
 * a Replay that flatters is worth less than one that is a little shy.
 *
 * ## Minutes and plays are different questions
 *
 * Minutes accumulate continuously. A *play* is counted once, when enough of the
 * track has gone by to call it listened to — the same half-or-four-minutes rule
 * the scrobbler uses, so the two never disagree about what a play is.
 */
object ListeningRecorder {

    private var currentId: Long? = null
    private var lastSampleAt: Long = 0L
    private var playedThisTrack: Long = 0L
    private var playCounted = false
    private var samplesSinceFlush = 0

    /**
     * One tick of the player's sampler.
     *
     * [durationMs] is the decoder's figure when it has one; the file's stated
     * runtime stands in until it does, and a track with neither simply has to
     * clear the thirty-second floor to count as a play.
     */
    @Synchronized
    fun onSample(song: Song, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (song.id != currentId) {
            // A new track anchors the clock and contributes nothing yet — see
            // the class note on undercounting.
            currentId = song.id
            lastSampleAt = now
            playedThisTrack = 0L
            playCounted = false
            resumeAnchor = false
            return
        }
        if (resumeAnchor) {
            // First tick after a pause: the time paused is not listening.
            resumeAnchor = false
            lastSampleAt = now
            return
        }
        val step = (now - lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        lastSampleAt = now
        if (step <= 0L) return
        playedThisTrack += step

        val length = durationMs.takeIf { it > 0 } ?: song.durationMs
        val threshold = if (length > 0) {
            min(length / 2, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS)
        } else {
            PLAY_FLOOR_MS
        }
        val counts = !playCounted && playedThisTrack >= threshold
        if (counts) {
            playCounted = true
            com.ghostmikz.ghostly.data.History.countPlay(song.id)
        }

        ListeningStats.record(song, genresOf(song), step, counts)

        if (++samplesSinceFlush >= FLUSH_EVERY) {
            samplesSinceFlush = 0
            ListeningStats.flush()
        }
    }

    /**
     * Playback stopped, paused, or moved on.
     *
     * Forgetting the current track is what makes the *next* tick anchor rather
     * than contribute: without it, a player paused for an afternoon would hand
     * [MAX_STEP_MS] of listening to whatever was on screen when it resumed.
     */
    /**
     * A pause (or a buffering stall or a seek): the clock is re-anchored at the
     * next sample so the gap doesn't count, but the track's progress towards a
     * play is kept. Only a track change ([onStopped]) starts that over.
     */
    @Synchronized
    fun onPaused() {
        resumeAnchor = true
        ListeningStats.flush()
    }

    private var resumeAnchor = false

    @Synchronized
    fun onStopped() {
        resumeAnchor = false
        currentId = null
        playedThisTrack = 0L
        playCounted = false
        samplesSinceFlush = 0
        ListeningStats.flush()
    }

    // ── Filling in what the queue didn't carry ──────────────────────────────

    /**
     * The genre tags of each file, by MediaStore id.
     *
     * The queue carries a track's title, artist and album but not its genre,
     * and the genre chart is built from nothing else. One query per track, kept
     * for the life of the process.
     */
    private val genres = ConcurrentHashMap<Long, List<String>>()

    /** Ids already asked about, so a track on repeat is asked about once. */
    private val asked = ConcurrentHashMap.newKeySet<Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whatever the lookup has found so far for [song].
     *
     * Empty while the query is in flight rather than holding the sample back:
     * the genre is filled into the track's entry by a later sample.
     */
    private fun genresOf(song: Song): List<String> {
        genres[song.id]?.let { return it }
        if (asked.add(song.id)) {
            scope.launch { genres[song.id] = readGenres(song.id) }
        }
        return emptyList()
    }

    /** The genre tag MediaStore read off the file, split into at most two genres. */
    private fun readGenres(id: Long): List<String> = runCatching {
        val resolver = ListeningStats.appContext.contentResolver
        val raw = if (Build.VERSION.SDK_INT >= 30) {
            resolver.query(
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                arrayOf(MediaStore.Audio.Media.GENRE), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } else {
            resolver.query(
                MediaStore.Audio.Genres.getContentUriForAudioId("external", id.toInt()),
                arrayOf(MediaStore.Audio.Genres.NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
        raw.orEmpty()
            .split(GENRE_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any(Char::isLetter) && !it.equals("unknown", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(MAX_GENRES_PER_TRACK)
    }.getOrDefault(emptyList())

    /**
     * The most a single tick may contribute.
     *
     * A shade over the sampler's interval, so an ordinary tick that arrived late
     * — a busy main thread, a device coming out of doze — is still counted in
     * full, while the unbounded gap across a pause is not.
     */
    private const val MAX_STEP_MS = 8_000L

    /** Under half a minute is not a listen, however it ended. */
    private const val PLAY_FLOOR_MS = 30_000L

    /** Past four minutes, half a track is more listening than anyone disputes. */
    private const val PLAY_CEILING_MS = 4 * 60 * 1000L

    /** Six ticks — about half a minute of listening between disk writes. */
    private const val FLUSH_EVERY = 6

    /** How often the playback service ticks [onSample] while audio is playing. */
    const val SAMPLE_MS = 5_000L

    private const val MAX_GENRES_PER_TRACK = 2

    /** How taggers join several genres in one field. */
    private val GENRE_SEPARATORS = Regex("""\s*[;/,|\u0000]\s*""")
}

/**
 * The queue item as the [Song] the recorder files it under.
 *
 * The album id is not in the item's metadata on its own, but the artwork URI the
 * queue is built with is `content://media/external/audio/albumart/<albumId>`.
 */
fun MediaItem.toListeningSong(): Song? {
    val id = mediaId.toLongOrNull() ?: return null
    val m = mediaMetadata
    return Song(
        id = id,
        title = m.title?.toString().orEmpty(),
        artist = m.artist?.toString().orEmpty(),
        album = m.albumTitle?.toString().orEmpty(),
        albumId = m.artworkUri?.lastPathSegment?.toLongOrNull() ?: -1L,
        durationMs = m.durationMs ?: 0L,
        track = 0,
        year = 0,
        dateAdded = 0,
        folder = "",
    )
}
