package com.ghostmikz.ghostly.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueShuffleTest {
    private fun item(id: String, tier: QueueTier) = MediaItem.Builder().setMediaId(id).build().asQueueEntry(tier)

    @Test fun startingOrderPutsChosenFirstThenUserQueue() {
        val items = listOf(
            item("a", QueueTier.CONTEXT), item("b", QueueTier.CONTEXT), item("u", QueueTier.USER_QUEUE),
            item("c", QueueTier.CONTEXT), item("d", QueueTier.CONTEXT),
        )
        val order = QueueShuffle.startingOrder(items, 3).map { it.mediaId }
        assertEquals("c", order[0])
        assertEquals("u", order[1])
        assertEquals(setOf("a", "b", "d"), order.drop(2).toSet())
        assertEquals(5, order.size)
    }

    @Test fun shuffleNeverKeepsTheSameOrder() {
        val original = listOf(1, 2, 3)
        assertNotEquals(original, QueueShuffle.avoidIdentityShuffle(original, original))
        assertEquals(listOf(5), QueueShuffle.avoidIdentityShuffle(listOf(5), listOf(5)))
    }

    @Test fun restoreGoesBackToTheOriginalOrder() {
        val upcoming = listOf("d", "b", "c")
        val original = listOf("a", "b", "c", "d")
        // "a" is playing: the rest come back in their first order.
        assertEquals(listOf(1, 2, 0), QueueShuffle.restoreOrder(upcoming, original, currentId = "a"))
    }

    @Test fun restoreKeepsTracksItDoesNotKnowAtTheEnd() {
        val order = QueueShuffle.restoreOrder(listOf("x", "b"), listOf("a", "b"), currentId = "a")
        assertEquals(listOf(1, 0), order)
    }
}
