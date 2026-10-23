package com.shuxian.videoclient.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvGridNavigationTest {
    @Test
    fun horizontalMovementStaysInTheCurrentRow() {
        assertEquals(0, tvPosterTarget(0, 24, TvGridDirection.Left))
        assertEquals(5, tvPosterTarget(5, 24, TvGridDirection.Right))
        assertEquals(6, tvPosterTarget(6, 24, TvGridDirection.Left))
        assertEquals(11, tvPosterTarget(11, 24, TvGridDirection.Right))
        assertEquals(4, tvPosterTarget(5, 24, TvGridDirection.Left))
        assertEquals(7, tvPosterTarget(6, 24, TvGridDirection.Right))
    }

    @Test
    fun everyPosterInTheFirstRowCanReturnToTheHeader() {
        for (index in 0..5) assertEquals(-1, tvPosterTarget(index, 24, TvGridDirection.Up))
    }

    @Test
    fun verticalMovementKeepsTheColumn() {
        assertEquals(10, tvPosterTarget(4, 24, TvGridDirection.Down))
        assertEquals(4, tvPosterTarget(10, 24, TvGridDirection.Up))
        assertEquals(23, tvPosterTarget(17, 24, TvGridDirection.Down))
    }

    @Test
    fun incompleteLastRowIsReachableAndDoesNotWrap() {
        assertEquals(13, tvPosterTarget(11, 14, TvGridDirection.Down))
        assertEquals(12, tvPosterTarget(6, 13, TvGridDirection.Down))
        assertEquals(12, tvPosterTarget(12, 13, TvGridDirection.Right))
        assertEquals(13, tvPosterTarget(13, 14, TvGridDirection.Down))
        assertEquals(7, tvPosterTarget(13, 14, TvGridDirection.Up))
    }

    @Test
    fun holdingDirectionAtTheEdgeDoesNotLeaveTheList() {
        var index = 0
        repeat(200) { index = tvPosterTarget(index, 25, TvGridDirection.Down) }
        assertEquals(24, index)
        repeat(200) { index = tvPosterTarget(index, 25, TvGridDirection.Right) }
        assertEquals(24, index)
        repeat(200) { index = tvPosterTarget(index, 25, TvGridDirection.Left) }
        assertEquals(24, index)
    }

    @Test
    fun allPageSizesProduceValidTargets() {
        for (count in 1..73) for (index in 0 until count) for (direction in TvGridDirection.entries) {
            val target = tvPosterTarget(index, count, direction)
            assertTrue("count=$count index=$index direction=$direction target=$target",
                target in 0 until count || (target == -1 && direction == TvGridDirection.Up && index < 6))
        }
    }

    @Test
    fun historyNavigationUsesOneColumnWithoutHorizontalWrapping() {
        assertEquals(-1, tvPosterTarget(0, 10, TvGridDirection.Up, columns = 1))
        assertEquals(4, tvPosterTarget(3, 10, TvGridDirection.Down, columns = 1))
        assertEquals(2, tvPosterTarget(3, 10, TvGridDirection.Up, columns = 1))
        assertEquals(3, tvPosterTarget(3, 10, TvGridDirection.Left, columns = 1))
        assertEquals(3, tvPosterTarget(3, 10, TvGridDirection.Right, columns = 1))
        assertEquals(9, tvPosterTarget(9, 10, TvGridDirection.Down, columns = 1))
    }

    @Test
    fun fullyVisiblePostersDoNotMoveTheViewport() {
        assertEquals(0, tvFocusScrollDistance(120, 350, 20, 520))
        assertEquals(0, tvFocusScrollDistance(20, 250, 20, 520))
        assertEquals(0, tvFocusScrollDistance(290, 520, 20, 520))
    }

    @Test
    fun movingDownRevealsOnlyTheHiddenPartInsteadOfAligningToTheTop() {
        assertEquals(70, tvFocusScrollDistance(360, 590, 20, 520))
        // 下一排尚未出现在屏幕内时，也只滚动一排的距离。
        assertEquals(248, tvFocusScrollDistance(538, 768, 20, 520))
    }

    @Test
    fun movingUpRevealsOnlyTheHiddenPart() {
        assertEquals(-70, tvFocusScrollDistance(-50, 180, 20, 520))
        assertEquals(-248, tvFocusScrollDistance(-228, 2, 20, 520))
    }

    @Test
    fun repeatedDownAndUpKeepTheFocusedRowAtTheNearestScreenEdge() {
        var scroll = 0
        val rowHeight = 230
        val rowStride = 248
        for (row in 0..40) {
            val top = 290 + row * rowStride - scroll
            scroll += tvFocusScrollDistance(top, top + rowHeight, 20, 520)
            assertEquals(520, 290 + row * rowStride - scroll + rowHeight)
        }
        for (row in 39 downTo 0) {
            val top = 290 + row * rowStride - scroll
            scroll += tvFocusScrollDistance(top, top + rowHeight, 20, 520)
            assertTrue(290 + row * rowStride - scroll >= 20)
            assertTrue(290 + row * rowStride - scroll + rowHeight <= 520)
        }
    }

    @Test
    fun oversizedCardDoesNotOscillateBetweenItsEdges() {
        assertEquals(0, tvFocusScrollDistance(-20, 600, 20, 520))
        assertEquals(20, tvFocusScrollDistance(40, 660, 20, 520))
        assertEquals(-20, tvFocusScrollDistance(-120, 500, 20, 520))
    }
}
