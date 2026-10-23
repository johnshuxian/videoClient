package com.shuxian.videoclient.tv

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.ScrollableState
import kotlin.math.abs

// 焦点目标改变会取消旧协程；AnimationState 仍保留上一帧的位置、速度和时间。
// 与每次新建动画不同，下一段从这些状态继续，连续下移时不会重新从静止加速。
internal class TvFocusScrollState {
    private var animation = AnimationState(0f)

    fun reset() { animation = AnimationState(0f) }

    suspend fun animateBy(scrollable: ScrollableState, distance: Float) {
        if (distance == 0f) { reset(); return }
        val motion = animation
        var applied = motion.value
        val target = applied + distance
        scrollable.scroll {
            motion.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = 900f, visibilityThreshold = .5f),
                sequentialAnimation = motion.velocity != 0f,
            ) {
                // 新目标离当前位置很近时，旧速度可能越过目标；到位即停，不产生回拉。
                val next = if (distance > 0f) value.coerceAtMost(target) else value.coerceAtLeast(target)
                val delta = next - applied
                val consumed = scrollBy(delta)
                applied = next
                // 列表首尾可能没有足够空间，停止无效滚动；正常的像素取整误差不打断动画。
                if (next != value || abs(delta - consumed) > .5f) cancelAnimation()
            }
        }
        // 只有自然停止或到达边缘才清零。被新目标取消时不会执行这里，速度留给下一段。
        reset()
    }
}
