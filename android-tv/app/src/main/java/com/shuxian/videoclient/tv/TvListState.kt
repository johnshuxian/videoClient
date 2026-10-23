package com.shuxian.videoclient.tv

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class TvHeaderFocus {
    Menu, Search, Settings, Filters, ParentFilter, ChildFilter, CategoryRetry, ClearHistory, SearchSubmit,
}

// 保存实际操作的入口，而不是每次返回都把焦点交给主菜单。
@Stable
@OptIn(ExperimentalFoundationApi::class)
internal class TvListState(initialHeader: TvHeaderFocus = TvHeaderFocus.Menu) {
    // 预布局前方约两排、保留后方约一排；图片另有更大的预取窗口。
    val grid = LazyGridState(cacheWindow = LazyLayoutCacheWindow(ahead = 600.dp, behind = 300.dp))
    var focusedKey by mutableStateOf<String?>(null)
        private set
    var headerFocus by mutableStateOf(initialHeader)
        private set
    var deleteFocused by mutableStateOf(false)
        private set
    var restoring by mutableStateOf(true)
        private set
    var pendingDelete by mutableStateOf(false)
        private set
    private var lastPosterKey: String? = null
    var revision by mutableIntStateOf(0)
        private set
    var pendingIndex by mutableStateOf<Int?>(null)
        private set
    var headerRequest by mutableIntStateOf(0)
        private set
    private var lastDirectionTimeMs = 0L

    fun focusPoster(index: Int, delete: Boolean = false) {
        // 新按键更新同一个目标，滚动任务跟随最新位置，不积压动画，也不丢弃后续移动。
        pendingDelete = delete
        pendingIndex = index
    }

    fun posterTarget(index: Int, count: Int, direction: TvGridDirection, columns: Int = TV_POSTER_COLUMNS): Int =
        tvPosterTarget(pendingIndex ?: index, count, direction, columns)

    fun acceptDirectionKey(repeatCount: Int, eventTimeMs: Long): Boolean {
        // 只限制系统长按连发；每次重新按下都立即响应，避免目标尚未布局时一次跨过太多排。
        if (repeatCount > 0 && eventTimeMs - lastDirectionTimeMs < 100L) return false
        lastDirectionTimeMs = eventTimeMs
        return true
    }

    fun focusCompleted() { pendingIndex = null }

    fun enterPosters(keys: List<String>) {
        // 从海报上移到筛选后再下移，仍回到原来的列；切换分类后才从第一张开始。
        focusPoster(keys.indexOf(lastPosterKey).coerceAtLeast(0))
    }

    fun focusHeader(target: TvHeaderFocus) {
        if (restoring) return
        focusedKey = null
        deleteFocused = false
        headerFocus = target
        focusCompleted()
    }

    fun posterFocused(key: String, delete: Boolean = false) {
        if (restoring) return
        focusedKey = key
        lastPosterKey = key
        deleteFocused = delete
    }

    fun leaveFromPoster(key: String) {
        focusedKey = key
        lastPosterKey = key
        deleteFocused = false
        pauseTracking()
    }

    fun leaveFromHeader(target: TvHeaderFocus) {
        focusedKey = null
        deleteFocused = false
        headerFocus = target
        pauseTracking()
    }

    fun pauseTracking() {
        // 页面重建和弹窗关闭可能先产生自动焦点，恢复完成前不记录这些临时位置。
        restoring = true
        focusCompleted()
    }

    fun restorationComplete() { restoring = false }

    fun restoreFocus() {
        pauseTracking()
        headerRequest++
    }

    fun showHeader(target: TvHeaderFocus) {
        focusedKey = null
        headerFocus = target
        restoreFocus()
    }

    fun reset() {
        revision++
        focusedKey = null
        lastPosterKey = null
        deleteFocused = false
        focusCompleted()
        grid.requestScrollToItem(0)
    }

    fun backToTop(target: TvHeaderFocus = TvHeaderFocus.Filters) {
        reset()
        headerFocus = target
        restoreFocus()
    }
}
