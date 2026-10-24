package com.shuxian.videoclient.tv

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import com.shuxian.videoclient.update.AppUpdater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class OtherSourcePrompt(
    val entry: TvHistoryEntry,
    val targetKey: String,
    val lineIndex: Int,
    val episodeIndex: Int,
    val episodeLabel: String,
)

class MainActivity : ComponentActivity() {
    private lateinit var updater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = AppUpdater(this, true)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { TvScreenLayout { TvApp(updater, onExit = { finish() }) } }
    }

    override fun onResume() {
        super.onResume()
        if (!::updater.isInitialized) return
        try { updater.resumeInstallation(this) }
        catch (error: Exception) { Toast.makeText(this, "打开安装程序失败：${error.message}", Toast.LENGTH_LONG).show() }
    }
}

@Composable
private fun TvApp(updater: AppUpdater, onExit: () -> Unit) {
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
    val catalog = remember { TvCatalogState(repository, scope) }
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
    val currentVersion = remember { updater.currentVersion() }
    var updateRoute by remember { mutableStateOf(settings.updateRoute) }
    var updatePrompt by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
    var updateBusy by remember { mutableStateOf("") }
    var updatePercent by remember { mutableIntStateOf(0) }
    var updateMessage by remember { mutableStateOf("") }
    var updateError by remember { mutableStateOf(false) }
    LaunchedEffect(updateMessage, updateBusy) {
        if (updateMessage.isNotEmpty() && updateBusy.isEmpty()) {
            delay(2000)
            updateMessage = ""
        }
    }
    // 保留源返回的全部结果，设置切换后搜索页可立即更新。
    val visibleSearchResults = remember(searchResults, exactSearch, searchKeyword) {
        val visible = if (exactSearch && searchKeyword.isNotEmpty()) {
            searchResults.filter { it.title.contains(searchKeyword, ignoreCase = true) }
        } else searchResults
        visible.map { it.toPosterItem() }
    }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searchCompleted by remember { mutableIntStateOf(0) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var searchFailures by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchVersion by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val searchKeys = remember { HashSet<String>() }
    var selectedVideo by remember { mutableStateOf<VideoItem?>(null) }
    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }
    var detailVersion by remember { mutableIntStateOf(0) }
    var detailFocusPending by remember { mutableStateOf(false) }
    var restorePlayingEpisode by remember { mutableStateOf(false) }
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
    val browseList = remember { TvListState() }
    val searchList = remember { TvListState(TvHeaderFocus.Search) }
    val settingsImportFocus = remember { FocusRequester() }
    val settingsSearchFocus = remember { FocusRequester() }
    val settingsVersionFocus = remember { FocusRequester() }
    val settingsRouteFocus = remember { FocusRequester() }
    val cancelUpdateFocus = remember { FocusRequester() }
    var settingsHeaderTarget by remember { mutableStateOf<FocusRequester?>(null) }
    val settingsListState = rememberLazyListState()
    val detailPlayFocus = remember { FocusRequester() }
    val detailSearchFocus = remember { FocusRequester() }
    val detailEpisodeFocus = remember { FocusRequester() }
    val detailEpisodeGrid = rememberLazyGridState()
    val cancelClearHistoryFocus = remember { FocusRequester() }
    val confirmOtherSourceFocus = remember { FocusRequester() }
    LaunchedEffect(screen) {
        if (screen == "settings") {
            settingsListState.scrollToItem(0)
            withFrameNanos { }
            settingsImportFocus.requestFocus()
        }
    }
    LaunchedEffect(screen, settingsHeaderTarget) {
        val target = settingsHeaderTarget
        if (screen == "settings" && target != null) {
            // 页头可能已被懒加载回收，先恢复布局再请求焦点；回到订阅区时标题也完整可见。
            settingsListState.animateScrollToItem(0)
            withFrameNanos { }
            target.requestFocus()
        }
        settingsHeaderTarget = null
    }

    fun chooseSection(next: String) {
        if (section == next) return
        // 播放记录和海报使用不同列表，先屏蔽旧页头销毁时产生的自动焦点。
        browseList.pauseTracking()
        section = next
        tag = "热门"
        categoryId = null
    }

    fun search(term: String) {
        val value = term.trim().replace(Regex("[\\s\\u3000]+"), " ").take(60).trimEnd()
        if (value.isEmpty()) return
        searchJob?.cancel()
        query = value
        searchedTerm = value
        keyboard?.hide()
        screen = "search"
        searchResults = emptyList()
        searchKeys.clear()
        searchTotal = sources.filter { it.enabled && !it.isAdult }.distinctBy { it.url }.size
        searchCompleted = 0
        searchFailures = emptyList()
        searchLoading = searchTotal > 0
        searchError = ""
        searchList.backToTop(TvHeaderFocus.Search)
        val version = ++searchVersion
        if (searchTotal == 0) return
        searchJob = scope.launch {
            try {
                // 跨源搜索固定在数据层限流，用户无需配置并发数。
                repository.search(value, sources) progress@{ completed, total, items, failure ->
                    if (version != searchVersion) return@progress
                    searchCompleted = completed
                    searchTotal = total
                    // 同一源可能返回重复影片，逐批去重，避免重复 key 和无效重组。
                    val added = items.filter { searchKeys.add(it.key) }
                    if (added.isNotEmpty()) searchResults = searchResults + added
                    if (failure != null) searchFailures = searchFailures + failure
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version == searchVersion) searchError = failure.message ?: "搜索失败"
            } finally {
                if (version == searchVersion) searchLoading = false
            }
        }
    }

    // 首次进入聚焦播放；播放器返回时定位当前集，只恢复一次，避免后台数据更新抢走焦点。
    LaunchedEffect(screen, detailVersion, detailLoading, otherSourcePrompt != null) {
        if (screen != "detail" || !detailFocusPending || otherSourcePrompt != null) return@LaunchedEffect
        val current = detail ?: return@LaunchedEffect
        val episodes = current.lines.getOrNull(lineIndex)?.episodes.orEmpty()
        if (restorePlayingEpisode && episodes.isNotEmpty()) {
            val episode = playingIndex.coerceIn(episodes.indices)
            val index = if (episodeDescending) episodes.lastIndex - episode else episode
            detailEpisodeGrid.scrollToItem(index + 1)
            snapshotFlow { detailEpisodeGrid.layoutInfo.visibleItemsInfo.any { it.key == episode } }.first { it }
            withFrameNanos { }
            detailEpisodeFocus.requestFocus()
        } else {
            withFrameNanos { }
            if (episodes.isNotEmpty()) detailPlayFocus.requestFocus() else detailSearchFocus.requestFocus()
        }
        detailFocusPending = false
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
        restorePlayingEpisode = true
        detailFocusPending = true
        screen = "detail"
    }

    fun play(
        index: Int,
        targetLineIndex: Int,
        triedLines: Set<Int> = setOf(targetLineIndex),
        startPositionMs: Long? = null,
        status: String = "正在解析播放地址...",
    ) {
        val episodes = detail?.lines?.getOrNull(targetLineIndex)?.episodes ?: return
        val episode = episodes.getOrNull(index) ?: return
        // 手动选中已看过的当前集也应续播；换线等流程显式传入的位置优先。
        val position = startPositionMs ?: history.firstOrNull { it.key == selectedVideo?.key }
            ?.resumePositionFor(episodes, index) ?: 0L
        selectedVideo?.let { item -> if (refreshedDetail == null) detail?.let { settings.saveDetail(item, it) } }
        lineIndex = targetLineIndex
        playingIndex = index
        attemptedLines = triedLines
        resumePositionMs = position
        currentPositionMs = position
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
                if (version == playVersion && screen == "player") switchLineOnFailure(failure.message ?: "视频地址无法解析", position)
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
        detailFocusPending = true
        restorePlayingEpisode = false
        detailEpisodeGrid.requestScrollToItem(0)
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
        playingIndex = cachedEpisode?.second ?: 0
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
    val activeList = if (screen == "search") searchList else browseList
    val gridIsScrolled by remember(activeList) {
        derivedStateOf { activeList.grid.firstVisibleItemIndex > 0 || activeList.grid.firstVisibleItemScrollOffset > 0 }
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
        if (listIsScrolled) activeList.backToTop(if (screen == "search") TvHeaderFocus.Search else TvHeaderFocus.Filters)
        else when (screen) {
            "player" -> {
                returnToDetail()
            }
            "detail" -> {
                (if (returnScreen == "search") searchList else browseList).restoreFocus()
                screen = returnScreen
            }
            "settings" -> {
                (if (settingsReturnScreen == "search") searchList else browseList).restoreFocus()
                screen = settingsReturnScreen
            }
            "browse" -> when {
                now < exitBlockedUntil -> Unit
                now < exitPromptUntil -> onExit()
                else -> exitPromptUntil = now + 2000L
            }
            else -> {
                searchJob?.cancel()
                searchList.pauseTracking()
                browseList.restoreFocus()
                screen = "browse"
            }
        }
    }

    val catalogSource = selectedSource.takeIf { section == "custom" }
    val catalogSourceRevision = if (section == "custom") sourceRevision else 0
    LaunchedEffect(section, catalogSource?.url, catalogSourceRevision, reload) {
        categories = emptyList()
        categoryError = ""
        if (section != "custom" || selectedSource == null) return@LaunchedEffect
        try {
            categories = repository.categories(selectedSource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            categoryError = failure.message ?: "分类加载失败"
        }
    }

    LaunchedEffect(section, tag, categoryId, catalogSource?.url, catalogSourceRevision) {
        browseList.reset()
        catalog.select(section, tag, catalogSource, categoryId)
    }

    Box(Modifier.fillMaxSize().background(background).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            if (event.key != Key.Back && exitPromptUntil != 0L) exitPromptUntil = 0L
        }
        // 返回页面的短暂恢复阶段不把按键交给系统临时选中的控件。
        val code = event.nativeKeyEvent.keyCode
        (screen == "browse" || screen == "search") && activeList.restoring && (
            event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
                code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            )
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
                        Label("加载中", 24, white)
                        Spacer(Modifier.height(8.dp))
                        Label(streamStatus, 20, muted)
                    }
                }
            }
            "detail" -> {
                detail?.let { current ->
                    val saved = history.firstOrNull { it.key == selectedVideo?.key }
                    TvDetailScreen(current, selectedVideo?.sourceName.orEmpty(), lineIndex, playingIndex, episodeDescending,
                        saved, detailError, repository, detailEpisodeGrid, detailPlayFocus, detailSearchFocus, detailEpisodeFocus,
                        onPlay = {
                            val matched = saved?.let { matchingEpisode(current, it.lineName, it.lineIndex, it.episodeLabel, it.episodeIndex) }
                            if (matched != null) play(matched.second, matched.first, startPositionMs = saved.positionMs)
                            else play(0, lineIndex)
                        },
                        onSearch = { search(current.title) }, onSort = { episodeDescending = it }, onEpisode = { play(it, lineIndex) })
                }
                if (detail == null && detailError.isNotEmpty()) Label(detailError, 22, danger, Modifier.padding(42.dp))
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
                        Label("加载中...", 24, white)
                    }
                }
            }
            "settings" -> {
                LazyColumn(state = settingsListState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "settings-header") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label("设置", 30, white, Modifier.weight(1f), bold = true)
                            FocusButton("v$currentVersion", loading = updateBusy.isNotEmpty(),
                                modifier = Modifier.focusRequester(settingsVersionFocus).onPreviewKeyEvent { event ->
                                    if (event.key == Key.DirectionDown) {
                                        if (event.type == KeyEventType.KeyDown) settingsHeaderTarget = settingsImportFocus
                                        true
                                    } else false
                                }, onClick = {
                                    if (updateBusy.isNotEmpty()) return@FocusButton
                                    updateBusy = "checking"
                                    updateMessage = ""
                                    updateError = false
                                    scope.launch {
                                        try {
                                            updatePrompt = withContext(Dispatchers.IO) { updater.check(updateRoute) }
                                            updateMessage = if (updatePrompt == null) "当前已是最新版本" else ""
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) {
                                            updateError = true
                                            updateMessage = "检查更新失败：${error.message}。可切换线路后重试。"
                                        } finally { updateBusy = "" }
                                    }
                                })
                        }
                    }
                    // 分成独立的焦点行，让懒列表能逐行发现屏幕上方的控件。
                    item(key = "settings-subscription") { Column {
                        Spacer(Modifier.height(12.dp))
                        Label("vod.json 订阅地址", 24, white)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.onPreviewKeyEvent { event ->
                            if (event.key == Key.DirectionUp) {
                                if (event.type == KeyEventType.KeyDown) settingsHeaderTarget = settingsVersionFocus
                                true
                            } else false
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SearchInput(subscriptionInput, { subscriptionInput = it }, "粘贴订阅地址", Modifier.weight(1f), ImeAction.Done) {}
                            FocusButton(if (importing) "正在导入" else "导入", loading = importing, primary = true,
                                modifier = Modifier.width(190.dp).focusRequester(settingsImportFocus), onClick = {
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
                                        sourceRevision++
                                        settingsMessage = "已导入 ${imported.size} 个点播源"
                                    } catch (failure: Exception) {
                                        settingsError = failure.message ?: "导入失败"
                                    } finally { importing = false }
                                }
                            })
                        }
                        if (settingsError.isNotEmpty()) Label(settingsError, 20, Color(0xFFFFA6A6), Modifier.padding(top = 10.dp))
                        if (settingsMessage.isNotEmpty()) Label(settingsMessage, 20, accent, Modifier.padding(top = 10.dp))
                    } }
                    item(key = "settings-search") { Column {
                        Spacer(Modifier.height(12.dp))
                        Label("搜索", 24, white, bold = true)
                        Spacer(Modifier.height(10.dp))
                        TvSwitchRow("精确搜索", "仅显示片名包含第一段关键词的结果", exactSearch,
                            modifier = Modifier.focusRequester(settingsSearchFocus).onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionUp) {
                                    if (event.type == KeyEventType.KeyDown) settingsHeaderTarget = settingsImportFocus
                                    true
                                } else false
                            }) {
                                exactSearch = it
                                settings.exactSearch = exactSearch
                        }
                        Spacer(Modifier.height(16.dp))
                        Label("线路选择", 24, white, bold = true)
                        Label("用于检查更新和下载安装包", 20, muted, Modifier.padding(top = 6.dp, bottom = 10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FocusButton("gh-proxy", selected = updateRoute == "gh-proxy", enabled = updateBusy.isEmpty(),
                                modifier = Modifier.focusRequester(settingsRouteFocus), onClick = {
                                    updateRoute = "gh-proxy"
                                    settings.updateRoute = updateRoute
                                    updateMessage = ""
                                })
                            FocusButton("直连", selected = updateRoute == "direct", enabled = updateBusy.isEmpty(), onClick = {
                                updateRoute = "direct"
                                settings.updateRoute = updateRoute
                                updateMessage = ""
                            })
                        }
                        if (updateBusy == "downloading") {
                            Label("正在下载更新 $updatePercent%（完成后打开安装程序）",
                                20, muted, Modifier.padding(top = 10.dp))
                        }
                    } }
                    item(key = "settings-sources-header") { Column {
                        Spacer(Modifier.height(8.dp))
                        Label("点播源 · ${sources.count { it.enabled }} 个已启用", 24, white, bold = true)
                        Label("成人源可启用并在“自定义”中浏览，不参与搜索。", 20, muted, Modifier.padding(top = 6.dp, bottom = 14.dp))
                    } }
                    itemsIndexed(sources, key = { _, source -> source.url }) { index, source ->
                        Row(
                            Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                if (index == 0 && event.key == Key.DirectionUp) {
                                    if (event.type == KeyEventType.KeyDown) settingsHeaderTarget =
                                        if (updateBusy.isEmpty()) settingsRouteFocus else settingsVersionFocus
                                    true
                                } else false
                            }.background(panel, RoundedCornerShape(16.dp)).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Label(source.name, 24, white, bold = true, maxLines = 1)
                                Label(source.url, 18, muted, maxLines = 1)
                            }
                            if (source.isAdult) Label("成人源", 18, muted)
                            // 两列操作随 24sp 字号加宽，完整容纳启用状态和“设为自定义源”。
                            FocusButton(if (source.enabled) "✓ 已启用" else "启用", selected = source.enabled, tab = true, modifier = Modifier.width(168.dp), onClick = {
                                sources = sources.map { if (it.url == source.url) it.copy(enabled = !it.enabled) else it }
                                settings.sources = sources
                                val chosen = sources.firstOrNull { it.url == selectedSourceUrl && it.enabled }
                                    ?: sources.firstOrNull { it.enabled }
                                selectedSourceUrl = chosen?.url.orEmpty()
                                settings.selectedSourceUrl = selectedSourceUrl
                                categoryId = null
                            })
                            FocusButton(if (selectedSource?.url == source.url) "✓ 自定义源" else "设为自定义源", selected = selectedSource?.url == source.url, enabled = source.enabled, tab = true, modifier = Modifier.width(224.dp), onClick = {
                                selectedSourceUrl = source.url
                                settings.selectedSourceUrl = source.url
                                categoryId = null
                            })
                        }
                    }
                }
            }
            "search" -> TvSearchScreen(
                state = searchList,
                repository = repository,
                query = query,
                term = searchedTerm,
                results = visibleSearchResults,
                loading = searchLoading,
                completed = searchCompleted,
                total = searchTotal,
                failures = searchFailures,
                error = searchError,
                exact = exactSearch,
                onQuery = { query = it },
                onSearch = { search(it) },
                onOpen = { it.video?.let { video -> openDetail(video) } },
            )
            else -> TvBrowseScreen(
                state = browseList,
                catalog = catalog,
                repository = repository,
                section = section,
                tag = tag,
                source = selectedSource,
                categories = categories,
                categoryId = categoryId,
                categoryError = categoryError,
                history = history,
                onSection = { chooseSection(it) },
                onTag = {
                    if (tag != it) { browseList.pauseTracking(); tag = it }
                },
                onCategory = {
                    if (categoryId != it) { browseList.pauseTracking(); categoryId = it }
                },
                onSearch = { searchList.backToTop(TvHeaderFocus.Search); screen = "search" },
                onSettings = { settingsReturnScreen = screen; screen = "settings" },
                onRetryCategories = { reload++ },
                onOpenPoster = { item ->
                    val video = item.video
                    if (video != null) openDetail(video) else search(item.title)
                },
                onOpenHistory = { openDetail(it.item, it) },
                onDeleteHistory = { key ->
                    history = history.filterNot { it.key == key }
                    settings.deleteHistory(key)
                },
                onClearHistory = { confirmClearHistory = true },
            )
        }
        if (screen == "browse" && exitPromptUntil != 0L) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp)
                    .background(Color(0xEE202025), RoundedCornerShape(12.dp))
                    .border(2.dp, accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Label("再按一次返回键退出", 24, white, bold = true)
            }
        }
        val prompt = otherSourcePrompt
        if (prompt != null) {
            val label = detail?.lines?.getOrNull(prompt.lineIndex)?.episodes?.getOrNull(prompt.episodeIndex)?.label.orEmpty()
            TvDialog(onDismissRequest = { otherSourcePrompt = null }) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth(.9f).clip(RoundedCornerShape(12.dp)).background(panel).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Label("发现其它源播放记录", 28, white, bold = true)
                    Label("${prompt.entry.item.sourceName.ifBlank { prompt.entry.item.sourceUrl }} 曾播放到 $label（约 ${prompt.entry.positionMs / 60_000} 分钟）。是否从当前源继续播放？", 20, muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        FocusButton("暂不恢复", onClick = { otherSourcePrompt = null })
                        FocusButton("恢复播放记录", primary = true, icon = R.drawable.tv_button_play,
                            modifier = Modifier.focusRequester(confirmOtherSourceFocus), onClick = {
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
        if (updateMessage.isNotEmpty()) {
            // 在页面内居中显示，沿用统一密度且不夺走遥控器焦点。
            Column(Modifier.align(Alignment.Center).width(560.dp).background(panel, RoundedCornerShape(16.dp))
                .border(2.dp, if (updateError) danger else accent, RoundedCornerShape(16.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("更新提示", 28, if (updateError) danger else white, bold = true)
                Label(updateMessage, 22, white)
            }
        }
        val availableUpdate = updatePrompt
        if (availableUpdate != null) {
            TvDialog(onDismissRequest = { updatePrompt = null; settingsHeaderTarget = settingsVersionFocus }) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(.9f).clip(RoundedCornerShape(12.dp)).background(panel).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Label("发现新版本 v${availableUpdate.version}", 28, white, bold = true)
                    Label("是否下载更新？安装包约 %.1f MB，下载完成后将打开安装程序。".format(availableUpdate.size / 1024.0 / 1024.0), 20, muted)
                    if (availableUpdate.notes.isNotBlank()) Label(availableUpdate.notes, 20, muted, maxLines = 5)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusButton("取消", modifier = Modifier.focusRequester(cancelUpdateFocus), onClick = {
                            updatePrompt = null
                            settingsHeaderTarget = settingsVersionFocus
                        })
                        FocusButton("同意", primary = true, onClick = {
                            updatePrompt = null
                            settingsHeaderTarget = settingsVersionFocus
                            updateBusy = "downloading"
                            updatePercent = 0
                            updateMessage = ""
                            updateError = false
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        updater.download { percent -> scope.launch { updatePercent = percent } }
                                    }
                                    val started = updater.install(context as MainActivity)
                                    updateMessage = if (started) "安装程序已打开，请按提示完成更新。"
                                        else "请允许安装未知应用，返回后会自动打开安装程序。"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    updateError = true
                                    updateMessage = "更新失败：${error.message}。可重新点击版本号重试。"
                                } finally { updateBusy = "" }
                            }
                        })
                    }
                }
                LaunchedEffect(availableUpdate) { cancelUpdateFocus.requestFocus() }
            }
        }
        if (confirmClearHistory) {
            TvDialog(onDismissRequest = { browseList.restoreFocus(); confirmClearHistory = false }) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth(.9f).clip(RoundedCornerShape(12.dp)).background(panel).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Label("删除全部播放记录？", 28, white, bold = true)
                    Label("这会同时清除所有影片的续播进度。", 20, muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusButton("取消", modifier = Modifier.focusRequester(cancelClearHistoryFocus), onClick = {
                            browseList.restoreFocus()
                            confirmClearHistory = false
                        })
                        FocusButton("确认删除", destructive = true, onClick = {
                            settings.clearHistory()
                            history = emptyList()
                            browseList.backToTop(TvHeaderFocus.Menu)
                            confirmClearHistory = false
                        })
                    }
                }
                LaunchedEffect(Unit) { cancelClearHistoryFocus.requestFocus() }
            }
        }
    }
}
