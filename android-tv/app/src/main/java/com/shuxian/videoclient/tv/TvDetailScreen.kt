package com.shuxian.videoclient.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun TvDetailScreen(
    detail: VideoDetail,
    sourceName: String,
    lineIndex: Int,
    playingIndex: Int,
    descending: Boolean,
    saved: TvHistoryEntry?,
    error: String,
    repository: VodRepository,
    grid: LazyGridState,
    playFocus: FocusRequester,
    searchFocus: FocusRequester,
    episodeFocus: FocusRequester,
    onPlay: () -> Unit,
    onSearch: () -> Unit,
    onSort: (Boolean) -> Unit,
    onEpisode: (Int) -> Unit,
) {
    val episodes = detail.lines.getOrNull(lineIndex)?.episodes.orEmpty()
    var movingUp by remember { mutableStateOf(false) }
    var revealHeader by remember { mutableStateOf(false) }
    var showDescription by remember(detail) { mutableStateOf(false) }
    LaunchedEffect(revealHeader) {
        if (!revealHeader) return@LaunchedEffect
        try {
            // 焦点系统只保证按钮可见；返回顶部操作区时，需要把上方介绍一起滚回。
            grid.animateScrollToItem(0)
        } finally {
            revealHeader = false
        }
    }
    // 介绍占满整行，集数使用紧凑网格，避免短标签被少量列撑成宽按钮。
    LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), state = grid, modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            movingUp = event.key == Key.DirectionUp
            // 重新下移时终止回顶；在顶部左右换按钮时仍需完整露出介绍。
            if (event.key == Key.DirectionDown) revealHeader = false
        }
        false
    },
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "detail-header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (error.isNotEmpty()) Label(error, 22, danger)
                // 只收紧逻辑画布内的尺寸，继续沿用电视端统一的等比缩放。
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Poster(detail.poster, repository, Modifier.width(120.dp).height(168.dp))
                    Column(Modifier.weight(1f)) {
                        Label(detail.title, 26, white, bold = true, maxLines = 2)
                        Label(listOf(detail.year, detail.area, sourceName).filter { it.isNotBlank() }.joinToString(" · "),
                            16, muted, Modifier.padding(top = 6.dp), maxLines = 1)
                        if (detail.actors.isNotBlank()) Label("主演：${detail.actors}", 16, muted, Modifier.padding(top = 4.dp), maxLines = 1)
                        Label(detail.description.ifBlank { "暂无剧情介绍" }, 18, white, Modifier.padding(top = 8.dp), maxLines = 3)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                        // 已在操作区继续按上，也能恢复触屏滚动后被裁掉的页头。
                        if (grid.canScrollBackward) revealHeader = true
                        true
                    } else false
                }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusButton(if (saved != null) "继续播放" else "立刻播放", primary = true,
                        icon = R.drawable.tv_button_play, enabled = episodes.isNotEmpty(), modifier = Modifier.focusRequester(playFocus),
                        onFocus = { if (movingUp && grid.canScrollBackward) revealHeader = true }, onClick = onPlay)
                    FocusButton("搜索更多", icon = R.drawable.tv_button_search, modifier = Modifier.focusRequester(searchFocus),
                        onFocus = { if (movingUp && grid.canScrollBackward) revealHeader = true }, onClick = onSearch)
                    if (detail.description.isNotBlank()) FocusButton("完整简介", tab = true, fontSize = 16,
                        onFocus = { if (movingUp && grid.canScrollBackward) revealHeader = true },
                        onClick = { showDescription = true })
                    // 续播位置与操作按钮共用一行，避免额外占用选集区的高度。
                    if (saved != null) Label("上次看到 ${saved.episodeLabel} · %02d:%02d".format(saved.positionMs / 60_000, saved.positionMs / 1000 % 60),
                        16, muted, Modifier.weight(1f), maxLines = 1)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("选集", 22, white, Modifier.weight(1f), bold = true)
                    Row(Modifier.background(panel, RoundedCornerShape(16.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FocusButton("正序", selected = !descending, tab = true, onClick = { onSort(false) })
                        FocusButton("倒序", selected = descending, tab = true, onClick = { onSort(true) })
                    }
                }
                if (episodes.isEmpty()) Label("该资源没有可播放地址", 22, muted, Modifier.padding(top = 12.dp))
            }
        }
        items(if (descending) episodes.indices.reversed().toList() else episodes.indices.toList(), key = { it }, contentType = { "episode" }) { index ->
            FocusButton(episodes[index].label, selected = index == playingIndex, tab = true, fontSize = 16,
                modifier = if (index == playingIndex.coerceIn(episodes.indices)) Modifier.focusRequester(episodeFocus) else Modifier,
                onClick = { onEpisode(index) })
        }
    }
    if (showDescription) {
        TvDialog(onDismissRequest = { showDescription = false }) {
            val scroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            val closeFocus = remember { FocusRequester() }
            val scrollStep = with(LocalDensity.current) { 72.dp.toPx() }
            Column(Modifier.width(760.dp).heightIn(max = 440.dp)
                .background(panel, RoundedCornerShape(12.dp)).padding(24.dp)
                .onPreviewKeyEvent { event ->
                    // 完整简介独立滚动，遥控器上下键不改变详情页的选集位置。
                    if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                        if (event.type == KeyEventType.KeyDown) scope.launch {
                            scroll.scrollBy(if (event.key == Key.DirectionDown) scrollStep else -scrollStep)
                        }
                        true
                    } else false
                }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Label(detail.title, 24, white, bold = true, maxLines = 2)
                Label(detail.description, 18, white, Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("↑↓ 滚动简介 · 返回关闭", 16, muted, Modifier.weight(1f))
                    FocusButton("关闭", modifier = Modifier.focusRequester(closeFocus), onClick = { showDescription = false })
                }
            }
            LaunchedEffect(Unit) { closeFocus.requestFocus() }
        }
    }
}
