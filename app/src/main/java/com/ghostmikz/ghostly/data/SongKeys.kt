package com.ghostmikz.ghostly.data

import android.content.Context
import com.ghostmikz.ghostly.data.stats.ListeningStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What each song the playlists, history, Replay and hidden songs point at actually
 * is: its title, artist and length, and the file it was, kept next to its
 * MediaStore id.
 *
 * The ids only mean something on the phone that made them. After a backup is
 * restored on another phone (or an export imported there, or the media scanner
 * rebuilt), an id may point at nothing or at a different song. [reconcile] finds
 * each song again by what it is and moves everything onto its new id.
 */
object SongKeys {
    private const val LENGTH_SLACK_S = 2L
    private lateinit var file: File
    @Volatile private var keys: Map<Long, Known> = emptyMap()
    private val running = Mutex()

    /** A stored song: its [key] ([keyOf]) and, once confirmed, the file it was ([fileOf]). */
    data class Known(val key: String, val file: String? = null)

    /** Where a song's ids moved: [all] for playlists, history and Replay, [sameFile] for hidden songs. */
    class Moves(val all: Map<Long, Long>, val sameFile: Map<Long, Long>)

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

    /** The file's folder and name: what tells a retagged song from a different one. */
    fun fileOf(song: Song): String? = song.fileName.takeIf { it.isNotEmpty() }?.let { "${song.folder}/$it" }

    private fun titleArtist(key: String): String = key.substringBeforeLast('|')

    private fun loose(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * Matches the stored songs against the phone's [songs] (all of them, from
     * [MusicRepository.scan]; read here when not given), moves everything that
     * points at a song whose id changed, and records the keys of every song
     * pointed at. Called whenever the library is read, and after an import.
     */
    suspend fun reconcile(context: Context, songs: List<Song>? = null) {
        if (!::file.isInitialized) return
        val all = songs ?: runCatching { MusicRepository.scan(context) }.getOrNull() ?: return
        if (all.isEmpty()) return
        running.withLock {
            val moves = movesFor(keys, all)
            if (moves.all.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    Playlists.remap(moves.all)
                    History.remap(moves.all)
                }
                ListeningStats.remap(moves.all, all.associateBy { it.id })
            }
            if (moves.sameFile.isNotEmpty()) withContext(Dispatchers.Main) { Prefs.remapHidden(moves.sameFile) }
            val referenced = Playlists.playlists.value.flatMap { it.songIds }.toSet() +
                History.stats.value.keys + Prefs.hiddenSongs.value + ListeningStats.trackIds()
            val next = nextKeys(keys, moves.all, all, referenced)
            if (next != keys) {
                keys = next
                save()
            }
        }
    }

    /**
     * Which stored ids now belong to another song id: [known] is what each stored
     * id was, [songs] the phone's library. An id whose file or song is still there
     * is left alone; one that is gone or now another song moves to the song it was.
     */
    internal fun movesFor(known: Map<Long, Known>, songs: List<Song>): Moves {
        val byId = songs.associateBy { it.id }
        val songKey = songs.associate { it.id to keyOf(it) }
        val byFile = songs.mapNotNull { s -> fileOf(s)?.let { it to s } }.groupBy({ it.first }, { it.second })
        val byKey = songs.groupBy { songKey.getValue(it.id) }
        val byTitleArtist = songs.groupBy { titleArtist(songKey.getValue(it.id)) }
        val all = HashMap<Long, Long>()
        val sameFile = HashMap<Long, Long>()
        known.forEach { (id, k) ->
            val here = byId[id]
            // Still the same file (retagged, maybe) or still the same song: it stays.
            if (here != null && (songKey[id] == k.key || (k.file != null && fileOf(here) == k.file))) return@forEach
            // The same file under a new id first (a rebuilt media list), then exactly
            // that song, then the only song with its title and artist within two
            // seconds of its length (a re-encode can differ a little; a live take or
            // remix differs more).
            val seconds = k.key.substringAfterLast('|').toLongOrNull()
            val fileMatch = k.file?.let { byFile[it]?.singleOrNull() }
            val found = fileMatch
                ?: byKey[k.key]?.firstOrNull()
                ?: seconds?.let { s ->
                    byTitleArtist[titleArtist(k.key)]
                        ?.filter { kotlin.math.abs(it.durationMs / 1000 - s) <= LENGTH_SLACK_S }
                        ?.singleOrNull()
                }
            if (found != null && found.id != id) {
                all[id] = found.id
                if (found === fileMatch) sameFile[id] = found.id
            }
        }
        return Moves(all, sameFile)
    }

    /**
     * What to remember after [moves]: a stored key goes with its song to the new id
     * (a found song outranks one that stayed put on the same id), a stored key that
     * wasn't found stays as it is, so the song can still be found once it is on the
     * phone (the scanner may not have reached it yet), and an id never seen before
     * takes the key of the song it is now. A song still in its own file takes its
     * current tags (it was retagged), and a key confirmed by its song learns the file.
     */
    internal fun nextKeys(
        known: Map<Long, Known>,
        moves: Map<Long, Long>,
        songs: List<Song>,
        referenced: Set<Long>,
    ): Map<Long, Known> {
        val next = HashMap<Long, Known>()
        known.forEach { (id, k) -> if (id !in moves) next[id] = k }
        known.forEach { (id, k) -> moves[id]?.let { next[it] = k } }
        val byId = songs.associateBy { it.id }
        referenced.forEach { id ->
            val song = byId[id] ?: return@forEach
            val now = Known(keyOf(song), fileOf(song))
            val had = next[id]
            next[id] = when {
                had == null -> now
                had.key == now.key -> had.copy(file = now.file ?: had.file)
                had.file != null && had.file == now.file -> now
                else -> had
            }
        }
        return next.filterKeys { it in referenced }
    }

    fun exportJson(): JSONObject = toJson(keys)

    /** Reads an export's keys without changing anything. */
    fun parseImport(o: JSONObject): Map<Long, Known> = parse(o)

    /** The keys as they are now, to put back if an import fails part way. */
    fun snapshot(): Map<Long, Known> = keys

    @Synchronized
    fun applyImport(imported: Map<Long, Known>) {
        keys = imported
        save()
    }

    private fun save() = SafeFiles.write(file, toJson(keys).toString())

    private fun toJson(map: Map<Long, Known>) = JSONObject().apply {
        map.forEach { (id, k) ->
            put(id.toString(), JSONObject().put("k", k.key).apply { k.file?.let { put("f", it) } })
        }
    }

    /** Each id as {"k": key, "f": file}, or (written before files were kept) the key alone. */
    private fun parse(o: JSONObject): Map<Long, Known> =
        o.keys().asSequence().mapNotNull { name ->
            val id = name.toLongOrNull() ?: return@mapNotNull null
            val known = when (val v = o.opt(name)) {
                is JSONObject -> v.optString("k").takeIf { it.isNotEmpty() }
                    ?.let { Known(it, v.optString("f").takeIf { f -> f.isNotEmpty() }) }
                is String -> v.takeIf { it.isNotEmpty() }?.let { Known(it) }
                else -> null
            }
            known?.let { id to it }
        }.toMap()
}
