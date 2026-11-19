/*
 * Ported from BitChord's ListeningStats (https://github.com/kushagrasinghx/BitChord, GPLv3).
 * Ghostly keys tracks by their MediaStore id instead of a YouTube video id, keeps a
 * reference to the local cover ("songId:albumId") instead of an artwork URL, and takes
 * genres from the files' own tags instead of BitChord's online ArtistFacts lookups.
 */
package com.ghostmikz.ghostly.data.stats

import android.content.Context
import android.util.Log
import com.ghostmikz.ghostly.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * What this device has listened to, kept on this device.
 *
 * ## Aggregates, not an event log
 *
 * The obvious shape for listening history is a row per play, summed up when
 * something asks. It is also the shape that grows without bound on a phone
 * nobody is going to garbage-collect: a year of ordinary listening is tens of
 * thousands of rows, and every one of them is carried around so that a page
 * almost nobody opens can add them up once.
 *
 * So the addition happens on the way in. A track's total is a counter that goes
 * up while it plays, and reading the Replay is a merge of a handful of already
 * finished sums rather than a pass over history. The cost of that is what any
 * aggregate costs: the raw plays are gone, so a question nobody thought of in
 * advance cannot be answered later. Every question Replay asks is here.
 *
 * ## One file per month
 *
 * Buckets are calendar months in the device's own time zone, one JSON file
 * each. That is what makes "this year" and "all time" the same operation on
 * different numbers of files, and it means the only bucket held in memory is
 * the one being written to — the rest are read, merged and released.
 *
 * ## Nothing leaves the device
 *
 * There is no upload, no account and no id. Settings > Your data > Export can
 * write the whole thing out as JSON because the user asked it to, and that is
 * the only way any of this goes anywhere.
 */
object ListeningStats {

    private lateinit var directory: File

    /** For the genre lookups in [ListeningRecorder], which ask MediaStore. */
    internal lateinit var appContext: Context
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The month being written to, held open between samples.
     *
     * Guarded by its own lock rather than made thread-safe piecewise: a sample
     * touches four maps and two arrays and has to leave them agreeing with each
     * other, and the writer is one coroutine ticking every few seconds, so
     * there is nothing here for finer locking to win.
     */
    private var open: OpenBucket? = null

    private val lock = Any()

    /** Set when [open] has changes not yet on disk. */
    private var dirty = false

    /**
     * Where every write to this folder happens.
     *
     * Its own scope rather than the caller's, because the caller is the
     * playback service and its scope is the main thread. [writeLock] serialises
     * them so a flush and a month rollover cannot be halfway into the same file
     * at once.
     */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /**
     * Bumped by every completed write and every import, so [summary] can tell
     * whether the answer it already has is still the answer.
     */
    @Volatile
    private var version = 0L

    /** The last summary handed out, and what it was computed from. */
    private var cached: Cached? = null

    private class Cached(
        val period: ReplayPeriod,
        val version: Long,
        val day: LocalDate,
        val summary: ReplaySummary,
    )

    /** Called by both the activity and the playback service; only the first call does anything. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        directory = File(context.filesDir, DIRECTORY)
        // Opening a month means reading and parsing it, and the first thing to
        // ask for one is the playback sampler — on the main thread, a few
        // seconds into the first track. Done here instead, it has happened long
        // before anything is playing.
        writer.launch { synchronized(lock) { bucketFor(YearMonth.now()) } }
    }

    private val ready: Boolean get() = this::directory.isInitialized

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Adds [playedMs] of [song] to the current month.
     *
     * [countsAsPlay] separates the two things a listener means by "played". The
     * minutes are what actually came out of the speaker and are added on every
     * sample; a *play* is a whole listen and is counted once, by whoever is
     * watching the track rather than here — see [ListeningRecorder], which holds
     * the same threshold the scrobbler uses.
     *
     * [genres] are the file's own genre tags, when the recorder has read them.
     */
    fun record(song: Song, genres: List<String>, playedMs: Long, countsAsPlay: Boolean) {
        if (!ready) return
        if (playedMs <= 0 && !countsAsPlay) return
        if (song.id < 0) return
        val now = System.currentTimeMillis()
        val at = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val dayKey = "${at.year}-${"%02d".format(at.monthValue)}-${"%02d".format(at.dayOfMonth)}"
        val art = artRef(song)
        val albumName = song.album.takeIf { it.isNotBlank() && it != UNKNOWN_ALBUM }
        val albumId = song.albumId.takeIf { it >= 0 }?.toString()
        synchronized(lock) {
            val bucket = bucketFor(YearMonth.from(at))
            val track = bucket.tracks.getOrPut(song.id.toString()) {
                TrackEntry(
                    id = song.id.toString(),
                    title = song.title,
                    artist = song.artist,
                    album = albumName,
                    albumId = albumId,
                    art = art,
                )
            }
            track.ms += playedMs
            track.last = now
            // Filled in as it becomes known: the genre is read off the file in
            // the background, and lands while the track is already playing.
            if (track.album == null) track.album = albumName
            if (track.albumId == null) track.albumId = albumId
            if (track.art == null) track.art = art
            if (track.genres.isEmpty() && genres.isNotEmpty()) track.genres = genres
            if (countsAsPlay) track.plays++

            // Day-level sub-bucket for the track.
            val trackDay = bucket.trackDays.computeIfAbsent(dayKey) { DayStats() }
            trackDay.ms += playedMs
            if (countsAsPlay) trackDay.plays++

            // Also populate per-entity days map so weekRange() can read it.
            track.days.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                d.ms += playedMs
                if (countsAsPlay) d.plays++
            }

            // The lead artist, not the credit as a string. A track billed
            // "Cheema Y & Gur Sidhu" is not a third artist who happens to share
            // both their names, and filing it as one is how a chart lists the
            // same person twice — once solo and again in each collaboration —
            // with their listening split between the rows.
            song.artist.takeIf { it != UNKNOWN_ARTIST }?.let(::primaryArtist)?.let { name ->
                val artist = bucket.artists.getOrPut(name.lowercase(Locale.ROOT)) {
                    NameEntry(name = name, art = art)
                }
                artist.ms += playedMs
                if (countsAsPlay) artist.plays++

                // Day-level sub-bucket for the artist.
                val artistDay = bucket.artistDays.computeIfAbsent(dayKey) { DayStats() }
                artistDay.ms += playedMs
                if (countsAsPlay) artistDay.plays++

                // Also populate per-entity days map so weekRange() can read it.
                artist.days.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                    d.ms += playedMs
                    if (countsAsPlay) d.plays++
                }
            }

            albumName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                // Keyed on the MediaStore album id (stable across artist-credit
                // variations), falling back to name+artist when there is none.
                val key = albumKey(albumId, name, song.artist)
                val album = bucket.albums.getOrPut(key) {
                    NameEntry(name = name, sub = song.artist, art = art)
                }
                album.ms += playedMs
                if (countsAsPlay) album.plays++
                if (album.id == null) album.id = albumId

                // Day-level sub-bucket for the album.
                val albumDay = bucket.albumDays.computeIfAbsent(dayKey) { DayStats() }
                albumDay.ms += playedMs
                if (countsAsPlay) albumDay.plays++

                // Also populate per-entity days map so weekRange() can read it.
                album.days.computeIfAbsent(dayKey) { DayStats() }.also { d ->
                    d.ms += playedMs
                    if (countsAsPlay) d.plays++
                }
            }

            bucket.hours[at.hour] += playedMs
            val day = at.dayOfMonth
            bucket.days[day] = (bucket.days[day] ?: 0L) + playedMs
            dirty = true
        }
    }

    /**
     * Writes the open month out, if anything has changed.
     *
     * Called on a cadence rather than on every sample: the numbers are worth
     * losing a few seconds of, and a music player has better uses for its disk
     * than re-serialising a month every time a track ticks over.
     */
    fun flush() {
        pending()?.let { (key, snapshot) -> writer.launch { write(key, snapshot) } }
    }

    /** As [flush], for a caller that is about to read back what it wrote. */
    private suspend fun flushAndAwait() {
        pending()?.let { (key, snapshot) -> writer.launch { write(key, snapshot) }.join() }
    }

    /** The open month as bytes-to-be, or null when nothing has changed. */
    private fun pending(): Pair<String, StoredBucket>? {
        if (!ready) return null
        return synchronized(lock) {
            if (!dirty) return null
            val bucket = open ?: return null
            dirty = false
            bucket.key to bucket.snapshot()
        }
    }

    /**
     * Serialises a month and swaps it into place.
     *
     * Never on the caller's thread: the caller is the playback service's
     * sampler, which runs on the main thread.
     *
     * Written aside and renamed, so a kill mid-write leaves the previous
     * month rather than a truncated one.
     */
    private suspend fun write(key: String, snapshot: StoredBucket) = writeLock.withLock {
        runCatching {
            directory.mkdirs()
            val file = File(directory, "$key.json")
            val temporary = File(directory, "$key.json.tmp")
            temporary.writeText(json.encodeToString(StoredBucket.serializer(), snapshot))
            if (!temporary.renameTo(file)) {
                // renameTo will not replace on some filesystems; the delete is
                // the only thing standing between that and a .tmp per flush.
                if (file.delete() && temporary.renameTo(file)) Unit else temporary.delete()
            }
            version++
        }.onFailure { Log.w(TAG, "Could not write listening bucket $key", it) }
    }

    /** The bucket for [month], loading or creating it and retiring the last one. */
    private fun bucketFor(month: YearMonth): OpenBucket {
        val key = month.toString()
        open?.takeIf { it.key == key }?.let { return it }
        // The month rolled over mid-session. Whatever the last one still held
        // has to reach disk before it is dropped on the floor.
        open?.let { previous ->
            val stale = previous.snapshot()
            writer.launch { write(previous.key, stale) }
        }
        val loaded = read(key) ?: StoredBucket(month = key)
        return OpenBucket.of(key, loaded).also {
            open = it
            prune()
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * The Replay for [period], merged off disk.
     *
     * Off the main thread and off the open bucket both: the months it needs are
     * files, and the one being written to is flushed first so that the page
     * agrees with what has just been playing.
     */
    suspend fun summary(period: ReplayPeriod): ReplaySummary = withContext(Dispatchers.IO) {
        flushAndAwait()
        val today = LocalDate.now()
        // Every input to the merge, so the cache cannot be stale: which months
        // are on disk and what is in them ([version]), which period was asked
        // for, and — because "this month" and "this year" are relative — what
        // day it is. Opening the Library tab asks for this, so it is asked
        // often and usually for an answer that has not changed.
        cached?.takeIf {
            it.period == period && it.version == version && it.day == today
        }?.let { return@withContext it.summary }

        val merged = MergedBucket()
        when {
            period.needsDayFilter() -> {
                // Week may span two months: read both, filter by day.
                val range = period.range(today)!!
                months().filter { month ->
                    val start = month.atDay(1)
                    val end = month.atEndOfMonth()
                    !(end < range.first || start > range.second)
                }.forEach { month -> read(month.toString())?.let { bucket -> merged.add(bucket, range) } }
            }
            else -> {
                months().filter { period.covers(it, today) }
                    .forEach { month -> read(month.toString())?.let(merged::add) }
            }
        }
        merged.toSummary(period, today).also {
            cached = Cached(period, version, today, it)
        }
    }

    /** Every month with a file, oldest first. */
    fun months(): List<YearMonth> {
        if (!ready) return emptyList()
        val files = directory.listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            file.name.removeSuffix(".json").takeIf { it != file.name }
                ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        }.sorted()
    }

    private fun read(key: String): StoredBucket? {
        val file = File(directory, "$key.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(StoredBucket.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "Discarding unreadable listening bucket $key", it) }
            .getOrNull()
    }

    // ── Housekeeping ────────────────────────────────────────────────────────

    /**
     * Keeps the folder to [KEEP_MONTHS] and each bucket to its entry caps.
     *
     * Both are the same bet: what survives is what was actually listened to, so
     * eviction is by time played rather than by recency. A track heard once in
     * passing is the first thing out of a full bucket, and it was never going to
     * appear on any page this data exists to draw.
     */
    private fun prune() {
        val existing = months()
        if (existing.size > KEEP_MONTHS) {
            existing.take(existing.size - KEEP_MONTHS).forEach {
                File(directory, "$it.json").delete()
            }
        }
        val bucket = open ?: return
        bucket.tracks.trimTo(MAX_TRACKS) { it.ms }
        bucket.artists.trimTo(MAX_NAMES) { it.ms }
        bucket.albums.trimTo(MAX_NAMES) { it.ms }
    }

    /**
     * [this] keyed by [key], with anything that lands on the same key added
     * together rather than overwriting.
     *
     * Needed because a key is *recomputed* on load rather than read back from
     * the file — see [NameEntry.key]. Two entries written under different keys
     * by an older build can therefore arrive at the same key now, and the
     * obvious `associateByTo` would silently drop one of their totals.
     */
    private inline fun List<NameEntry>.mergedBy(
        into: LinkedHashMap<String, NameEntry>,
        key: (NameEntry) -> String,
    ): LinkedHashMap<String, NameEntry> {
        forEach { entry ->
            val k = key(entry)
            val existing = into[k]
            if (existing == null) into[k] = entry else existing.absorb(entry)
        }
        return into
    }

    private inline fun <K, V> MutableMap<K, V>.trimTo(limit: Int, crossinline weight: (V) -> Long) {
        if (size <= limit) return
        entries.sortedBy { weight(it.value) }
            .take(size - limit)
            .forEach { remove(it.key) }
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    /** Every bucket, for Settings > Your data > Export. */
    suspend fun exportAll(): List<StoredBucket> {
        if (!ready) return emptyList()
        flushAndAwait()
        return withContext(Dispatchers.IO) { months().mapNotNull { read(it.toString()) } }
    }

    /** [exportAll] as a JSON array, for the export file. */
    suspend fun exportJson(): String =
        json.encodeToString(ListSerializer(StoredBucket.serializer()), exportAll())

    /** The other half of [exportJson]. */
    fun importJson(text: String) {
        importAll(json.decodeFromString(ListSerializer(StoredBucket.serializer()), text))
    }

    /**
     * Replaces everything held with [buckets].
     *
     * A replace rather than a merge, and deliberately so: an import is a restore
     * onto a new device, and two copies of the same month added together would
     * silently double every number on the page with no way to tell afterwards.
     */
    fun importAll(buckets: List<StoredBucket>) {
        if (!ready) return
        synchronized(lock) {
            open = null
            dirty = false
            directory.listFiles()?.forEach { it.delete() }
            directory.mkdirs()
            version++
            cached = null
            buckets.forEach { bucket ->
                val key = runCatching { YearMonth.parse(bucket.month).toString() }.getOrNull()
                    ?: return@forEach
                runCatching {
                    File(directory, "$key.json")
                        .writeText(json.encodeToString(StoredBucket.serializer(), bucket))
                }.onFailure { Log.w(TAG, "Could not import bucket $key", it) }
            }
        }
    }

    // ── Shapes ──────────────────────────────────────────────────────────────

    /**
     * The open month, in mutable form.
     *
     * Apart from [StoredBucket] because the stored shape is a schema and this
     * one is a working set: a counter that goes up thousands of times a session
     * wants to be a `var` in a map, and a file format wants to be immutable and
     * boring.
     */
    private class OpenBucket(
        val key: String,
        val tracks: MutableMap<String, TrackEntry>,
        val artists: MutableMap<String, NameEntry>,
        val albums: MutableMap<String, NameEntry>,
        val hours: LongArray,
        val days: MutableMap<Int, Long>,
        /** Per-day sub-buckets for track/artist/album. Key: "YYYY-MM-DD". */
        val trackDays: MutableMap<String, DayStats> = mutableMapOf(),
        val artistDays: MutableMap<String, DayStats> = mutableMapOf(),
        val albumDays: MutableMap<String, DayStats> = mutableMapOf(),
    ) {
        fun snapshot() = StoredBucket(
            month = key,
            tracks = tracks.values.map { it.copy(days = it.days.deepCopy()) },
            artists = artists.map { (key, entry) -> entry.copy(key = key, days = entry.days.deepCopy()) },
            albums = albums.map { (key, entry) -> entry.copy(key = key, days = entry.days.deepCopy()) },
            hours = hours.toList(),
            days = days.toMap(),
            trackDays = trackDays.deepCopy(),
            artistDays = artistDays.deepCopy(),
            albumDays = albumDays.deepCopy(),
        )

        companion object {
            fun of(key: String, stored: StoredBucket) = OpenBucket(
                key = key,
                tracks = stored.tracks.associateByTo(LinkedHashMap()) { it.id },
                // Re-keyed on the lead artist, so entries a previous build
                // filed under a whole credit fold into the person on read
                // instead of sitting beside them forever.
                artists = stored.artists
                    .map { it.copy(name = primaryArtist(it.name) ?: it.name) }
                    .mergedBy(LinkedHashMap()) { it.name.lowercase(Locale.ROOT) },
                albums = stored.albums.mergedBy(LinkedHashMap()) { albumKey(it.id, it.name, it.sub.orEmpty()) },
                hours = LongArray(24) { stored.hours.getOrElse(it) { 0L } },
                days = stored.days.toMutableMap(),
                trackDays = stored.trackDays?.toMutableMap() ?: mutableMapOf(),
                artistDays = stored.artistDays?.toMutableMap() ?: mutableMapOf(),
                albumDays = stored.albumDays?.toMutableMap() ?: mutableMapOf(),
            )
        }
    }

    /** Several months added together, on the way to a [ReplaySummary]. */
    private class MergedBucket {
        val tracks = HashMap<String, TrackEntry>()
        val artists = HashMap<String, NameEntry>()
        val albums = HashMap<String, NameEntry>()
        val hours = LongArray(24)
        val days = HashMap<String, Long>()
        /** Per-day sub-buckets for tracks. Key: "YYYY-MM-DD". */
        val trackDays = HashMap<String, DayStats>()
        /** Per-day sub-buckets for artists. Key: "YYYY-MM-DD". */
        val artistDays = HashMap<String, DayStats>()
        /** Per-day sub-buckets for albums. Key: "YYYY-MM-DD". */
        val albumDays = HashMap<String, DayStats>()
        var earliest: String? = null

        private fun addEntries(bucket: StoredBucket) {
            bucket.tracks.forEach { entry ->
                tracks.merge(entry.id, entry.copy(days = entry.days.deepCopy())) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.artists.forEach { entry ->
                val lead = entry.copy(name = primaryArtist(entry.name) ?: entry.name, days = entry.days.deepCopy())
                artists.merge(lead.name.lowercase(Locale.ROOT), lead) { a, b -> a.also { it.absorb(b) } }
            }
            bucket.albums.forEach { entry ->
                val key = albumKey(entry.id, entry.name, entry.sub.orEmpty())
                albums.merge(key, entry.copy(days = entry.days.deepCopy())) { a, b -> a.also { it.absorb(b) } }
            }
        }

        fun add(bucket: StoredBucket) {
            addEntries(bucket)
            repeat(24) { hours[it] += bucket.hours.getOrElse(it) { 0L } }
            bucket.days.forEach { (day, ms) ->
                val date = "${bucket.month}-%02d".format(day)
                days[date] = (days[date] ?: 0L) + ms
            }
            if (earliest == null || bucket.month < earliest!!) earliest = bucket.month
        }

        /** Add all day-level sub-buckets for weekly queries. */
        fun addDaySubs(bucket: StoredBucket) {
            bucket.trackDays?.forEach { (dateStr, stats) ->
                trackDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
            }
            bucket.artistDays?.forEach { (dateStr, stats) ->
                artistDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
            }
            bucket.albumDays?.forEach { (dateStr, stats) ->
                albumDays.merge(dateStr, stats) { a, b -> DayStats(a.ms + b.ms, a.plays + b.plays) }
            }
        }

        /** Add only the days that fall within [range], keyed as "YYYY-MM-DD". */
        fun add(bucket: StoredBucket, range: Pair<LocalDate, LocalDate>) {
            // Merge entity-level data (tracks/artists/albums) so weekRange() can read them.
            addEntries(bucket)
            // Merge day-level sub-buckets for the flat days map and cross-month totals.
            addDaySubs(bucket)
            bucket.days.forEach { (day, ms) ->
                val candidate = "${bucket.month}-%02d".format(day)
                val date = runCatching { LocalDate.parse(candidate) }.getOrNull() ?: return@forEach
                if (date >= range.first && date <= range.second) {
                    days[candidate] = (days[candidate] ?: 0L) + ms
                }
            }
        }

        fun toSummary(period: ReplayPeriod, today: LocalDate): ReplaySummary {
            // For weekly mode, compute per-entity totals from day-level sub-buckets.
            val weekRange = if (period.needsDayFilter()) period.range(today) else null

            val rankedTracks: List<Triple<TrackEntry, Long, Int>> = if (weekRange != null) {
                tracks.values.mapNotNull { entry ->
                    val (ms, plays) = entry.weekRange(weekRange)
                    if (ms > 0) Triple(entry, ms, plays) else null
                }
            } else {
                tracks.values.map { Triple(it, it.ms, it.plays) }
            }.sortedWith(compareByDescending<Triple<TrackEntry, Long, Int>> { it.second }.thenByDescending { it.third })

            val rankedSongs = rankedTracks.map { (entry, ms, plays) ->
                RankedSong(song = entry.toSong(), ms = ms, plays = plays)
            }

            val rankedArtists = if (weekRange != null) {
                artists.values
                    .mapNotNull { entry ->
                        val (ms, plays) = entry.weekRange(weekRange)
                        if (ms > 0) entry to (ms to plays) else null
                    }
                    .sortedWith(compareByDescending<Pair<NameEntry, Pair<Long, Int>>> { it.second.first }
                        .thenByDescending { it.second.second })
                    .map { (entry, msPlays) ->
                        val (ms, plays) = msPlays
                        RankedEntry(entry.name, entry.sub, entry.art, entry.id, ms, plays)
                    }
            } else {
                artists.values
                    .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                    .map { RankedEntry(it.name, it.sub, it.art, it.id, it.ms, it.plays) }
            }

            val rankedAlbums = if (weekRange != null) {
                albums.values
                    .mapNotNull { entry ->
                        val (ms, plays) = entry.weekRange(weekRange)
                        if (ms > 0) entry to (ms to plays) else null
                    }
                    .sortedWith(compareByDescending<Pair<NameEntry, Pair<Long, Int>>> { it.second.first }
                        .thenByDescending { it.second.second })
                    .map { (entry, msPlays) ->
                        val (ms, plays) = msPlays
                        RankedEntry(entry.name, entry.sub, entry.art, entry.id, ms, plays)
                    }
            } else {
                albums.values
                    .sortedWith(compareByDescending<NameEntry> { it.ms }.thenByDescending { it.plays })
                    .map { RankedEntry(it.name, it.sub, it.art, it.id, it.ms, it.plays) }
            }

            // Genres are derived rather than stored per bucket: each track votes
            // for the genres its own file is tagged with. A file with no genre
            // tag simply doesn't vote, which is why this can be an empty list on
            // a page whose other charts are full.
            val genreName = LinkedHashMap<String, String>()
            val genreMs = LinkedHashMap<String, Long>()
            val genrePlays = LinkedHashMap<String, Int>()
            val genreArt = LinkedHashMap<String, String?>()
            rankedTracks.forEach { (entry, ms, plays) ->
                entry.genres.forEach { genre ->
                    val key = genre.lowercase(Locale.ROOT)
                    genreName.putIfAbsent(key, genre)
                    genreMs[key] = (genreMs[key] ?: 0L) + ms
                    genrePlays[key] = (genrePlays[key] ?: 0) + plays
                    if (genreArt[key] == null) genreArt[key] = entry.art
                }
            }

            val rankedGenres = genreMs.entries
                .sortedByDescending { it.value }
                .map {
                    RankedEntry(
                        title = genreName[it.key] ?: it.key,
                        subtitle = null,
                        artwork = genreArt[it.key],
                        browseId = null,
                        ms = it.value,
                        plays = genrePlays[it.key] ?: 0,
                    )
                }

            val busiest = days.entries.maxByOrNull { it.value }
            return ReplaySummary(
                period = period,
                label = period.label(today),
                totalMs = rankedSongs.sumOf { it.ms },
                totalPlays = rankedSongs.sumOf { it.plays },
                songs = rankedSongs,
                artists = rankedArtists,
                albums = rankedAlbums,
                genres = rankedGenres,
                hourOfDay = hours.toList(),
                busiestDay = busiest?.key,
                busiestDayMs = busiest?.value ?: 0L,
                distinctSongs = rankedSongs.size,
                distinctArtists = rankedArtists.size,
                distinctAlbums = rankedAlbums.size,
                since = earliest,
            )
        }
    }

    /**
     * How an album is filed: uses the album id as the primary key when available
     * (stable across different artist-credit strings), falling back to
     * name+artist when there is none.
     *
     * Joined on a unit separator rather than a space, because that is the one
     * character neither half can contain: with a space, "Greatest Hits" by
     * "Vol 2" and "Greatest Hits Vol" by "2" are the same key.
     */
    private fun albumKey(albumId: String?, name: String, artist: String): String =
        albumId ?: (name.lowercase(Locale.ROOT) + ALBUM_KEY_SEPARATOR + artist.lowercase(Locale.ROOT))

    /** ASCII 0x1F, which exists for exactly this and cannot occur in a title. */
    private val ALBUM_KEY_SEPARATOR = Char(31).toString()

    /**
     * The people named on a track's credit, reduced to the first of them.
     *
     * Split conservatively, and on punctuation rather than on words: `,`, `&`,
     * `x` and the feature markers are how every catalogue joins two artists,
     * while " and " and " with " are as often part of a band's actual name —
     * "Florence and the Machine", "Nick Cave and the Bad Seeds" — so they are
     * left alone.
     */
    fun primaryArtist(credit: String): String? {
        lastCredit?.let { (raw, name) -> if (raw == credit) return name }
        val name = credit.split(CREDIT_SEPARATORS)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Memoised on the last credit seen, because the caller is the playback
        // sampler: it asks about the same string a dozen times a minute for as
        // long as a track is playing.
        lastCredit = credit to name
        return name
    }

    @Volatile
    private var lastCredit: Pair<String, String?>? = null

    private val CREDIT_SEPARATORS = Regex(
        """\s*,\s*|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+""",
        RegexOption.IGNORE_CASE,
    )

    /** What MusicRepository files a song under when the tag is missing. */
    private const val UNKNOWN_ARTIST = "Unknown artist"
    private const val UNKNOWN_ALBUM = "Unknown album"

    private const val TAG = "GhostlyListening"
    private const val DIRECTORY = "listening"

    /** Three years, which is longer than "all time" means to most people. */
    private const val KEEP_MONTHS = 36

    /**
     * Per-month caps. Generous enough that nobody reaches them by listening,
     * tight enough that a bucket stays well under a hundred kilobytes.
     */
    private const val MAX_TRACKS = 600
    private const val MAX_NAMES = 400
}

/**
 * The local cover a row is drawn with, as "songId:albumId" — what Ghostly's art
 * cache needs to find a cover (the file for its embedded art, the album for the
 * folder art). Stands where BitChord keeps an artwork URL.
 */
fun artRef(song: Song): String = "${song.id}:${song.albumId}"

/** The [Song] (only id and album id filled in) that [artRef] points at, for the art cache. */
fun artSong(ref: String?): Song? {
    ref ?: return null
    val id = ref.substringBefore(':').toLongOrNull() ?: return null
    val albumId = ref.substringAfter(':', "").toLongOrNull() ?: -1L
    return Song(id, "", "", "", albumId, 0, 0, 0, 0, "")
}

private fun MutableMap<String, DayStats>.deepCopy(): MutableMap<String, DayStats> =
    mapValuesTo(LinkedHashMap()) { it.value.copy() }

/** Daily-level listening for a single track or artist. */
@Serializable
data class DayStats(
    var ms: Long = 0L,
    var plays: Int = 0,
)

/** One track's totals inside a bucket. */
@Serializable
data class TrackEntry(
    /** The MediaStore id of the file. */
    val id: String,
    val title: String = "",
    val artist: String = "",
    var album: String? = null,
    var albumId: String? = null,
    var art: String? = null,
    /** The file's own genre tags, at most two. */
    var genres: List<String> = emptyList(),
    var ms: Long = 0L,
    var plays: Int = 0,
    var last: Long = 0L,
    /** Per-day breakdown for weekly queries. Key: "YYYY-MM-DD". */
    var days: MutableMap<String, DayStats> = mutableMapOf(),
) {
    fun absorb(other: TrackEntry) {
        ms += other.ms
        plays += other.plays
        last = maxOf(last, other.last)
        if (album == null) album = other.album
        if (albumId == null) albumId = other.albumId
        if (art == null) art = other.art
        if (genres.isEmpty()) genres = other.genres
        // Merge per-day sub-buckets.
        other.days.forEach { (dateKey, stats) ->
            days.computeIfAbsent(dateKey) { DayStats() }.also { existing ->
                existing.ms += stats.ms
                existing.plays += stats.plays
            }
        }
    }

    /** Sum ms/plays for days that fall within [range]. Falls back to full totals if .days is empty. */
    fun weekRange(range: Pair<LocalDate, LocalDate>): Pair<Long, Int> = days.weekRange(range) ?: (ms to plays)

    /** Enough of a [Song] to draw and find the track again. */
    fun toSong(): Song = Song(
        id = id.toLongOrNull() ?: -1L,
        title = title,
        artist = artist,
        album = album ?: "Unknown album",
        albumId = albumId?.toLongOrNull() ?: -1L,
        durationMs = 0,
        track = 0,
        year = 0,
        dateAdded = 0,
        folder = "",
    )
}

/**
 * One artist's or album's totals.
 *
 * [key] is the map key it was stored under. Written for a reader's benefit and
 * for anything downstream of an export; it is deliberately *not* read back.
 * Recomputing on load means a change in how keys are spelt heals itself — see
 * [ListeningStats]' OpenBucket.of and the merge it does on the way in.
 */
@Serializable
data class NameEntry(
    val name: String = "",
    val sub: String? = null,
    var art: String? = null,
    /** The MediaStore album id, for an album. */
    var id: String? = null,
    var ms: Long = 0L,
    var plays: Int = 0,
    val key: String? = null,
    /** Per-day breakdown for weekly queries. Key: "YYYY-MM-DD". */
    var days: MutableMap<String, DayStats> = mutableMapOf(),
) {
    fun absorb(other: NameEntry) {
        ms += other.ms
        plays += other.plays
        if (art == null) art = other.art
        if (id == null) id = other.id
        // Merge per-day sub-buckets.
        other.days.forEach { (dateKey, stats) ->
            days.computeIfAbsent(dateKey) { DayStats() }.also { existing ->
                existing.ms += stats.ms
                existing.plays += stats.plays
            }
        }
    }

    /** Sum ms/plays for days that fall within [range]. Falls back to full totals if .days is empty. */
    fun weekRange(range: Pair<LocalDate, LocalDate>): Pair<Long, Int> = days.weekRange(range) ?: (ms to plays)
}

/** The days in [range], summed; null when there is no per-day data at all. */
private fun Map<String, DayStats>.weekRange(range: Pair<LocalDate, LocalDate>): Pair<Long, Int>? {
    if (isEmpty()) return null
    var totalMs = 0L
    var totalPlays = 0
    for ((dateStr, stats) in this) {
        val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
        if (date >= range.first && date <= range.second) {
            totalMs += stats.ms
            totalPlays += stats.plays
        }
    }
    return totalMs to totalPlays
}

/** One calendar month on disk. */
@Serializable
data class StoredBucket(
    val version: Int = 1,
    val month: String,
    val tracks: List<TrackEntry> = emptyList(),
    val artists: List<NameEntry> = emptyList(),
    val albums: List<NameEntry> = emptyList(),
    /** Milliseconds played per hour of the day, 0..23. */
    val hours: List<Long> = List(24) { 0L },
    /** Milliseconds played per day of the month. */
    val days: Map<Int, Long> = emptyMap(),
    /** Per-day sub-buckets for tracks. Key: "YYYY-MM-DD". */
    val trackDays: Map<String, DayStats>? = null,
    /** Per-day sub-buckets for artists. Key: "YYYY-MM-DD". */
    val artistDays: Map<String, DayStats>? = null,
    /** Per-day sub-buckets for albums. Key: "YYYY-MM-DD". */
    val albumDays: Map<String, DayStats>? = null,
)

/** A row on one of the four charts. [artwork] is an [artRef]. */
data class RankedEntry(
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    /** The MediaStore album id for an album; null otherwise. */
    val browseId: String?,
    val ms: Long,
    val plays: Int,
)

/** A song row, which keeps the whole [Song] so tapping it can play it. */
data class RankedSong(val song: Song, val ms: Long, val plays: Int)

/** How far back a Replay reaches. */
enum class ReplayPeriod(val chip: String) {
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    ALL_TIME("All time"),
    ;

    /** The inclusive date range this period covers. Null for ALL_TIME. */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate>? = when (this) {
        THIS_WEEK -> {
            val start = today.with(java.time.DayOfWeek.MONDAY)
            start to today
        }
        THIS_MONTH -> {
            val ym = YearMonth.from(today)
            ym.atDay(1) to ym.atEndOfMonth()
        }
        THIS_YEAR -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
        ALL_TIME -> null
    }

    fun covers(month: YearMonth, today: LocalDate): Boolean = when (this) {
        THIS_WEEK -> {
            val range = month.atDay(1)..month.atEndOfMonth()
            val weekStart = today.with(java.time.DayOfWeek.MONDAY)
            val weekEnd = today
            !(weekEnd < range.start || weekStart > range.endInclusive)
        }
        THIS_MONTH -> month == YearMonth.from(today)
        THIS_YEAR -> month.year == today.year
        ALL_TIME -> true
    }

    /** Whether this period needs day-level filtering (week spans potentially multiple months). */
    fun needsDayFilter(): Boolean = this == THIS_WEEK

    fun label(today: LocalDate): String = when (this) {
        THIS_WEEK -> "This week"
        THIS_MONTH -> YearMonth.from(today).month.name.lowercase(Locale.ROOT)
            .replaceFirstChar { it.uppercase(Locale.ROOT) } + " ${today.year}"
        THIS_YEAR -> today.year.toString()
        ALL_TIME -> "All time"
    }
}

/**
 * Everything the Replay page and the stories draw, worked out once.
 *
 * Deliberately a plain value with the charts already sorted: the stories flip
 * between eight views of the same numbers, and recomputing a ranking per page
 * would put a sort on the swipe.
 */
data class ReplaySummary(
    val period: ReplayPeriod,
    val label: String,
    val totalMs: Long,
    val totalPlays: Int,
    val songs: List<RankedSong>,
    val artists: List<RankedEntry>,
    val albums: List<RankedEntry>,
    val genres: List<RankedEntry>,
    val hourOfDay: List<Long>,
    /** `YYYY-MM-DD` of the day with the most listening, or null. */
    val busiestDay: String?,
    val busiestDayMs: Long,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val distinctAlbums: Int,
    /** The earliest month with anything in it, `YYYY-MM`. */
    val since: String?,
) {
    val minutes: Long get() = totalMs / 60_000
    val hours: Long get() = totalMs / 3_600_000

    /**
     * Whether there is anything at all to draw.
     *
     * One track is enough. A small Replay is a small Replay, and it grows.
     */
    val isEmpty: Boolean get() = songs.isEmpty()

    /** The hour of the day with the most listening, or null if nothing was. */
    val peakHour: Int? get() = hourOfDay.withIndex()
        .filter { it.value > 0 }
        .maxByOrNull { it.value }
        ?.index
}
