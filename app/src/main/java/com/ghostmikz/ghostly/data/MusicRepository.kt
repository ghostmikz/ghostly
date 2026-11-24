package com.ghostmikz.ghostly.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val year: Int,
    val dateAdded: Long,
    val folder: String,
    val isWav: Boolean = false,
    /** When the file last changed, in seconds (MediaStore DATE_MODIFIED), for BitChord's sort. */
    val dateModified: Long = 0,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val albumArtUri: Uri get() = ContentUris.withAppendedId(ALBUM_ART, albumId)

    companion object {
        val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

data class Album(val id: Long, val title: String, val artist: String, val year: Int, val songs: List<Song>)

data class Artist(val name: String, val songs: List<Song>, val albums: List<Album>)

data class Library(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

/** Reads every music file Android's media scanner knows about. Nothing online. */
object MusicRepository {
    private const val UNKNOWN = "<unknown>"

    /** Folder names that hold sounds rather than music (used by "Filter non-music audio"). */
    private val NON_MUSIC_FOLDERS = listOf("recordings", "recorder", "call", "voice", "notifications", "ringtones", "alarms", "audiobooks", "podcasts")

    /** [includeHidden] also lists the songs hidden in Settings (the saved queue, the Hidden songs page). */
    suspend fun load(context: Context, filterNonMusic: Boolean = true, includeHidden: Boolean = false): Library = withContext(Dispatchers.IO) {
        val hidden = if (includeHidden) emptySet() else Prefs.hiddenSongs.value
        val songs = querySongs(context, onlyMusic = filterNonMusic).filter { s ->
            s.id !in hidden
        }.filter { s ->
            !filterNonMusic || (
                s.durationMs >= 30_000 && !s.isWav &&
                    s.folder.lowercase().split('/').none { part -> NON_MUSIC_FOLDERS.any { part.startsWith(it) } }
                )
        }
        val albums = songs.groupBy { it.albumId }.map { (id, list) ->
            val sorted = list.sortedWith(compareBy({ it.track / 1000 }, { it.track % 1000 }, { it.title.lowercase() }))
            Album(
                id = id,
                title = sorted.first().album,
                artist = sorted.groupingBy { it.artist }.eachCount().maxBy { it.value }.key,
                year = sorted.maxOf { it.year },
                songs = sorted,
            )
        }.sortedBy { it.title.lowercase() }
        val artists = songs.groupBy { it.artist }.map { (name, list) ->
            Artist(
                name = name,
                songs = list.sortedBy { it.title.lowercase() },
                albums = albums.filter { a -> a.songs.any { it.artist == name } },
            )
        }.sortedBy { it.name.lowercase() }
        Library(songs.sortedBy { it.title.lowercase() }, albums, artists)
    }

    /** What the duplicate finder shows about each copy: its file name, format, size and bitrate. */
    data class FileInfo(val name: String, val format: String, val bytes: Long, val kbps: Int)

    suspend fun fileInfo(context: Context, songs: List<Song>): Map<Long, FileInfo> = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext emptyMap()
        val durations = songs.associate { it.id to it.durationMs }
        val cols = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.MIME_TYPE); add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.BITRATE)
        }.toTypedArray()
        val out = HashMap<Long, FileInfo>()
        songs.map { it.id }.chunked(500).forEach { chunk ->
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",")})", null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1).orEmpty()
                        val bytes = c.getLong(3)
                        val tagged = if (Build.VERSION.SDK_INT >= 30) c.getLong(4) / 1000 else 0L
                        val dur = durations[id] ?: 0L
                        // Size over length: a little high for a big cover, but right for a VBR
                        // MP3, where the scanner reads one frame's rate (32 kbps for a 258 kbps file).
                        val estimate = if (dur > 0) bytes * 8 / dur else 0L
                        val kbps = if (tagged > 0 && tagged * 2 >= estimate) tagged else estimate
                        out[id] = FileInfo(name, formatName(c.getString(2).orEmpty(), name), bytes, kbps.toInt())
                    }
                }
            }
        }
        out
    }

    private fun formatName(mime: String, name: String): String = when {
        mime == "audio/mpeg" -> "MP3"
        "flac" in mime -> "FLAC"
        "opus" in mime -> "Opus"
        "ogg" in mime || "vorbis" in mime -> "OGG"
        "wav" in mime -> "WAV"
        "mp4" in mime || "m4a" in mime || "aac" in mime -> name.substringAfterLast('.', "M4A").uppercase()
        else -> name.substringAfterLast('.', "").uppercase().ifEmpty { "Audio" }
    }

    private fun querySongs(context: Context, onlyMusic: Boolean): List<Song> {
        val folderCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            folderCol,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        // With the filter off, list every audio file (recordings, notes...), like BitChord does.
        val selection = if (onlyMusic) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        } else {
            "${MediaStore.Audio.Media.DURATION} > 0"
        }
        val out = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iModified = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iFolder = c.getColumnIndexOrThrow(folderCol)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (c.moveToNext()) {
                var folder = c.getString(iFolder).orEmpty()
                if (Build.VERSION.SDK_INT < 29) folder = folder.substringBeforeLast('/', "")
                out += Song(
                    id = c.getLong(iId),
                    title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                    artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != UNKNOWN } ?: "Unknown artist",
                    album = c.getString(iAlbum)?.takeIf { it.isNotBlank() && it != UNKNOWN } ?: "Unknown album",
                    albumId = c.getLong(iAlbumId),
                    durationMs = c.getLong(iDur),
                    track = c.getInt(iTrack),
                    year = c.getInt(iYear),
                    dateAdded = c.getLong(iAdded),
                    folder = folder.trimEnd('/'),
                    isWav = c.getString(iMime).orEmpty().contains("wav"),
                    dateModified = c.getLong(iModified),
                )
            }
        }
        return out
    }
}

/** BitChord's LocalMusicSort: the stable, persisted order of the on-device library. */
enum class LocalMusicSort(val label: String) {
    TITLE_ASC("Alphabetical (A to Z)"),
    TITLE_DESC("Alphabetical (Z to A)"),
    DATE_ADDED("Date added (newest first)"),
    DATE_MODIFIED("Date modified (newest first)"),
}

/** BitChord's sortedForLibrary: a song list in the order the sort menu picked. */
fun List<Song>.sortedForLibrary(order: LocalMusicSort): List<Song> = when (order) {
    LocalMusicSort.TITLE_ASC -> sortedWith(compareBy<Song> { it.title.lowercase(java.util.Locale.ROOT) })
    LocalMusicSort.TITLE_DESC -> sortedWith(compareByDescending<Song> { it.title.lowercase(java.util.Locale.ROOT) })
    LocalMusicSort.DATE_ADDED -> sortedWith(
        compareByDescending<Song> { it.dateAdded }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
    LocalMusicSort.DATE_MODIFIED -> sortedWith(
        compareByDescending<Song> { it.dateModified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
}
