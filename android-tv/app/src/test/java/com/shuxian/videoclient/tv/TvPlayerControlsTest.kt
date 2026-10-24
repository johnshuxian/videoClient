package com.shuxian.videoclient.tv

import android.app.Activity
import android.animation.StateListAnimator
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.media3.ui.PlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvPlayerControlsTest {
    @Test
    fun resizingBackToFullscreenKeepsTheProgressBarAboveTheButtons() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val player = PlayerView(activity).apply {
            setControllerAnimationEnabled(false)
            controllerShowTimeoutMs = 0
            showController()
        }
        activity.setContentView(player, FrameLayout.LayoutParams(960, 160))
        fun resize(height: Int) {
            player.layoutParams.height = height
            player.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            player.layout(0, 0, 960, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        // Media3 会在退出紧凑布局时重写进度条边距，不能只检查 XML 初始值。
        resize(160)
        resize(540)
        val bottom = player.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val progress = player.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        assertEquals(96, bottom.height)
        assertTrue("进度条不能与底栏按钮重叠", progress.top + progress.height / 2 < bottom.top)
        val background = player.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_controls_background)
        assertTrue("渐变只覆盖底部区域", background.getChildAt(0).height < background.height)
        activity.finish()
    }

    @Test
    fun focusedButtonHasVerticalRoomInTheMainAndOverflowRows() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val player = PlayerView(activity)
        val size = activity.resources.getDimensionPixelSize(R.dimen.tv_player_control_size)
        for (id in listOf(androidx.media3.ui.R.id.exo_basic_controls, androidx.media3.ui.R.id.exo_extra_controls)) {
            val row = player.findViewById<LinearLayout>(id)
            val button = ImageButton(activity)
            row.addView(button, LinearLayout.LayoutParams(size, size))
            row.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.AT_MOST))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            // 焦点放大到 1.05 倍后，白色背景仍须完整留在主栏和更多栏内。
            val expandedHalf = size * .05f / 2
            assertTrue("焦点背景顶部不能裁切", button.top - expandedHalf >= 0)
            assertTrue("焦点背景底部不能裁切", button.bottom + expandedHalf <= row.height)
        }
        activity.finish()
    }

    @Test
    fun focusAndPressDoNotRestoreThePlatformButtonShadow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val button = ImageButton(activity).apply {
            defaultFocusHighlightEnabled = true
            stateListAnimator = StateListAnimator()
            elevation = 8f
            translationZ = 6f
        }
        button.applyTvControlFocus(true)
        button.isPressed = true
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(button.stateListAnimator)
        assertFalse(button.defaultFocusHighlightEnabled)
        assertEquals(0f, button.elevation, 0f)
        assertEquals(0f, button.translationZ, 0f)
        button.applyTvControlFocus(false)
        assertNull(button.background)
        activity.finish()
    }
}
