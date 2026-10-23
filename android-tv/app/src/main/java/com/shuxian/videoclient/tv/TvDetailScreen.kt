package com.shuxian.videoclient.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

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
    // 详情介绍和选集共用懒加载网格，小屏幕也能完整浏览大字号操作区。
    LazyVerticalGrid(columns = GridCells.Adaptive(136.dp), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 42.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "detail-header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (error.isNotEmpty()) Label(error, 20, danger)
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Poster(detail.poster, repository, Modifier.width(128.dp).height(180.dp))
                    Column(Modifier.weight(1f)) {
                        Label(detail.title, 30, white, bold = true)
                        Label(listOf(detail.year, detail.area, sourceName).filter { it.isNotBlank() }.joinToString(" · "),
                            18, muted, Modifier.padding(top = 10.dp), maxLines = 1)
                        if (detail.actors.isNotBlank()) Label("主演：${detail.actors}", 17, muted, Modifier.padding(top = 8.dp), maxLines = 1)
                        Label(detail.description.ifBlank { "暂无剧情介绍" }, 17, white, Modifier.padding(top = 10.dp), maxLines = 3)
                    }
                }
                Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FocusButton(if (saved != null) "继续播放" else "立刻播放", primary = true, fontSize = 22,
                        icon = R.drawable.tv_player_play, enabled = episodes.isNotEmpty(), modifier = Modifier.focusRequester(playFocus), onClick = onPlay)
                    FocusButton("搜索其他资源", icon = R.drawable.tv_search, modifier = Modifier.focusRequester(searchFocus), onClick = onSearch)
                }
                if (saved != null) Label("上次看到 ${saved.episodeLabel} · %02d:%02d".format(saved.positionMs / 60_000, saved.positionMs / 1000 % 60),
                    17, muted, Modifier.padding(top = 10.dp), maxLines = 1)
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("选集", 22, white, Modifier.weight(1f), bold = true)
                    Row(Modifier.background(panel, RoundedCornerShape(16.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FocusButton("正序", selected = !descending, tab = true, onClick = { onSort(false) })
                        FocusButton("倒序", selected = descending, tab = true, onClick = { onSort(true) })
                    }
                }
                if (episodes.isEmpty()) Label("该资源没有可播放地址", 20, muted, Modifier.padding(top = 12.dp))
            }
        }
        items(if (descending) episodes.indices.reversed().toList() else episodes.indices.toList(), key = { it }, contentType = { "episode" }) { index ->
            FocusButton(episodes[index].label, selected = index == playingIndex, tab = true,
                modifier = if (index == playingIndex.coerceIn(episodes.indices)) Modifier.focusRequester(episodeFocus) else Modifier,
                onClick = { onEpisode(index) })
        }
    }
}
