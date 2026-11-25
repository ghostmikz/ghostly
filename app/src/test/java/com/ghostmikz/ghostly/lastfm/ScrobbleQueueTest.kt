package com.ghostmikz.ghostly.lastfm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ScrobbleQueueTest {
    private val now = 1_800_000_000L
    private fun entry(i: Int, ageS: Long = 60) = ScrobbleQueue.Pending("Tron", "Song $i", null, 200, now - ageS)

    @Before fun empty() = ScrobbleQueue.clear()

    @Test fun sentScrobblesLeave() = runTest {
        (1..3).forEach { ScrobbleQueue.add(entry(it)) }
        val sent = mutableListOf<String>()
        ScrobbleQueue.flush(now) { batch -> sent += batch.map { it.track }; Result.success(Unit) }
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), sent)
        assertEquals(0, ScrobbleQueue.waiting.value.size)
    }

    @Test fun offlineKeepsThemForLater() = runTest {
        ScrobbleQueue.add(entry(1))
        ScrobbleQueue.flush(now) { Result.failure<Unit>(IOException("no internet")) }
        assertEquals(1, ScrobbleQueue.waiting.value.size)
        ScrobbleQueue.flush(now) { Result.success(Unit) }
        assertEquals(0, ScrobbleQueue.waiting.value.size)
    }

    @Test fun signedOutKeepsThem() = runTest {
        ScrobbleQueue.add(entry(1))
        ScrobbleQueue.flush(now) { Result.failure<Unit>(LastFM.LastFmException(9, "Invalid session key")) }
        assertEquals(1, ScrobbleQueue.waiting.value.size)
    }

    @Test fun sentInBatchesOfFifty() = runTest {
        (1..120).forEach { ScrobbleQueue.add(entry(it)) }
        val sizes = mutableListOf<Int>()
        ScrobbleQueue.flush(now) { batch -> sizes += batch.size; Result.success(Unit) }
        assertEquals(listOf(50, 50, 20), sizes)
    }

    @Test fun oneBadScrobbleDoesNotTakeTheOthers() = runTest {
        (1..3).forEach { ScrobbleQueue.add(entry(it)) }
        val accepted = mutableListOf<String>()
        ScrobbleQueue.flush(now) { batch ->
            if (batch.any { it.track == "Song 2" }) Result.failure<Unit>(LastFM.LastFmException(6, "Invalid parameters"))
            else { accepted += batch.map { it.track }; Result.success(Unit) }
        }
        assertEquals(listOf("Song 1", "Song 3"), accepted)
        assertEquals(0, ScrobbleQueue.waiting.value.size)
    }

    @Test fun tooOldForLastFmIsDropped() = runTest {
        ScrobbleQueue.add(entry(1, ageS = 20L * 24 * 60 * 60))
        ScrobbleQueue.add(entry(2))
        val sent = mutableListOf<String>()
        ScrobbleQueue.flush(now) { batch -> sent += batch.map { it.track }; Result.success(Unit) }
        assertEquals(listOf("Song 2"), sent)
    }

    @Test fun aRefusedBatchIsTriedOneByOneOnlyOnce() = runTest {
        (1..60).forEach { ScrobbleQueue.add(entry(it)) }
        val sizes = mutableListOf<Int>()
        ScrobbleQueue.flush(now) { batch ->
            sizes += batch.size
            if (batch.any { it.track == "Song 2" }) Result.failure<Unit>(LastFM.LastFmException(6, "Invalid parameters"))
            else Result.success(Unit)
        }
        // The refused 50, then those 50 one by one, then the last 10 together.
        assertEquals(listOf(50) + List(50) { 1 } + listOf(10), sizes)
        assertEquals(0, ScrobbleQueue.waiting.value.size)
    }
}
