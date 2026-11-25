package com.ghostmikz.ghostly.lastfm

import android.content.Context
import android.util.Log
import com.ghostmikz.ghostly.data.SafeFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Scrobbles that haven't reached Last.fm yet, kept on the phone until they do.
 *
 * Every scrobble goes through here: it is saved first, then sent. One that can't
 * be sent (no internet, Last.fm down, signed out) waits for the next chance: the
 * next scrobble, the internet coming back, or the app starting. Last.fm takes
 * scrobbles up to two weeks old, so older ones are let go.
 */
object ScrobbleQueue {
    data class Pending(
        val artist: String,
        val track: String,
        val album: String?,
        /** Seconds; 0 when unknown. */
        val duration: Int,
        /** When the song started, in seconds since 1970. */
        val timestamp: Long,
    )

    /** Last.fm ignores scrobbles older than two weeks; a little less, to be safe. */
    private const val MAX_AGE_S = 13L * 24 * 60 * 60

    /** About a week of nonstop listening; past it, the oldest go first. */
    private const val MAX_WAITING = 3_000

    /** Last.fm's answers that mean this scrobble will never be taken: invalid parameters, invalid resource. */
    private val REJECTED = setOf(6, 7)

    private const val TAG = "ScrobbleQueue"

    private var file: File? = null
    private val _waiting = MutableStateFlow<List<Pending>>(emptyList())
    /** What is waiting to be sent, for Settings to show. */
    val waiting: StateFlow<List<Pending>> = _waiting
    private val sending = Mutex()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "scrobble_queue.json")
        file = f
        val text = SafeFiles.read(f) ?: return
        val parsed = runCatching { parse(text) }.getOrNull()
        if (parsed == null) SafeFiles.setAside(f) else _waiting.value = parsed
    }

    @Synchronized
    fun add(entry: Pending) {
        _waiting.value = (_waiting.value + entry).takeLast(MAX_WAITING)
        save()
    }

    /** Signed out: what waits belongs to that account, never to the next one. */
    @Synchronized
    fun clear() {
        _waiting.value = emptyList()
        save()
    }

    /**
     * Sends what is waiting, oldest first, in batches, until it is all sent or a
     * send fails for a reason that may pass (then it waits for the next try). A
     * batch Last.fm rejects as invalid is tried one scrobble at a time, so one bad
     * scrobble can't take the others with it. [nowS] is the time in seconds.
     */
    suspend fun flush(
        nowS: Long = System.currentTimeMillis() / 1000,
        send: suspend (List<Pending>) -> Result<*> = { LastFM.scrobbleBatch(it) },
    ) = sending.withLock {
        dropStale(nowS)
        // How many more go one at a time: the rest of a batch Last.fm refused.
        var single = 0
        while (true) {
            val batch = _waiting.value.take(if (single > 0) 1 else LastFM.MAX_BATCH)
            if (batch.isEmpty()) return@withLock
            val result = send(batch)
            val error = result.exceptionOrNull()
            when {
                error == null -> {
                    remove(batch)
                    if (single > 0) single--
                }
                error is CancellationException -> throw error
                error is LastFM.LastFmException && error.code in REJECTED ->
                    if (single == 0 && batch.size > 1) single = batch.size else {
                        Log.w(TAG, "Last.fm refused a scrobble of ${batch[0].track}: ${error.message}")
                        remove(batch)
                        if (single > 0) single--
                    }
                else -> {
                    Log.d(TAG, "${_waiting.value.size} scrobbles wait for the next try: ${error.message}")
                    return@withLock
                }
            }
        }
    }

    @Synchronized
    private fun remove(sent: List<Pending>) {
        // Taken out by what they are, not where they were: anything added, trimmed
        // or cleared meanwhile is left as it is.
        val left = sent.toMutableList()
        _waiting.value = _waiting.value.filter { !left.remove(it) }
        save()
    }

    @Synchronized
    private fun dropStale(nowS: Long) {
        val fresh = _waiting.value.filter { nowS - it.timestamp <= MAX_AGE_S }
        if (fresh.size != _waiting.value.size) {
            _waiting.value = fresh
            save()
        }
    }

    private fun save() {
        val f = file ?: return
        SafeFiles.write(f, toJson(_waiting.value))
    }

    private fun toJson(list: List<Pending>) = JSONArray().apply {
        list.forEach { p ->
            put(
                JSONObject().put("artist", p.artist).put("track", p.track).put("duration", p.duration).put("ts", p.timestamp)
                    .apply { p.album?.let { put("album", it) } },
            )
        }
    }.toString()

    private fun parse(text: String): List<Pending> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Pending(
                    artist = o.getString("artist"),
                    track = o.getString("track"),
                    album = o.optString("album").takeIf { it.isNotEmpty() },
                    duration = o.optInt("duration"),
                    timestamp = o.getLong("ts"),
                )
            }.getOrNull()
        }
    }
}
