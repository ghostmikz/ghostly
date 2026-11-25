package com.ghostmikz.ghostly.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test fun sliderEndsAreTheShortestAndLongest() {
        assertEquals(SleepTimer.MAX_MINUTES, SleepTimer.minutesAt(1f))
        assertEquals(SleepTimer.minutesAt(0f), SleepTimer.minutesAt(-1f))
    }

    @Test fun fractionIsTheInverse() {
        listOf(0f, 0.25f, 0.5f, 1f).forEach { f ->
            val minutes = SleepTimer.minutesAt(f)
            assertEquals(minutes, SleepTimer.minutesAt(SleepTimer.fractionOf(minutes)))
        }
    }
}
