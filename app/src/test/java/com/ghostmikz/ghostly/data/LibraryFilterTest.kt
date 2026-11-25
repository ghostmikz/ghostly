package com.ghostmikz.ghostly.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterTest {
    private val music = song(1, "Song")
    private val short = song(2, "Jingle", seconds = 20)
    private val recording = song(3, "Memo").copy(isMusic = false)
    private val inCalls = song(4, "Call").copy(folder = "Call recordings")

    @Test fun filterKeepsOnlyMusic() {
        val lib = MusicRepository.libraryFrom(listOf(music, short, recording, inCalls), filterNonMusic = true, hidden = emptySet())
        assertEquals(listOf(1L), lib.songs.map { it.id })
    }

    @Test fun filterOffKeepsEverything() {
        val lib = MusicRepository.libraryFrom(listOf(music, short, recording, inCalls), filterNonMusic = false, hidden = emptySet())
        assertEquals(4, lib.songs.size)
    }

    @Test fun hiddenSongsLeave() {
        val lib = MusicRepository.libraryFrom(listOf(music, song(5, "Other")), filterNonMusic = true, hidden = setOf(5L))
        assertEquals(listOf(1L), lib.songs.map { it.id })
        assertEquals(1, lib.albums.single().songs.size)
    }
}
