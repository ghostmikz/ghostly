package com.ghostmikz.ghostly.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What each song the playlists and history point at actually is: its title,
 * artist and length, kept next to its MediaStore id.
 *
 * The ids only mean something on the phone that made them. After a backup is
 * restored on another phone (or an export imported there, or the media scanner
 * rebuilt), an id may point at nothing or at a different song. [reconcile] finds
 * each song again by what it is and moves the playlists and history onto its
 * new id.
 */
object SongKeys {
    private const val LENGTH_SLACK_S = 2L
    private lateinit var file: File
    @Volatile private var keys: Map<Long, String> = emptyMap()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "song_keys.json")
        val text = SafeFiles.read(file) ?: return
        val parsed = runCatching { parse(JSONObject(text)) }.getOrNull()
        if (parsed == null) SafeFiles.setAside(file) else keys = parsed
    }

    /** Title, artist and length in seconds: the same file reads the same on any phone. */
    fun keyOf(song: Song): String = "${loose(song.title)}|${loose(song.artist)}|${song.durationMs / 1000}"

    private fun titleArtist(key: String): String = key.substringBeforeLast('|')

    private fun loose(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * Matches the stored songs against the phone's library, moves playlists and
     * history to any song that now has a different id, and records the keys of
     * every song they point at. Called whenever the library is read.
     */
    suspend fun reconcile(context: Context) {
        if (!::file.isInitialized) return
        val songs = runCatching {
            MusicRepository.load(context, filterNonMusic = false, includeHidden = true).songs
        }.getOrNull() ?: return
        if (songs.isEmpty()) return
        withContext(Dispatchers.Main) { reconcile(songs) }
    }

    /**
     * Which stored ids now belong to another song id: [known] is what each stored
     * id was, [songs] the phone's library. An id whose song is still there is left
     * alone; one that is gone or now another song moves to the song it was.
     */
    internal fun movesFor(known: Map<Long, String>, songs: List<Song>): Map<Long, Long> {
        val songKey = songs.associate { it.id to keyOf(it) }
        val byKey = songs.groupBy { songKey.getValue(it.id) }
        val byTitleArtist = songs.groupBy { titleArtist(songKey.getValue(it.id)) }
        val moves = HashMap<Long, Long>()
        known.forEach { (id, key) ->
            if (songKey[id] == key) return@forEach
            // Exactly that song first; failing that, the only song with its title and
            // artist within two seconds of its length (a re-encode can differ a little;
            // a live take or remix differs more).
            val seconds = key.substringAfterLast('|').toLongOrNull()
            val found = byKey[key]?.firstOrNull()
                ?: seconds?.let { s ->
                    byTitleArtist[titleArtist(key)]
                        ?.filter { kotlin.math.abs(it.durationMs / 1000 - s) <= LENGTH_SLACK_S }
                        ?.singleOrNull()
                }
            if (found != null && found.id != id) moves[id] = found.id
        }
        return moves
    }

    @Synchronized
    internal fun reconcile(songs: List<Song>) {
        val moves = movesFor(keys, songs)
        if (moves.isNotEmpty()) {
            Playlists.remap(moves)
            History.remap(moves)
        }
        val referenced = Playlists.playlists.value.flatMap { it.songIds }.toSet() + History.stats.value.keys
        val next = nextKeys(keys, moves, songs, referenced)
        if (next != keys) {
            keys = next
            save()
        }
    }

    /**
     * What to remember after [moves]: a stored key goes with its song to the new id
     * (a found song outranks one that stayed put on the same id), a stored key that
     * wasn't found stays as it is, so the song can still be found once it is on the
     * phone (the scanner may not have reached it yet), and only an id never seen
     * before takes the key of the song it is now.
     */
    internal fun nextKeys(
        known: Map<Long, String>,
        moves: Map<Long, Long>,
        songs: List<Song>,
        referenced: Set<Long>,
    ): Map<Long, String> {
        val next = HashMap<Long, String>()
        known.forEach { (id, key) -> if (id !in moves) next[id] = key }
        known.forEach { (id, key) -> moves[id]?.let { next[it] = key } }
        val songKey = songs.associate { it.id to keyOf(it) }
        referenced.forEach { id -> if (id !in next) songKey[id]?.let { next[id] = it } }
        return next.filterKeys { it in referenced }
    }

    fun exportJson(): JSONObject = toJson(keys)

    /** Reads an export's keys without changing anything. */
    fun parseImport(o: JSONObject): Map<Long, String> = parse(o)

    @Synchronized
    fun applyImport(imported: Map<Long, String>) {
        keys = imported
        save()
    }

    private fun save() = SafeFiles.write(file, toJson(keys).toString())

    private fun toJson(map: Map<Long, String>) = JSONObject().apply { map.forEach { (id, key) -> put(id.toString(), key) } }

    private fun parse(o: JSONObject): Map<Long, String> =
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { id -> o.optString(k).takeIf { it.isNotEmpty() }?.let { id to it } } }.toMap()
}
