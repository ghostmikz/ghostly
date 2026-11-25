package com.ghostmikz.ghostly.lastfm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobbleManagerTest {
    private class Recorder : ScrobbleSender {
        val scrobbles = mutableListOf<String>()
        val nowPlaying = mutableListOf<String>()
        override suspend fun scrobble(artist: String, track: String, timestamp: Long, album: String?, duration: Int): Result<*> {
            scrobbles += "$artist - $track"
            return Result.success(Unit)
        }
        override suspend fun nowPlaying(artist: String, track: String, album: String?, duration: Int?): Result<*> {
            nowPlaying += "$artist - $track"
            return Result.success(Unit)
        }
    }

    private fun track(seconds: Long, title: String = "Veranda") = ScrobbleTrack("1", title, "Tron", "Album", seconds * 1000)

    private fun TestScope.manager(sender: Recorder) =
        ScrobbleManager(backgroundScope, sender = sender, clock = { START + testScheduler.currentTime })

    @Test fun scrobblesAtHalfTheSong() = runTest {
        val sent = Recorder()
        val m = manager(sent)
        m.onPlayerStateChanged(true, track(200))
        advanceTimeBy(99_000); runCurrent()
        assertEquals(0, sent.scrobbles.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Tron - Veranda"), sent.scrobbles)
    }

    @Test fun longSongScrobblesAtThreeMinutes() = runTest {
        val sent = Recorder()
        val m = manager(sent)
        m.onPlayerStateChanged(true, track(600))
        advanceTimeBy(181_000); runCurrent()
        assertEquals(1, sent.scrobbles.size)
    }

    @Test fun shortSongNeverScrobbles() = runTest {
        val sent = Recorder()
        val m = manager(sent)
        m.onPlayerStateChanged(true, track(25))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, sent.scrobbles.size)
    }

    @Test fun skippedSongNeverScrobbles() = runTest {
        val sent = Recorder()
        val m = manager(sent)
        m.onPlayerStateChanged(true, track(200))
        advanceTimeBy(10_000)
        m.onSongStop()
        advanceTimeBy(200_000); runCurrent()
        assertEquals(0, sent.scrobbles.size)
    }

    @Test fun pausedTimeDoesNotCount() = runTest {
        val sent = Recorder()
        val m = manager(sent)
        m.onPlayerStateChanged(true, track(200))
        advanceTimeBy(60_000)
        m.onPlayerStateChanged(false, track(200))
        advanceTimeBy(300_000); runCurrent()
        assertEquals(0, sent.scrobbles.size)
        m.onPlayerStateChanged(true, track(200))
        advanceTimeBy(39_000); runCurrent()
        assertEquals(0, sent.scrobbles.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, sent.scrobbles.size)
    }

    @Test fun resumeAfterScrobbleDoesNotRepeat() = runTest {
        val sent = Recorder()
        val m = manager(sent)
        m.onPlayerStateChanged(true, track(200))
        advanceTimeBy(101_000); runCurrent()
        m.onPlayerStateChanged(false, track(200))
        m.onPlayerStateChanged(true, track(200))
        advanceTimeBy(200_000); runCurrent()
        assertEquals(1, sent.scrobbles.size)
    }

    @Test fun nowPlayingOnlyWhenOn() = runTest {
        val sent = Recorder()
        val m = manager(sent)
        m.useNowPlaying = false
        m.onPlayerStateChanged(true, track(200)); runCurrent()
        assertEquals(0, sent.nowPlaying.size)
        m.onSongStop()
        m.useNowPlaying = true
        m.onPlayerStateChanged(true, track(200, "Next")); runCurrent()
        assertEquals(listOf("Tron - Next"), sent.nowPlaying)
    }

    private companion object {
        /** A real clock is never at zero, which the scrobbler reads as "no timer". */
        const val START = 1_700_000_000_000L
    }
}
