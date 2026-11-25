package com.ghostmikz.ghostly.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.ghostmikz.ghostly.data.MusicRepository
import com.ghostmikz.ghostly.ui.toMediaItem

/**
 * The queue, kept across the process dying, like BitChord's persisted queue: so
 * the widget's buttons (and the app reopening) have the last songs to go back to.
 * Bounded to [LIMIT] songs around the current one.
 */
internal object QueueStore {
    private const val LIMIT = 500

    fun save(context: Context, player: Player) {
        val count = player.mediaItemCount
        // An empty player is one that hasn't restored yet (a service the system
        // restarted and stopped again within a second), never a choice to forget
        // the queue: saving it would wipe the queue that is still there to restore.
        if (count == 0) return
        val sp = prefs(context)
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val from = (current - LIMIT / 2).coerceIn(0, maxOf(0, count - LIMIT))
        val until = minOf(count, from + LIMIT)
        val ids = (from until until).joinToString(",") { player.getMediaItemAt(it).mediaId }
        // Which entries the user queued by hand ("Play next"), so a restored queue
        // still keeps them out of a shuffle.
        val tiers = (from until until).joinToString("") { if (player.getMediaItemAt(it).queueTier == QueueTier.USER_QUEUE) "U" else "C" }
        sp.edit()
            .putString("ids", ids)
            .putString("tiers", tiers)
            .putInt("index", current - from)
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", QueueShuffle.enabled.value)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    /** The saved queue, read back: its songs, where it was, and its shuffle and repeat. */
    class Saved(val items: List<MediaItem>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeat: Int)

    /** The saved queue with only the songs still on the phone, or null when there is none. */
    suspend fun load(context: Context): Saved? {
        val sp = prefs(context)
        val ids = sp.getString("ids", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (ids.isEmpty()) return null
        // No permission to read music (yet) means nothing to restore.
        val songs = runCatching { MusicRepository.load(context, filterNonMusic = false, includeHidden = true) }.getOrNull()
            ?.songs?.associateBy { it.id } ?: return null
        val index = sp.getInt("index", 0)
        val savedCurrent = ids.getOrNull(index)
        val tiers = sp.getString("tiers", "").orEmpty()
        val keptIdx = ids.indices.filter { songs[ids[it]] != null }
        if (keptIdx.isEmpty()) return null
        val kept = keptIdx.map { songs.getValue(ids[it]) }
        val start = kept.indexOfFirst { it.id == savedCurrent }
        return Saved(
            items = keptIdx.mapIndexed { k, i ->
                kept[k].toMediaItem().asQueueEntry(if (tiers.getOrNull(i) == 'U') QueueTier.USER_QUEUE else QueueTier.CONTEXT)
            },
            index = maxOf(start, 0),
            positionMs = if (start >= 0) sp.getLong("position", 0) else 0,
            shuffle = sp.getBoolean("shuffle", false),
            repeat = sp.getInt("repeat", Player.REPEAT_MODE_OFF),
        )
    }

    /** Puts the saved queue back into an empty [player], paused where it was left. */
    suspend fun restore(context: Context, player: Player) {
        val saved = load(context) ?: return
        if (player.mediaItemCount > 0) return
        player.setMediaItems(saved.items, saved.index, saved.positionMs)
        // The saved order already is the shuffled one; the player's own shuffle stays off.
        player.shuffleModeEnabled = false
        QueueShuffle.setEnabled(saved.shuffle)
        player.repeatMode = saved.repeat
        player.prepare()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("ghostly_queue", Context.MODE_PRIVATE)
}
