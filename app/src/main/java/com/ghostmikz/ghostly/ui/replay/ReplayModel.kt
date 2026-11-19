/*
 * Ported from BitChord's ReplayModel (https://github.com/kushagrasinghx/BitChord, GPLv3).
 * Same wording and the same numbers; artwork is a local cover reference instead of a URL,
 * and BitChord's string resources are written out in English here.
 */
package com.ghostmikz.ghostly.ui.replay

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ghostmikz.ghostly.data.stats.ListeningStats
import com.ghostmikz.ghostly.data.stats.ReplayPeriod
import com.ghostmikz.ghostly.data.stats.ReplaySummary
import com.ghostmikz.ghostly.data.stats.artRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Replay's state: which stretch of listening is being shown, and the
 * numbers for it.
 *
 * Only alive while the page is: the summary is a merge of a few files and is
 * cheap to make. The *period* does survive, because it is a choice rather than
 * a result.
 */
class ReplayState(
    val period: ReplayPeriod,
    val summary: ReplaySummary?,
    val loading: Boolean,
    /**
     * `MM/YY` of the first month this device recorded anything, for the card.
     *
     * Deliberately all-time rather than the open period's own first month: a
     * card that says "member since" has to mean since you started, and reading
     * it off the period would have it announce a new membership every time the
     * chips were switched to This month.
     */
    val memberSince: String?,
)

/**
 * @param active whether the Replay is open in any of its forms — the Library
 *   cards, the page, the stories, the share sheet. The state is hoisted to the
 *   app so all of them read one set of numbers, and this is what stops that
 *   hoisting from costing a file merge for a page that is not on screen. It also
 *   means reopening Replay re-reads: whatever has been played since is on it.
 */
@Composable
fun rememberReplayState(active: Boolean): Pair<ReplayState, (ReplayPeriod) -> Unit> {
    var period by rememberSaveable { mutableStateOf(ReplayPeriod.THIS_YEAR) }
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var memberSince by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // A directory listing, so off the composition's thread.
        memberSince = withContext(Dispatchers.IO) {
            ListeningStats.months().firstOrNull()?.let {
                "%02d/%02d".format(Locale.ROOT, it.monthValue, it.year % 100)
            }
        }
    }
    LaunchedEffect(period, active) {
        if (!active) return@LaunchedEffect
        // Only the first read shows a spinner. Switching period must not blank
        // the charts for the beat it takes to merge the files — that reads as
        // the page breaking rather than as it answering a different question.
        loading = summary == null
        summary = ListeningStats.summary(period)
        loading = false
    }
    return ReplayState(period, summary, loading, memberSince) to
        { next: ReplayPeriod -> period = next }
}

/**
 * One run of a card's headline, and whether it is the emphasised part.
 *
 * The sentence lives here rather than in the story that draws it because it is
 * drawn twice — once on screen and once into the picture the share button
 * produces — and a card that says something different in the version people
 * send is worse than no picture at all.
 */
data class HeadlineRun(val text: String, val bold: Boolean)

private fun runs(vararg parts: Pair<String, Boolean>): List<HeadlineRun> =
    parts.map { HeadlineRun(it.first, it.second) }

/** The sentence at the top of [page]. */
fun ReplaySummary.storyHeadline(page: ReplayStoryPage): List<HeadlineRun> = when (page) {
    ReplayStoryPage.INTRO -> runs(
        "Your" to false,
        " " to false,
        "Replay" to true,
        " " to false,
        "is ready." to false,
    )
    ReplayStoryPage.MINUTES -> runs(
        "You spent" to false,
        " " to false,
        replayCount(formatMinutes(totalMs).replace(",", "").toIntOrNull() ?: 0, ReplayNoun.MINUTE, formatMinutes(totalMs)) to true,
        " " to false,
        "with music." to false,
    )
    ReplayStoryPage.SONGS -> runs(
        "In total," to false,
        " " to false,
        replayCount(totalPlays, ReplayNoun.SONG) to true,
        " " to false,
        (if (period == ReplayPeriod.THIS_WEEK) "filled your week." else "filled your month.") to false,
    )
    ReplayStoryPage.ARTISTS -> runs(
        (if (period == ReplayPeriod.THIS_WEEK) "This week," else "All month,") to false,
        " " to false,
        "One artist" to true,
        " " to false,
        "kept coming back." to false,
    )
    ReplayStoryPage.ALBUMS -> runs(
        "This time," to false,
        " " to false,
        "One album" to true,
        " " to false,
        "held your attention." to false,
    )
    ReplayStoryPage.GENRES -> runs(
        "Together," to false,
        " " to false,
        "Your genres" to true,
        " " to false,
        "shaped your sound." to false,
    )
    ReplayStoryPage.HABITS -> runs(
        "You explored" to false,
        " " to false,
        replayCount(distinctSongs, ReplayNoun.SONG) to true,
        " " to false,
        "from" to false,
        " " to false,
        replayCount(distinctArtists, ReplayNoun.ARTIST) to true,
        "." to false,
    )
    ReplayStoryPage.SUMMARY -> runs(
        "Your" to false,
        " " to false,
        if (period == ReplayPeriod.THIS_WEEK) "week" to true else label to true,
        " in music." to false,
    )
}

fun ReplayPeriod.localizedChip(): String = when (this) {
    ReplayPeriod.THIS_WEEK -> "This week"
    ReplayPeriod.THIS_MONTH -> "This month"
    ReplayPeriod.THIS_YEAR -> "This year"
    ReplayPeriod.ALL_TIME -> "All time"
}

/** The period picker's short name for each stretch — see `PeriodPicker`. */
fun ReplayPeriod.localizedTab(): String = when (this) {
    ReplayPeriod.THIS_WEEK -> "Weekly"
    ReplayPeriod.THIS_MONTH -> "Monthly"
    ReplayPeriod.THIS_YEAR -> "Yearly"
    ReplayPeriod.ALL_TIME -> "Lifetime"
}

fun ReplaySummary.localizedLabel(): String = when (period) {
    ReplayPeriod.THIS_WEEK -> "This week"
    ReplayPeriod.THIS_MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        .format(LocalDate.now())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    ReplayPeriod.THIS_YEAR -> LocalDate.now().year.toString()
    ReplayPeriod.ALL_TIME -> "All time"
}

/**
 * Which cover a card is washed in.
 *
 * The three cards that are *about* something take that thing's artwork. The rest
 * walk a pool of everything on the Replay, so a run of eight cards is lit by
 * eight different records rather than by the top song eight times.
 */
fun ReplaySummary.storyArtwork(page: ReplayStoryPage): String? {
    val pool = (
        songs.map { artRef(it.song) } +
            artists.map { it.artwork } +
            albums.map { it.artwork }
        ).filterNotNull().distinct()
    val pinned = when (page) {
        ReplayStoryPage.SONGS -> songs.firstOrNull()?.song?.let(::artRef)
        ReplayStoryPage.ARTISTS -> artists.firstOrNull()?.artwork
        ReplayStoryPage.ALBUMS -> albums.firstOrNull()?.artwork
        else -> null
    }
    if (pinned != null) return pinned
    if (pool.isEmpty()) return null
    return pool[page.ordinal % pool.size]
}

/**
 * How far a card's colours are turned around the colour wheel.
 *
 * The mesh is sampled from artwork, and a Replay is frequently four covers by
 * two artists with the same art direction — which is a run of eight cards in one
 * shade of blue. Rotating the hue per card is what makes the story *look* like a
 * story: every one arrives a different colour, and because only the hue moves,
 * the saturation and lightness are untouched, so no card comes out muddy or
 * blown out.
 *
 * The steps are irregular rather than an even eighth of the wheel: an even walk
 * reads as a colour-picker demo, and the gaps here keep neighbours far enough
 * apart to be obviously different without the run looking mechanical.
 */
fun storyHue(page: ReplayStoryPage): Float = when (page) {
    ReplayStoryPage.INTRO -> 0f
    ReplayStoryPage.MINUTES -> 40f
    ReplayStoryPage.SONGS -> 95f
    ReplayStoryPage.ARTISTS -> 145f
    ReplayStoryPage.ALBUMS -> 195f
    ReplayStoryPage.GENRES -> 240f
    ReplayStoryPage.HABITS -> 285f
    ReplayStoryPage.SUMMARY -> 325f
}

/** Which page of the story view something opens onto. */
enum class ReplayStoryPage {
    INTRO, MINUTES, ARTISTS, SONGS, ALBUMS, GENRES, HABITS, SUMMARY;

    companion object {
        val ordered: List<ReplayStoryPage> = entries
    }
}

// ── Formatting ──────────────────────────────────────────────────────────────

/** The nouns Replay counts, with their singular and plural (BitChord's plurals). */
enum class ReplayNoun(val one: String, val other: String) {
    MINUTE("minute", "minutes"),
    ARTIST("artist", "artists"),
    PLAY("play", "plays"),
    SONG("song", "songs"),
}

/** "1 play", "1,284 plays". [shown] is the figure as written, when it is already formatted. */
fun replayCount(count: Int, noun: ReplayNoun, shown: String = grouped(count.toLong())): String =
    "$shown ${if (count == 1) noun.one else noun.other}"

/**
 * Listening time, in the largest unit that still says something.
 *
 * Minutes up to an hour, then hours and minutes up to a day's worth: "1,284
 * minutes" is a number people read as a number, and "21h 24m" is one they read
 * as an amount. Past a day it is grouped minutes again.
 */
fun formatListening(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 1_440 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${grouped(minutes)} min"
    }
}

/** The headline figure on the minutes card: always minutes, always grouped. */
fun formatMinutes(ms: Long): String = grouped(ms / 60_000)

fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

/** "3 PM", "midnight" — an hour of the day said the way anyone would say it. */
fun formatHour(hour: Int): String = when (hour) {
    0 -> "midnight"
    12 -> "midday"
    in 1..11 -> "$hour AM"
    else -> "${hour - 12} PM"
}

/** `2026-08-14` as "14 August". */
fun formatDay(context: Context, iso: String): String = runCatching {
    LocalDate.parse(iso).format(
        DateTimeFormatter.ofPattern("d MMMM", context.resources.configuration.locales[0]),
    )
}.getOrDefault(iso)

// ── Rows and cards ──────────────────────────────────────────────────────────

/**
 * One line of one chart, with the four categories flattened onto a common
 * shape.
 *
 * Songs, artists, albums and genres are unlike enough in the data layer to be
 * kept apart there and alike enough on screen to be drawn once. [rank] is
 * carried on the row rather than derived from its index because the same row is
 * drawn in three places — the page, a story, the shared poster — and only one of
 * them has an index to hand.
 */
data class ReplayRow(
    val key: String,
    val rank: Int,
    val title: String,
    val subtitle: String?,
    /** A local cover reference, see [artRef]. */
    val artwork: String?,
    val ms: Long,
    val plays: Int,
)

fun ReplaySummary.songRows(limit: Int): List<ReplayRow> =
    songs.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.song.id.toString(),
            rank = index + 1,
            title = entry.song.title,
            subtitle = entry.song.artist.takeIf { it.isNotBlank() },
            artwork = artRef(entry.song),
            ms = entry.ms,
            plays = entry.plays,
        )
    }

fun ReplaySummary.artistRows(limit: Int): List<ReplayRow> =
    artists.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title,
            rank = index + 1,
            title = entry.title,
            subtitle = null,
            artwork = entry.artwork,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

fun ReplaySummary.albumRows(limit: Int): List<ReplayRow> =
    albums.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title + "|" + entry.subtitle.orEmpty(),
            rank = index + 1,
            title = entry.title,
            subtitle = entry.subtitle,
            artwork = entry.artwork,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

/**
 * Genres, with the artwork deliberately dropped.
 *
 * The cover of whichever track happened to lead the genre is not a picture of
 * the genre, and putting it there makes a row of five look like five albums
 * mislabelled. [InitialTile] stands in instead.
 */
fun ReplaySummary.genreRows(limit: Int): List<ReplayRow> =
    genres.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title,
            rank = index + 1,
            title = entry.title,
            subtitle = null,
            artwork = null,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

/** One of the Replay headline cards shown on Library and inside Replay. */
data class ReplayHeroCard(
    val label: String,
    val value: String,
    val detail: String?,
    val artwork: String?,
    val page: ReplayStoryPage,
)

/**
 * The four headline facts, in their Library navigation order: overview, songs,
 * artists and albums.
 *
 * Minutes leads because it is the one figure that needs no context to mean
 * something. A category with nothing in it is left out rather than shown empty:
 * a Replay of loose singles has no album chart, and a card reading "—" is worse
 * than three cards.
 */
fun ReplaySummary.cards(): List<ReplayHeroCard> = buildList {
    add(
        ReplayHeroCard(
            label = "Minutes listened",
            value = formatMinutes(totalMs),
            detail = "${replayCount(totalPlays, ReplayNoun.PLAY)} · " + localizedLabel(),
            artwork = songs.firstOrNull()?.song?.let(::artRef),
            page = ReplayStoryPage.MINUTES,
        ),
    )
    songs.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = "Top song",
                value = it.song.title,
                detail = "${it.song.artist} · ${replayCount(it.plays, ReplayNoun.PLAY)}",
                artwork = artRef(it.song),
                page = ReplayStoryPage.SONGS,
            ),
        )
    }
    artists.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = "Top artist",
                value = it.title,
                detail = "${formatListening(it.ms)} · " + replayCount(it.plays, ReplayNoun.PLAY),
                artwork = it.artwork,
                page = ReplayStoryPage.ARTISTS,
            ),
        )
    }
    albums.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = "Top album",
                value = it.title,
                detail = listOfNotNull(it.subtitle, formatListening(it.ms)).joinToString(" · "),
                artwork = it.artwork,
                page = ReplayStoryPage.ALBUMS,
            ),
        )
    }
}

/** Whose card it is: the Last.fm account when signed in, as BitChord uses the signed-in account. */
fun replayHolder(lastfmUser: String): String = lastfmUser.ifBlank { DEFAULT_REPLAY_HOLDER }

/** Whose card it is when there is no signed-in account to name. */
const val DEFAULT_REPLAY_HOLDER = "Ghostly listener"
