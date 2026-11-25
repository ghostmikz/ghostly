package com.ghostmikz.ghostly.data

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HistoryTest {
    @Before fun clear() = History.clearHistory()

    @Test fun aStartIsNotAPlay() {
        History.touch(1)
        assertEquals(0, History.stats.value.getValue(1).count)
        assertEquals(emptyList<Long>(), History.mostPlayed())
    }

    @Test fun aListenIsAPlay() {
        History.touch(1)
        History.countPlay(1)
        History.touch(2)
        assertEquals(1, History.stats.value.getValue(1).count)
        assertEquals(listOf(1L), History.mostPlayed())
    }

    @Test fun remapAddsPlaysTogether() {
        History.countPlay(1)
        History.countPlay(2)
        History.countPlay(2)
        History.remap(mapOf(1L to 2L))
        assertEquals(mapOf(2L to 3), History.stats.value.mapValues { it.value.count })
    }
}
