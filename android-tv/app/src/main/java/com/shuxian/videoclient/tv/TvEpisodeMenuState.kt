package com.shuxian.videoclient.tv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 浏览中的线路可以与播放线路不同；每次打开面板时重新回到实际播放线路。
@Stable
internal class TvEpisodeMenuState(currentLineIndex: Int) {
    var selectedLine by mutableIntStateOf(currentLineIndex)
        private set
    var restoring by mutableStateOf(false)
        private set

    fun open(currentLineIndex: Int) {
        selectedLine = currentLineIndex
        restoring = true
    }

    fun selectLine(index: Int): Boolean {
        // Dialog 初次显示时可能自动聚焦第一条线路，恢复当前集之前不能让它覆盖播放线路。
        if (restoring || selectedLine == index) return false
        selectedLine = index
        return true
    }

    fun restorationComplete() { restoring = false }
}
