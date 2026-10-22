package com.shuxian.videoclient.tv

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 电视端用深色内容背景与暖橙焦点色，保证远距离观看时文字和遥控器焦点清楚。
private val background = Color(0xFF101014)
private val panel = Color(0xFF202025)
private val accent = Color(0xFFFF792C)
private val muted = Color(0xFFA6A6AF)
private val white = Color(0xFFF7F7F9)
private val movieTags = listOf("热门", "最新", "经典", "可播放", "豆瓣高分", "冷门佳片", "华语", "欧美", "韩国", "日本", "动作", "喜剧", "爱情", "科幻", "悬疑", "恐怖", "成长", "动画")
private val tvTags = listOf("热门", "最新", "国产剧", "港剧", "美剧", "英剧", "日剧", "韩剧", "日本动画", "综艺", "纪录片")

private data class OtherSourcePrompt(
    val entry: TvHistoryEntry,
    val targetKey: String,
    val lineIndex: Int,
    val episodeIndex: Int,
    val episodeLabel: String,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { TvApp(onExit = { finish() }) }
    }
}

@Composable
private fun TvApp(onExit: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val repository = remember { VodRepository() }
    val settings = remember { TvSettings(context) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(settings.sources) }
    var history by remember { mutableStateOf(settings.history) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var otherSourcePrompt by remember { mutableStateOf<OtherSourcePrompt?>(null) }
    var otherSourceReference by remember { mutableStateOf<OtherSourcePrompt?>(null) }
    var referencedPlaybackStarted by remember { mutableStateOf(false) }
    var selectedSourceUrl by remember { mutableStateOf(settings.selectedSourceUrl) }
    var subscriptionInput by remember { mutableStateOf(settings.subscriptionUrl) }
    // 自定义页可浏览已启用的成人源；搜索仍在 VodRepository 中排除成人源。
    val selectedSource = sources.firstOrNull { it.url == selectedSourceUrl && it.enabled }
        ?: sources.firstOrNull { it.enabled }
    var section by remember { mutableStateOf("movie") }
    var tag by remember { mutableStateOf("热门") }
    var categoryId by remember { mutableStateOf<Int?>(null) }
    var categories by remember { mutableStateOf<List<VodCategory>>(emptyList()) }
    var catalog by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var categoryError by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var sourceRevision by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf("browse") }
    var exitPromptUntil by remember { mutableLongStateOf(0L) }
    var exitBlockedUntil by remember { mutableLongStateOf(0L) }
    var returnScreen by remember { mutableStateOf("browse") }
    var settingsReturnScreen by remember { mutableStateOf("browse") }
    var query by remember { mutableStateOf("") }
    var searchedTerm by remember { mutableStateOf("") }
    // 点播源接收完整搜索词；精确搜索筛选片名时才取首段。
    val searchKeyword = searchedTerm.substringBefore(' ')
    var searchResults by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var exactSearch by remember { mutableStateOf(settings.exactSearch) }
    // 保留源返回的全部结果，设置切换后搜索页可立即更新。
    val visibleSearchResults = if (exactSearch && searchKeyword.isNotEmpty()) {
        searchResults.filter { it.title.contains(searchKeyword, ignoreCase = true) }
    } else searchResults
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searchCompleted by remember { mutableIntStateOf(0) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var searchFailures by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchVersion by remember { mutableIntStateOf(0) }
    var selectedVideo by remember { mutableStateOf<VideoItem?>(null) }
    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }
    var detailVersion by remember { mutableIntStateOf(0) }
    var refreshedDetail by remember { mutableStateOf<VideoDetail?>(null) }
    var lineIndex by remember { mutableIntStateOf(0) }
    var episodeDescending by remember { mutableStateOf(false) }
    var playingIndex by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var streamUrl by remember { mutableStateOf("") }
    var streamError by remember { mutableStateOf("") }
    var streamStatus by remember { mutableStateOf("正在解析播放地址...") }
    var attemptedLines by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var resumePositionMs by remember { mutableLongStateOf(0L) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var currentDurationMs by remember { mutableLongStateOf(0L) }
    var playbackStarted by remember { mutableStateOf(false) }
    var playVersion by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf("") }
    var settingsMessage by remember { mutableStateOf("") }
    val browseGridState = rememberLazyGridState()
    val browseGridColumns by remember { derivedStateOf { browseGridState.layoutInfo.maxSpan } }
    // 遥控器跨行返回当前选中的标签，不让空间距离决定落到哪个分类。
    val selectedFilterFocus = remember { FocusRequester() }
    val selectedParentFocus = remember { FocusRequester() }
    val selectedChildFocus = remember { FocusRequester() }
    val firstPosterFocus = remember { FocusRequester() }
    val searchFieldFocus = remember { FocusRequester() }
    val settingsImportFocus = remember { FocusRequester() }
    val detailPlayFocus = remember { FocusRequester() }
    val cancelClearHistoryFocus = remember { FocusRequester() }
    val confirmOtherSourceFocus = remember { FocusRequester() }
    val menuFocus = remember { listOf("movie", "tv", "variety", "custom", "history").associateWith { FocusRequester() } }
    var lastFocusedMenu by remember { mutableStateOf("movie") }
    var navigationToken by remember { mutableIntStateOf(0) }
    var usedNavigationToken by remember { mutableIntStateOf(0) }
    var initialBrowseFocusPending by remember { mutableStateOf(true) }
    LaunchedEffect(screen) {
        if (screen == "settings") {
            withFrameNanos { }
            settingsImportFocus.requestFocus()
        }
    }

    // 一个方向键只触发一次菜单请求；列表重排造成的自动焦点迁移不应再次切换分类。
    fun selectOnRemoteFocus(action: () -> Unit) {
        if (navigationToken != usedNavigationToken) {
            usedNavigationToken = navigationToken
            action()
        }
    }

    // 切换菜单时清空上一分类的海报，后续页只追加到当前分类。
    fun chooseSection(next: String) {
        if (section == next) {
            screen = "browse"
            return
        }
        section = next
        tag = "热门"
        categoryId = null
        page = 0
        catalog = emptyList()
        videos = emptyList()
        hasMore = true
        error = ""
        screen = "browse"
        scope.launch { browseGridState.scrollToItem(0) }
    }

    fun search(term: String) {
        val value = term.trim().replace(Regex("[\\s\\u3000]+"), " ").take(60).trimEnd()
        if (value.isEmpty()) return
        usedNavigationToken = navigationToken
        query = value
        searchedTerm = value
        keyboard?.hide()
        screen = "search"
        searchResults = emptyList()
        searchTotal = sources.count { it.enabled && !it.isAdult }
        searchCompleted = 0
        searchFailures = emptyList()
        searchLoading = searchTotal > 0
        searchError = ""
        scope.launch { browseGridState.scrollToItem(0) }
        val version = ++searchVersion
        if (searchTotal == 0) return
        scope.launch {
            try {
                // 跨源搜索固定在数据层限流，用户无需配置并发数。
                repository.search(value, sources) progress@{ completed, total, items, failure ->
                    if (version != searchVersion) return@progress
                    searchCompleted = completed
                    searchTotal = total
                    if (items.isNotEmpty()) searchResults = searchResults + items
                    if (failure != null) searchFailures = searchFailures + failure
                }
            } catch (failure: Exception) {
                if (version == searchVersion) searchError = failure.message ?: "搜索失败"
            } finally {
                if (version == searchVersion) searchLoading = false
            }
        }
    }

    LaunchedEffect(screen) {
        if (screen == "search") {
            withFrameNanos { }
            searchFieldFocus.requestFocus()
        }
    }

    // 详情数据加载完成后把遥控器焦点交给主操作，进入页面即可直接确认播放。
    LaunchedEffect(screen, detailVersion, detailLoading) {
        if (screen == "detail" && !detailLoading && detail?.lines?.any { it.episodes.isNotEmpty() } == true) {
            withFrameNanos { }
            detailPlayFocus.requestFocus()
        }
    }

    // 地址解析和播放器解码都可能失败，共用同一条自动换线流程。
    lateinit var switchLineOnFailure: (String, Long) -> Unit

    fun saveHistory(positionMs: Long, durationMs: Long) {
        val item = selectedVideo ?: return
        val line = detail?.lines?.getOrNull(lineIndex) ?: return
        val episode = line.episodes.getOrNull(playingIndex) ?: return
        val entry = TvHistoryEntry(
            item, line.name, lineIndex, episode.label, playingIndex,
            positionMs.coerceAtLeast(0L), durationMs.coerceAtLeast(0L), System.currentTimeMillis(),
        )
        val referenced = otherSourceReference?.takeIf {
            referencedPlaybackStarted && it.targetKey == entry.key && it.episodeLabel == episode.label
        }
        val updated = listOf(entry) + history.filterNot { it.key == entry.key || it.key == referenced?.entry?.key }
        settings.upsertHistory(entry)
        if (referenced != null) {
            // 当前源已开始播放且进度保存成功，才删除借用的原记录。
            settings.deleteHistory(referenced.entry.key)
            otherSourceReference = null
            referencedPlaybackStarted = false
        }
        updated.drop(60).forEach { settings.deleteHistory(it.key) }
        history = updated.take(60)
    }

    // 实时线路到达时，用名称和集名对齐旧进度，避免源调整顺序后跳错集。
    fun matchingEpisode(loaded: VideoDetail, lineName: String, savedLineIndex: Int, episodeLabel: String, savedEpisodeIndex: Int): Pair<Int, Int>? {
        val matchedLine = loaded.lines.indexOfFirst { it.name == lineName }
            .takeIf { it >= 0 } ?: savedLineIndex.takeIf { it in loaded.lines.indices } ?: return null
        val episodes = loaded.lines[matchedLine].episodes
        val matchedEpisode = episodes.indexOfFirst { it.label == episodeLabel }
            .takeIf { it >= 0 } ?: savedEpisodeIndex.takeIf { it in episodes.indices } ?: return null
        return matchedLine to matchedEpisode
    }

    fun returnToDetail() {
        if (playbackStarted) saveHistory(currentPositionMs, currentDurationMs)
        otherSourceReference = null
        referencedPlaybackStarted = false
        playVersion++
        streamUrl = ""
        refreshedDetail?.let { latest ->
            val oldEpisode = detail?.lines?.getOrNull(lineIndex)?.episodes?.getOrNull(playingIndex)
            val matched = matchingEpisode(latest, detail?.lines?.getOrNull(lineIndex)?.name.orEmpty(), lineIndex, oldEpisode?.label.orEmpty(), playingIndex)
            lineIndex = matched?.first ?: 0
            playingIndex = matched?.second ?: 0
            detail = latest
            refreshedDetail = null
        }
        screen = "detail"
    }

    fun play(
        index: Int,
        targetLineIndex: Int,
        triedLines: Set<Int> = setOf(targetLineIndex),
        startPositionMs: Long = 0L,
        status: String = "正在解析播放地址...",
    ) {
        val episode = detail?.lines?.getOrNull(targetLineIndex)?.episodes?.getOrNull(index) ?: return
        selectedVideo?.let { item -> if (refreshedDetail == null) detail?.let { settings.saveDetail(item, it) } }
        lineIndex = targetLineIndex
        playingIndex = index
        attemptedLines = triedLines
        resumePositionMs = startPositionMs
        currentPositionMs = startPositionMs
        currentDurationMs = 0L
        playbackStarted = false
        streamError = ""
        streamStatus = status
        streamUrl = ""
        screen = "player"
        val version = ++playVersion
        scope.launch {
            try {
                val resolved = repository.playableUrl(episode.url)
                if (version == playVersion && screen == "player") streamUrl = resolved
            } catch (failure: Exception) {
                if (version == playVersion && screen == "player") switchLineOnFailure(failure.message ?: "视频地址无法解析", startPositionMs)
            }
        }
    }

    fun tryRefreshedLines(positionMs: Long): Boolean {
        val latest = refreshedDetail ?: return false
        val oldLine = detail?.lines?.getOrNull(lineIndex)
        val oldEpisode = oldLine?.episodes?.getOrNull(playingIndex)
        val matched = matchingEpisode(latest, oldLine?.name.orEmpty(), lineIndex, oldEpisode?.label.orEmpty(), playingIndex)
            ?: return false
        if (otherSourceReference?.episodeLabel == oldEpisode?.label)
            otherSourceReference = otherSourceReference?.copy(episodeLabel = latest.lines[matched.first].episodes[matched.second].label)
        detail = latest
        refreshedDetail = null
        play(matched.second, matched.first, startPositionMs = positionMs.coerceAtLeast(0L), status = "正在尝试更新后的线路...")
        return true
    }

    switchLineOnFailure = { reason, positionMs ->
        val lines = detail?.lines.orEmpty()
        val label = lines.getOrNull(lineIndex)?.episodes?.getOrNull(playingIndex)?.label
        // 优先匹配同名集；线路命名不同但集数一致时，沿用当前集序号。
        val alternative = (1 until lines.size).firstNotNullOfOrNull { offset ->
            val candidate = (lineIndex + offset) % lines.size
            if (candidate in attemptedLines) return@firstNotNullOfOrNull null
            val episodes = lines[candidate].episodes
            val matchingIndex = episodes.indexOfFirst { it.label == label }
            val episodeIndex = if (matchingIndex >= 0) matchingIndex else playingIndex.takeIf { it in episodes.indices }
            episodeIndex?.let { candidate to it }
        }
        if (alternative != null) {
            val (nextLine, nextEpisode) = alternative
            if (otherSourceReference?.episodeLabel == label)
                otherSourceReference = otherSourceReference?.copy(episodeLabel = lines[nextLine].episodes[nextEpisode].label)
            play(
                nextEpisode, nextLine, attemptedLines + nextLine, positionMs.coerceAtLeast(0L),
                "线路不可用，正在切换到 ${lines[nextLine].name}...",
            )
        } else if (!tryRefreshedLines(positionMs)) {
            streamUrl = ""
            streamError = "播放失败，未找到其他可用线路：$reason"
        }
    }

    fun openDetail(item: VideoItem, resume: TvHistoryEntry? = null) {
        returnScreen = screen
        screen = "detail"
        selectedVideo = item
        otherSourcePrompt = null
        otherSourceReference = null
        referencedPlaybackStarted = false
        val otherHistory = if (resume == null && history.none { it.key == "${item.sourceUrl}:${item.id}" })
            settings.latestOtherSourceHistory(item.title, item.sourceUrl) else null
        val cached = resume?.let { settings.cachedDetail(item) }
        val cachedEpisode = if (cached != null) {
            matchingEpisode(cached, resume.lineName, resume.lineIndex, resume.episodeLabel, resume.episodeIndex)
        } else null
        detail = if (cachedEpisode != null) cached else null
        refreshedDetail = null
        detailError = ""
        detailLoading = cachedEpisode == null
        lineIndex = cachedEpisode?.first ?: 0
        episodeDescending = false
        val version = ++detailVersion
        // 播放记录有可用缓存就先续播；实时详情请求独立进行，慢源也不会挡住播放器。
        if (cachedEpisode != null && resume != null) play(cachedEpisode.second, cachedEpisode.first, startPositionMs = resume.positionMs)
        scope.launch {
            try {
                val loaded = repository.detail(item)
                if (version == detailVersion) {
                    if (cachedEpisode != null && loaded.lines.isEmpty()) return@launch
                    if (resume != null) settings.saveDetail(item, loaded)
                    if (screen == "player" && cachedEpisode != null) {
                        // 正在播放缓存地址时先保留播放器所用线路，退出播放器再显示最新详情。
                        refreshedDetail = loaded
                        if (streamError.isNotEmpty()) tryRefreshedLines(currentPositionMs.coerceAtLeast(resumePositionMs))
                    } else {
                        detail = loaded
                        if (otherHistory != null && screen == "detail") {
                            val savedLines = settings.cachedDetail(otherHistory.item)?.lines.orEmpty()
                            // 两边所有线路都只有一集时按电影处理，集名常是“正片”或画质名。
                            val singleEpisodeMovie = savedLines.isNotEmpty() && savedLines.all { it.episodes.size == 1 } &&
                                loaded.lines.isNotEmpty() && loaded.lines.all { it.episodes.size == 1 }
                            val savedLabel = otherHistory.episodeLabel.trim()
                            val episodePattern = Regex("^(?:第\\s*)?0*(\\d+)\\s*(?:集|话)?$")
                            val savedNumber = episodePattern.matchEntire(savedLabel)?.groupValues?.get(1)
                            // 其它源的线路顺序不可靠，按集名或集号找当前源的对应集。
                            val match = if (singleEpisodeMovie) 0 to 0 else loaded.lines.withIndex().firstNotNullOfOrNull { (candidateLine, line) ->
                                val exact = line.episodes.indexOfFirst { it.label.trim() == savedLabel }
                                val byNumber = if (savedNumber != null) line.episodes.indexOfFirst {
                                    episodePattern.matchEntire(it.label.trim())?.groupValues?.get(1) == savedNumber
                                } else -1
                                val byIndex = if (savedLabel.isEmpty() && otherHistory.episodeIndex in line.episodes.indices)
                                    otherHistory.episodeIndex else -1
                                val candidateEpisode = listOf(exact, byNumber, byIndex).firstOrNull { it >= 0 }
                                candidateEpisode?.let { candidateLine to it }
                            }
                            if (match != null) otherSourcePrompt = OtherSourcePrompt(
                                otherHistory, "${item.sourceUrl}:${item.id}", match.first, match.second,
                                loaded.lines[match.first].episodes[match.second].label,
                            )
                        }
                        if (resume != null) {
                            val savedEpisode = matchingEpisode(loaded, resume.lineName, resume.lineIndex, resume.episodeLabel, resume.episodeIndex)
                            if (cachedEpisode == null) {
                                if (savedEpisode != null) play(savedEpisode.second, savedEpisode.first, startPositionMs = resume.positionMs)
                                else detailError = "播放记录中的集数已不可用，请重新选集。"
                            } else {
                                lineIndex = savedEpisode?.first ?: 0
                                playingIndex = savedEpisode?.second ?: 0
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                if (version == detailVersion && cachedEpisode == null) detailError = failure.message ?: "详情加载失败"
            } finally {
                if (version == detailVersion) detailLoading = false
            }
        }
    }

    // 滚动偏移每帧都会变化，只有“是否离开顶部”发生变化时才让整个页面重组。
    val gridIsScrolled by remember(browseGridState) {
        derivedStateOf { browseGridState.firstVisibleItemIndex > 0 || browseGridState.firstVisibleItemScrollOffset > 0 }
    }
    val listIsScrolled = (screen == "browse" || screen == "search") && gridIsScrolled
    // 首页各分类首次返回才提示；退出其他页面的连续按键不计入首页退出操作。
    LaunchedEffect(exitPromptUntil) {
        if (exitPromptUntil != 0L) {
            delay(2000)
            exitPromptUntil = 0L
        }
    }
    LaunchedEffect(screen) {
        if (screen != "browse") exitPromptUntil = 0L
    }
    BackHandler {
        val now = SystemClock.elapsedRealtime()
        if (otherSourcePrompt != null) {
            otherSourcePrompt = null
            return@BackHandler
        }
        if (screen != "browse" || listIsScrolled) {
            exitPromptUntil = 0L
            exitBlockedUntil = now + 800L
        }
        if (listIsScrolled) scope.launch { browseGridState.scrollToItem(0) }
        else when (screen) {
            "player" -> {
                returnToDetail()
            }
            "detail" -> screen = returnScreen
            "settings" -> screen = settingsReturnScreen
            "browse" -> when {
                now < exitBlockedUntil -> Unit
                now < exitPromptUntil -> onExit()
                else -> exitPromptUntil = now + 2000L
            }
            else -> screen = "browse"
        }
    }

    LaunchedEffect(section, selectedSource?.url, sourceRevision, reload) {
        if (section != "custom" || selectedSource == null) return@LaunchedEffect
        categories = emptyList()
        categoryError = ""
        try {
            categories = repository.categories(selectedSource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            categoryError = failure.message ?: "分类加载失败"
        }
    }

    LaunchedEffect(section, tag, categoryId, page, selectedSource?.url, sourceRevision, reload) {
        if (section == "history") { loading = false; return@LaunchedEffect }
        if (section == "custom" && selectedSource == null) {
            videos = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = ""
        try {
            if (section == "custom") {
                val result = repository.sourceCatalog(selectedSource!!, categoryId, page + 1)
                videos = if (page == 0) result.items else videos + result.items
                hasMore = result.hasMore
            } else {
                val result = repository.catalog(section, tag, page)
                catalog = if (page == 0) result else catalog + result
                hasMore = result.size == 24
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "内容加载失败"
            hasMore = false
        } finally {
            // 遥控器快速移过菜单时，旧请求取消后不能覆盖新菜单的加载状态。
            if (currentCoroutineContext().isActive) loading = false
        }
    }

    // 列表末尾进入可视区域后自动取下一页；请求期间锁住触发条件，避免重复翻页。
    LaunchedEffect(screen, section, page, hasMore, loading, catalog.size, videos.size) {
        if (screen != "browse" || section == "history") return@LaunchedEffect
        snapshotFlow { browseGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { lastVisible ->
            val count = if (section == "custom") videos.size else catalog.size
            if (count > 0 && hasMore && !loading && lastVisible >= browseGridState.layoutInfo.totalItemsCount - 5) {
                loading = true
                page++
            }
        }
    }

    Box(Modifier.fillMaxSize().background(background).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            if (event.key != Key.Back && exitPromptUntil != 0L) exitPromptUntil = 0L
            when (event.key) {
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                    initialBrowseFocusPending = false
                    navigationToken++
                }
                else -> usedNavigationToken = navigationToken
            }
        }
        false
    }) {
        when (screen) {
            "player" -> {
                if (streamError.isNotEmpty()) {
                    Column(Modifier.fillMaxSize().padding(48.dp)) {
                        Label(streamError, 22, white)
                        Spacer(Modifier.height(18.dp))
                        FocusButton("重试当前集", onClick = { play(playingIndex, lineIndex) })
                    }
                } else if (streamUrl.isNotEmpty()) {
                    // 即使相邻两集返回相同播放地址，也要重新创建播放器并重置当前集状态。
                    key(playVersion) {
                        val currentPlayVersion = playVersion
                        NativePlayer(
                            url = streamUrl,
                            title = detail?.title.orEmpty(),
                            lines = detail?.lines.orEmpty(),
                            currentLineIndex = lineIndex,
                            currentEpisodeIndex = playingIndex,
                            startPositionMs = resumePositionMs,
                            playbackSpeed = playbackSpeed,
                            onSpeedChange = { playbackSpeed = it },
                            onBack = { returnToDetail() },
                            onSelectEpisode = { selectedLine, selectedEpisode ->
                                otherSourceReference = null
                                referencedPlaybackStarted = false
                                play(selectedEpisode, selectedLine)
                            },
                            onNextEpisode = {
                                otherSourceReference = null
                                referencedPlaybackStarted = false
                                play(playingIndex + 1, lineIndex)
                            },
                            onPlaybackStarted = {
                                val reference = otherSourceReference
                                val label = detail?.lines?.getOrNull(lineIndex)?.episodes?.getOrNull(playingIndex)?.label
                                if (currentPlayVersion == playVersion && reference != null &&
                                    reference.targetKey == selectedVideo?.let { "${it.sourceUrl}:${it.id}" } &&
                                    reference.episodeLabel == label) referencedPlaybackStarted = true
                                if (currentPlayVersion == playVersion) playbackStarted = true
                            },
                            onProgress = { position, duration ->
                                if (currentPlayVersion == playVersion && screen == "player") {
                                    currentPositionMs = position
                                    currentDurationMs = duration
                                    if (playbackStarted) saveHistory(position, duration)
                                }
                            },
                            onPlaybackFailure = { reason, position ->
                                if (currentPlayVersion == playVersion && screen == "player") switchLineOnFailure(reason, position)
                            },
                        )
                    }
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Spacer(Modifier.height(12.dp))
                        Label("加载中", 22, white)
                        Spacer(Modifier.height(8.dp))
                        Label(streamStatus, 16, muted)
                    }
                }
            }
            "detail" -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 24.dp)) {
                    if (detailError.isNotEmpty()) Label(detailError, 20, accent)
                    detail?.let { current ->
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Poster(current.poster, repository, Modifier.width(145.dp).height(205.dp))
                            Column(Modifier.weight(1f)) {
                                Label(current.title, 32, white, bold = true)
                                Spacer(Modifier.height(12.dp))
                                Label(listOf(current.year, current.area, selectedVideo?.sourceName.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  "), 17, muted)
                                if (current.actors.isNotBlank()) Label("主演：${current.actors}", 16, muted, Modifier.padding(top = 10.dp), maxLines = 1)
                                Label(current.description.ifBlank { "暂无剧情介绍" }, 17, white, Modifier.padding(top = 12.dp), maxLines = 3)
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        val episodes = current.lines.getOrNull(lineIndex)?.episodes.orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FocusButton("立刻播放", selected = true, enabled = episodes.isNotEmpty(), tab = true, modifier = Modifier.focusRequester(detailPlayFocus), onClick = {
                                val saved = history.firstOrNull { it.key == selectedVideo?.let { video -> "${video.sourceUrl}:${video.id}" } }
                                val matched = saved?.let { matchingEpisode(current, it.lineName, it.lineIndex, it.episodeLabel, it.episodeIndex) }
                                if (matched != null) play(matched.second, matched.first, startPositionMs = saved?.positionMs ?: 0L)
                                else play(0, lineIndex)
                            })
                            var searchFocused by remember { mutableStateOf(false) }
                            val searchShape = RoundedCornerShape(8.dp)
                            // 详情页只显示图标，遥控器焦点仍沿用按钮的描边和短横杠。
                            Column(
                                Modifier.onFocusChanged { searchFocused = it.isFocused }
                                    .scale(if (searchFocused) 1.06f else 1f)
                                    .border(3.dp, if (searchFocused) white else Color.Transparent, searchShape)
                                    .semantics { contentDescription = "搜索更多" }
                                    .clickable { search(current.title) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.tv_search),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(if (searchFocused) accent else white),
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.height(5.dp))
                                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(3.dp)).background(if (searchFocused) accent else Color.Transparent))
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Label("选集", 20, white, Modifier.weight(1f), bold = true)
                            FocusButton("正序", selected = !episodeDescending, tab = true, onClick = { episodeDescending = false })
                            FocusButton("倒序", selected = episodeDescending, tab = true, onClick = { episodeDescending = true })
                        }
                        Spacer(Modifier.height(10.dp))
                        if (episodes.isEmpty()) Label("该资源没有可播放地址", 18, muted)
                        else LazyVerticalGrid(
                            columns = GridCells.Adaptive(132.dp),
                            // 焦点放大时为首尾按钮留出空间，避免白色描边被网格裁掉。
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(if (episodeDescending) episodes.indices.reversed().toList() else episodes.indices.toList()) { index ->
                                FocusButton(episodes[index].label, tab = true, onClick = { play(index, lineIndex) })
                            }
                        }
                    }
                }
                // 各入口进入详情时，加载提示都相对整屏居中。
                if (detailLoading) {
                    Row(
                        Modifier.align(Alignment.Center)
                            .background(panel, RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LoadingIndicator()
                        Label("加载中...", 18, white)
                    }
                }
            }
            "settings" -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 24.dp)) {
                    Label("设置", 30, white, bold = true)
                    Spacer(Modifier.height(22.dp))
                    Label("vod.json 订阅地址", 18, white)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SearchInput(subscriptionInput, { subscriptionInput = it }, "粘贴订阅地址", Modifier.weight(1f), ImeAction.Done) {}
                        FocusButton(if (importing) "正在导入" else "导入 / 更新", enabled = !importing, tab = true, modifier = Modifier.focusRequester(settingsImportFocus), onClick = {
                            importing = true
                            settingsError = ""
                            settingsMessage = ""
                            scope.launch {
                                try {
                                    val previous = sources.associateBy { it.url }
                                    val imported = repository.importSources(subscriptionInput).map { source ->
                                        source.copy(enabled = previous[source.url]?.enabled ?: !source.isAdult)
                                    }
                                    sources = imported
                                    settings.sources = imported
                                    settings.subscriptionUrl = subscriptionInput.trim()
                                    val chosen = imported.firstOrNull { it.url == selectedSourceUrl && it.enabled }
                                        ?: imported.firstOrNull { it.enabled }
                                    selectedSourceUrl = chosen?.url.orEmpty()
                                    settings.selectedSourceUrl = selectedSourceUrl
                                    categoryId = null
                                    page = 0
                                    videos = emptyList()
                                    sourceRevision++
                                    settingsMessage = "已导入 ${imported.size} 个点播源"
                                } catch (failure: Exception) {
                                    settingsError = failure.message ?: "导入失败"
                                } finally { importing = false }
                            }
                        })
                    }
                    if (settingsError.isNotEmpty()) Label(settingsError, 16, Color(0xFFFFA6A6), Modifier.padding(top = 10.dp))
                    if (settingsMessage.isNotEmpty()) Label(settingsMessage, 16, accent, Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(22.dp))
                    Label("搜索", 20, white, bold = true)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label("精确搜索", 18, white, bold = true)
                            Label("精确匹配时只取第一段；仅显示片名包含该关键词的结果", 15, muted, Modifier.padding(top = 5.dp))
                        }
                        FocusButton(if (exactSearch) "已开启" else "已关闭", selected = exactSearch, tab = true, onClick = {
                            exactSearch = !exactSearch
                            settings.exactSearch = exactSearch
                        })
                    }
                    Label("点播源 · ${sources.count { it.enabled }} 个已启用", 20, white, bold = true)
                    Label("成人源可启用并在“自定义”中浏览，不参与搜索。", 15, muted, Modifier.padding(top = 6.dp, bottom = 14.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        items(sources, key = { it.url }) { source ->
                            Row(
                                Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Label(source.name, 18, white, bold = true, maxLines = 1)
                                    Label(source.url, 13, muted, maxLines = 1)
                                }
                                if (source.isAdult) Label("成人源", 14, muted)
                                // 两列操作保持固定宽度，状态文字长短变化时按钮和短横杠仍能逐行对齐。
                                FocusButton(if (source.enabled) "已启用" else "启用", selected = source.enabled, tab = true, modifier = Modifier.width(130.dp), onClick = {
                                    sources = sources.map { if (it.url == source.url) it.copy(enabled = !it.enabled) else it }
                                    settings.sources = sources
                                    val chosen = sources.firstOrNull { it.url == selectedSourceUrl && it.enabled }
                                        ?: sources.firstOrNull { it.enabled }
                                    selectedSourceUrl = chosen?.url.orEmpty()
                                    settings.selectedSourceUrl = selectedSourceUrl
                                    categoryId = null
                                    page = 0
                                    videos = emptyList()
                                })
                                FocusButton(if (selectedSource?.url == source.url) "自定义源" else "选为自定义源", selected = selectedSource?.url == source.url, enabled = source.enabled, tab = true, modifier = Modifier.width(170.dp), onClick = {
                                    selectedSourceUrl = source.url
                                    settings.selectedSourceUrl = source.url
                                    categoryId = null
                                    page = 0
                                    videos = emptyList()
                                })
                            }
                        }
                    }
                }
            }
            else -> {
                // 搜索、菜单和海报共用一个纵向网格，向下翻页时顶部会自然滚出屏幕。
                val selectedCustomChild = categories.firstOrNull { it.id == categoryId }?.let { child ->
                    categories.any { it.id == child.parentId }
                } == true
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(170.dp),
                    state = browseGridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 42.dp, vertical = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (screen == "browse") item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            LaunchedEffect(Unit) {
                                // 顶部菜单实际进入懒加载列表后，才把首次焦点给“电影”。
                                while (initialBrowseFocusPending) {
                                    withFrameNanos { }
                                    if (menuFocus.getValue("movie").requestFocus()) initialBrowseFocusPending = false
                                    else delay(100)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                Label("影窗 TV", 27, white, bold = true)
                                var searchFocused by remember { mutableStateOf(false) }
                                val searchShape = RoundedCornerShape(18.dp)
                                // 首页搜索框只负责进入搜索页，避免遥控器路过时直接弹出输入法。
                                Box(
                                    Modifier.width(300.dp).focusProperties { down = menuFocus.getValue(lastFocusedMenu) }
                                        .onFocusChanged { searchFocused = it.isFocused }
                                        .clip(searchShape).background(panel)
                                        .border(3.dp, if (searchFocused) white else Color.Transparent, searchShape)
                                        .clickable {
                                            screen = "search"
                                            scope.launch { browseGridState.scrollToItem(0) }
                                        }.padding(horizontal = 18.dp, vertical = 13.dp),
                                ) {
                                    Label("搜索电影、电视、综艺", 18, if (searchFocused) white else muted, maxLines = 1)
                                }
                                Spacer(Modifier.weight(1f))
                                var settingsFocused by remember { mutableStateOf(false) }
                                val settingsShape = RoundedCornerShape(8.dp)
                                // 齿轮入口沿用首页按钮的白色焦点框和橙色短横杠。
                                Column(
                                    Modifier.focusProperties { down = menuFocus.getValue(lastFocusedMenu) }
                                        .onFocusChanged { settingsFocused = it.isFocused }
                                        .scale(if (settingsFocused) 1.06f else 1f)
                                        .border(3.dp, if (settingsFocused) white else Color.Transparent, settingsShape)
                                        .semantics { contentDescription = "设置" }
                                        .clickable { settingsReturnScreen = screen; screen = "settings" }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.tv_settings),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(if (settingsFocused) accent else white),
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(3.dp)).background(if (settingsFocused) accent else Color.Transparent))
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf("movie" to "电影", "tv" to "电视", "variety" to "综艺", "custom" to "自定义", "history" to "播放记录").forEach { (value, label) ->
                                    val chooseMenu = { chooseSection(value) }
                                    FocusButton(
                                        label, selected = screen == "browse" && section == value, tab = true,
                                        modifier = Modifier.focusRequester(menuFocus.getValue(value)).focusProperties {
                                            // 有筛选项就进入筛选；综艺没有筛选项，直接进入第一张海报。
                                            if (section == "movie" || section == "tv") down = selectedFilterFocus
                                            else if (section == "custom" && selectedSource != null) down = selectedParentFocus
                                            else if (section == "variety" && catalog.isNotEmpty()) down = firstPosterFocus
                                        },
                                        onFocus = {
                                            if (navigationToken != usedNavigationToken) lastFocusedMenu = value
                                            selectOnRemoteFocus(chooseMenu)
                                        }, onClick = { lastFocusedMenu = value; chooseMenu() },
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF303036)))
                        }
                    }
                    if (screen == "search") {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Label("搜索影片", 28, white, bold = true)
                                Spacer(Modifier.height(18.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    SearchInput(query, { query = it }, "用拼音输入法输入片名", Modifier.weight(1f).widthIn(max = 680.dp).focusRequester(searchFieldFocus), ImeAction.Search) { search(query) }
                                    FocusButton("搜索", tab = true, onClick = { search(query) })
                                }
                                Label("按确认键输入中文片名，再选择搜索。", 16, muted, Modifier.padding(top = 10.dp))
                                Spacer(Modifier.height(24.dp))
                                if (searchedTerm.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Label("“$searchedTerm” 的搜索结果", 24, white, Modifier.weight(1f), bold = true, maxLines = 1)
                                        Label("${visibleSearchResults.size} 个结果", 16, muted)
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    if (searchTotal > 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            if (searchLoading) LoadingIndicator()
                                            Label("已完成 $searchCompleted / $searchTotal 个点播源", 16, muted)
                                        }
                                        Spacer(Modifier.height(9.dp))
                                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(panel)) {
                                            Box(Modifier.fillMaxWidth(searchCompleted.toFloat() / searchTotal).fillMaxHeight().background(accent))
                                        }
                                    }
                                    if (searchError.isNotEmpty()) Label(searchError, 17, Color(0xFFFFB5A6))
                                    else if (searchTotal == 0) Label("请先在设置中导入并启用非成人源。", 18, muted)
                                    else if (!searchLoading && visibleSearchResults.isEmpty() && searchFailures.size == searchTotal) Label("所有点播源均搜索失败，请检查网络或源地址。", 18, Color(0xFFFFB5A6))
                                    else if (!searchLoading && visibleSearchResults.isEmpty()) Label(
                                        if (exactSearch) "片名没有包含完整关键词，可缩短关键词或关闭精确搜索。" else "没有找到相关影片，可以试试其他关键词。",
                                        18, muted,
                                    )
                                    if (searchFailures.isNotEmpty()) Label("${searchFailures.size} 个源请求失败：${searchFailures.take(2).joinToString("；")}", 14, muted, Modifier.padding(top = 7.dp), maxLines = 2)
                                    if (!searchLoading && (visibleSearchResults.isEmpty() || searchFailures.isNotEmpty())) {
                                        Spacer(Modifier.height(10.dp))
                                        FocusButton("重新搜索", tab = true, onClick = { search(searchedTerm) })
                                    }
                                }
                            }
                        }
                        items(visibleSearchResults, key = { "${it.sourceUrl}:${it.id}" }) { video ->
                            PosterCard(video.title, video.poster, listOf(video.sourceName, video.remarks).filter { it.isNotBlank() }.joinToString(" · "), repository, onFocus = { usedNavigationToken = navigationToken }) { openDetail(video) }
                        }
                    } else if (section == "history") {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Label("播放记录 · ${history.size}", 24, white, Modifier.weight(1f), bold = true)
                                    if (history.isNotEmpty()) FocusButton("删除全部", tab = true, onClick = { confirmClearHistory = true })
                                }
                                if (history.isEmpty()) Label("还没有播放记录，播放影片后会出现在这里。", 18, muted, Modifier.padding(top = 16.dp))
                            }
                        }
                        items(history, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { entry ->
                            var focused by remember { mutableStateOf(false) }
                            val rowFocus = remember { FocusRequester() }
                            val deleteFocus = remember { FocusRequester() }
                            val shape = RoundedCornerShape(10.dp)
                            Row(
                                Modifier.fillMaxWidth().focusRequester(rowFocus).focusProperties { right = deleteFocus }
                                    .onFocusChanged { focused = it.isFocused }
                                    .clip(shape).background(panel)
                                    .border(3.dp, if (focused) white else Color.Transparent, shape)
                                    .clickable { openDetail(entry.item, entry) }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Poster(entry.item.poster, repository, Modifier.width(80.dp).height(110.dp))
                                Column(Modifier.weight(1f)) {
                                    Label(entry.item.title, 20, white, bold = true, maxLines = 1)
                                    Label("${entry.episodeLabel} · ${entry.lineName} · ${entry.item.sourceName}", 15, muted, Modifier.padding(top = 7.dp), maxLines = 1)
                                    val seconds = entry.positionMs / 1000
                                    Label("看到 %02d:%02d".format(seconds / 60, seconds % 60), 15, accent, Modifier.padding(top = 7.dp))
                                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF44444B))) {
                                        val progress = if (entry.durationMs > 0L) (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f) else 0f
                                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent))
                                    }
                                }
                                FocusButton("删除", tab = true, modifier = Modifier.focusRequester(deleteFocus).focusProperties { left = rowFocus }, onClick = {
                                    history = history.filterNot { it.key == entry.key }
                                    settings.deleteHistory(entry.key)
                                })
                            }
                        }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Label(when (section) { "movie" -> "电影推荐"; "tv" -> "电视推荐"; "variety" -> "综艺推荐"; else -> "自定义 · ${selectedSource?.name ?: "未选择点播源"}" }, 22, white, bold = true)
                                Spacer(Modifier.height(12.dp))
                                if (section == "movie" || section == "tv") {
                                    key(section) {
                                        // 横向列表会裁切放大的焦点框，首尾和上下都预留一点空间。
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                                        ) {
                                            items(if (section == "movie") movieTags else tvTags) { name ->
                                                val chooseTag = {
                                                    if (tag != name) { tag = name; page = 0; catalog = emptyList(); hasMore = true }
                                                }
                                                FocusButton(
                                                    name, selected = tag == name, tab = true,
                                                    modifier = Modifier
                                                        .then(if (tag == name) Modifier.focusRequester(selectedFilterFocus) else Modifier)
                                                        .focusProperties { up = menuFocus.getValue(lastFocusedMenu) },
                                                    onFocus = { selectOnRemoteFocus(chooseTag) }, onClick = chooseTag,
                                                )
                                            }
                                        }
                                    }
                                }
                                if (section == "custom" && selectedSource != null) {
                                    val roots = categories.filter { item -> item.parentId == 0 || categories.none { it.id == item.parentId } }
                                    val active = categories.firstOrNull { it.id == categoryId }
                                    val parentId = active?.parentId?.takeIf { id -> categories.any { it.id == id } } ?: categoryId
                                    val children = categories.filter { it.parentId == parentId }
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                                    ) {
                                        item {
                                            val chooseAll = { if (categoryId != null) { categoryId = null; page = 0; videos = emptyList() } }
                                            FocusButton("全部", selected = categoryId == null, tab = true,
                                                modifier = Modifier
                                                    .then(if (categoryId == null) Modifier.focusRequester(selectedParentFocus) else Modifier)
                                                    .focusProperties { up = menuFocus.getValue(lastFocusedMenu) },
                                                onFocus = { selectOnRemoteFocus(chooseAll) }, onClick = chooseAll)
                                        }
                                        items(roots) { category ->
                                            val chooseCategory = {
                                                if (parentId != category.id) {
                                                    categoryId = categories.firstOrNull { it.parentId == category.id }?.id ?: category.id
                                                    page = 0; videos = emptyList()
                                                }
                                            }
                                            FocusButton(
                                                category.name, selected = parentId == category.id, tab = true,
                                                modifier = Modifier
                                                    .then(if (parentId == category.id) Modifier.focusRequester(selectedParentFocus) else Modifier)
                                                    .focusProperties {
                                                        up = menuFocus.getValue(lastFocusedMenu)
                                                        if (children.isNotEmpty()) down = selectedChildFocus
                                                    },
                                                onFocus = { selectOnRemoteFocus(chooseCategory) }, onClick = chooseCategory,
                                            )
                                        }
                                    }
                                    if (children.isNotEmpty()) {
                                        Spacer(Modifier.height(10.dp))
                                        key(parentId) {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                            ) {
                                                items(children) { category ->
                                                    val chooseChild = { if (categoryId != category.id) { categoryId = category.id; page = 0; videos = emptyList() } }
                                                    FocusButton(
                                                        category.name, selected = categoryId == category.id, tab = true,
                                                        modifier = Modifier
                                                            .then(if (categoryId == category.id) Modifier.focusRequester(selectedChildFocus) else Modifier)
                                                            .focusProperties { up = selectedParentFocus },
                                                        onFocus = { selectOnRemoteFocus(chooseChild) }, onClick = chooseChild,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (section == "custom" && selectedSource == null) Label("请在设置中导入 vod.json 并启用点播源。", 18, muted, Modifier.padding(top = 16.dp))
                                if (categoryError.isNotEmpty() && section == "custom") Label("分类加载失败：$categoryError", 16, Color(0xFFFFA6A6), Modifier.padding(top = 12.dp))
                                if (error.isNotEmpty()) Label(error, 16, Color(0xFFFFA6A6), Modifier.padding(top = 12.dp))
                                if (error.isNotEmpty() || (section == "custom" && categoryError.isNotEmpty())) {
                                    Spacer(Modifier.height(8.dp))
                                    Row { FocusButton("重试", onClick = { page = 0; videos = emptyList(); catalog = emptyList(); hasMore = true; reload++ }) }
                                }
                            }
                        }
                        if (section == "custom") {
                            itemsIndexed(videos, key = { _, video -> "${video.sourceUrl}:${video.id}" }) { index, video ->
                                PosterCard(video.title, video.poster, listOf(video.sourceName, video.remarks).filter { it.isNotBlank() }.joinToString(" · "), repository,
                                    modifier = if (index < browseGridColumns) Modifier.focusProperties {
                                        up = if (selectedCustomChild) selectedChildFocus else selectedParentFocus
                                    } else Modifier,
                                    onFocus = { usedNavigationToken = navigationToken }) { openDetail(video) }
                            }
                        } else {
                            itemsIndexed(catalog) { index, video ->
                                PosterCard(video.title, video.poster, video.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: video.info, repository,
                                    modifier = when {
                                        section == "variety" && index == 0 -> Modifier.focusRequester(firstPosterFocus).focusProperties { up = menuFocus.getValue(lastFocusedMenu) }
                                        section != "variety" && index < browseGridColumns -> Modifier.focusProperties { up = selectedFilterFocus }
                                        else -> Modifier
                                    },
                                    onFocus = { usedNavigationToken = navigationToken }) { search(video.title) }
                            }
                        }
                        if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LoadingIndicator() }
                    }
                }
            }
        }
        if (screen == "browse" && exitPromptUntil != 0L) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp)
                    .background(Color(0xEE202025), RoundedCornerShape(12.dp))
                    .border(2.dp, accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Label("再按一次返回键退出", 18, white, bold = true)
            }
        }
        val prompt = otherSourcePrompt
        if (prompt != null) {
            val label = detail?.lines?.getOrNull(prompt.lineIndex)?.episodes?.getOrNull(prompt.episodeIndex)?.label.orEmpty()
            Dialog(onDismissRequest = { otherSourcePrompt = null }) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Label("发现其它源播放记录", 24, white, bold = true)
                    Label("${prompt.entry.item.sourceName.ifBlank { prompt.entry.item.sourceUrl }} 曾播放到 $label（约 ${prompt.entry.positionMs / 60_000} 分钟）。是否从当前源继续播放？", 17, muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        FocusButton("取消", tab = true, onClick = { otherSourcePrompt = null })
                        FocusButton("同意", tab = true, modifier = Modifier.focusRequester(confirmOtherSourceFocus), onClick = {
                            val position = prompt.entry.positionMs.takeIf {
                                it > 5_000 && (prompt.entry.durationMs <= 0 || it < prompt.entry.durationMs - 15_000)
                            } ?: 0L
                            otherSourcePrompt = null
                            otherSourceReference = prompt
                            referencedPlaybackStarted = false
                            play(prompt.episodeIndex, prompt.lineIndex, startPositionMs = position)
                        })
                    }
                }
                LaunchedEffect(prompt) { confirmOtherSourceFocus.requestFocus() }
            }
        }
        if (confirmClearHistory) {
            Dialog(onDismissRequest = { confirmClearHistory = false }) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Label("删除全部播放记录？", 24, white, bold = true)
                    Label("这会同时清除所有影片的续播进度。", 17, muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusButton("取消", tab = true, modifier = Modifier.focusRequester(cancelClearHistoryFocus), onClick = { confirmClearHistory = false })
                        FocusButton("确认删除", tab = true, onClick = {
                            settings.clearHistory()
                            history = emptyList()
                            confirmClearHistory = false
                        })
                    }
                }
                LaunchedEffect(Unit) { cancelClearHistoryFocus.requestFocus() }
            }
        }
    }
}

@Composable
internal fun LoadingIndicator(modifier: Modifier = Modifier) {
    val rotation by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "rotation",
    )
    Canvas(modifier.requiredSize(24.dp)) {
        val stroke = 3.dp.toPx()
        // 网格项可能给子项整行宽度，绘制区域始终取正方形，避免圆弧被拉伸。
        val diameter = size.minDimension - stroke
        drawArc(
            accent, rotation, 270f, useCenter = false,
            topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
            size = Size(diameter, diameter),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun Label(value: String, size: Int, color: Color, modifier: Modifier = Modifier, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    BasicText(value, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}

@Composable
fun FocusButton(label: String, selected: Boolean = false, enabled: Boolean = true, tab: Boolean = false, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .onFocusChanged {
                val newlyFocused = it.isFocused && !focused
                focused = it.isFocused
                if (enabled && newlyFocused) onFocus?.invoke()
            }
            .scale(if (focused) 1.06f else 1f)
            .background(if (tab) Color.Transparent else if (selected || focused) accent else panel, shape)
            // 已选状态可能常亮，菜单与按钮都用白色描边标明遥控器当前位置。
            .border(3.dp, if (focused) white else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = if (tab) 8.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (tab) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label(label, 18, if (!enabled) muted else if (selected || focused) accent else white, bold = selected || focused)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(3.dp)).background(if (selected || focused) accent else Color.Transparent))
            }
        } else {
            Label(label, 17, if (enabled) white else muted, bold = selected || focused)
        }
    }
}

@Composable
private fun SearchInput(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, action: ImeAction, onSubmit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var keyboardRequest by remember { mutableIntStateOf(0) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun beginEditing() {
        // 触屏点击和遥控器确认共用入口，避免只处理按键时平板无法唤起输入法。
        fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
        editing = true
        keyboardRequest++
    }
    LaunchedEffect(value) {
        if (fieldValue.text != value) fieldValue = TextFieldValue(value, selection = TextRange(value.length))
    }
    LaunchedEffect(keyboardRequest) {
        if (keyboardRequest > 0) {
            // 先让输入框切换为可编辑状态，再主动打开电视输入法。
            withFrameNanos { }
            keyboard?.show()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        // 搜索及订阅地址框保持原底色，用描边提示输入焦点。
        modifier.scale(if (focused) 1.02f else 1f)
            .background(panel, shape)
            .border(3.dp, if (focused) white else Color.Transparent, shape)
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it; onChange(it.text) },
            readOnly = !editing,
            singleLine = true,
            textStyle = TextStyle(color = white, fontSize = 18.sp),
            cursorBrush = SolidColor(Color(0xFFFFC15E)),
            keyboardOptions = KeyboardOptions(imeAction = action, showKeyboardOnFocus = false),
            keyboardActions = KeyboardActions(
                onSearch = { editing = false; keyboard?.hide(); onSubmit() },
                onDone = { editing = false; keyboard?.hide(); onSubmit() },
            ),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused) editing = false }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Release && !editing) beginEditing()
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 &&
                        (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) {
                        beginEditing()
                        true
                    } else false
                },
            decorationBox = { input ->
                Box {
                    if (value.isEmpty()) Label(hint, 18, muted)
                    input()
                }
            },
        )
    }
}

@Composable
private fun Poster(
    address: String,
    repository: VodRepository,
    modifier: Modifier = Modifier,
    showLoading: Boolean = false,
    imageScale: Float = 1f,
) {
    // 地址变化时重置加载状态，避免列表复用卡片时短暂显示上一张海报。
    val posterState by key(address) {
        produceState<Pair<android.graphics.Bitmap?, Boolean>>(null to false) {
            value = repository.poster(address) to true
        }
    }
    val (bitmap, complete) = posterState
    val imageAlpha by animateFloatAsState(
        targetValue = if (showLoading && bitmap == null) 0f else 1f,
        animationSpec = tween(300),
    )
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    // TV 海报采用桌面端深色模式的占位底色。
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (showLoading) Color(0xFF1B1D26) else panel),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            // 沿用原先的图片缩放与淡入效果，ImageBitmap 随海报数据缓存。
            Image(image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(imageScale).alpha(imageAlpha))
        } else if (showLoading && !complete && address.isNotBlank()) {
            val shimmer = rememberInfiniteTransition(label = "poster loading")
            val progress = shimmer.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1700, easing = CubicBezierEasing(.42f, 0f, .58f, 1f))),
                label = "poster shimmer",
            )
            Canvas(Modifier.fillMaxSize()) {
                // 与桌面端深色模式一样，让白色斜向光带在 1.7 秒内扫过占位底色。
                val startX = (-1.2f + 2.4f * progress.value) * size.width
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            .1f to Color.Transparent,
                            .45f to Color(0x2EFFFFFF),
                            .8f to Color.Transparent,
                        ),
                        start = Offset(startX, 0f),
                        end = Offset(startX + size.width, size.height * .18f),
                    ),
                )
            }
        } else if (!showLoading) {
            Label("影窗", 22, muted)
        }
    }
}

@Composable
private fun PosterCard(title: String, poster: String, subtitle: String, repository: VodRepository, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    // 焦点区域放在缩放层外，避免列表按放大后的边界反复调整滚动位置。
    val focusProgress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(300, easing = CubicBezierEasing(.165f, .84f, .44f, 1f)),
    )
    val imageScale by animateFloatAsState(
        targetValue = if (focused) 1.1f else 1f,
        animationSpec = tween(800, easing = CubicBezierEasing(.165f, .84f, .44f, 1f)),
    )
    Column(
        modifier.fillMaxWidth().onFocusChanged {
            val newlyFocused = it.isFocused && !focused
            focused = it.isFocused
            if (newlyFocused) onFocus()
        }.clickable(onClick = onClick).graphicsLayer {
            scaleX = 1f + .06f * focusProgress.value
            scaleY = 1f + .06f * focusProgress.value
        }
            .background(if (focused) Color(0xFF3B302B) else panel, shape)
            .border(3.dp, if (focused) white else Color.Transparent, shape)
            .padding(7.dp),
    ) {
        Poster(poster, repository, Modifier.fillMaxWidth().aspectRatio(.72f), showLoading = true, imageScale = imageScale)
        Spacer(Modifier.height(10.dp))
        BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = if (focused) accent else white, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
        BasicText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = muted, fontSize = 13.sp), modifier = Modifier.padding(top = 3.dp))
    }
}

