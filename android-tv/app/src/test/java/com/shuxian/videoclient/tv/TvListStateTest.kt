package com.shuxian.videoclient.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvListStateTest {
    @Test
    fun returningFromSettingsKeepsTheSettingsEntry() {
        val state = TvListState()
        state.restorationComplete()
        state.focusHeader(TvHeaderFocus.Settings)
        state.leaveFromHeader(TvHeaderFocus.Settings)
        state.restoreFocus()
        // 页面刚挂载时的默认焦点不能覆盖返回入口。
        state.focusHeader(TvHeaderFocus.Menu)
        assertEquals(TvHeaderFocus.Settings, state.headerFocus)
        assertTrue(state.restoring)
        state.restorationComplete()
        state.focusHeader(TvHeaderFocus.Settings)
        assertFalse(state.restoring)
    }

    @Test
    fun returningFromSearchKeepsTheHomeSearchEntry() {
        val state = TvListState()
        state.restorationComplete()
        state.leaveFromHeader(TvHeaderFocus.Search)
        state.restoreFocus()
        state.focusHeader(TvHeaderFocus.Menu)
        assertEquals(TvHeaderFocus.Search, state.headerFocus)
        assertNull(state.focusedKey)
    }

    @Test
    fun detailReturnIgnoresTemporaryMenuAndPosterFocus() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("source:23")
        state.leaveFromPoster("source:23")
        state.restoreFocus()
        state.focusHeader(TvHeaderFocus.Menu)
        state.posterFocused("source:0")
        assertEquals("source:23", state.focusedKey)
        state.focusPoster(17)
        state.restorationComplete()
        state.posterFocused("source:23")
        state.focusCompleted()
        assertEquals("source:23", state.focusedKey)
        assertNull(state.pendingIndex)
    }

    @Test
    fun cancellingClearHistoryReturnsToTheClearButton() {
        val state = TvListState()
        state.restorationComplete()
        state.leaveFromHeader(TvHeaderFocus.ClearHistory)
        state.restoreFocus()
        state.posterFocused("history:first")
        assertEquals(TvHeaderFocus.ClearHistory, state.headerFocus)
        assertNull(state.focusedKey)
    }

    @Test
    fun movingBetweenDeleteButtonsKeepsTheActionColumn() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("history:first", delete = true)
        state.focusPoster(1, delete = state.deleteFocused)
        assertTrue(state.pendingDelete)
        state.posterFocused("history:second", delete = true)
        state.focusCompleted()
        assertEquals("history:second", state.focusedKey)
        assertTrue(state.deleteFocused)
    }

    @Test
    fun deletingAnEntryIgnoresAutomaticFocusUntilTheNextDeleteButtonIsReady() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("history:removed", delete = true)
        state.pauseTracking()
        state.focusHeader(TvHeaderFocus.Menu)
        state.focusPoster(2, delete = true)
        state.restorationComplete()
        state.posterFocused("history:next", delete = true)
        state.focusCompleted()
        assertEquals("history:next", state.focusedKey)
        assertTrue(state.deleteFocused)
    }

    @Test
    fun leavingThePageCancelsAnUnfinishedDirectionMove() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("source:4")
        state.focusPoster(10)
        state.leaveFromPoster("source:4")
        assertNull(state.pendingIndex)
        state.restoreFocus()
        state.focusPoster(4)
        assertEquals(4, state.pendingIndex)
    }

    @Test
    fun aNewMoveReplacesThePendingTargetAndActionColumn() {
        val state = TvListState()
        state.restorationComplete()
        state.focusPoster(6)
        state.focusPoster(12, delete = true)
        assertEquals(12, state.pendingIndex)
        assertTrue(state.pendingDelete)
    }

    @Test
    fun repeatedDownMovesAdvanceBeforeTheOldCardLosesFocus() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("source:4")
        repeat(3) {
            state.focusPoster(state.posterTarget(4, 30, TvGridDirection.Down))
        }
        assertEquals(22, state.pendingIndex)
        // 新目标还未进入布局时，确认键仍对应屏幕上实际聚焦的卡片。
        assertEquals("source:4", state.focusedKey)
    }

    @Test
    fun reversingDirectionUsesTheLatestPendingRow() {
        val state = TvListState()
        state.restorationComplete()
        state.focusPoster(18)
        state.focusPoster(state.posterTarget(6, 30, TvGridDirection.Up))
        assertEquals(12, state.pendingIndex)
        state.focusPoster(state.posterTarget(6, 30, TvGridDirection.Left))
        assertEquals(12, state.pendingIndex)
    }

    @Test
    fun completingAMoveMakesTheActualFocusedIndexTheNextStartingPoint() {
        val state = TvListState()
        state.restorationComplete()
        state.focusPoster(18)
        state.posterFocused("source:18")
        state.focusCompleted()
        assertEquals(24, state.posterTarget(18, 30, TvGridDirection.Down))
    }

    @Test
    fun pendingHistoryMovesKeepTheirDeleteColumnAndAdvanceOneRow() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("history:2", delete = true)
        repeat(3) {
            state.focusPoster(state.posterTarget(2, 10, TvGridDirection.Down, columns = 1), delete = true)
        }
        assertEquals(5, state.pendingIndex)
        assertTrue(state.pendingDelete)
        state.focusPoster(5, delete = false)
        assertEquals(5, state.pendingIndex)
        assertFalse(state.pendingDelete)
    }

    @Test
    fun onlyHighFrequencyHeldKeysAreLimited() {
        val state = TvListState()
        assertTrue(state.acceptDirectionKey(repeatCount = 0, eventTimeMs = 1000))
        assertFalse(state.acceptDirectionKey(repeatCount = 1, eventTimeMs = 1040))
        assertFalse(state.acceptDirectionKey(repeatCount = 2, eventTimeMs = 1080))
        assertTrue(state.acceptDirectionKey(repeatCount = 3, eventTimeMs = 1100))
        assertFalse(state.acceptDirectionKey(repeatCount = 4, eventTimeMs = 1120))
        // 快速松开再按和改按反方向都立即响应。
        assertTrue(state.acceptDirectionKey(repeatCount = 0, eventTimeMs = 1130))
        assertTrue(state.acceptDirectionKey(repeatCount = 0, eventTimeMs = 1140))
    }

    @Test
    fun returningToTheHeaderCancelsThePendingMove() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("source:6")
        state.focusPoster(0)
        assertEquals(-1, state.posterTarget(6, 30, TvGridDirection.Up))
        state.showHeader(TvHeaderFocus.Filters)
        assertNull(state.pendingIndex)
        assertNull(state.focusedKey)
        assertTrue(state.restoring)
    }

    @Test
    fun movingUpToFiltersAndDownAgainKeepsThePosterColumn() {
        val state = TvListState()
        val keys = (0 until 24).map { "source:$it" }
        state.restorationComplete()
        state.posterFocused("source:4")
        state.focusHeader(TvHeaderFocus.Filters)
        state.enterPosters(keys)
        assertEquals(4, state.pendingIndex)
    }

    @Test
    fun returningToAnUncomposedHeaderKeepsThePosterBookmark() {
        val state = TvListState()
        val keys = (0 until 24).map { "source:$it" }
        state.restorationComplete()
        state.posterFocused("source:5")
        state.showHeader(TvHeaderFocus.Filters)
        assertTrue(state.restoring)
        assertNull(state.focusedKey)
        state.focusHeader(TvHeaderFocus.Menu)
        assertEquals(TvHeaderFocus.Filters, state.headerFocus)
        state.restorationComplete()
        state.focusHeader(TvHeaderFocus.Filters)
        state.enterPosters(keys)
        assertEquals(5, state.pendingIndex)
    }

    @Test
    fun selectingANewCategoryClearsThePreviousPosterPosition() {
        val state = TvListState()
        state.restorationComplete()
        state.posterFocused("old:4")
        state.focusHeader(TvHeaderFocus.ChildFilter)
        state.reset()
        state.enterPosters((0 until 24).map { "new:$it" })
        assertEquals(0, state.pendingIndex)
        assertEquals(TvHeaderFocus.ChildFilter, state.headerFocus)
    }

    @Test
    fun returningToResultsUsesTheStableKeyAfterTheListOrderChanges() {
        val state = TvListState(TvHeaderFocus.Search)
        state.restorationComplete()
        state.posterFocused("source:target")
        state.focusHeader(TvHeaderFocus.Search)
        state.enterPosters(listOf("source:new", "source:first", "source:target"))
        assertEquals(2, state.pendingIndex)
    }

    @Test
    fun backToTopReturnsToFiltersOrSearchWithoutTheOldPoster() {
        val browse = TvListState()
        browse.restorationComplete()
        browse.posterFocused("source:40")
        browse.backToTop()
        assertNull(browse.focusedKey)
        assertEquals(TvHeaderFocus.Filters, browse.headerFocus)
        assertTrue(browse.restoring)

        val search = TvListState(TvHeaderFocus.Search)
        search.restorationComplete()
        search.posterFocused("search:30")
        search.backToTop(TvHeaderFocus.Search)
        assertNull(search.focusedKey)
        assertEquals(TvHeaderFocus.Search, search.headerFocus)
    }
}
