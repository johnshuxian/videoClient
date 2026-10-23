package com.shuxian.videoclient.tv

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TvFocusScrollStateTest {
    private val clock = BroadcastFrameClock()
    private var frameTime = 0L
    private var position = 0f
    private val deltas = mutableListOf<Float>()
    private val scrollable = ScrollableState { delta ->
        position += delta
        deltas += delta
        delta
    }

    private suspend fun frame() {
        clock.sendFrame(frameTime)
        frameTime += 16_000_000L
        yield()
    }

    private suspend fun finish(job: Job) {
        repeat(120) {
            if (job.isCompleted) { job.join(); return }
            frame()
        }
        job.cancelAndJoin()
        fail("滚动应收敛到目标，不能一直占用列表")
    }

    @Test
    fun changingTheTargetKeepsMovingAtThePreviousSpeed() = runBlocking(clock) {
        val motion = TvFocusScrollState()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(scrollable, 400f) }
        repeat(5) { frame() }
        val previousDelta = deltas.last()
        val before = position
        first.cancelAndJoin()
        val next = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(scrollable, 400f) }
        assertEquals(before, position, .001f)
        frame()
        // 新目标的第一帧就应延续移动；若重新从零加速，这一帧位移会明显变小。
        assertTrue(deltas.last() >= previousDelta * .9f)
        finish(next)
        assertEquals(before + 400f, position, .5f)
    }

    @Test
    fun aNearbyTargetStopsWithoutOvershootingAndPullingBack() = runBlocking(clock) {
        val motion = TvFocusScrollState()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(scrollable, 400f) }
        repeat(5) { frame() }
        first.cancelAndJoin()
        val target = position + 10f
        deltas.clear()
        val next = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(scrollable, 10f) }
        finish(next)
        assertEquals(target, position, .5f)
        assertTrue(deltas.all { it >= 0f })
    }

    @Test
    fun reversingDirectionBrakesThenSettlesOnTheNewTarget() = runBlocking(clock) {
        val motion = TvFocusScrollState()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(scrollable, 400f) }
        repeat(5) { frame() }
        first.cancelAndJoin()
        val target = position - 120f
        deltas.clear()
        val next = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(scrollable, -120f) }
        finish(next)
        assertEquals(target, position, .5f)
        assertTrue(deltas.any { it > 0f })
        val reversing = deltas.dropWhile { it >= 0f }
        assertTrue(reversing.isNotEmpty())
        assertTrue(reversing.all { it <= 0f })
    }

    @Test
    fun reachingTheListEdgeStopsAndAllowsMovingBack() = runBlocking(clock) {
        val motion = TvFocusScrollState()
        val bounded = ScrollableState { delta ->
            val before = position
            position = (position + delta).coerceIn(0f, 100f)
            position - before
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(bounded, 400f) }
        finish(first)
        assertEquals(100f, position, .001f)
        val next = launch(start = CoroutineStart.UNDISPATCHED) { motion.animateBy(bounded, -80f) }
        finish(next)
        assertEquals(20f, position, .5f)
    }
}
