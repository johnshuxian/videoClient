package com.shuxian.videoclient.tv

import android.net.Uri
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun NativePlayer(
    url: String,
    title: String,
    lines: List<PlayLine>,
    currentLineIndex: Int,
    currentEpisodeIndex: Int,
    startPositionMs: Long,
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    onBack: () -> Unit,
    onSelectEpisode: (lineIndex: Int, episodeIndex: Int) -> Unit,
    onNextEpisode: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onPlaybackFailure: (reason: String, positionMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var showEpisodes by remember(url) { mutableStateOf(false) }
    var showSpeedMenu by remember(url) { mutableStateOf(false) }
    var controllerVisible by remember(url) { mutableStateOf(false) }
    var playbackPaused by remember(url) { mutableStateOf(false) }
    var showPlayFeedback by remember(url) { mutableStateOf(false) }
    var seekFeedback by remember(url) { mutableStateOf("") }
    var seekActivity by remember(url) { mutableIntStateOf(0) }
    var seekStartPositionMs by remember(url) { mutableLongStateOf(-1L) }
    var lastRemoteSeekMs by remember(url) { mutableLongStateOf(0L) }
    var controlFocusLabel by remember(url) { mutableStateOf("") }
    var playerView by remember(url) { mutableStateOf<PlayerView?>(null) }
    var selectedLine by remember(url) { mutableIntStateOf(currentLineIndex) }
    val lineFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }
    val speedOptions = remember { listOf(0.75f, 1f, 1.25f, 1.5f, 2f) }
    val episodeGridState = rememberLazyGridState()
    val currentEpisodes = lines.getOrNull(currentLineIndex)?.episodes.orEmpty()
    val hasNext = currentEpisodeIndex + 1 < currentEpisodes.size
    val hasEpisodeSelection = lines.any { it.episodes.size > 1 }
    val player = remember(url) {
        val uri = Uri.parse(url)
        val referer = "${uri.scheme}://${uri.authority}/"
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to referer))
        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, http))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSource)
            .setSeekBackIncrementMs(20_000)
            .setSeekForwardIncrementMs(20_000)
            .build().apply {
                val item = MediaItem.Builder().setUri(url)
                if (Regex("\\.m3u8(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                    item.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                setMediaItem(item.build())
                if (startPositionMs > 0L) seekTo(startPositionMs)
                prepare()
                // 地址解析可能在应用退到后台后才完成，新建播放器也应保持暂停。
                playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
    }

    LaunchedEffect(player, playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }

    fun reportPosition() {
        if (player.playbackState == Player.STATE_READY && player.currentPosition >= 0L) {
            onProgress(player.currentPosition, player.duration.coerceAtLeast(0L))
        }
    }

    fun handlePlaybackKey(event: KeyEvent): Boolean {
        if (showEpisodes || showSpeedMenu) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val isDpad = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                if (isDpad && playerView?.isControllerFullyVisible == true) {
                    val focusedView = playerView?.findFocus()
                    val progress = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                    // 底栏按钮继续用左右键移动焦点；进度条由这里统一控制跳转频率。
                    if (focusedView != null && focusedView !== playerView && focusedView !== progress) return false
                }
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (event.repeatCount == 0 || event.eventTime - lastRemoteSeekMs >= 600L)
                ) {
                    val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                    if (event.repeatCount == 0) {
                        if (forward) player.seekForward() else player.seekBack()
                    } else {
                        // 长按时减小步长，避免系统高频连发把影片瞬间跳到末尾。
                        val target = (player.currentPosition + if (forward) 10_000L else -10_000L).coerceAtLeast(0L)
                        player.seekTo(if (player.duration > 0L) target.coerceAtMost(player.duration) else target)
                    }
                    lastRemoteSeekMs = event.eventTime
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (controllerVisible) {
                    val progress = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                    if (playerView?.findFocus() === progress) {
                        // Media3 进度条会吃掉下键；明确移到下方按钮，避免焦点看似没有移动。
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                        }
                        return true
                    }
                    return false
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    playerView?.showController()
                    // 控制栏出现后先聚焦进度条，焦点描边和文字会明确提示当前位置。
                    playerView?.post {
                        val progress = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                        if (progress?.requestFocus() != true) {
                            playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                        }
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (controllerVisible) {
                    val progress = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                    val focused = playerView?.findFocus()
                    if (focused != null && focused !== playerView && focused !== progress) {
                        if (event.action == KeyEvent.ACTION_DOWN) progress?.requestFocus()
                        return true
                    }
                    return false
                }
                // 隐藏状态下只用下键打开控制栏，避免 Media3 自动弹出中央控件。
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val focusedView = playerView?.findFocus()
                if (controllerVisible && focusedView != null && focusedView !== playerView) return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (player.playWhenReady) player.pause() else player.play()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                        else -> if (player.playWhenReady) player.pause() else player.play()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!controllerVisible) return false
                if (event.action == KeyEvent.ACTION_DOWN) playerView?.hideController()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN && hasEpisodeSelection) {
                    playerView?.hideController()
                    showEpisodes = true
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (event.action == KeyEvent.ACTION_DOWN && hasNext) {
                    reportPosition()
                    onNextEpisode()
                }
                return true
            }
        }
        return false
    }

    DisposableEffect(player) {
        // 播放页持有原生解码器；退出选集时立即释放，避免后台继续占用电视资源。
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 暂停图标常驻；恢复播放时短暂提示一次播放状态。
                if (playWhenReady && playbackPaused) showPlayFeedback = true
                if (!playWhenReady) showPlayFeedback = false
                playbackPaused = !playWhenReady
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) reportPosition()
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackFailure(error.message ?: "当前线路播放失败", player.currentPosition)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                // 连续按快进时从第一次的位置累计，让提示显示总共跳过的时间。
                if (seekStartPositionMs < 0) seekStartPositionMs = oldPosition.positionMs
                val differenceMs = newPosition.positionMs - seekStartPositionMs
                val direction = if (differenceMs < 0) "快退" else "快进"
                val seconds = kotlin.math.abs(differenceMs) / 1000
                val now = DateUtils.formatElapsedTime((newPosition.positionMs / 1000).coerceAtLeast(0))
                val duration = player.duration.takeIf { it > 0 }?.let { " / ${DateUtils.formatElapsedTime(it / 1000)}" }.orEmpty()
                seekFeedback = "$direction ${seconds} 秒 · 当前 $now$duration"
                seekActivity++
            }
        }
        player.addListener(listener)
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        // 电视切到桌面、其他应用或系统界面时立即暂停；返回应用后由用户主动继续播放。
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                reportPosition()
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(showEpisodes || showSpeedMenu) {
        showEpisodes = false
        showSpeedMenu = false
    }
    BackHandler(!showEpisodes && !showSpeedMenu) {
        if (controllerVisible) playerView?.hideController()
        else { reportPosition(); onBack() }
    }
    LaunchedEffect(showEpisodes, playerView) {
        if (showEpisodes) {
            val episodes = lines.getOrNull(selectedLine)?.episodes.orEmpty()
            if (episodes.isEmpty()) {
                withFrameNanos { }
                lineFocus.requestFocus()
            }
            else {
                val target = if (selectedLine == currentLineIndex) currentEpisodeIndex.coerceIn(episodes.indices) else 0
                episodeGridState.scrollToItem(target)
                withFrameNanos { }
                episodeFocus.requestFocus()
            }
        } else playerView?.requestFocus()
    }
    LaunchedEffect(showSpeedMenu) {
        if (showSpeedMenu) {
            withFrameNanos { }
            speedFocus.requestFocus()
        } else playerView?.requestFocus()
    }
    LaunchedEffect(showPlayFeedback) {
        if (showPlayFeedback) {
            delay(800)
            showPlayFeedback = false
        }
    }
    LaunchedEffect(seekActivity) {
        if (seekActivity > 0) {
            delay(1_800)
            seekFeedback = ""
            seekStartPositionMs = -1L
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            delay(3_000)
            if (player.isPlaying) reportPosition()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { handlePlaybackKey(it.nativeKeyEvent) }) {
        AndroidView(
            factory = {
                object : PlayerView(it) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        return handlePlaybackKey(event) || super.dispatchKeyEvent(event)
                    }
                }.apply {
                    useController = true
                    // 由遥控器下键主动打开底栏，5 秒无操作后交给 Media3 自动隐藏。
                    controllerAutoShow = false
                    controllerShowTimeoutMs = 5_000
                    this.player = player
                    isFocusable = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controllerVisible = visibility == View.VISIBLE
                        if (visibility != View.VISIBLE) {
                            controlFocusLabel = ""
                            if (!showEpisodes && !showSpeedMenu) requestFocus()
                        }
                    })
                    hideController()
                    // 进度条和底栏按钮用同样的白色描边，焦点在深色画面上更容易辨认。
                    val controlLabels = listOf(
                        androidx.media3.ui.R.id.exo_progress to "进度条 · 左右键跳转",
                        androidx.media3.ui.R.id.exo_play_pause to "播放 / 暂停",
                        androidx.media3.ui.R.id.exo_subtitle to "字幕",
                        androidx.media3.ui.R.id.exo_fullscreen to "全屏",
                        androidx.media3.ui.R.id.exo_overflow_show to "更多",
                    )
                    controlLabels.forEach { (id, label) ->
                        findViewById<View>(id)?.apply {
                            if (id == androidx.media3.ui.R.id.exo_progress) isFocusable = true
                            setOnFocusChangeListener { target, focused ->
                                target.foreground = if (focused) GradientDrawable().apply {
                                    cornerRadius = 8 * resources.displayMetrics.density
                                    setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.WHITE)
                                } else null
                                if (focused) controlFocusLabel = label
                                else if (controlFocusLabel == label) controlFocusLabel = ""
                            }
                        }
                    }
                    // 隐藏 Media3 的多级设置入口，用一个倍速按钮直接打开速率列表。
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    val basicControls = findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls)
                    var insertAt = basicControls.indexOfChild(findViewById(androidx.media3.ui.R.id.exo_settings)) + 1
                    val buttonSize = (48 * resources.displayMetrics.density).toInt()
                    val iconPadding = (12 * resources.displayMetrics.density).toInt()
                    val speedButton = TextView(context).apply {
                        id = View.generateViewId()
                        tag = "speed_control"
                        text = "$playbackSpeed×"
                        textSize = 16f
                        setTextColor(android.graphics.Color.WHITE)
                        gravity = Gravity.CENTER
                        isFocusable = true
                        val shape = GradientDrawable().apply { cornerRadius = 8 * resources.displayMetrics.density }
                        background = shape
                        setOnFocusChangeListener { _, focused ->
                            shape.setColor(if (focused) android.graphics.Color.rgb(255, 121, 44) else android.graphics.Color.TRANSPARENT)
                            shape.setStroke((3 * resources.displayMetrics.density).toInt(), if (focused) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
                            if (focused) controlFocusLabel = "倍速"
                            else if (controlFocusLabel == "倍速") controlFocusLabel = ""
                        }
                        setOnClickListener {
                            hideController()
                            showSpeedMenu = true
                        }
                    }
                    findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.nextFocusRightId = speedButton.id
                    speedButton.nextFocusLeftId = androidx.media3.ui.R.id.exo_play_pause
                    basicControls.addView(speedButton, insertAt++, LinearLayout.LayoutParams((64 * resources.displayMetrics.density).toInt(), buttonSize))
                    fun addControl(icon: Int, description: String, enabled: Boolean = true, action: () -> Unit) {
                        val button = ImageButton(context).apply {
                            setImageResource(icon)
                            setColorFilter(android.graphics.Color.WHITE)
                            contentDescription = description
                            isEnabled = enabled
                            alpha = if (enabled) 1f else .4f
                            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                            val shape = GradientDrawable().apply { cornerRadius = 8 * resources.displayMetrics.density }
                            background = shape
                            setOnFocusChangeListener { _, focused ->
                                shape.setColor(if (focused) android.graphics.Color.rgb(255, 121, 44) else android.graphics.Color.TRANSPARENT)
                                shape.setStroke((3 * resources.displayMetrics.density).toInt(), if (focused) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
                                if (focused) controlFocusLabel = description
                                else if (controlFocusLabel == description) controlFocusLabel = ""
                            }
                            setOnClickListener { action() }
                        }
                        basicControls.addView(button, insertAt++, LinearLayout.LayoutParams(buttonSize, buttonSize))
                    }
                    addControl(R.drawable.tv_next_episode, "下一集", hasNext) {
                        reportPosition()
                        onNextEpisode()
                    }
                    if (hasEpisodeSelection) addControl(R.drawable.tv_episode_list, "选集") { showEpisodes = true }
                }
            },
            update = {
                it.player = player
                playerView = it
                it.findViewWithTag<TextView>("speed_control")?.apply {
                    text = "$playbackSpeed×"
                    contentDescription = "倍速 $playbackSpeed"
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (playbackPaused || showPlayFeedback) {
            Box(
                Modifier.align(Alignment.Center).size(88.dp).background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(if (playbackPaused) R.drawable.tv_player_pause else R.drawable.tv_player_play),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(46.dp),
                )
            }
        }
        if (controllerVisible && !showEpisodes && !showSpeedMenu) {
            Label(
                "$title · ${currentEpisodes.getOrNull(currentEpisodeIndex)?.label.orEmpty()}",
                18, Color.White,
                Modifier.align(Alignment.TopStart).background(Color(0xC0101014)).padding(horizontal = 20.dp, vertical = 14.dp),
                maxLines = 1,
            )
        }
        if (controllerVisible && !showEpisodes && !showSpeedMenu && controlFocusLabel.isNotEmpty()) {
            Label(
                "当前焦点：$controlFocusLabel", 17, Color.White,
                Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 88.dp)
                    .background(Color(0xCC101014), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (seekFeedback.isNotEmpty()) {
            Label(
                seekFeedback, 22, Color.White,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp)
                    .background(Color(0xDD101014), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp), bold = true,
            )
        }
        if (showEpisodes) {
            // Dialog 独占遥控器焦点，方向键不会从选集面板移回播放器底栏。
            Dialog(onDismissRequest = { showEpisodes = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
                    Column(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(410.dp)
                            .background(Color(0xF51C1C21)).padding(22.dp),
                    ) {
                        Label("选集", 25, Color.White, bold = true)
                        Spacer(Modifier.height(18.dp))
                        if (lines.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                items(lines.indices.toList()) { index ->
                                    val chooseLine: () -> Unit = {
                                        if (selectedLine != index) {
                                            selectedLine = index
                                            scope.launch { episodeGridState.scrollToItem(0) }
                                        }
                                    }
                                    FocusButton(lines[index].name, selected = selectedLine == index,
                                        modifier = if (index == 0) Modifier.focusRequester(lineFocus) else Modifier,
                                        onFocus = chooseLine, onClick = chooseLine)
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                        }
                        val episodes = lines.getOrNull(selectedLine)?.episodes.orEmpty()
                        val focusIndex = if (episodes.isNotEmpty() && selectedLine == currentLineIndex) {
                            currentEpisodeIndex.coerceIn(episodes.indices)
                        } else 0
                        Label("${episodes.size} 集 · 选择后立即播放", 16, Color(0xFFA6A6AF))
                        Spacer(Modifier.height(12.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = episodeGridState,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(episodes.indices.toList()) { index ->
                                FocusButton(
                                    episodes[index].label,
                                    selected = selectedLine == currentLineIndex && index == currentEpisodeIndex,
                                    modifier = if (index == focusIndex) Modifier.focusRequester(episodeFocus) else Modifier,
                                    onClick = {
                                        reportPosition()
                                        showEpisodes = false
                                        onSelectEpisode(selectedLine, index)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showSpeedMenu) {
            // 倍速面板也使用独立焦点窗口，保持在菜单内直到选择或按返回键。
            Dialog(onDismissRequest = { showSpeedMenu = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
                    Column(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp)
                            .background(Color(0xF51C1C21)).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Label("播放速率", 25, Color.White, bold = true)
                        Spacer(Modifier.height(6.dp))
                        speedOptions.forEach { speed ->
                            FocusButton(
                                "$speed×", selected = playbackSpeed == speed,
                                modifier = if (playbackSpeed == speed) Modifier.focusRequester(speedFocus) else Modifier,
                                onClick = { onSpeedChange(speed); showSpeedMenu = false },
                            )
                        }
                        Label("按返回键关闭", 15, Color(0xFFA6A6AF))
                    }
                }
            }
        }
    }
}
