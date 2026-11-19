package com.ghostmikz.ghostly.playback

import android.content.Context
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
        val sp = prefs(context)
        if (count == 0) { sp.edit().clear().apply(); return }
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val from = (current - LIMIT / 2).coerceIn(0, maxOf(0, count - LIMIT))
        val until = minOf(count, from + LIMIT)
        val ids = (from until until).joinToString(",") { player.getMediaItemAt(it).mediaId }
        sp.edit()
            .putString("ids", ids)
            .putInt("index", current - from)
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    /** Puts the saved queue back into an empty [player], paused where it was left. */
    suspend fun restore(context: Context, player: Player) {
        val sp = prefs(context)
        val ids = sp.getString("ids", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return
        if (ids.isEmpty()) return
        // No permission to read music (yet) means nothing to restore.
        val songs = runCatching { MusicRepository.load(context, filterNonMusic = false) }.getOrNull()
            ?.songs?.associateBy { it.id } ?: return
        val index = sp.getInt("index", 0)
        val savedCurrent = ids.getOrNull(index)
        val kept = ids.mapNotNull { songs[it] }
        if (kept.isEmpty() || player.mediaItemCount > 0) return
        val start = kept.indexOfFirst { it.id == savedCurrent }
        player.setMediaItems(
            kept.map { it.toMediaItem() },
            maxOf(start, 0),
            if (start >= 0) sp.getLong("position", 0) else 0,
        )
        player.shuffleModeEnabled = sp.getBoolean("shuffle", false)
        player.repeatMode = sp.getInt("repeat", Player.REPEAT_MODE_OFF)
        player.prepare()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("ghostly_queue", Context.MODE_PRIVATE)
}
