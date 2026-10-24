package com.shuxian.videoclient.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val movieTags = listOf("热门", "最新", "经典", "可播放", "豆瓣高分", "冷门佳片", "华语", "欧美", "韩国", "日本", "动作", "喜剧", "爱情", "科幻", "悬疑", "恐怖", "成长", "动画")
private val tvTags = listOf("热门", "最新", "国产剧", "港剧", "美剧", "英剧", "日剧", "韩剧", "日本动画", "综艺", "纪录片")
private val sections = listOf("history" to "播放记录", "movie" to "电影", "tv" to "电视", "variety" to "综艺", "documentary" to "纪录片", "custom" to "自定义")

@Composable
internal fun TvBrowseScreen(
    state: TvListState,
    catalog: TvCatalogState,
    repository: VodRepository,
    section: String,
    tag: String,
    source: VodSource?,
    categories: List<VodCategory>,
    categoryId: Int?,
    categoryError: String,
    history: List<TvHistoryEntry>,
    onSection: (String) -> Unit,
    onTag: (String) -> Unit,
    onCategory: (Int?) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRetryCategories: () -> Unit,
    onOpenPoster: (TvPosterItem) -> Unit,
    onOpenHistory: (TvHistoryEntry) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val menuFocus = remember { sections.associate { it.first to FocusRequester() } }
    val searchFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val tagFocus = remember { FocusRequester() }
    val parentFocus = remember { FocusRequester() }
    val childFocus = remember { FocusRequester() }
    val categoryRetryFocus = remember { FocusRequester() }
    val clearHistoryFocus = remember { FocusRequester() }
    val activeMenu = menuFocus.getValue(section)
    val categoryIds = remember(categories) { categories.map { it.id }.toSet() }
    val roots = remember(categories) { categories.filter { it.parentId !in categoryIds } }
    val active = categories.firstOrNull { it.id == categoryId }
    val parentId = active?.parentId?.takeIf { it in categoryIds } ?: categoryId
    val children = remember(categories, parentId) { categories.filter { it.parentId == parentId } }
    val hasTags = section == "movie" || section == "tv"
    val hasCategories = section == "custom" && source != null
    val gridUp = when {
        hasTags -> tagFocus
        hasCategories && children.isNotEmpty() -> childFocus
        hasCategories -> parentFocus
        else -> activeMenu
    }
    val categoryFailed = section == "custom" && categoryError.isNotEmpty()
    val posterKeys = remember(catalog.items) { catalog.items.map { it.key } }
    val historyKeys = remember(history) { history.map { it.key } }
    fun enterContent() { state.enterPosters(if (section == "history") historyKeys else posterKeys) }

    LaunchedEffect(state.headerRequest) {
        if (state.focusedKey == null && state.restoring) {
            state.grid.scrollToItem(0)
            snapshotFlow { state.grid.layoutInfo.visibleItemsInfo.any { it.key == "header" } }.first { it }
            withFrameNanos { }
            val target = when (state.headerFocus) {
                TvHeaderFocus.Search -> searchFocus
                TvHeaderFocus.Settings -> settingsFocus
                TvHeaderFocus.Filters -> gridUp
                TvHeaderFocus.ParentFilter -> if (hasCategories) parentFocus else activeMenu
                TvHeaderFocus.ChildFilter -> if (children.isNotEmpty()) childFocus else gridUp
                TvHeaderFocus.CategoryRetry -> if (categoryFailed) categoryRetryFocus else gridUp
                TvHeaderFocus.ClearHistory -> if (section == "history" && history.isNotEmpty()) clearHistoryFocus else activeMenu
                else -> activeMenu
            }
            target.requestFocus()
            // 请求焦点也会触发菜单/分类回调，恢复完成前不让回调再次切换内容。
            state.restorationComplete()
        }
    }

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Label("影窗 TV", 24, white, bold = true)
                FocusButton("搜索电影、电视、综艺", icon = R.drawable.tv_search, outlined = true,
                    modifier = Modifier.widthIn(min = 260.dp)
                        .focusRequester(searchFocus).focusProperties {
                        up = FocusRequester.Cancel; down = activeMenu; left = FocusRequester.Cancel; right = settingsFocus
                    }, onFocus = { state.focusHeader(TvHeaderFocus.Search) }, onClick = {
                        state.leaveFromHeader(TvHeaderFocus.Search)
                        onSearch()
                    })
                Spacer(Modifier.weight(1f))
                FocusButton("设置", icon = R.drawable.tv_settings, modifier = Modifier.focusRequester(settingsFocus).focusProperties {
                    up = FocusRequester.Cancel; down = activeMenu; left = searchFocus; right = FocusRequester.Cancel
                }, onFocus = { state.focusHeader(TvHeaderFocus.Settings) }, onClick = {
                        state.leaveFromHeader(TvHeaderFocus.Settings)
                        onSettings()
                    })
            }
            Row(Modifier.padding(top = 16.dp, bottom = 8.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEachIndexed { index, (value, label) ->
                    FocusButton(label, selected = section == value, tab = true, fontSize = 20,
                        modifier = Modifier.height(40.dp).focusRequester(menuFocus.getValue(value))
                            .focusProperties {
                                up = searchFocus
                                left = sections.getOrNull(index - 1)?.let { menuFocus.getValue(it.first) } ?: FocusRequester.Cancel
                                right = sections.getOrNull(index + 1)?.let { menuFocus.getValue(it.first) } ?: FocusRequester.Cancel
                            }.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    when {
                                        hasTags -> tagFocus.requestFocus()
                                        hasCategories -> parentFocus.requestFocus()
                                        section == "history" && history.isNotEmpty() -> clearHistoryFocus.requestFocus()
                                        else -> enterContent()
                                    }
                                    true
                                } else false
                            },
                        onFocus = {
                            if (state.focusHeader(TvHeaderFocus.Menu)) onSection(value)
                        }, onClick = { onSection(value) })
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF303036)))
            if (hasTags) key(section) {
                val tags = if (section == "movie") movieTags else tvTags
                TvFilterRow(tags.map { it to it }, tag, tagFocus, activeMenu, onDown = { enterContent() }) {
                    if (state.focusHeader(TvHeaderFocus.Filters)) onTag(it)
                }
            }
            if (hasCategories) {
                TvFilterRow(listOf("all" to "全部") + roots.map { it.id.toString() to it.name },
                    parentId?.toString() ?: "all", parentFocus, activeMenu,
                    onDown = {
                        when {
                            children.isNotEmpty() -> childFocus.requestFocus()
                            categoryFailed -> categoryRetryFocus.requestFocus()
                            else -> enterContent()
                        }
                    }) { selected ->
                    if (state.focusHeader(TvHeaderFocus.ParentFilter)) {
                        val id = selected.toIntOrNull()
                        if (id != parentId) onCategory(categories.firstOrNull { it.parentId == id }?.id ?: id)
                    }
                }
                if (children.isNotEmpty()) key(parentId) {
                    TvFilterRow(children.map { it.id.toString() to it.name }, categoryId.toString(), childFocus, parentFocus,
                        onDown = { if (categoryFailed) categoryRetryFocus.requestFocus() else enterContent() }) {
                        if (state.focusHeader(TvHeaderFocus.ChildFilter)) onCategory(it.toInt())
                    }
                }
            }
            if (section == "custom" && source == null) Label("请在设置中导入 vod.json 并启用点播源。", 22, muted, Modifier.padding(vertical = 12.dp))
            if (categoryFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                Label("分类加载失败：$categoryError", 20, danger, Modifier.weight(1f), maxLines = 1)
                FocusButton("重试分类", modifier = Modifier.focusRequester(categoryRetryFocus)
                    .focusProperties { up = gridUp }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { enterContent(); true } else false
                    }, onFocus = { state.focusHeader(TvHeaderFocus.CategoryRetry) },
                    onClick = { gridUp.requestFocus(); onRetryCategories() })
            }
        }
    }
    if (section == "history") {
        TvHistoryList(state, history, repository, activeMenu, clearHistoryFocus,
            onOpenHistory, onDeleteHistory, onClearHistory, Modifier.fillMaxSize(), header)
    } else {
        TvPosterGrid(state, catalog.items, repository, catalog.loading, catalog.hasMore, catalog.error,
            onLoadMore = catalog::loadNextPage, onOpen = onOpenPoster, modifier = Modifier.fillMaxSize(), header = header,
            upHeader = if (categoryFailed) TvHeaderFocus.CategoryRetry else TvHeaderFocus.Filters)
    }
}

@Composable
private fun TvFilterRow(
    options: List<Pair<String, String>>,
    selected: String,
    selectedFocus: FocusRequester,
    up: FocusRequester,
    onDown: () -> Unit,
    onSelect: (String) -> Unit,
) {
    // 返回列表时让已选标签从一开始就在横向可视区域，焦点不依赖首屏标签。
    val row = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    LazyRow(state = row, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
        items(options, key = { it.first }) { (id, label) ->
            FocusButton(label, selected = id == selected, tab = true,
                modifier = Modifier.then(if (id == selected) Modifier.focusRequester(selectedFocus) else Modifier)
                    .focusProperties {
                        this.up = up
                        if (id == options.first().first) left = FocusRequester.Cancel
                        if (id == options.last().first) right = FocusRequester.Cancel
                    }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { onDown(); true } else false
                    }, onFocus = { onSelect(id) }, onClick = { onSelect(id) })
        }
    }
}

@Composable
internal fun TvSearchScreen(
    state: TvListState,
    repository: VodRepository,
    query: String,
    term: String,
    results: List<TvPosterItem>,
    loading: Boolean,
    completed: Int,
    total: Int,
    failures: List<String>,
    error: String,
    exact: Boolean,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpen: (TvPosterItem) -> Unit,
) {
    val inputFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val resultKeys = remember(results) { results.map { it.key } }
    fun enterResults() {
        if (results.isNotEmpty()) state.enterPosters(resultKeys)
    }
    LaunchedEffect(state.headerRequest) {
        if (state.focusedKey == null && state.restoring) {
            state.grid.scrollToItem(0)
            snapshotFlow { state.grid.layoutInfo.visibleItemsInfo.any { it.key == "header" } }.first { it }
            withFrameNanos { }
            val target = when (state.headerFocus) {
                TvHeaderFocus.SearchSubmit -> submitFocus
                else -> inputFocus
            }
            target.requestFocus()
            state.restorationComplete()
        }
    }
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Label("搜索", 24, white, bold = true)
                Label("影窗 TV", 20, muted)
            }
            // 输入框与搜索按钮统一高度，结果区保持六列和原有方向键顺序。
            Row(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SearchInput(query, onQuery, "输入中文片名", Modifier.weight(1f).height(44.dp)
                    .focusRequester(inputFocus).focusProperties {
                        up = FocusRequester.Cancel; left = FocusRequester.Cancel; right = submitFocus
                    }.onFocusChanged { if (it.isFocused) state.focusHeader(TvHeaderFocus.Search) }, ImeAction.Search,
                    onDown = { enterResults() }, icon = R.drawable.tv_search) { onSearch(query) }
                FocusButton("搜索", primary = true, modifier = Modifier.width(88.dp).height(44.dp).focusRequester(submitFocus).focusProperties {
                    up = FocusRequester.Cancel; left = inputFocus; right = FocusRequester.Cancel
                }.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        enterResults()
                        true
                    } else false
                }, onFocus = { state.focusHeader(TvHeaderFocus.SearchSubmit) }, onClick = { onSearch(query) })
            }
            if (term.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("“$term” · ${results.size} 个结果", 24, white, Modifier.weight(1f), bold = true, maxLines = 1)
                    if (total > 0) {
                        val status = when { loading -> "搜索中"; error.isNotEmpty() -> "搜索中断"; else -> "搜索完成" }
                        Label("$status · $completed / $total 个源", 20, muted)
                    }
                }
                val message = when {
                    error.isNotEmpty() -> error
                    total == 0 -> "请先在设置中导入并启用非成人源。"
                    !loading && results.isEmpty() && failures.size == total -> "所有点播源均搜索失败，请检查网络或源地址。"
                    !loading && results.isEmpty() -> if (exact) "片名没有包含完整关键词，可缩短关键词或关闭精确搜索。" else "没有找到相关影片，可以试试其他关键词。"
                    else -> ""
                }
                if (message.isNotEmpty()) Label(message, 20, muted, Modifier.padding(top = 8.dp))
            }
        }
    }
    TvPosterGrid(state, results, repository, loading = false, hasMore = false, error = "",
        onLoadMore = {}, onOpen = onOpen, modifier = Modifier.fillMaxSize(), showEmpty = false,
        upHeader = TvHeaderFocus.Search, header = header)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun rememberTvListBringIntoViewSpec(state: TvListState): BringIntoViewSpec {
    val defaultSpec = LocalBringIntoViewSpec.current
    return remember(state, defaultSpec) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // 卡片由下方逻辑定位，避免聚焦时系统再发起一次滚动；页头仍沿用系统规则。
                return if (state.focusedKey != null || state.pendingIndex != null) 0f
                else defaultSpec.calculateScrollDistance(offset, size, containerSize)
            }
        }
    }
}

// 目标进入懒布局后便聚焦；滚动独立追随目标，连续移动沿用同一份动画状态。
@Composable
private fun RestoreListFocus(
    state: TvListState,
    keys: List<String>,
    requesters: Map<String, FocusRequester>,
    deleteRequesters: Map<String, FocusRequester> = emptyMap(),
    waitForItems: Boolean = false,
    columns: Int = TV_POSTER_COLUMNS,
    onMissing: () -> Unit,
) {
    val scroll = remember(state) { TvFocusScrollState() }
    LaunchedEffect(state, state.headerRequest, state.revision) {
        if (state.focusedKey != null) {
            val index = keys.indexOf(state.focusedKey)
            if (index >= 0) state.focusPoster(index, state.deleteFocused)
            else {
                withFrameNanos { }
                state.restorationComplete()
                onMissing()
            }
        }
    }
    LaunchedEffect(state, state.pendingIndex, state.pendingDelete, keys, waitForItems) {
        val index = state.pendingIndex ?: run { scroll.reset(); return@LaunchedEffect }
        var scrollJob: Job? = null
        val target = keys.getOrNull(index)
        if (target != null) {
            val layout = state.grid.layoutInfo
            if (state.restoring) {
                scroll.reset()
                // 返回页面时沿用原来的滚动位置，仅在目标尚未布局时直接定位。
                if (layout.visibleItemsInfo.none { it.key == target }) state.grid.scrollToItem(index + 1)
            } else {
                val anchor = layout.visibleItemsInfo.firstOrNull { it.key == target }
                    ?: layout.visibleItemsInfo.firstOrNull { it.index > 0 && keys.getOrNull(it.index - 1) == it.key }
                if (anchor != null) {
                    // 页头占一个全宽条目，海报从索引 1 开始。屏幕外的相邻排可用已布局排的高度计算位置。
                    val rowOffset = index / columns - (anchor.index - 1) / columns
                    val top = anchor.offset.y + rowOffset * (anchor.size.height + layout.mainAxisItemSpacing)
                    val distance = tvFocusScrollDistance(top, top + anchor.size.height,
                        layout.viewportStartOffset + layout.beforeContentPadding,
                        layout.viewportEndOffset - layout.afterContentPadding)
                    // 只补足越过屏幕边缘的距离，保留上一排作为视觉参照，不再把下一排强行拉到顶部。
                    scrollJob = launch { scroll.animateBy(state.grid, distance.toFloat()) }
                } else {
                    scroll.reset()
                    // 长页头占满屏幕时还没有卡片尺寸，直接进入内容区并把目标放在视口中部。
                    state.grid.scrollToItem(index + 1, scrollOffset = -layout.viewportSize.height / 2)
                }
            }
            snapshotFlow { state.grid.layoutInfo.visibleItemsInfo.any { it.key == target } }.first { it }
            val delete = state.pendingDelete
            // 已挂载的卡片直接聚焦；只有新进入布局的卡片需要等 FocusRequester 挂载。
            if ((if (delete) deleteRequesters[target] else requesters[target]) == null) withFrameNanos { }
            val requester = if (delete) deleteRequesters[target] else requesters[target]
            state.restorationComplete()
            if (requester?.requestFocus() == true) state.posterFocused(target, delete) else onMissing()
        } else if (waitForItems) {
            // 在首屏加载期间按下方向键，等结果布局完成后再进入卡片。
            return@LaunchedEffect
        } else {
            state.restorationComplete()
            onMissing()
        }
        // 焦点已提前交给目标卡片；等过渡结束再清理，避免清空 pendingIndex 时取消动画。
        // 新按键会更新目标并取消整个旧任务，不需要等这次过渡结束。
        scrollJob?.join()
        state.focusCompleted()
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvPosterGrid(
    state: TvListState,
    items: List<TvPosterItem>,
    repository: VodRepository,
    loading: Boolean,
    hasMore: Boolean,
    error: String,
    onLoadMore: () -> Unit,
    onOpen: (TvPosterItem) -> Unit,
    modifier: Modifier,
    showEmpty: Boolean = true,
    upHeader: TvHeaderFocus = TvHeaderFocus.Filters,
    header: @Composable () -> Unit,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val retryFocus = remember { FocusRequester() }
    val keys = remember(items) { items.map { it.key } }
    var waitingRow by remember(state.revision) { mutableStateOf<Pair<Int, Int>?>(null) }
    RestoreListFocus(state, keys, requesters, waitForItems = loading) {
        if (error.isNotEmpty()) retryFocus.requestFocus() else state.showHeader(upHeader)
    }
    LaunchedEffect(items.size, loading, error, hasMore) {
        val (index, previousCount) = waitingRow ?: return@LaunchedEffect
        if (items.size > previousCount) {
            state.focusPoster(minOf(index, items.lastIndex))
            waitingRow = null
        } else if (!loading && (error.isNotEmpty() || !hasMore)) waitingRow = null
    }
    // 仅观察是否接近末尾，不让逐帧滚动偏移触发整个页面重组。
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(items.size, loading, hasMore, error) {
        if (items.isEmpty() || loading || !hasMore || error.isNotEmpty()) return@LaunchedEffect
        snapshotFlow {
            val layout = state.grid.layoutInfo
            layout.totalItemsCount >= items.size + 1 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= items.size + 1 - TV_POSTER_COLUMNS * 4
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) loadMore() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val posterWidth = with(LocalDensity.current) {
            (((maxWidth - 96.dp - 16.dp * 5) / TV_POSTER_COLUMNS) - 14.dp).roundToPx().coerceAtLeast(64)
        }
        val visibleKeys by remember(state) { derivedStateOf { state.grid.layoutInfo.visibleItemsInfo.map { it.key }.toSet() } }
        LaunchedEffect(items, posterWidth) {
            snapshotFlow { state.grid.layoutInfo.visibleItemsInfo.filter {
                it.contentType == "poster" && it.index - 1 in items.indices && items[it.index - 1].key == it.key
            }.map { it.index - 1 } }
                .map { visible ->
                    if (visible.isEmpty()) emptyList() else {
                        tvPosterPrefetchIndices(visible.first(), visible.last(), items.size)
                    }
                }.distinctUntilChanged().collectLatest { indices ->
                    repository.prefetchPosters(indices.map { items[it].poster }, posterWidth)
                }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberTvListBringIntoViewSpec(state)) {
            LazyVerticalGrid(columns = GridCells.Fixed(TV_POSTER_COLUMNS), state = state.grid, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
                itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, _ -> "poster" }) { index, item ->
                    val focus = remember { FocusRequester() }
                    DisposableEffect(item.key) { requesters[item.key] = focus; onDispose { requesters.remove(item.key) } }
                    PosterCard(item.title, item.poster, item.subtitle, repository,
                        modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
                            val direction = when (event.key) {
                                Key.DirectionLeft -> TvGridDirection.Left; Key.DirectionRight -> TvGridDirection.Right
                                Key.DirectionUp -> TvGridDirection.Up; Key.DirectionDown -> TvGridDirection.Down
                                else -> null
                            }
                            if (direction == null) false else {
                                if (event.type == KeyEventType.KeyUp) state.releaseDirectionKey()
                                if (event.type == KeyEventType.KeyDown &&
                                    state.acceptDirectionKey(event.nativeKeyEvent.repeatCount, event.nativeKeyEvent.eventTime)) {
                                    val fromIndex = state.pendingIndex ?: index
                                    val target = state.posterTarget(index, items.size, direction)
                                    when {
                                        target == -1 -> { waitingRow = null; state.showHeader(upHeader) }
                                        target != fromIndex -> { waitingRow = null; state.focusPoster(target) }
                                        direction == TvGridDirection.Down && error.isNotEmpty() -> retryFocus.requestFocus()
                                        direction == TvGridDirection.Down && hasMore && error.isEmpty() -> {
                                            waitingRow = fromIndex + TV_POSTER_COLUMNS to items.size
                                            onLoadMore()
                                        }
                                    }
                                }
                                true
                            }
                        }, targetWidth = posterWidth, visible = item.key in visibleKeys, movingQuickly = state.directionHeld,
                        onFocus = { state.posterFocused(item.key) }, onClick = { state.leaveFromPoster(item.key); onOpen(item) })
                }
                if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                if (error.isNotEmpty()) item(key = "error", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Label(error, 20, Color(0xFFFFA6A6), Modifier.weight(1f), maxLines = 2)
                        FocusButton("重试", tab = true, modifier = Modifier.focusRequester(retryFocus).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                if (items.isEmpty()) state.showHeader(upHeader) else state.focusPoster(items.lastIndex)
                                true
                            } else false
                        }, onClick = {
                            if (items.isEmpty()) state.showHeader(upHeader) else state.focusPoster(items.lastIndex)
                            waitingRow = items.size to items.size
                            onLoadMore()
                        })
                    }
                } else if (showEmpty && items.isEmpty() && !loading && !hasMore) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Label("暂无影片", 22, muted, Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvHistoryList(
    state: TvListState,
    history: List<TvHistoryEntry>,
    repository: VodRepository,
    up: FocusRequester,
    clearFocus: FocusRequester,
    onOpen: (TvHistoryEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
    header: @Composable () -> Unit,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val deleteRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val keys = remember(history) { history.map { it.key } }
    val scope = rememberCoroutineScope()
    var revealedKey by remember { mutableStateOf<String?>(null) }
    var deletingKey by remember { mutableStateOf<String?>(null) }

    fun closeDelete() {
        val key = revealedKey ?: return
        val index = keys.indexOf(key)
        if (index >= 0) {
            // 先覆盖尚未完成的删除焦点请求，再把焦点还给行，最后收起操作区。
            state.focusPoster(index)
            requesters[key]?.requestFocus()
        }
        revealedKey = null
    }

    BackHandler(enabled = revealedKey != null || deletingKey != null) {
        // 删除区打开时，返回不能继续传给页面的回顶/退出逻辑。
        if (deletingKey == null) closeDelete()
    }
    LaunchedEffect(state.focusedKey, state.pendingIndex) {
        if (state.pendingIndex == null && revealedKey != state.focusedKey && deletingKey == null) revealedKey = null
    }
    RestoreListFocus(state, keys, requesters, deleteRequesters, columns = 1) {
        state.showHeader(TvHeaderFocus.Menu)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberTvListBringIntoViewSpec(state)) {
        Box(modifier) {
            LazyVerticalGrid(columns = GridCells.Fixed(1), state = state.grid, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 48.dp, bottom = 64.dp)) {
                item(key = "header", contentType = "header") {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        header()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label("播放记录 · ${history.size}", 24, white, Modifier.weight(1f), bold = true)
                            if (history.isNotEmpty()) FocusButton("删除全部", destructive = true,
                                modifier = Modifier.focusRequester(clearFocus).focusProperties { this.up = up }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { state.enterPosters(keys); true } else false
                                    }, onFocus = { state.focusHeader(TvHeaderFocus.ClearHistory) }, onClick = {
                                        state.leaveFromHeader(TvHeaderFocus.ClearHistory)
                                        onClear()
                                    })
                        }
                        if (history.isEmpty()) Label("还没有播放记录，播放影片后会出现在这里。", 22, muted, Modifier.padding(top = 16.dp))
                    }
                }
                itemsIndexed(history, key = { _, entry -> entry.key }, contentType = { _, _ -> "history" }) { index, entry ->
                    val focus = remember { FocusRequester() }
                    val deleteFocus = remember { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    var deleteFocused by remember { mutableStateOf(false) }
                    val revealed = revealedKey == entry.key
                    val slide = animateFloatAsState(if (revealed) 1f else 0f, tween(200), label = "history delete reveal")
                    val removal = remember { Animatable(1f) }
                    val shape = RoundedCornerShape(10.dp)
                    DisposableEffect(entry.key) {
                        requesters[entry.key] = focus
                        onDispose { requesters.remove(entry.key) }
                    }

                    fun resumeEntry() {
                        if (revealedKey != null || deletingKey != null) return
                        state.leaveFromPoster(entry.key)
                        onOpen(entry)
                    }
                    fun deleteEntry() {
                        if (deletingKey != null) return
                        deletingKey = entry.key
                        state.pauseTracking()
                        scope.launch {
                            // 高度和透明度共用进度，下方记录随当前行收缩自然补位。
                            removal.animateTo(0f, tween(180))
                            onDelete(entry.key)
                            revealedKey = null
                            deletingKey = null
                            if (history.size > 1) state.focusPoster(minOf(index, history.size - 2))
                            else state.backToTop(TvHeaderFocus.Menu)
                        }
                    }

                    Box(Modifier.fillMaxWidth()
                        .graphicsLayer { alpha = removal.value; clip = true }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, (placeable.height * removal.value).roundToInt()) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                        // 把行间距也放进收缩区域，删除结束时不会额外跳动一个间距。
                        .padding(bottom = 12.dp)
                        .onPreviewKeyEvent { event ->
                            val code = event.nativeKeyEvent.keyCode
                            val confirm = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                            if (confirm) {
                                // 同时消费按下和松开，只接受首次按下，避免按住确定后连删或误续播下一行。
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && deletingKey == null) {
                                    if (revealed && deleteFocused) deleteEntry()
                                    else if (!revealed) resumeEntry()
                                }
                                true
                            } else if (event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                                event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                                if (event.type == KeyEventType.KeyUp) state.releaseDirectionKey()
                                if (event.type == KeyEventType.KeyDown && deletingKey == null &&
                                    state.acceptDirectionKey(event.nativeKeyEvent.repeatCount, event.nativeKeyEvent.eventTime)) {
                                    val fromIndex = state.pendingIndex ?: index
                                    when (event.key) {
                                        Key.DirectionLeft -> if (event.nativeKeyEvent.repeatCount == 0) {
                                            revealedKey = keys.getOrNull(fromIndex)
                                            state.focusPoster(fromIndex, delete = true)
                                        }
                                        Key.DirectionRight -> if (revealedKey != null) closeDelete()
                                        Key.DirectionUp, Key.DirectionDown -> {
                                            val direction = if (event.key == Key.DirectionUp) TvGridDirection.Up else TvGridDirection.Down
                                            val target = state.posterTarget(index, history.size, direction, columns = 1)
                                            if (revealedKey != null) closeDelete()
                                            if (target == -1) state.showHeader(TvHeaderFocus.ClearHistory)
                                            else state.focusPoster(target)
                                        }
                                        else -> Unit
                                    }
                                }
                                true
                            } else false
                    }) {
                        // 删除与整行是并列的焦点目标，普通状态下操作区不参与焦点搜索。
                        if (revealed || slide.value > 0f) {
                            DisposableEffect(entry.key) {
                                deleteRequesters[entry.key] = deleteFocus
                                onDispose { deleteRequesters.remove(entry.key) }
                            }
                            Box(Modifier.matchParentSize()) {
                                Column(Modifier.align(Alignment.CenterEnd).width(104.dp).fillMaxHeight()
                                    .graphicsLayer { alpha = slide.value }
                                    .clip(shape).background(if (deleteFocused) Color(0xFFBC3737) else Color(0xFF742C32))
                                    .border(2.dp, if (deleteFocused) white else Color.Transparent, shape)
                                    .focusRequester(deleteFocus).focusProperties { canFocus = revealed }
                                    .onFocusChanged {
                                        deleteFocused = it.isFocused
                                        if (it.isFocused) state.posterFocused(entry.key, delete = true)
                                    }
                                    .clickable { if (revealed) deleteEntry() }.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                                    Image(painterResource(R.drawable.tv_delete), contentDescription = null,
                                        colorFilter = ColorFilter.tint(white), modifier = Modifier.size(26.dp))
                                    Label("删除", 18, white, bold = true, maxLines = 1)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth()
                            .graphicsLayer { translationX = -116.dp.toPx() * slide.value }
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) state.posterFocused(entry.key) }
                            .clip(shape).background(if (focused) white else panel)
                            .clickable { if (revealedKey != null) closeDelete() else resumeEntry() }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Poster(entry.item.poster, repository, Modifier.width(80.dp).height(112.dp))
                            Column(Modifier.weight(1f)) {
                                Label(entry.item.title, 24, if (focused) focusInk else white, bold = true, maxLines = 1)
                                Label("${entry.episodeLabel} · ${entry.item.sourceName}", 20, if (focused) Color(0xFF53535E) else muted, Modifier.padding(top = 7.dp), maxLines = 1)
                                val seconds = entry.positionMs / 1000
                                Label("看到 %02d:%02d".format(seconds / 60, seconds % 60), 20, if (focused) Color(0xFF9E410C) else accent, Modifier.padding(top = 7.dp))
                                Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).background(Color(0xFF44444B))) {
                                    val progress = if (entry.durationMs > 0) (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f) else 0f
                                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent))
                                }
                            }
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                Label(if (revealedKey != null) "确定：删除记录　→ / 返回：取消" else "确定：继续播放　←：删除",
                    14, muted, Modifier.align(Alignment.BottomCenter)
                        .background(background.copy(alpha = .95f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1)
            }
        }
    }
}
