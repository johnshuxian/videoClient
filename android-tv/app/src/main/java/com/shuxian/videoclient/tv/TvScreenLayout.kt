package com.shuxian.videoclient.tv

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

@Composable
internal fun TvScreenLayout(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 使用窗口实际像素计算密度，避免厂商 DPI 和系统显示大小改变控件占屏比例。
        val scale = minOf(constraints.maxWidth / 960f, constraints.maxHeight / 540f)
        CompositionLocalProvider(LocalDensity provides Density(scale, fontScale = 1f)) {
            // 固定 16:9 逻辑画布；其它宽高比居中留边，文字、海报和按钮一起等比适配。
            Box(Modifier.requiredSize(960.dp, 540.dp)) { content() }
        }
    }
}

@Composable
internal fun TvDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        // Dialog 创建独立窗口，会重新提供系统密度；内容必须沿用主界面的适配比例。
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

internal fun Context.withTvDensity(density: Density): Context = ContextThemeWrapper(this, 0).apply {
    // 只调整原生播放器的资源上下文，使 XML、动态按钮和文字使用同一比例。
    applyOverrideConfiguration(Configuration().apply {
        densityDpi = (density.density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        fontScale = density.fontScale
    })
    theme.setTo(this@withTvDensity.theme)
}
