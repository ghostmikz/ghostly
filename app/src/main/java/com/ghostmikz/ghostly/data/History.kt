package com.ghostmikz.ghostly.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What was played and when, plus starred songs. Stays on the phone, used by the Home screen. */
object History {
    data class Stats(val count: Int, val lastPlayed: Long)

    private lateinit var file: File
    private val _stats = MutableStateFlow<Map<Long, Stats>>(emptyMap())
    val stats: StateFlow<Map<Long, Stats>> = _stats
    private val _favorites = MutableStateFlow<Set<Long>>(emptySet())
    val favorites: StateFlow<Set<Long>> = _favorites

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "history.json")
        runCatching {
            val root = JSONObject(file.readText())
            val plays = root.getJSONObject("plays")
            _stats.value = plays.keys().asSequence().associate { k ->
                val o = plays.getJSONObject(k)
                k.toLong() to Stats(o.getInt("c"), o.getLong("t"))
            }
            val fav = root.getJSONArray("favorites")
            _favorites.value = (0 until fav.length()).map { fav.getLong(it) }.toSet()
        }
    }

    fun recordPlay(songId: Long) {
        val old = _stats.value[songId]
        _stats.value = _stats.value + (songId to Stats((old?.count ?: 0) + 1, System.currentTimeMillis()))
        save()
    }

    /** The star is a like, and likes live in the Liked playlist (as in BitChord). */
    fun toggleFavorite(songId: Long) { Playlists.toggleLike(songId) }

    /** Set by [Playlists] whenever Liked changes. */
    internal fun setFavorites(ids: Set<Long>) {
        _favorites.value = ids
        save()
    }

    fun clearHistory() {
        _stats.value = emptyMap()
        save()
    }

    fun clearFavorites() = Playlists.clearLiked()

    /** History + favorites as JSON (for "Export data"). */
    fun exportJson(): JSONObject {
        val plays = JSONObject()
        _stats.value.forEach { (id, s) -> plays.put(id.toString(), JSONObject().put("c", s.count).put("t", s.lastPlayed)) }
        return JSONObject().put("plays", plays).put("favorites", JSONArray(_favorites.value.toList()))
    }

    fun importJson(root: JSONObject) {
        val plays = root.optJSONObject("plays") ?: JSONObject()
        _stats.value = plays.keys().asSequence().associate { k ->
            val o = plays.getJSONObject(k)
            k.toLong() to Stats(o.getInt("c"), o.getLong("t"))
        }
        val fav = root.optJSONArray("favorites") ?: JSONArray()
        _favorites.value = (0 until fav.length()).map { fav.getLong(it) }.toSet()
        save()
    }

    @Synchronized
    private fun save() {
        val root = exportJson()
        runCatching {
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }
}
