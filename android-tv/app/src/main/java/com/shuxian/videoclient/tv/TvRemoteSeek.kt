package com.shuxian.videoclient.tv

import android.view.KeyEvent
import androidx.media3.common.Player

// 一次遥控器快进/快退：按住时只计算预览位置，松手才让解码器跳转。
internal class TvRemoteSeek(private val player: Player) {
    var keyCode = KeyEvent.KEYCODE_UNKNOWN
        private set
    val isActive get() = keyCode != KeyEvent.KEYCODE_UNKNOWN
    var forward = true
        private set
    var startPositionMs = 0L
        private set
    var positionMs = 0L
        private set
    var durationMs = 0L
        private set
    private var startedAtMs = 0L
    private var wasPlaying = false

    fun start(key: Int, atMs: Long) {
        if (isActive) finish(atMs, commit = false)
        keyCode = key
        forward = key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        startedAtMs = atMs
        startPositionMs = player.currentPosition.coerceAtLeast(0L)
        wasPlaying = player.playWhenReady
        player.pause()
        update(atMs)
    }

    fun update(atMs: Long): Long {
        if (!isActive) return positionMs
        durationMs = player.duration.coerceAtLeast(0L)
        val heldMs = (atMs - startedAtMs).coerceAtLeast(0L)
        // 按实际经过的时间积分，遥控器连发次数和预览刷新频率都不影响速度。
        val minuteStageMs = (heldMs - 1_000).coerceIn(0L, 2_000L)
        val fastStageMs = (heldMs - 3_000).coerceAtLeast(0L)
        // 短片和暂未获取到时长的视频，进入第三档后也不应反而减速。
        val fastRate = maxOf(60_000L, durationMs / 10)
        val distanceMs = 10_000L + minuteStageMs * 60 + fastStageMs * fastRate / 1_000
        positionMs = if (forward) {
            // 保留片尾 10 秒；已经在片尾范围内时，向前按键不能把位置倒退。
            val end = if (durationMs > 0L) maxOf(startPositionMs, durationMs - 10_000L) else Long.MAX_VALUE
            (startPositionMs + distanceMs).coerceAtMost(end)
        } else (startPositionMs - distanceMs).coerceAtLeast(0L)
        return positionMs
    }

    fun finish(atMs: Long, commit: Boolean = true, resume: Boolean = true) {
        if (!isActive) return
        update(atMs)
        keyCode = KeyEvent.KEYCODE_UNKNOWN
        if (commit) player.seekTo(positionMs)
        if (resume && wasPlaying) player.play()
    }
}
