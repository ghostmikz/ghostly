package com.ghostmikz.ghostly.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongKeysTest {
    private fun keysOf(vararg songs: Song) = songs.associate { it.id to SongKeys.keyOf(it) }

    @Test fun sameSongSameIdStays() {
        val a = song(1, "Veranda")
        assertTrue(SongKeys.movesFor(keysOf(a), listOf(a)).isEmpty())
    }

    @Test fun songFoundUnderNewId() {
        val old = song(1, "Veranda")
        val now = song(7, "Veranda")
        assertEquals(mapOf(1L to 7L), SongKeys.movesFor(keysOf(old), listOf(now)))
    }

    @Test fun idNowAnotherSongMovesAway() {
        // On a new phone, id 1 is a different song and the old one is id 2.
        val old = song(1, "Veranda")
        val library = listOf(song(1, "Something else"), song(2, "Veranda"))
        assertEquals(mapOf(1L to 2L), SongKeys.movesFor(keysOf(old), library))
    }

    @Test fun swappedIdsSwap() {
        val known = keysOf(song(1, "A"), song(2, "B"))
        val library = listOf(song(1, "B"), song(2, "A"))
        assertEquals(mapOf(1L to 2L, 2L to 1L), SongKeys.movesFor(known, library))
    }

    @Test fun caseAndSpacesDoNotMatter() {
        val old = song(1, "  Veranda ", artist = "TRON")
        val now = song(5, "veranda", artist = "tron")
        assertEquals(mapOf(1L to 5L), SongKeys.movesFor(keysOf(old), listOf(now)))
    }

    @Test fun oneSecondOffStillMatches() {
        val old = song(1, "Veranda", seconds = 200)
        val now = song(5, "Veranda", seconds = 201)
        assertEquals(mapOf(1L to 5L), SongKeys.movesFor(keysOf(old), listOf(now)))
    }

    @Test fun liveTakeIsNotTheSameSong() {
        val old = song(1, "Veranda", seconds = 200)
        val live = song(5, "Veranda", seconds = 260)
        assertTrue(SongKeys.movesFor(keysOf(old), listOf(live)).isEmpty())
    }

    @Test fun twoCloseCandidatesPickNeither() {
        val old = song(1, "Veranda", seconds = 200)
        val library = listOf(song(5, "Veranda", seconds = 201), song(6, "Veranda", seconds = 199))
        assertTrue(SongKeys.movesFor(keysOf(old), library).isEmpty())
    }

    @Test fun swappedIdsKeepTheirOwnKeys() {
        val a = song(1, "A")
        val b = song(2, "B")
        val known = keysOf(a, b)
        val library = listOf(song(1, "B"), song(2, "A"))
        val moves = SongKeys.movesFor(known, library)
        val next = SongKeys.nextKeys(known, moves, library, setOf(1L, 2L))
        assertEquals(SongKeys.keyOf(a), next[2L])
        assertEquals(SongKeys.keyOf(b), next[1L])
    }

    @Test fun notScannedYetKeepsTheStoredKey() {
        // A new phone whose id 1 is some other song, and the real one not scanned yet.
        val old = song(1, "Veranda")
        val known = keysOf(old)
        val library = listOf(song(1, "Something else"))
        val moves = SongKeys.movesFor(known, library)
        assertTrue(moves.isEmpty())
        assertEquals(SongKeys.keyOf(old), SongKeys.nextKeys(known, moves, library, setOf(1L))[1L])
    }

    @Test fun newIdTakesItsSongsKey() {
        val library = listOf(song(3, "New"))
        val next = SongKeys.nextKeys(emptyMap(), emptyMap(), library, setOf(3L))
        assertEquals(mapOf(3L to SongKeys.keyOf(library[0])), next)
    }

    @Test fun unreferencedKeysAreDropped() {
        val known = keysOf(song(1, "A"), song(2, "B"))
        val next = SongKeys.nextKeys(known, emptyMap(), emptyList(), setOf(1L))
        assertEquals(setOf(1L), next.keys)
    }

    @Test fun missingSongIsLeftAlone() {
        val old = song(1, "Veranda")
        assertTrue(SongKeys.movesFor(keysOf(old), listOf(song(2, "Other"))).isEmpty())
    }
}
