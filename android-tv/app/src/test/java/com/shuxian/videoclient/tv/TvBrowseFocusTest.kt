package com.shuxian.videoclient.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class TvBrowseFocusTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var listState: TvListState
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var rootView: View
    private var pageBackCount = 0
    private var openedHistoryKey: String? = null
    private val deletedHistoryKeys = mutableListOf<String>()

    private fun showBrowse(historyCount: Int = 12) {
        compose.setContent {
            rootView = LocalView.current
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { pageBackCount++ }
            val state = remember { TvListState() }
            listState = state
            val repository = remember { VodRepository() }
            val scope = rememberCoroutineScope()
            val catalog = remember { TvCatalogState(repository, scope) }
            var section by remember { mutableStateOf("movie") }
            var tag by remember { mutableStateOf("热门") }
            var category by remember { mutableStateOf<Int?>(null) }
            var history by remember {
                mutableStateOf((1..historyCount).map { index ->
                    TvHistoryEntry(
                        VideoItem("$index", "记录 $index", "", "", "", "测试源", "test", ""),
                        "默认线路", 0, "第 1 集", 0, 60000, 3600000, index.toLong(),
                    )
                })
            }
            // 与页面加载流程一样，在栏目/分类改变后重置列表；测试不发起网络请求。
            LaunchedEffect(section, tag, category) { state.reset() }
            TvBrowseScreen(
                state, catalog, repository, section, tag,
                VodSource("测试源", "test", false, true),
                listOf(VodCategory(1, "自定义分类", 0)), category, "", history,
                onSection = { section = it; tag = "热门"; category = null },
                onTag = { tag = it }, onCategory = { category = it },
                onSearch = {}, onSettings = {}, onRetryCategories = {}, onOpenPoster = {},
                onOpenHistory = { openedHistoryKey = it.key },
                onDeleteHistory = { key -> deletedHistoryKeys.add(key); history = history.filterNot { it.key == key } },
                onClearHistory = {},
            )
        }
        compose.waitForIdle()
    }

    private fun enterHistory(historyCount: Int = 12) {
        showBrowse(historyCount)
        compose.onNodeWithText("电影").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("播放记录").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("删除全部").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("记录 1").assertIsFocused()
    }

    @Test
    fun confirmingTheRowResumesWithoutPermanentActionButtons() {
        enterHistory()
        compose.onNodeWithText("▶ 继续播放").assertDoesNotExist()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.onNodeWithText("记录 1").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("test:1", openedHistoryKey) }
    }

    @Test
    fun leftRevealsDeleteAndRightRestoresTheSameRow() {
        enterHistory()
        compose.onNodeWithText("记录 1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("删除").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("记录 1").assertIsFocused()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.runOnIdle { assertFalse(listState.deleteFocused) }
    }

    @Test
    fun backClosesDeleteBeforeThePageReceivesBack() {
        enterHistory()
        compose.onNodeWithText("记录 1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("删除").assertIsFocused()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("记录 1").assertIsFocused()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, pageBackCount)
            backDispatcher.onBackPressed()
            assertEquals(1, pageBackCount)
        }
    }

    @Test
    fun movingDownClosesDeleteAndFocusesTheNextRecord() {
        enterHistory()
        compose.onNodeWithText("记录 1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("删除").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("记录 2").assertIsFocused()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), deletedHistoryKeys) }
    }

    @Test
    fun deletingTheMiddleAndLastRowsReturnsToNormalNeighborRows() {
        enterHistory(historyCount = 3)
        compose.onNodeWithText("记录 1").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("记录 2").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("删除").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("记录 2").assertDoesNotExist()
        compose.onNodeWithText("记录 3").assertIsFocused()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.onNodeWithText("记录 3").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("删除").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("记录 3").assertDoesNotExist()
        compose.onNodeWithText("记录 1").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("test:2", "test:3"), deletedHistoryKeys)
            assertFalse(listState.deleteFocused)
        }
    }

    @Test
    fun deletingTheOnlyRecordReturnsToTheHistoryMenu() {
        enterHistory(historyCount = 1)
        compose.onNodeWithText("记录 1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("删除").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("记录 1").assertDoesNotExist()
        compose.onNodeWithText("删除全部").assertDoesNotExist()
        compose.onNodeWithText("播放记录").assertIsFocused()
    }

    @Test
    fun aQuickLeftThenRightCancelsAnUnfinishedDeleteFocusRequest() {
        enterHistory()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("记录 1").performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("记录 1").assertIsFocused()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.runOnIdle { assertFalse(listState.deleteFocused) }
    }

    @Test
    fun heldConfirmRepeatsAfterDeletionDoNotDeleteOrResumeTheNextRecord() {
        enterHistory(historyCount = 3)
        compose.onNodeWithText("记录 1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("删除").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("记录 2").assertIsFocused()
        compose.runOnIdle {
            // 模拟删除后仍按住遥控器确定，新焦点只能收到长按连发和最终松键。
            val now = SystemClock.uptimeMillis()
            rootView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
            rootView.dispatchKeyEvent(KeyEvent(now, now + 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2))
            rootView.dispatchKeyEvent(KeyEvent(now, now + 150, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        compose.onNodeWithText("记录 2").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("test:1"), deletedHistoryKeys)
            assertNull(openedHistoryKey)
        }
    }

    @Test
    fun movingRightKeepsFocusOnCustomAndItsCategory() {
        showBrowse()
        compose.onNodeWithText("电影").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("电视").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("综艺").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("纪录片").assertIsFocused()
        compose.onNodeWithText("纪录片推荐").assertDoesNotExist()
        compose.onNodeWithText("纪录片").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("自定义").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("全部").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("自定义分类").assertIsFocused()
    }

    @Test
    fun historyIsLeftOfTheDefaultMovieAndDownStaysInHistory() {
        showBrowse()
        compose.onNodeWithText("电影").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("播放记录").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("删除全部").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        // 多次下移会跨过页头回收的位置，焦点应始终跟随下一条记录。
        for (index in 1..7) {
            compose.onNodeWithText("记录 $index").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithText("记录 8").assertIsFocused()
        for (index in 8 downTo 1) {
            compose.onNodeWithText("记录 $index").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        }
        compose.onNodeWithText("删除全部").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("播放记录").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("电影").assertIsFocused()
    }

    @Test
    fun emptyHistoryKeepsTheMenuFocusWhenMovingDown() {
        showBrowse(historyCount = 0)
        compose.onNodeWithText("电影").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("播放记录").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("播放记录").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("电影").assertIsFocused()
    }

    @Test
    fun retargetingWhileScrollingAndReturningToTheMenuKeepsFocus() {
        showBrowse(historyCount = 40)
        compose.onNodeWithText("电影").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("播放记录").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("删除全部").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("记录 1").assertIsFocused()
        // 手动推进帧，让新目标在旧动画尚未结束时到来，覆盖连续下移和反向移动。
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { listState.focusPoster(12) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { listState.focusPoster(18) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { listState.focusPoster(10) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("记录 11").assertIsFocused()
        // 页头恢复应终止剩余滚动，不能被旧动画再次拉回记录区。
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { listState.focusPoster(24) }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { listState.showHeader(TvHeaderFocus.Menu) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("播放记录").assertIsFocused()
    }
}
