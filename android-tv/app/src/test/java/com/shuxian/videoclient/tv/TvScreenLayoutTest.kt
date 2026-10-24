package com.shuxian.videoclient.tv

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvScreenLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun controlsKeepTheirProportionsAcrossResolutionsAndSystemScales() {
        val displays = listOf(
            IntSize(1280, 720) to Density(1f, 1f),
            IntSize(1920, 1080) to Density(2f, 1f),
            IntSize(1920, 1080) to Density(3f, 1.8f),
            IntSize(3840, 2160) to Density(4f, 1.5f),
            IntSize(1280, 1024) to Density(2f, 2f),
        )
        var display by mutableStateOf(displays.first())
        lateinit var canvasDensity: Density
        compose.setContent {
            val (pixels, systemDensity) = display
            CompositionLocalProvider(LocalDensity provides systemDensity) {
                // 在同一个测试窗口模拟不同像素约束，不能直接用系统 dp 当作屏幕像素。
                Box(Modifier.requiredSize((pixels.width / systemDensity.density).dp,
                    (pixels.height / systemDensity.density).dp)) {
                    TvScreenLayout {
                        canvasDensity = LocalDensity.current
                        Column(Modifier.fillMaxSize().testTag("canvas")) {
                            FocusButton("电影", tab = true, modifier = Modifier.width(120.dp).testTag("button"), onClick = {})
                        }
                    }
                }
            }
        }
        for ((pixels, systemDensity) in displays) {
            compose.runOnIdle { display = pixels to systemDensity }
            val scale = minOf(pixels.width / 960f, pixels.height / 540f)
            val canvas = compose.onNodeWithTag("canvas").fetchSemanticsNode().size
            val button = compose.onNodeWithTag("button").fetchSemanticsNode().size
            assertEquals(960f * scale, canvas.width.toFloat(), 1f)
            assertEquals(540f * scale, canvas.height.toFloat(), 1f)
            assertEquals(120f * scale, button.width.toFloat(), 1f)
            assertEquals(36f * scale, button.height.toFloat(), 1f)
            assertEquals(1f, canvasDensity.fontScale, 0f)
        }
    }

    @Test
    fun dialogKeepsTheMainCanvasDensityInItsSeparateWindow() {
        lateinit var dialogDensity: Density
        compose.setContent {
            Box(Modifier.requiredSize(1920.dp, 1080.dp)) {
                TvScreenLayout {
                    TvDialog(onDismissRequest = {}) {
                        dialogDensity = LocalDensity.current
                        Box(Modifier.size(120.dp, 48.dp).testTag("dialogControl"))
                    }
                }
            }
        }
        val control = compose.onNodeWithTag("dialogControl").fetchSemanticsNode().size
        assertEquals(IntSize(240, 96), control)
        assertEquals(2f, dialogDensity.density, 0f)
        assertEquals(1f, dialogDensity.fontScale, 0f)
    }

    @Test
    fun nativePlayerResourcesUseTheCanvasScaleWithoutChangingSystemResources() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val systemContext = activity.createConfigurationContext(Configuration().apply {
            densityDpi = 480
            fontScale = 1.8f
        })
        val playerContext = systemContext.withTvDensity(Density(2f, 1f))
        val player = PlayerView(playerContext)
        val bottom = player.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val position = player.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
        assertEquals(192, bottom.layoutParams.height)
        assertEquals(112, playerContext.resources.getDimensionPixelSize(R.dimen.tv_player_control_size))
        assertEquals(36f, position.textSize, .5f)
        assertEquals(480, systemContext.resources.configuration.densityDpi)
        assertEquals(1.8f, systemContext.resources.configuration.fontScale, 0f)
        activity.finish()
    }
}
