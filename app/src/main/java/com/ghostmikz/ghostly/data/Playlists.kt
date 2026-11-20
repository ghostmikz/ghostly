package com.ghostmikz.ghostly.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A playlist of songs on this phone. [LIKED_ID] is always there and always first.
 * Ported from BitChord's OfflinePlaylist (https://github.com/kushagrasinghx/BitChord, GPLv3).
 */
data class Playlist(
    val id: String,
    val title: String,
    /** In the order they were added; the newest like first for Liked. */
    val songIds: List<Long>,
) {
    val isLiked: Boolean get() = id == Playlists.LIKED_ID
}

/**
 * The playlist store, after BitChord's OfflinePlaylists: in memory as a StateFlow,
 * on disk as one JSON file. The star on the player is a like, and a like lives in
 * the Liked playlist; [History.favorites] mirrors it for the rest of the app.
 */
object Playlists {
    const val LIKED_ID = "liked"
    private lateinit var file: File
    private val lock = Any()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "playlists.json")
        History.init(context)
        val text = SafeFiles.read(file)
        val loaded = text?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
        // A file that exists but can't be read is set aside, never overwritten.
        if (text != null && loaded == null) SafeFiles.setAside(file)
        // First run after playlists arrived: the old favourites become Liked.
        publish(loaded ?: listOf(Playlist(LIKED_ID, "Liked", History.favorites.value.toList())))
        if (loaded == null) save()
    }

    fun get(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun isLiked(songId: Long): Boolean = songId in History.favorites.value

    /** Likes or unlikes a song; returns whether it is liked now. */
    fun toggleLike(songId: Long): Boolean {
        var now = false
        mutate { list ->
            now = songId !in History.favorites.value
            list.map { p ->
                if (!p.isLiked) p
                else p.copy(songIds = if (now) listOf(songId) + p.songIds.filterNot { it == songId } else p.songIds.filterNot { it == songId })
            }
        }
        return now
    }

    /** A new playlist, holding [songIds] when it was made to take them. */
    fun create(title: String, songIds: List<Long> = emptyList()): Playlist {
        val p = Playlist("p" + System.currentTimeMillis().toString(36), title.trim(), songIds.distinct())
        mutate { it + p }
        return p
    }

    class AddResult(val added: Int, val alreadyThere: Int)

    /** Adds [songIds] to every playlist in [ids], each song at most once per playlist. */
    fun add(ids: Collection<String>, songIds: List<Long>): AddResult {
        val incoming = songIds.distinct()
        var added = 0
        var already = 0
        mutate { list ->
            list.map { p ->
                if (p.id !in ids) return@map p
                val known = p.songIds.toHashSet()
                val fresh = incoming.filter { known.add(it) }
                if (fresh.isEmpty()) { already++; return@map p }
                added++
                // Liked keeps its newest first, as a like from the menu does.
                p.copy(songIds = if (p.isLiked) fresh + p.songIds else p.songIds + fresh)
            }
        }
        return AddResult(added, already)
    }

    /**
     * The playlist in a new order. [songIds] are the songs the reorder page showed
     * (the ones on the phone); any the playlist holds that aren't on the phone keep
     * their places at the end, so a reorder never drops them.
     */
    fun setOrder(id: String, songIds: List<Long>) = mutate { list ->
        list.map { p ->
            if (p.id != id) p
            else {
                // The shown songs take the slots shown songs held, in their new order;
                // a song that isn't on the phone stays in its own slot.
                val shown = songIds.toHashSet()
                val inPlaylist = p.songIds.toHashSet()
                val next = songIds.filter { it in inPlaylist }.iterator()
                p.copy(songIds = p.songIds.map { id -> if (id in shown && next.hasNext()) next.next() else id }.distinct())
            }
        }
    }

    fun remove(id: String, songId: Long) =
        mutate { list -> list.map { if (it.id == id) it.copy(songIds = it.songIds.filterNot { s -> s == songId }) else it } }

    /** Liked is the app's, not the user's: it can't be renamed or deleted. */
    fun rename(id: String, title: String) {
        if (id == LIKED_ID || title.isBlank()) return
        mutate { list -> list.map { if (it.id == id) it.copy(title = title.trim()) else it } }
    }

    fun delete(id: String) {
        if (id == LIKED_ID) return
        mutate { list -> list.filterNot { it.id == id } }
    }

    /** Liked set to exactly [songIds] (an import of likes without playlists). */
    fun replaceLiked(songIds: List<Long>) =
        mutate { list -> list.map { if (it.isLiked) it.copy(songIds = songIds.distinct()) else it } }

    fun clearLiked() = mutate { list -> list.map { if (it.isLiked) it.copy(songIds = emptyList()) else it } }

    fun exportJson(): JSONObject = toJson(_playlists.value)

    fun importJson(o: JSONObject) {
        synchronized(lock) { publish(parse(o)); save() }
    }

    private inline fun mutate(change: (List<Playlist>) -> List<Playlist>) {
        synchronized(lock) { publish(change(_playlists.value)); save() }
    }

    /** Sets the list and the liked set together, Liked pinned first. */
    private fun publish(list: List<Playlist>) {
        val liked = list.firstOrNull { it.isLiked } ?: Playlist(LIKED_ID, "Liked", emptyList())
        _playlists.value = listOf(liked) + list.filterNot { it.isLiked }
        History.setFavorites(liked.songIds.toSet())
    }

    private fun save() = SafeFiles.write(file, toJson(_playlists.value).toString())

    private fun toJson(list: List<Playlist>) = JSONObject().put("playlists", JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("id", p.id).put("title", p.title).put("songs", JSONArray(p.songIds))) }
    })

    private fun parse(o: JSONObject): List<Playlist> {
        val arr = o.optJSONArray("playlists") ?: JSONArray()
        // One bad entry is skipped rather than losing every playlist with it, and a
        // song listed twice is kept once (the pages key their rows by song id).
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val p = arr.getJSONObject(i)
                val songs = p.optJSONArray("songs") ?: JSONArray()
                Playlist(p.getString("id"), p.optString("title", "Playlist"),
                    (0 until songs.length()).mapNotNull { songs.optLong(it, -1).takeIf { id -> id >= 0 } }.distinct())
            }.getOrNull()
        }.distinctBy { it.id }
    }
}
