package com.ghostmikz.ghostly.data

import androidx.core.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/**
 * The app's small JSON files (history, playlists): written whole and atomically
 * (AtomicFile syncs before it swaps the new copy in, so a power cut leaves the
 * old file, never half of a new one), on one background thread so writes keep
 * their order and never run on the main thread.
 */
internal object SafeFiles {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ghostly-files").apply { isDaemon = true } }

    /**
     * The file's text, or null when it doesn't exist yet. A file that exists but
     * can't be read is set aside first, since the next write replaces it.
     */
    fun read(file: File): String? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".new").exists()) return null
        return runCatching { String(atomic.readFully()) }
            .onFailure { setAside(file) }
            .getOrNull()
    }

    /** Queues [text] to replace [file]; [text] is taken now, so later changes can't leak into it. */
    fun write(file: File, text: String) {
        writer.execute {
            val atomic = AtomicFile(file)
            val out = runCatching { atomic.startWrite() }.getOrNull() ?: return@execute
            try {
                out.write(text.toByteArray())
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
            }
        }
    }

    /**
     * A file that exists but can't be read as ours is set aside, not overwritten,
     * so whatever is in it can still be recovered by hand.
     */
    fun setAside(file: File) {
        runCatching { file.copyTo(File(file.path + ".unreadable"), overwrite = true) }
    }
}
