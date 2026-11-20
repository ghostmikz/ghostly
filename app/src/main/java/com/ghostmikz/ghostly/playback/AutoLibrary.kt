// Ported from BitChord's MediaLibraryCallback browse tree in PlaybackService
// (https://github.com/kushagrasinghx/BitChord, GPLv3). BitChord fills it from
// YouTube Music; Ghostly fills the same folders from the phone: Quick picks are
// the most played songs, Recent the play history, and Playlists / Liked songs /
// Local Music the app's own.
package com.ghostmikz.ghostly.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.ghostmikz.ghostly.data.History
import com.ghostmikz.ghostly.data.Library
import com.ghostmikz.ghostly.data.MusicRepository
import com.ghostmikz.ghostly.data.Playlists
import com.ghostmikz.ghostly.data.Prefs
import com.ghostmikz.ghostly.data.Song
import com.ghostmikz.ghostly.data.sortedForLibrary
import com.ghostmikz.ghostly.ui.toMediaItem
import java.util.Locale

/**
 * What Android Auto (and any other media browser) sees of Ghostly: a root of
 * folders, each a list of songs. Tapping a song plays the folder it was in from
 * that song on — a song row's id carries its folder for that ("folder|songId").
 */
internal class AutoLibrary(private val context: Context) {

    @Volatile private var library: Library? = null
    @Volatile private var loadedAt = 0L

    /** The library, read at most every [FRESH_MS] (a car browses many folders in a row). */
    private suspend fun library(): Library {
        val now = System.currentTimeMillis()
        library?.takeIf { now - loadedAt < FRESH_MS }?.let { return it }
        val fresh = runCatching { MusicRepository.load(context, Prefs.filterNonMusic.value) }.getOrNull() ?: Library()
        library = fresh
        loadedAt = now
        return fresh
    }

    fun rootItem(params: LibraryParams?): Pair<MediaItem, LibraryParams> {
        val isRecent = params?.isRecent == true ||
            params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
            params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true
        val rootId = if (isRecent) MEDIA_RECENTS_ID else MEDIA_ROOT_ID
        val rootExtras = contentStyle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        val rootItem = MediaItem.Builder()
            .setMediaId(rootId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Ghostly")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .setExtras(rootExtras)
                    .build(),
            )
            .build()
        return rootItem to LibraryParams.Builder().setExtras(rootExtras).build()
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): Pair<List<MediaItem>, LibraryParams> {
        val isGridFolder = parentId == MEDIA_ROOT_ID || parentId == MEDIA_PLAYLISTS_ID
        val items: List<MediaItem> = when (parentId) {
            MEDIA_ROOT_ID -> listOf(
                folder(MEDIA_QUICK_PICKS_ID, "Quick picks", MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                folder(MEDIA_RECENTS_ID, "Recent", MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                folder(MEDIA_PLAYLISTS_ID, "Playlists", MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                folder(MEDIA_LIKED_ID, "Liked songs", MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                folder(MEDIA_MORE_ID, "More", MediaMetadata.FOLDER_TYPE_MIXED),
            )
            MEDIA_MORE_ID -> listOf(
                folder(MEDIA_LOCAL_MUSIC_ID, "Local Music", MediaMetadata.FOLDER_TYPE_MIXED),
            )
            MEDIA_PLAYLISTS_ID -> {
                val lib = library()
                val own = Playlists.playlists.value.filterNot { it.isLiked }
                val byId = lib.songs.associateBy { it.id }
                if (own.isNotEmpty()) {
                    own.map { p ->
                        val count = p.songIds.count { it in byId }
                        val cover = p.songIds.firstNotNullOfOrNull { byId[it] }
                        gridPlaylist("playlist:${p.id}", p.title, if (count == 1) "1 song" else "$count songs", cover)
                    }
                } else {
                    listOf(info("msg:empty_playlists", "No playlists yet", "Make one from any song's menu on your phone"))
                }
            }
            else -> {
                val songs = songsIn(parentId)
                if (songs.isNotEmpty()) songs.map { it.asRow(parentId) } else listOf(emptyNote(parentId))
            }
        }
        val returnParams = LibraryParams.Builder().setExtras(
            contentStyle(
                if (isGridFolder) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            ),
        ).build()
        return paginate(items, page, pageSize) to returnParams
    }

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        MEDIA_ROOT_ID -> rootItem(null).first
        MEDIA_QUICK_PICKS_ID -> folder(MEDIA_QUICK_PICKS_ID, "Quick picks", MediaMetadata.FOLDER_TYPE_PLAYLISTS)
        MEDIA_RECENTS_ID -> folder(MEDIA_RECENTS_ID, "Recent", MediaMetadata.FOLDER_TYPE_PLAYLISTS)
        MEDIA_PLAYLISTS_ID -> folder(MEDIA_PLAYLISTS_ID, "Playlists", MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
        MEDIA_LIKED_ID -> folder(MEDIA_LIKED_ID, "Liked songs", MediaMetadata.FOLDER_TYPE_PLAYLISTS)
        MEDIA_MORE_ID -> folder(MEDIA_MORE_ID, "More", MediaMetadata.FOLDER_TYPE_MIXED)
        MEDIA_LOCAL_MUSIC_ID -> folder(MEDIA_LOCAL_MUSIC_ID, "Local Music", MediaMetadata.FOLDER_TYPE_MIXED)
        else -> songById(mediaId.substringAfterLast(ROW_SEPARATOR).toLongOrNull())?.toMediaItem()
    }

    /** Songs whose title, artist or album matches every word of [query]. */
    suspend fun search(query: String): List<MediaItem> {
        val words = query.trim().take(MAX_SEARCH_QUERY_CHARS).lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return library().songs.filter { s ->
            val text = "${s.title} ${s.artist} ${s.album}".lowercase(Locale.ROOT)
            words.all { it in text }
        }.take(MAX_SEARCH_RESULTS).map { it.asRow(SEARCH_FOLDER + query) }
    }

    /**
     * Turns what a car or voice command asks to play into playable items:
     * a song row plays its folder from that song on, a folder plays whole, and
     * a voice query plays what it matches.
     */
    suspend fun resolve(mediaItems: List<MediaItem>, startIndex: Int): Pair<List<MediaItem>, Int> {
        // The app's own requests already carry their files and queue tags: handed
        // back untouched (the same list), so shuffle and its saved order survive.
        if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration != null }) return mediaItems to startIndex
        if (mediaItems.size == 1) {
            val item = mediaItems[0]
            val query = item.requestMetadata.searchQuery
            if (!query.isNullOrBlank()) {
                val found = search(query).mapNotNull { item(it.mediaId) }
                if (found.isNotEmpty()) return found.asQueue() to 0
            }
            val id = item.mediaId
            if (ROW_SEPARATOR in id) {
                val folder = id.substringBeforeLast(ROW_SEPARATOR)
                val songId = id.substringAfterLast(ROW_SEPARATOR).toLongOrNull()
                val songs = if (folder.startsWith(SEARCH_FOLDER)) {
                    search(folder.removePrefix(SEARCH_FOLDER)).mapNotNull { songById(it.mediaId.substringAfterLast(ROW_SEPARATOR).toLongOrNull()) }
                } else {
                    songsIn(folder)
                }
                val start = songs.indexOfFirst { it.id == songId }
                if (start >= 0) return songs.map { it.toMediaItem() }.asQueue() to start
            }
            val folderSongs = songsIn(id)
            if (folderSongs.isNotEmpty()) return folderSongs.map { it.toMediaItem() }.asQueue() to 0
        }
        val resolved = mediaItems.mapNotNull { item ->
            when {
                item.localConfiguration != null -> item
                else -> item(item.mediaId)?.takeIf { it.localConfiguration != null }
            }
        }
        if (resolved.isNotEmpty()) return resolved.asQueue() to startIndex.coerceIn(0, resolved.size - 1)
        // Nothing matched ("play Ghostly", an unknown title, a song since deleted):
        // never hand the player an item without a file, which it can't play and
        // would crash on. Play the library instead, as a music app asked to "play" does.
        val all = library().songs.sortedForLibrary(Prefs.localSort.value)
        return all.map { it.toMediaItem() }.asQueue() to 0
    }

    /**
     * One item added to an existing queue: a row adds just its song (not its
     * folder), and an item that already has its file is kept as it is.
     */
    suspend fun resolveOne(item: MediaItem): MediaItem? = when {
        item.localConfiguration != null -> item
        else -> item(item.mediaId)?.takeIf { it.localConfiguration != null }?.asQueueEntry(QueueTier.CONTEXT)
    }

    private fun List<MediaItem>.asQueue() = map { it.asQueueEntry(QueueTier.CONTEXT) }

    private suspend fun songsIn(folderId: String): List<Song> {
        val lib = library()
        val byId = lib.songs.associateBy { it.id }
        return when {
            folderId == MEDIA_QUICK_PICKS_ID ->
                History.stats.value.entries.sortedByDescending { it.value.count }.mapNotNull { byId[it.key] }.take(MAX_FOLDER_SONGS)
            folderId == MEDIA_RECENTS_ID ->
                History.stats.value.entries.sortedByDescending { it.value.lastPlayed }.mapNotNull { byId[it.key] }.take(MAX_FOLDER_SONGS)
            folderId == MEDIA_LIKED_ID ->
                Playlists.get(Playlists.LIKED_ID)?.songIds.orEmpty().mapNotNull { byId[it] }
            folderId == MEDIA_LOCAL_MUSIC_ID -> lib.songs.sortedForLibrary(Prefs.localSort.value)
            folderId.startsWith("playlist:") ->
                Playlists.get(folderId.removePrefix("playlist:"))?.songIds.orEmpty().mapNotNull { byId[it] }
            else -> emptyList()
        }
    }

    private suspend fun songById(id: Long?): Song? = id?.let { i -> library().songs.firstOrNull { it.id == i } }

    private fun emptyNote(folderId: String): MediaItem = when (folderId) {
        MEDIA_RECENTS_ID -> info("msg:empty_recents", "No recent songs", "Songs you play will appear here")
        MEDIA_QUICK_PICKS_ID -> info("msg:empty_quick_picks", "No suggestions", "Songs you play often will appear here")
        MEDIA_LIKED_ID -> info("msg:empty_liked", "No liked songs", "Tap the star on a song to save it here")
        else -> info("msg:empty_local", "No songs", "Music on your phone will appear here")
    }

    /** A song in a folder list: playable, with its folder in its id. */
    private fun Song.asRow(folderId: String): MediaItem {
        val base = toMediaItem()
        return base.buildUpon()
            .setMediaId(folderId + ROW_SEPARATOR + id)
            .build()
    }

    private fun folder(mediaId: String, title: String, folderType: Int, isGrid: Boolean = false): MediaItem {
        val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(folderType)
                    .setExtras(contentStyle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()
    }

    private fun gridPlaylist(mediaId: String, title: String, subtitle: String?, cover: Song?): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(cover?.albumArtUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                    .setExtras(contentStyle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()

    /** A row that only says something: an empty folder's explanation. */
    private fun info(mediaId: String, title: String, subtitle: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    /** Every spelling of the content-style hints, as different car hosts read different ones. */
    private fun contentStyle(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

    /**
     * One page of a folder. Android Auto's legacy browser asks for page 0 of size
     * Int.MAX_VALUE and never for a second page, so no cap here: a cap would cut
     * every long folder short (and, with real paging, skew the offsets).
     */
    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val fromLong = page.toLong() * pageSize.toLong()
        if (fromLong >= items.size.toLong()) return emptyList()
        val from = fromLong.toInt()
        val to = (fromLong + pageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    companion object {
        const val MEDIA_ROOT_ID = "root"
        const val MEDIA_RECENTS_ID = "recents"
        const val MEDIA_QUICK_PICKS_ID = "quick_picks"
        const val MEDIA_PLAYLISTS_ID = "playlists"
        const val MEDIA_MORE_ID = "more"
        const val MEDIA_LIKED_ID = "liked"
        const val MEDIA_LOCAL_MUSIC_ID = "local_music"
        private const val ROW_SEPARATOR = "|"
        private const val SEARCH_FOLDER = "search:"
        private const val MAX_FOLDER_SONGS = 100
        private const val MAX_SEARCH_RESULTS = 50
        private const val MAX_SEARCH_QUERY_CHARS = 100
        private const val FRESH_MS = 60_000L
        const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
        const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4
    }
}
