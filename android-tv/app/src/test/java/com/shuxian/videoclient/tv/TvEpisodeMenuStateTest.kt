package com.shuxian.videoclient.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvEpisodeMenuStateTest {
    @Test
    fun reopeningAfterBrowsingAnotherLineReturnsToThePlayingLine() {
        val menu = TvEpisodeMenuState(currentLineIndex = 2)
        menu.open(currentLineIndex = 2)
        menu.restorationComplete()
        assertTrue(menu.selectLine(0))
        assertEquals(0, menu.selectedLine)

        menu.open(currentLineIndex = 2)
        assertEquals(2, menu.selectedLine)
        assertTrue(menu.restoring)
    }

    @Test
    fun defaultDialogFocusCannotReplaceThePlayingLineWhileOpening() {
        val menu = TvEpisodeMenuState(currentLineIndex = 3)
        menu.open(currentLineIndex = 3)
        assertFalse(menu.selectLine(0))
        assertFalse(menu.selectLine(1))
        assertEquals(3, menu.selectedLine)
        assertTrue(menu.restoring)
    }

    @Test
    fun userCanBrowseOtherLinesAfterTheCurrentEpisodeReceivesFocus() {
        val menu = TvEpisodeMenuState(currentLineIndex = 2)
        menu.open(currentLineIndex = 2)
        menu.restorationComplete()

        assertFalse(menu.restoring)
        assertTrue(menu.selectLine(1))
        assertEquals(1, menu.selectedLine)
    }

    @Test
    fun openingUsesTheLatestPlayingLineInsteadOfTheInitialLine() {
        val menu = TvEpisodeMenuState(currentLineIndex = 0)
        menu.open(currentLineIndex = 2)
        assertEquals(2, menu.selectedLine)
        menu.restorationComplete()

        menu.open(currentLineIndex = 1)
        assertEquals(1, menu.selectedLine)
        assertTrue(menu.restoring)
    }

    @Test
    fun focusingTheSelectedLineDoesNotResetTheEpisodeList() {
        val menu = TvEpisodeMenuState(currentLineIndex = 2)
        menu.open(currentLineIndex = 2)
        menu.restorationComplete()
        assertFalse(menu.selectLine(2))
        assertEquals(2, menu.selectedLine)
    }
}
