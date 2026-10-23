package com.shuxian.videoclient.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPosterWindowTest {
    @Test
    fun firstScreenPreparesThreeMoreRows() {
        assertEquals((0..29).toList(), tvPosterPrefetchIndices(0, 11, 100))
    }

    @Test
    fun visibleAndForwardPostersHavePriorityOverThePreviousRow() {
        val indices = tvPosterPrefetchIndices(18, 29, 100)
        assertEquals((18..47).toList(), indices.take(30))
        assertEquals((12..17).toList(), indices.takeLast(6))
    }

    @Test
    fun reachingTheEndDoesNotRequestMissingPosters() {
        assertEquals((18..24).toList() + (12..17).toList(), tvPosterPrefetchIndices(18, 24, 25))
    }

    @Test
    fun switchingToAnEmptyOrShorterCategoryIgnoresTheOldLayout() {
        assertTrue(tvPosterPrefetchIndices(18, 29, 0).isEmpty())
        assertTrue(tvPosterPrefetchIndices(18, 29, 6).isEmpty())
        assertTrue(tvPosterPrefetchIndices(-1, 5, 24).isEmpty())
    }

    @Test
    fun everyWindowStaysBoundedAndContainsNoDuplicateRequests() {
        for (count in 1..120) for (first in 0 until count) {
            val indices = tvPosterPrefetchIndices(first, minOf(first + 11, count - 1), count)
            assertEquals(indices.size, indices.distinct().size)
            assertTrue(indices.all { it in 0 until count })
            assertTrue(indices.size <= 36)
            assertEquals(first, indices.first())
        }
    }
}
