package com.shuxian.videoclient.tv

import android.view.KeyEvent
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteSeekTest {
    // 记录实际 seek/play 调用，验证预览不会反复请求解码器跳转。
    private class Playback(var positionMs: Long = 600_000L, var durationMs: Long = 7_200_000L, var playing: Boolean = true) {
        val seeks = mutableListOf<Long>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentPosition" -> positionMs
                "getDuration" -> durationMs
                "getPlayWhenReady" -> playing
                "pause" -> { playing = false; null }
                "play" -> { playing = true; null }
                "seekTo" -> { positionMs = args!![0] as Long; seeks += positionMs; null }
                else -> throw AssertionError("预览不应调用 ${method.name}")
            }
        } as Player
    }

    @Test
    fun shortPressSeeksTenSecondsOnlyWhenReleased() {
        val playback = Playback()
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 100L)
        assertFalse(playback.playing)
        assertEquals(610_000L, seek.update(600L))
        assertTrue(playback.seeks.isEmpty())
        seek.finish(600L)
        assertEquals(listOf(610_000L), playback.seeks)
        assertTrue(playback.playing)
        assertFalse(seek.isActive)
        // 重复收到松键也不能重复跳转。
        seek.finish(600L)
        assertEquals(1, playback.seeks.size)
    }

    @Test
    fun holdingAcceleratesContinuouslyAtOneAndThreeSeconds() {
        val playback = Playback()
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        assertEquals(610_000L, seek.update(1_000L))
        assertEquals(670_000L, seek.update(2_000L))
        assertEquals(730_000L, seek.update(3_000L))
        assertEquals(1_450_000L, seek.update(4_000L))
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun previewRefreshFrequencyDoesNotChangeTheDistance() {
        val playback = Playback()
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        for (time in 0L..4_500L step 50L) seek.update(time)
        val frequent = seek.positionMs
        seek.finish(4_500L, commit = false)
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        assertEquals(frequent, seek.update(4_500L))
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun reachingTheEndLeavesTenSecondsAndRewindingStopsAtZero() {
        val playback = Playback(positionMs = 0L)
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        seek.finish(13_000L)
        assertEquals(7_190_000L, playback.positionMs)
        seek.start(KeyEvent.KEYCODE_DPAD_LEFT, 20_000L)
        seek.finish(40_000L)
        assertEquals(0L, playback.positionMs)
    }

    @Test
    fun forwardInsideTheLastTenSecondsDoesNotMoveBackwards() {
        val playback = Playback(positionMs = 7_195_000L)
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 0L)
        seek.finish(5_000L)
        assertEquals(7_195_000L, playback.positionMs)
    }

    @Test
    fun cancellingRestoresPlaybackWithoutSeeking() {
        val playback = Playback()
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_MEDIA_REWIND, 0L)
        seek.update(5_000L)
        seek.finish(5_000L, commit = false)
        assertTrue(playback.seeks.isEmpty())
        assertEquals(600_000L, playback.positionMs)
        assertTrue(playback.playing)
    }

    @Test
    fun pausedPlaybackAndBackgroundCancellationDoNotResume() {
        val playback = Playback(playing = false)
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        seek.finish(500L)
        assertFalse(playback.playing)
        playback.playing = true
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 1_000L)
        seek.finish(5_000L, commit = false, resume = false)
        assertFalse(playback.playing)
        assertEquals(1, playback.seeks.size)
    }

    @Test
    fun unknownDurationKeepsMinuteSpeedAndShortClipsDoNotSlowDown() {
        val playback = Playback(durationMs = 0L)
        val seek = TvRemoteSeek(playback.player)
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        assertEquals(790_000L, seek.update(4_000L))
        seek.finish(4_000L, commit = false)
        playback.positionMs = 0L
        playback.durationMs = 300_000L
        seek.start(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
        assertEquals(190_000L, seek.update(4_000L))
    }
}
