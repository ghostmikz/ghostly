// Ported from BitChord (https://github.com/kushagrasinghx/BitChord, GPLv3). Ghostly adds
// MP3 (ID3v2) on top of BitChord's FLAC, Matroska and MP4 readers, and reads a
// song's own .lrc file through [LocalLyrics] instead of BitChord's download sidecar.
package com.ghostmikz.ghostly.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The lyrics already sitting inside a downloaded file.
 *
 * The read side of what the download path wrote — see `MediaTagger`, and the
 * three taggers under it. A track that was downloaded had its lyrics fetched
 * once, at download time, and written into the file; asking four servers for
 * them again every time it is played is a network round trip to arrive at a
 * string that is already on disk, and it is the reason a downloaded song showed
 * nothing at all with the connection off.
 *
 * Two fields are read, in this order:
 *
 *  - `BITCHORD_LYRICS`, this app's own, holding the "enhanced" A2 form with the
 *    word timings intact — see [toEnhancedLrc].
 *  - the container's standard lyrics field, holding plain `[mm:ss.xx]` LRC.
 *
 * The second is what every other player reads and what older downloads have,
 * so it is the fallback rather than the exception. Preferring the first is what
 * keeps a downloaded song lighting up word by word instead of a line at a time.
 *
 * Never throws. A file that isn't one of the three containers, or is one and
 * has no lyrics in it, is a null — the caller falls back to the network, which
 * is exactly what it did before this existed.
 */
object EmbeddedLyrics {

    private const val TAG = "GhostlyLyrics"

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: this reads whatever region of the file holds
     * the metadata, and that region is small in all three containers — but its
     * length is stated *by the file*, so a corrupt or hostile one could claim
     * any number at all. `LyricsTag` caps what it writes at 64k, so anything
     * past this is not a tag this app produced.
     */
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /**
     * The lyrics inside [uriString], or null when it has none worth showing.
     *
     * Touches the filesystem, so it runs on [Dispatchers.IO] regardless of
     * where it is called from.
     */
    suspend fun forUri(context: Context, uriString: String): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val raw = runCatching { read(context, Uri.parse(uriString)) }
                .onFailure { Log.d(TAG, "no embedded lyrics in $uriString: ${it.message}") }
                .getOrNull()
                ?: return@withContext null
            // The same last pass the network sources get, so a downloaded track
            // and a streamed one draw their backing vocals the same way.
            LrcParser.parseLrc(raw).takeIf { lines -> lines.any { it.text.isNotBlank() } }
                ?.withBackgroundVocals()
        }

    /** The raw LRC text for the file, preferring this app's word-timed field. */
    internal fun read(context: Context, uri: Uri): String? {
        val head = open(context, uri)?.use { it.readAtMost(MAX_TAG_BYTES) } ?: return null
        fromBytes(head)?.let { return it }
        // An MP4 may keep its moov after the audio, out of reach of the head read on
        // anything longer than a few minutes: found by walking the top-level boxes.
        return if (head.isMp4()) trailingMoov(context, uri)?.let(::mp4) else null
    }

    /**
     * The file's `moov` box wherever it is, found by hopping from one top-level box
     * header to the next (the audio in between is skipped, not read). Null when there
     * is none, or it is bigger than [MAX_TAG_BYTES].
     */
    private fun trailingMoov(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val size = channel.size()
                val header = java.nio.ByteBuffer.allocate(16)
                var position = 0L
                while (position + 8 <= size) {
                    header.clear()
                    if (channel.read(header, position) < 8) return@use null
                    val bytes = header.array()
                    val small = readU32(bytes, 0)
                    val type = String(bytes, 4, 4, Charsets.ISO_8859_1)
                    val boxSize = when (small) {
                        1L -> if (header.position() >= 16) readU64(bytes, 8) else return@use null
                        0L -> size - position
                        else -> small
                    }
                    if (boxSize < 8) return@use null
                    if (type == "moov") {
                        if (boxSize > MAX_TAG_BYTES) return@use null
                        val box = java.nio.ByteBuffer.allocate(boxSize.toInt())
                        while (box.hasRemaining() && channel.read(box, position + box.position()) > 0) Unit
                        return@use box.array()
                    }
                    position += boxSize
                }
                null
            }
        }
    }.getOrNull()

    /**
     * The raw LRC text in [head], whichever of the three containers it is.
     *
     * Split from [read] so the parsing can be tested against bytes a tagger
     * just produced, without a device or a `Context` in the way — the round
     * trip is the only thing that proves a reader and a writer agree.
     */
    internal fun fromBytes(head: ByteArray): String? {
        val found = when {
            head.startsWith(ID3_MAGIC) -> id3(head)
            head.startsWith(FLAC_MAGIC) -> flac(head)
            head.startsWith(MATROSKA_MAGIC) -> matroska(head)
            head.isMp4() -> mp4(head)
            else -> null
        }
        return found?.takeIf { it.isNotBlank() }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }

    // ---- MP3 (ID3v2) ---------------------------------------------------------

    /**
     * Lyrics from an ID3v2 tag at the head of an MP3: a synced SYLT frame turned
     * into LRC first (it keeps the timing), then the plain USLT frame, then a
     * TXXX frame named LYRICS, which some taggers write instead.
     */
    private fun id3(b: ByteArray): String? {
        if (b.size < 10) return null
        val major = b[3].toInt()
        if (major !in 2..4) return null
        val flags = b[5].toInt()
        val tagSize = syncsafe(b, 6)
        val end = minOf(b.size, 10 + tagSize)
        var pos = 10
        // An extended header, when flagged, is skipped by its own stated size.
        if (flags and 0x40 != 0 && major >= 3) {
            val ext = if (major == 4) syncsafe(b, pos) else readU32(b, pos).toInt() + 4
            pos += ext.coerceAtLeast(0)
        }
        var synced: String? = null
        var plain: String? = null
        var custom: String? = null
        val idLen = if (major == 2) 3 else 4
        val headLen = if (major == 2) 6 else 10
        while (pos + headLen <= end) {
            val id = String(b, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = when (major) {
                2 -> ((b[pos + 3].toInt() and 0xFF) shl 16) or ((b[pos + 4].toInt() and 0xFF) shl 8) or (b[pos + 5].toInt() and 0xFF)
                4 -> syncsafe(b, pos + 4)
                else -> readU32(b, pos + 4).toInt()
            }
            val bodyStart = pos + headLen
            val bodyEnd = bodyStart + size
            if (size <= 0 || bodyEnd > end) break
            when (id) {
                "SYLT", "SLT" -> if (synced == null) synced = sylt(b, bodyStart, bodyEnd)
                "USLT", "ULT" -> if (plain == null) plain = uslt(b, bodyStart, bodyEnd)
                "TXXX", "TXX" -> if (custom == null) custom = txxxLyrics(b, bodyStart, bodyEnd)
            }
            pos = bodyEnd
        }
        return synced ?: plain ?: custom
    }

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    private fun charsetOf(encoding: Int) = when (encoding) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** Where a string in [encoding] that starts at [from] ends (exclusive), and where the next one starts. */
    private fun terminated(b: ByteArray, from: Int, end: Int, encoding: Int): Pair<Int, Int> {
        val wide = encoding == 1 || encoding == 2
        var i = from
        while (i < end) {
            if (!wide && b[i].toInt() == 0) return i to i + 1
            if (wide && i + 1 < end && b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i to i + 2
            i += if (wide) 2 else 1
        }
        return end to end
    }

    private fun uslt(b: ByteArray, start: Int, end: Int): String? {
        if (end - start < 5) return null
        val encoding = b[start].toInt()
        // encoding, three-letter language, then a content descriptor and the text.
        val (_, textStart) = terminated(b, start + 4, end, encoding)
        return String(b, textStart, end - textStart, charsetOf(encoding)).trimEnd('\u0000').takeIf { it.isNotBlank() }
    }

    private fun txxxLyrics(b: ByteArray, start: Int, end: Int): String? {
        if (end - start < 2) return null
        val encoding = b[start].toInt()
        val (descEnd, valueStart) = terminated(b, start + 1, end, encoding)
        val description = String(b, start + 1, descEnd - start - 1, charsetOf(encoding)).trim()
        // Taggers name this frame differently: LYRICS / UNSYNCEDLYRICS, and ffmpeg
        // writes its "lyrics" metadata as a TXXX described "USLT".
        val named = description.startsWith("LYRICS", ignoreCase = true) ||
            description.equals("UNSYNCEDLYRICS", ignoreCase = true) || description.equals("USLT", ignoreCase = true)
        if (!named) return null
        return String(b, valueStart, end - valueStart, charsetOf(encoding)).trimEnd('\u0000').takeIf { it.isNotBlank() }
    }

    /** A synced lyrics frame, in milliseconds, written out as LRC for the shared parser. */
    private fun sylt(b: ByteArray, start: Int, end: Int): String? {
        if (end - start < 7) return null
        val encoding = b[start].toInt()
        val stampFormat = b[start + 4].toInt()
        if (stampFormat != 2) return null // MPEG frames rather than milliseconds: not worth guessing a rate.
        val (_, firstEntry) = terminated(b, start + 6, end, encoding)
        val out = StringBuilder()
        var pos = firstEntry
        while (pos < end) {
            val (textEnd, stampAt) = terminated(b, pos, end, encoding)
            if (stampAt + 4 > end) break
            val text = String(b, pos, textEnd - pos, charsetOf(encoding)).replace("\n", " ").trim()
            val ms = readU32(b, stampAt)
            out.append('[').append("%02d:%02d.%02d".format(ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10)).append(']')
                .append(text).append('\n')
            pos = stampAt + 4
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    // ---- MP4 / M4A ----------------------------------------------------------

    /**
     * The `©lyr` atom's text, or this app's freeform one where it is present.
     *
     * Both live under `moov/udta/meta/ilst`, and the search is scoped to `moov`
     * rather than run over the file: a four-byte pattern turns up in audio data
     * often enough that scanning the whole thing would eventually read a frame
     * as a tag. Inside `moov` the same pattern is a tag or it is nothing.
     */
    private fun mp4(bytes: ByteArray): String? {
        val moov = topLevelBox(bytes, "moov") ?: return null
        // Exclusive, and deliberately so: the lyrics are the last item written
        // into `ilst`, so their value ends exactly on `moov`'s own end — an
        // inclusive bound here rejects the one atom this is looking for.
        val end = moov.last + 1
        return ilstText(bytes, moov.first, end, freeform = true)
            ?: ilstText(bytes, moov.first, end, freeform = false)
    }

    /**
     * The bounds of a top-level box, without walking into it.
     *
     * `moov` is not required to come before the audio — a file written without
     * the faststart pass puts it after `mdat` — so this steps box to box rather
     * than assuming a position.
     */
    private fun topLevelBox(bytes: ByteArray, type: String): IntRange? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val declared = readU32(bytes, pos)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (pos + 16 > bytes.size) return null
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (bytes.size - pos).toLong()
            }
            if (size < headerLen || size > Int.MAX_VALUE) return null
            val end = (pos + size).toInt().coerceAtMost(bytes.size)
            if (String(bytes, pos + 4, 4, Charsets.ISO_8859_1) == type) return pos until end
            pos += size.toInt()
        }
        return null
    }

    /**
     * The text of the lyrics item inside [within].
     *
     * [freeform] picks which of the two: this app's `----` item, whose name is
     * carried in a `name` box beside the value, or the standard `©lyr`, whose
     * four-byte type *is* the name. They are stored differently enough that one
     * search cannot find both.
     */
    private fun ilstText(bytes: ByteArray, from: Int, endExclusive: Int, freeform: Boolean): String? {
        val marker = if (freeform) WORD_LYRICS_FIELD.toByteArray(Charsets.UTF_8) else LYR_ATOM
        var at = from
        while (true) {
            val found = bytes.indexOf(marker, at, endExclusive) ?: return null
            // The value is the first `data` box after the name, in both layouts:
            // a freeform item is mean/name/data, a standard one is type/data.
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + marker.size
        }
    }

    /**
     * An iTunes `data` box's payload as text.
     *
     * The box is version/flags(4) + locale(4) + the value, and the length in
     * front of it is what says where the value stops — a lyric sheet has
     * newlines in it and nothing else terminates it.
     */
    private fun dataText(bytes: ByteArray, dataAt: Int, endExclusive: Int): String? {
        val start = dataAt - 4
        if (start < 0 || dataAt + 12 > endExclusive) return null
        val size = readU32(bytes, start).toInt()
        if (size <= 16 || start + size > endExclusive) return null
        // Type indicator 1 is UTF-8 text; a cover's 13/14 is the other thing a
        // `data` box holds, and decoding a JPEG as a string is not a lyric.
        if (readU32(bytes, dataAt + 4).toInt() != 1) return null
        return String(bytes, dataAt + 12, start + size - (dataAt + 12), Charsets.UTF_8)
    }

    // ---- FLAC ---------------------------------------------------------------

    /**
     * The `LYRICS` (or [WORD_LYRICS_FIELD]) comment out of the `VORBIS_COMMENT` block.
     *
     * Every length in the block is **little-endian** — it reuses Ogg Vorbis'
     * layout, which is the one part of FLAC that isn't big-endian.
     */
    private fun flac(bytes: ByteArray): String? {
        var pos = FLAC_MAGIC.size
        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) return null
            if (flags and 0x7F == FLAC_VORBIS_COMMENT) {
                return vorbisComment(bytes, start, start + length)
            }
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun vorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun u32(): Int? {
            if (pos + 4 > end) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendor = u32() ?: return null
        pos += vendor
        val count = u32() ?: return null
        var plain: String? = null
        repeat(count.coerceAtMost(4_096)) {
            val length = u32() ?: return plain
            if (length < 0 || pos + length > end) return plain
            val entry = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            // This app's own field wins outright; the standard one is held in
            // case it turns out to be the only one there.
            if (name == WORD_LYRICS_FIELD && value.isNotBlank()) return value
            if (name == "LYRICS" && plain == null && value.isNotBlank()) plain = value
        }
        return plain
    }

    // ---- Matroska / WebM ----------------------------------------------------

    /**
     * The `LYRICS` SimpleTag's string.
     *
     * Scanned for rather than walked down to: the tags a download writes are
     * appended after everything else (see `WebmTagger`), so reaching them
     * properly would mean parsing the whole Segment — every cluster of audio —
     * to arrive at the last few hundred bytes. The name is matched inside a
     * `TagName` element and the value read out of the `TagString` that follows,
     * so this is looking at tag structure rather than guessing at loose bytes.
     */
    private fun matroska(bytes: ByteArray): String? {
        var plain: String? = null
        for (name in listOf(WORD_LYRICS_FIELD, "LYRICS")) {
            val needle = name.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: break
                from = at + needle.size
                // The name element's own header sits immediately in front of it:
                // id(2) + a one-byte length for a name this short.
                if (at < 3 || bytes[at - 3] != ID_TAGNAME[0] || bytes[at - 2] != ID_TAGNAME[1]) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val string = bytes.indexOf(ID_TAGSTRING, from, bytes.size) ?: continue
                val size = readVint(bytes, string + 2) ?: continue
                val valueAt = string + 2 + size.width
                if (size.value <= 0 || valueAt + size.value > bytes.size) continue
                val value = String(bytes, valueAt, size.value.toInt(), Charsets.UTF_8)
                if (value.isBlank()) continue
                if (name == WORD_LYRICS_FIELD) return value
                if (plain == null) plain = value
            }
        }
        return plain
    }

    private class Vint(val value: Long, val width: Int)

    /**
     * An EBML variable-length integer: the highest set bit of the first byte
     * gives the width, and the bits after it are the value.
     */
    private fun readVint(bytes: ByteArray, offset: Int): Vint? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    // ---- Bytes --------------------------------------------------------------

    /** Reads up to [max] bytes, which is all of a normal file and a prefix of a huge one. */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 1 shl 16))
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    /** `ftyp` at offset 4 is what says "this is an MP4" — there is no leading magic. */
    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private const val FLAC_VORBIS_COMMENT = 4
    /** BitChord's own word-timed lyrics field, read so a file BitChord tagged keeps its word timing. */
    private const val WORD_LYRICS_FIELD = "BITCHORD_LYRICS"

    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val DATA_ATOM = "data".toByteArray(Charsets.ISO_8859_1)
    private val LYR_ATOM = byteArrayOf(0xA9.toByte()) + "lyr".toByteArray(Charsets.ISO_8859_1)
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
}
