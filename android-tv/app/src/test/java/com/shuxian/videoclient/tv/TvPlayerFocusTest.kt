package com.shuxian.videoclient.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.ui.PlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvPlayerFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun showPlayer(episode: Int = 0): PlayerView {
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            // 只验证真实播放器的按键路由和提示框，不请求外部视频源。
            NativePlayer("file:///tv-focus-test.mp4", "焦点回归",
                listOf(PlayLine("默认", listOf(Episode("第1集", ""), Episode("第2集", "")))), 0, episode,
                0L, 1f, onSpeedChange = {}, onBack = {}, onSelectEpisode = { _, _ -> },
                onNextEpisode = {}, onProgress = { _, _ -> }, onPlaybackStarted = {}, onPlaybackFailure = { _, _ -> })
        }
        compose.waitForIdle()
        val player = checkNotNull(findPlayer(root))
        compose.runOnIdle {
            player.player?.stop()
            player.setControllerAnimationEnabled(false)
            player.controllerShowTimeoutMs = 0
            player.showController()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            // 没有加载真实媒体，手动开放进度条；触控测试窗口也允许原生控件获得焦点。
            val progress = player.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            progress.isEnabled = true
            progress.isFocusableInTouchMode = true
            player.findViewWithTag<View>("next_control").isFocusableInTouchMode = true
            player.findViewWithTag<View>("speed_control").isFocusableInTouchMode = true
            assertTrue(progress.requestFocus())
        }
        compose.waitForIdle()
        return player
    }

    @Test
    fun downFromProgressFocusesNextEpisodeFirst() {
        val player = showPlayer()
        compose.runOnIdle {
            player.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            assertEquals("next_control", player.findFocus()?.tag)
        }
    }

    @Test
    fun lastEpisodeFallsBackToSpeed() {
        val player = showPlayer(episode = 1)
        compose.runOnIdle {
            player.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            assertEquals("speed_control", player.findFocus()?.tag)
        }
    }

    @Test
    fun seekingShowsOnlyTheTargetHint() {
        val player = showPlayer()
        val hint = player.findViewById<View>(R.id.tv_control_hint)
        compose.runOnIdle {
            player.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(View.VISIBLE, hint.visibility)
            val now = SystemClock.uptimeMillis()
            player.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        compose.onNodeWithText("快进 10 秒 · 目标 00:10").assertIsDisplayed()
        compose.onNodeWithText("松手跳转", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            player.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("预览时不能叠加进度条的操作提示", View.INVISIBLE, hint.visibility)
            val now = SystemClock.uptimeMillis()
            val release = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
            player.dispatchKeyEvent(KeyEvent.changeFlags(release, KeyEvent.FLAG_CANCELED))
        }
        compose.runOnIdle {
            player.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("取消预览后恢复普通焦点提示", View.VISIBLE, hint.visibility)
        }
    }

    @Test
    fun quickTapKeepsTheTargetHintForOneSecond() {
        val player = showPlayer()
        compose.runOnIdle {
            // 同一帧内按下并松手，验证提示不依赖预览协程是否已经开始刷新。
            val now = SystemClock.uptimeMillis()
            player.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            player.dispatchKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("快进 10 秒 · 目标 00:10").assertIsDisplayed()
        compose.onNodeWithText("当前", substring = true).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("快进 10 秒 · 目标 00:10").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("快进 10 秒 · 目标 00:10").assertDoesNotExist()
    }

    @Test
    fun releaseKeepsTheFinalTargetAfterHolding() {
        val player = showPlayer()
        var pressedAtMs = 0L
        compose.runOnIdle {
            pressedAtMs = SystemClock.uptimeMillis()
            player.dispatchKeyEvent(KeyEvent(pressedAtMs, pressedAtMs, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("快进 10 秒 · 目标 00:10").assertIsDisplayed()
        compose.runOnIdle {
            // 松手时重算最终目标，即使最后一次预览刷新尚未到来也能保持正确文案。
            player.dispatchKeyEvent(KeyEvent(pressedAtMs, pressedAtMs + 2_000, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("快进 70 秒 · 目标 01:10").assertIsDisplayed()
        compose.onNodeWithText("松手跳转", substring = true).assertDoesNotExist()
    }
}
