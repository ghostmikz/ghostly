package com.ghostmikz.ghostly.data

import java.util.Locale

/**
 * Finds songs stored more than once: the same title and main artist, with
 * download noise like "(Official Video)" or "feat. X" left out, and lengths
 * within a few seconds of each other (so a remix or live take stays apart).
 */
object Duplicates {
    private const val LENGTH_SLACK_MS = 3_000L

    private val NOISE = Regex(
        """[(\[]\s*(official|music video|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|mv|m/v|explicit|clean)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("""[(\[]?\s*\b(feat\.?|ft\.?|featuring)\s[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(,|&|;|/|\sx\s|\sand\s|\bfeat\.?\s|\bft\.?\s|\bfeaturing\s)\s*""", RegexOption.IGNORE_CASE)
    private val CHANNEL = Regex("""(\s-\s*topic|vevo|\sofficial)$""", RegexOption.IGNORE_CASE)
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    private fun words(s: String) = s.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim()

    private fun mainArtist(artist: String): String =
        if (artist == "Unknown artist") "" else words(artist.split(ARTIST_SPLIT).first().replace(CHANNEL, ""))

    private fun key(song: Song): String {
        var artist = mainArtist(song.artist)
        var title = song.title.replace(NOISE, "").replace(FEAT, "")
        // A video's title is often "Artist - Song", with the channel as the artist.
        val dash = title.indexOf(" - ")
        if (dash > 0) {
            val left = mainArtist(title.substring(0, dash))
            val near = left.length >= 3 && artist.length >= 3 && (artist.startsWith(left) || left.startsWith(artist))
            if (artist.isEmpty() || left == artist || near) {
                artist = left
                title = title.substring(dash + 3)
            }
        }
        return artist + "\u0000" + words(title)
    }

    /** Groups of two or more copies, the best-sounding copy first in each, largest groups first. */
    fun find(songs: List<Song>): List<List<Song>> =
        songs.groupBy(::key)
            .filterKeys { !it.endsWith("\u0000") }
            .values.flatMap { sameName ->
                if (sameName.size < 2) return@flatMap emptyList()
                val runs = ArrayList<MutableList<Song>>()
                sameName.sortedBy { it.durationMs }.forEach { s ->
                    val run = runs.lastOrNull()
                    if (run != null && s.durationMs - run.last().durationMs <= LENGTH_SLACK_MS) run += s
                    else runs += mutableListOf(s)
                }
                runs.filter { it.size >= 2 }
            }
            .sortedWith(compareByDescending<List<Song>> { it.size }.thenBy { it.first().title.lowercase(Locale.ROOT) })

    /** The copy to keep: a lossless file first, then the highest bitrate, then the biggest file. */
    fun best(copies: List<Song>, info: Map<Long, MusicRepository.FileInfo>): Song =
        copies.maxWith(
            compareBy<Song> { info[it.id]?.format in LOSSLESS }
                .thenBy { info[it.id]?.kbps ?: 0 }
                .thenBy { info[it.id]?.bytes ?: 0L },
        )

    private val LOSSLESS = setOf("FLAC", "WAV", "ALAC", "AIFF")
}
