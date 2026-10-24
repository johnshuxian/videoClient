package com.shuxian.videoclient.tv

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class TvDetailFocusTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var grid: LazyGridState

    private fun showDetail(search: Boolean = false, restoreEpisode: Int? = null, descriptionRepeats: Int = 8) {
        compose.setContent {
            val state = rememberLazyGridState()
            grid = state
            val play = remember { FocusRequester() }
            val searchFocus = remember { FocusRequester() }
            val episode = remember { FocusRequester() }
            val repository = remember { VodRepository() }
            val detail = remember {
                VideoDetail("详情页滚动回归", "", "2026", "中国", "演员甲、演员乙",
                    "用于验证详情页上下移动后，海报、片名和剧情介绍完整显示。\n".repeat(descriptionRepeats),
                    listOf(PlayLine("默认", (1..120).map { Episode("第${it}集", "") })))
            }
            TvDetailScreen(detail, "测试源", 0, restoreEpisode ?: 0, false, null, "", repository,
                state, play, searchFocus, episode, onPlay = {}, onSearch = {}, onSort = {}, onEpisode = {})
            LaunchedEffect(Unit) {
                if (restoreEpisode != null) state.scrollToItem(restoreEpisode + 1)
                withFrameNanos { }
                (if (restoreEpisode != null) episode else if (search) searchFocus else play).requestFocus()
            }
        }
        compose.waitForIdle()
    }

    private fun moveDownAndBackToActions() {
        repeat(12) { compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) } }
        compose.runOnIdle { assertTrue("下移后应已滚动到选集区", grid.canScrollBackward) }
        for (attempt in 0..24) {
            val actionFocused = listOf("立刻播放", "搜索更多").any { text ->
                compose.onAllNodesWithText(text).fetchSemanticsNodes().any {
                    it.config.getOrNull(SemanticsProperties.Focused) == true
                }
            }
            if (actionFocused) break
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        }
        compose.runOnIdle {
            assertEquals("回到顶部操作时，介绍区必须从顶端完整显示", 0, grid.firstVisibleItemIndex)
            assertEquals("不能只露出播放按钮而裁掉上方海报和标题", 0, grid.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun movingBackToPlayRevealsTheWholeHeader() {
        showDetail()
        moveDownAndBackToActions()
    }

    @Test
    @Config(qualifiers = "w720dp-h405dp-land-mdpi")
    fun movingBackToSearchOnASmallerTvRevealsTheWholeHeader() {
        showDetail(search = true)
        moveDownAndBackToActions()
    }

    @Test
    fun pressingUpWhileAnActionIsAlreadyFocusedThenMovingLeftStillRevealsTheHeader() {
        showDetail(search = true)
        // 模拟触屏滚动后，搜索按钮仍保持焦点、介绍区却已离开顶部的情况。
        compose.runOnIdle { grid.requestScrollToItem(0, 150) }
        compose.onNodeWithText("搜索更多").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionLeft)
        }
        compose.onNodeWithText("立刻播放").assertIsFocused()
        compose.runOnIdle { assertEquals(0, grid.firstVisibleItemScrollOffset) }
    }

    @Test
    fun returningFromPlaybackKeepsTheCurrentEpisodeInView() {
        showDetail(restoreEpisode = 80)
        compose.onNodeWithText("第81集").assertIsFocused()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("第81集").assertIsFocused()
        compose.runOnIdle { assertTrue("播放器返回时不能被页头滚动抢走位置", grid.firstVisibleItemIndex > 0) }
    }

    @Test
    fun fullDescriptionScrollsWithTheRemoteAndClosingRestoresItsButton() {
        showDetail(search = true, descriptionRepeats = 40)
        compose.onNodeWithText("搜索更多").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("完整简介").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("关闭").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        val description = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue("长简介应响应遥控器下键滚动", description.config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertEquals("简介滚动不应带动底层选集列表", 0, grid.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithText("关闭").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithText("完整简介").assertIsFocused()
    }
}
