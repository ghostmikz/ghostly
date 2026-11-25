package com.ghostmikz.ghostly.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private fun ids(groups: List<List<Song>>) = groups.map { g -> g.map { it.id }.toSet() }

    @Test fun sameSongTwice() {
        val groups = Duplicates.find(listOf(song(1, "Veranda"), song(2, "Veranda"), song(3, "Other")))
        assertEquals(listOf(setOf(1L, 2L)), ids(groups))
    }

    @Test fun videoNoiseAndFeatAreIgnored() {
        val groups = Duplicates.find(
            listOf(
                song(1, "Veranda"),
                song(2, "Veranda (Official Video)"),
                song(3, "Veranda feat. Someone"),
            ),
        )
        assertEquals(listOf(setOf(1L, 2L, 3L)), ids(groups))
    }

    @Test fun channelStyleTitle() {
        val groups = Duplicates.find(
            listOf(song(1, "Veranda", artist = "Tron"), song(2, "Tron - Veranda", artist = "TronVEVO")),
        )
        assertEquals(listOf(setOf(1L, 2L)), ids(groups))
    }

    @Test fun differentLengthsStayApart() {
        val groups = Duplicates.find(listOf(song(1, "Veranda", seconds = 200), song(2, "Veranda", seconds = 260)))
        assertTrue(groups.isEmpty())
    }

    @Test fun differentArtistsStayApart() {
        val groups = Duplicates.find(listOf(song(1, "Intro", artist = "A"), song(2, "Intro", artist = "B")))
        assertTrue(groups.isEmpty())
    }

    @Test fun bestPrefersLosslessThenBitrate() {
        val a = song(1, "Veranda")
        val b = song(2, "Veranda")
        val c = song(3, "Veranda")
        val info = mapOf(
            1L to MusicRepository.FileInfo("a.mp3", "MP3", 8_000_000, 320),
            2L to MusicRepository.FileInfo("b.flac", "FLAC", 30_000_000, 1000),
            3L to MusicRepository.FileInfo("c.mp3", "MP3", 5_000_000, 192),
        )
        assertEquals(2L, Duplicates.best(listOf(a, b, c), info).id)
        assertEquals(1L, Duplicates.best(listOf(a, c), info).id)
    }
}
