package com.shuxian.videoclient.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class TvPosterTitleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longTitleUsesItsFullWidthOnlyWhileThePosterIsFocused() {
        // Robolectric 的字体测量与真机不同，使用足够长的文本保证实际超出卡片宽度。
        val title = "这是一个用于验证焦点滚动展示的超长电影名称".repeat(20)
        val posterFocus = FocusRequester()
        val otherFocus = FocusRequester()
        compose.setContent {
            val repository = remember { VodRepository() }
            Column(Modifier.width(160.dp)) {
                PosterCard(title, "", "", repository, Modifier.focusRequester(posterFocus), onClick = {})
                FocusButton("下一项", modifier = Modifier.focusRequester(otherFocus), onClick = {})
            }
        }
        compose.runOnIdle { posterFocus.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText(title).assertIsFocused()
        val focusedLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(focusedLayouts)
        }
        assertEquals(1, focusedLayouts.single().lineCount)
        assertFalse("聚焦时完整片名交给跑马灯绘制，不应先被省略：${focusedLayouts.single().size} / ${focusedLayouts.single().multiParagraph.width}",
            focusedLayouts.single().isLineEllipsized(0))

        compose.runOnIdle { otherFocus.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText("下一项").assertIsFocused()
        val unfocusedLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(unfocusedLayouts)
        }
        assertEquals(1, unfocusedLayouts.single().lineCount)
        // 对比完整文本与失焦后的受限排版宽度，不依赖跑马灯绘制层的瞬时滚动偏移。
        assertTrue("聚焦时应保留完整文本宽度，移开后恢复卡片宽度：${focusedLayouts.single().size} ${focusedLayouts.single().layoutInput.style.color} / ${unfocusedLayouts.single().size} ${unfocusedLayouts.single().layoutInput.style.color}",
            focusedLayouts.single().size.width > unfocusedLayouts.single().size.width)
    }
}
