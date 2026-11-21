package com.ghostmikz.ghostly.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The lyrics for a song on this phone, from the song itself: a `.lrc` file of
 * the same name beside it, or failing that the lyrics stored inside the file
 * ([EmbeddedLyrics]). Nothing is fetched from the internet.
 *
 * Android only lets a music app read music files, so a `.lrc` beside a song is
 * read directly where the system allows it (Android 10 and older), and otherwise
 * from the folder the listener granted once in Settings (see [Prefs.lyricsFolder]).
 */
object LocalLyrics {
    private const val TAG = "GhostlyLyrics"

    /** Most bytes worth reading from a `.lrc`: anything bigger isn't lyrics. */
    private const val MAX_LRC_BYTES = 1L shl 20

    /** The granted folder's `.lrc` files by lower-case base name, built once per folder. */
    @Volatile private var index: Pair<String, Map<String, Uri>>? = null

    /** The song's lyrics, or null when it has none. Runs on [Dispatchers.IO]. */
    suspend fun forSong(context: Context, song: Song): List<LyricLine>? = withContext(Dispatchers.IO) {
        val raw = runCatching { sidecar(context, song) }.onFailure { Log.d(TAG, "no .lrc for ${song.id}: ${it.message}") }.getOrNull()
            ?: runCatching { EmbeddedLyrics.read(context, song.uri) }.getOrNull()
            ?: return@withContext null
        linesOf(raw)
    }

    /** Forgets the granted folder's list of files, after a rescan or a new folder. */
    fun forget() {
        index = null
    }

    /** Timed LRC where there are timestamps; otherwise plain text, every line at 0:00, as BitChord shows unsynced lyrics. */
    internal fun linesOf(raw: String): List<LyricLine>? {
        val synced = LrcParser.parseLrc(raw).takeIf { lines -> lines.any { it.text.isNotBlank() } }
        if (synced != null) return synced.withBackgroundVocals()
        val plain = raw.lineSequence()
            .map { it.trim() }
            // LRC header tags ([ar:], [ti:], ...) in a file with no timed lines.
            .filterNot { it.startsWith("[") && it.endsWith("]") && ':' in it }
            .toList()
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
        if (plain.none { it.isNotEmpty() }) return null
        return plain.map { LyricLine(0L, it) }
    }

    private fun sidecar(context: Context, song: Song): String? {
        val name = displayName(context, song) ?: return null
        val base = name.substringBeforeLast('.', name)
        // Directly beside the song, where the system lets a music app read it.
        filePath(context, song)?.let { path ->
            val lrc = File(File(path).parentFile, "$base.lrc")
            if (lrc.canRead() && lrc.length() in 1..MAX_LRC_BYTES) {
                runCatching { return lrc.readText() }
            }
        }
        // From the folder granted in Settings.
        val tree = Prefs.lyricsFolder.value.takeIf { it.isNotEmpty() } ?: return null
        val uri = indexFor(context, tree)[base.lowercase()] ?: return null
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_LRC_BYTES.toInt())
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun displayName(context: Context, song: Song): String? =
        context.contentResolver.query(song.uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    @Suppress("DEPRECATION")
    private fun filePath(context: Context, song: Song): String? =
        context.contentResolver.query(song.uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Every `.lrc` under the granted folder, by base name; walked once and kept. */
    private fun indexFor(context: Context, tree: String): Map<String, Uri> {
        index?.takeIf { it.first == tree }?.let { return it.second }
        val treeUri = Uri.parse(tree)
        val found = HashMap<String, Uri>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_FOLDERS) {
            val parent = pending.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            runCatching {
                context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(id)
                        } else if (name.endsWith(".lrc", ignoreCase = true)) {
                            found.putIfAbsent(name.dropLast(4).lowercase(), DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                        }
                    }
                }
            }
        }
        index = tree to found
        return found
    }

    /** Folders walked at most, so a granted folder like the whole of storage can't keep it busy forever. */
    private const val MAX_FOLDERS = 2_000

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
