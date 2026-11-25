package com.ghostmikz.ghostly.data.stats

import com.ghostmikz.ghostly.data.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayRemapTest {
    private fun track(id: Long, ms: Long, plays: Int, albumId: Long = 1) = TrackEntry(
        id = id.toString(), title = "Veranda", artist = "Tron", album = "Album",
        albumId = albumId.toString(), art = "$id:$albumId", ms = ms, plays = plays,
        days = mutableMapOf("2026-11-25" to DayStats(ms, plays)),
    )

    @Test fun nothingMovedIsNull() {
        val bucket = StoredBucket(month = "2026-11", tracks = listOf(track(1, 1000, 1)))
        assertNull(remapBucket(bucket, mapOf(9L to 10L), emptyMap()))
    }

    @Test fun movedTrackTakesNewIdCoverAndAlbum() {
        val bucket = StoredBucket(
            month = "2026-11",
            tracks = listOf(track(1, 1000, 1, albumId = 3)),
            albums = listOf(NameEntry(name = "Album", sub = "Tron", art = "1:3", id = "3", ms = 1000, plays = 1)),
            artists = listOf(NameEntry(name = "Tron", art = "1:3", ms = 1000, plays = 1)),
        )
        val moved = remapBucket(bucket, mapOf(1L to 7L), mapOf(7L to song(7, "Veranda", albumId = 4)))!!
        val t = moved.tracks.single()
        assertEquals("7", t.id)
        assertEquals("4", t.albumId)
        assertEquals("7:4", t.art)
        assertEquals("4", moved.albums.single().id)
        assertEquals("7:4", moved.albums.single().art)
        assertEquals("7:4", moved.artists.single().art)
    }

    @Test fun oldAndNewIdAddTogether() {
        // Played before the restore under id 1, and after it under id 7: one row.
        val bucket = StoredBucket(month = "2026-11", tracks = listOf(track(1, 1000, 1), track(7, 500, 2)))
        val moved = remapBucket(bucket, mapOf(1L to 7L), mapOf(7L to song(7, "Veranda")))!!
        val t = moved.tracks.single()
        assertEquals(1500L, t.ms)
        assertEquals(3, t.plays)
        assertEquals(1500L, t.days.getValue("2026-11-25").ms)
    }
}
