package com.shuxian.videoclient.tv

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

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
    onPlaybackStarted: () -> Unit,
    onPlaybackFailure: (reason: String, positionMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showEpisodes by remember(url) { mutableStateOf(false) }
    var showSpeedMenu by remember(url) { mutableStateOf(false) }
    var controllerVisible by remember(url) { mutableStateOf(false) }
    var playbackPaused by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(true) }
    var showPlayFeedback by remember(url) { mutableStateOf(false) }
    var seekFeedback by remember(url) { mutableStateOf("") }
    var heldSpeed by remember(url) { mutableStateOf<Float?>(null) }
    var wasPlayingBeforeHold by remember(url) { mutableStateOf(false) }
    var seekActivity by remember(url) { mutableIntStateOf(0) }
    var seekStartPositionMs by remember(url) { mutableLongStateOf(-1L) }
    var lastRemoteSeekMs by remember(url) { mutableLongStateOf(0L) }
    var popupReturnTag by remember(url) { mutableStateOf<String?>(null) }
    var playerView by remember(url) { mutableStateOf<PlayerView?>(null) }
    var restoringEpisodes by remember(url) { mutableStateOf(false) }
    val episodeFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }
    val speedOptions = remember { listOf(0.75f, 1f, 1.25f, 1.5f, 2f) }
    val episodeGridState = rememberLazyGridState()
    val currentEpisodes = lines.getOrNull(currentLineIndex)?.episodes.orEmpty()
    val hasNext = currentEpisodeIndex + 1 < currentEpisodes.size
    val hasEpisodeSelection = currentEpisodes.size > 1
    val player = remember(url) {
        val uri = Uri.parse(url)
        val referer = "${uri.scheme}://${uri.authority}/"
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to referer))
        val dataSource = DefaultDataSource.Factory(context, http)
        val mediaSource = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSource)
            .setSeekBackIncrementMs(20_000)
            .setSeekForwardIncrementMs(20_000)
            .build().apply {
                val item = MediaItem.Builder().setUri(url)
                if (Regex("\\.m3u8(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                    item.setMimeType(MimeTypes.APPLICATION_M3U8)
                    // 只在 HLS 清单解析时过滤广告片段，MP4 和视频分片继续直接取流。
                    setMediaSource(HlsMediaSource.Factory(dataSource)
                        .setPlaylistParserFactory(AdFilteringHlsPlaylistParserFactory())
                        .createMediaSource(item.build()))
                } else {
                    setMediaItem(item.build())
                }
                if (startPositionMs > 0L) seekTo(startPositionMs)
                prepare()
                // 地址解析可能在应用退到后台后才完成，新建播放器也应保持暂停。
                playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
    }
    val originalBrightness = remember(player) { activity.window.attributes.screenBrightness }

    LaunchedEffect(player, playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }

    fun stopFastForward() {
        val previousSpeed = heldSpeed ?: return
        heldSpeed = null
        player.setPlaybackSpeed(previousSpeed)
        if (!wasPlayingBeforeHold) player.pause()
    }

    fun reportPosition() {
        if (player.playbackState == Player.STATE_READY && player.currentPosition >= 0L) {
            onProgress(player.currentPosition, player.duration.coerceAtLeast(0L))
        }
    }

    fun openEpisodeMenu() {
        // 每次打开都定位当前播放集；播放线路只由失败自动切换逻辑管理。
        restoringEpisodes = true
        popupReturnTag = "episode_control"
        showEpisodes = true
        playerView?.hideController()
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
                        // Media3 进度条会吃掉下键；明确移到底栏的倍速按钮。
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            playerView?.findViewWithTag<View>("speed_control")?.requestFocus()
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
                            playerView?.findViewWithTag<View>("speed_control")?.requestFocus()
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
                    openEpisodeMenu()
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
                // 中央图标显示当前状态：暂停时常驻，恢复播放时短暂提示。
                if (playWhenReady && playbackPaused) showPlayFeedback = true
                if (!playWhenReady) showPlayFeedback = false
                playbackPaused = !playWhenReady
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    onPlaybackStarted()
                    reportPosition()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                // Media3 播放完当前集后，沿用手动“下一集”的切集流程。
                if (playbackState == Player.STATE_ENDED && hasNext) onNextEpisode()
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
        // prepare() 先于监听器注册，初始状态也需要同步，避免首帧已就绪时仍显示加载提示。
        buffering = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_BUFFERING
        playbackPaused = !player.playWhenReady
        if (player.isPlaying) {
            onPlaybackStarted()
            reportPosition()
        }
        view.keepScreenOn = true
        onDispose {
            stopFastForward()
            view.keepScreenOn = false
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        // 电视切到桌面、其他应用或系统界面时立即暂停；返回应用后由用户主动继续播放。
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                reportPosition()
                stopFastForward()
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
        // 打开面板的焦点恢复在 Dialog 内进行，确保窗口和当前集都已挂载。
        if (showEpisodes) return@LaunchedEffect
        if (popupReturnTag == "episode_control") {
            withFrameNanos { }
            playerView?.showController()
            playerView?.findViewWithTag<View>("episode_control")?.requestFocus()
            popupReturnTag = null
        } else if (!showSpeedMenu) playerView?.requestFocus()
    }
    LaunchedEffect(showSpeedMenu) {
        if (showSpeedMenu) {
            withFrameNanos { }
            speedFocus.requestFocus()
        } else if (popupReturnTag == "speed_control") {
            withFrameNanos { }
            playerView?.showController()
            playerView?.findViewWithTag<View>("speed_control")?.requestFocus()
            popupReturnTag = null
        } else if (!showEpisodes) playerView?.requestFocus()
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
            // 底栏进度由 Media3 自己刷新；续播记录每 4 秒保存一次，退出和暂停时另行即时保存。
            delay(4_000)
            if (player.isPlaying) reportPosition()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { handlePlaybackKey(it.nativeKeyEvent) }) {
        AndroidView(
            factory = {
                object : PlayerView(it) {
                    private var handleVideoGesture = false
                    private var gestureMode = 0 // 0 待判定，1 横向跳转，2 亮度，3 音量，4 忽略，5 长按倍速。
                    private var startBrightness = 0f
                    private var appliedBrightness = -1f
                    private var startVolume = 0
                    private var appliedVolume = -1
                    private val gestureThreshold = 48f * resources.displayMetrics.density
                    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(event: MotionEvent): Boolean = true

                        override fun onDoubleTap(event: MotionEvent): Boolean {
                            if (player.playWhenReady) player.pause() else player.play()
                            return true
                        }

                        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                            if (isControllerFullyVisible) hideController() else showController()
                            return true
                        }

                        override fun onLongPress(event: MotionEvent) {
                            // 仅右侧静止长按启动倍速；滑动一旦开始就不再切换手势。
                            if (gestureMode != 0 || event.x <= width * .72f || heldSpeed != null) return
                            gestureMode = 5
                            wasPlayingBeforeHold = player.playWhenReady
                            heldSpeed = player.playbackParameters.speed
                            player.setPlaybackSpeed(2f)
                            if (!wasPlayingBeforeHold) player.play()
                        }

                        override fun onScroll(start: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                            if (start == null || gestureMode == 4 || gestureMode == 5) return true
                            val dx = current.x - start.x
                            val dy = current.y - start.y
                            if (gestureMode == 0) {
                                if (abs(dx) < gestureThreshold && abs(dy) < gestureThreshold) return true
                                gestureMode = when {
                                    abs(dx) > abs(dy) * 1.5f -> 1
                                    abs(dy) > abs(dx) * 1.5f && start.x < width * .28f -> 2
                                    abs(dy) > abs(dx) * 1.5f && start.x > width * .72f -> 3
                                    else -> 4
                                }
                                when (gestureMode) {
                                    1 -> {
                                        // 一个完整的横滑只跳转一次，避免连续 MOVE 事件重复快进。
                                        if (dx > 0) player.seekForward() else player.seekBack()
                                    }
                                    2 -> {
                                        val brightness = activity.window.attributes.screenBrightness
                                        startBrightness = if (brightness >= 0f) brightness else
                                            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                                    }
                                    3 -> {
                                        if (audioManager.isVolumeFixed) {
                                            seekFeedback = "此设备的系统音量不可调"
                                            seekActivity++
                                            gestureMode = 4
                                        } else startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    }
                                }
                            }
                            when (gestureMode) {
                                2 -> {
                                    val brightness = (startBrightness - dy / height).coerceIn(.02f, 1f)
                                    if (abs(brightness - appliedBrightness) >= .01f) {
                                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                                        appliedBrightness = brightness
                                        seekFeedback = "亮度 ${(brightness * 100).roundToInt()}%"
                                        seekActivity++
                                    }
                                }
                                3 -> {
                                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    val volume = (startVolume - dy / height * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                    if (volume != appliedVolume) {
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                                        appliedVolume = volume
                                        seekFeedback = "音量 ${(volume * 100f / maxVolume).roundToInt()}%"
                                        seekActivity++
                                    }
                                }
                            }
                            return true
                        }
                    })

                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            stopFastForward()
                            // 控制栏下方四分之一区域保留原生点击；画面区域由手势统一处理。
                            handleVideoGesture = !isControllerFullyVisible || event.y < height * .75f
                            gestureMode = 0
                            appliedBrightness = -1f
                            appliedVolume = -1
                        }
                        if (!handleVideoGesture) return super.dispatchTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                            stopFastForward()
                            gestureMode = 4
                        }
                        gestures.onTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            stopFastForward()
                            handleVideoGesture = false
                        }
                        return true
                    }

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
                    val controlHint = findViewById<TextView>(R.id.tv_control_hint)
                    val hintContainer = controlHint.parent as ViewGroup
                    val hintLocation = IntArray(2)
                    val hintContainerLocation = IntArray(2)
                    val progressBar = findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                    val hintInset = (24 * resources.displayMetrics.density).roundToInt()
                    val hintGap = (12 * resources.displayMetrics.density).roundToInt()
                    var hintTarget: View? = null
                    fun updateControlHint(target: View, focused: Boolean, label: String) {
                        if (focused) {
                            hintTarget = target
                            controlHint.text = label
                            controlHint.visibility = View.VISIBLE
                        } else if (hintTarget === target) {
                            hintTarget = null
                            controlHint.visibility = View.INVISIBLE
                        }
                    }
                    val hintPositionListener = ViewTreeObserver.OnPreDrawListener {
                        val target = hintTarget
                        if (target != null && controlHint.visibility == View.VISIBLE && target.hasFocus()) {
                            // 使用同一原生容器内的实际坐标，跟随控制栏动画、横向滚动及屏幕尺寸变化。
                            val maxWidth = (hintContainer.width - hintInset * 2).coerceAtLeast(0)
                            if (controlHint.maxWidth != maxWidth) controlHint.maxWidth = maxWidth
                            hintContainer.getLocationInWindow(hintContainerLocation)
                            target.getLocationInWindow(hintLocation)
                            val centerX = (hintLocation[0] - hintContainerLocation[0] + target.width * target.scaleX / 2f).roundToInt()
                            var top = hintLocation[1] - hintContainerLocation[1]
                            if (target !== progressBar && progressBar.isShown) {
                                progressBar.getLocationInWindow(hintLocation)
                                top = minOf(top, hintLocation[1] - hintContainerLocation[1])
                            }
                            val maxLeft = (hintContainer.width - controlHint.measuredWidth - hintInset).coerceAtLeast(hintInset)
                            controlHint.x = (centerX - controlHint.measuredWidth / 2)
                                .coerceIn(hintInset, maxLeft).toFloat()
                            // 提示放在对应按钮上方，并避开进度条。
                            controlHint.y = (top - controlHint.measuredHeight - hintGap).coerceAtLeast(hintInset).toFloat()
                        }
                        true
                    }
                    var hintObserver: ViewTreeObserver? = null
                    hintContainer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(target: View) {
                            hintObserver = target.viewTreeObserver.also { it.addOnPreDrawListener(hintPositionListener) }
                        }

                        override fun onViewDetachedFromWindow(target: View) {
                            // 下一集会重建播放器，必须从原观察器移除监听，避免累积旧页面的绘制回调。
                            hintObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(hintPositionListener)
                            hintObserver = null
                        }
                    })
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controllerVisible = visibility == View.VISIBLE
                        if (visibility != View.VISIBLE) {
                            hintTarget = null
                            controlHint.visibility = View.INVISIBLE
                            if (!showEpisodes && !showSpeedMenu) requestFocus()
                        }
                    })
                    hideController()
                    // 原生底栏与 Compose 按钮使用相同的浅底深色焦点。
                    val buttonSize = resources.getDimensionPixelSize(R.dimen.tv_player_control_size)
                    val iconPadding = resources.getDimensionPixelSize(R.dimen.tv_player_control_icon_padding)
                    val controlLabels = listOf(
                        androidx.media3.ui.R.id.exo_progress to "进度条 · 左右键跳转",
                        androidx.media3.ui.R.id.exo_vr to "VR",
                        androidx.media3.ui.R.id.exo_shuffle to "随机播放",
                        androidx.media3.ui.R.id.exo_repeat_toggle to "循环播放",
                        androidx.media3.ui.R.id.exo_subtitle to "字幕",
                        androidx.media3.ui.R.id.exo_fullscreen to "全屏",
                        androidx.media3.ui.R.id.exo_overflow_show to "更多",
                        androidx.media3.ui.R.id.exo_overflow_hide to "收起更多",
                        androidx.media3.ui.R.id.exo_minimal_fullscreen to "全屏",
                    )
                    controlLabels.forEach { (id, label) ->
                        findViewById<View>(id)?.apply {
                            val progress = id == androidx.media3.ui.R.id.exo_progress
                            if (progress) isFocusable = true
                            else {
                                // 字幕、更多等原生按钮也统一放大，避免底栏混用 Media3 默认小图标。
                                layoutParams = layoutParams.apply {
                                    width = buttonSize
                                    height = buttonSize
                                }
                                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                            setOnFocusChangeListener { target, focused ->
                                if (progress) {
                                    (target as? androidx.media3.ui.DefaultTimeBar)?.apply {
                                        setScrubberColor(if (focused) 0xFFFF792C.toInt() else android.graphics.Color.WHITE)
                                        setPlayedColor(0xFFFF792C.toInt())
                                    }
                                } else target.applyTvControlFocus(focused)
                                updateControlHint(target, focused, label)
                            }
                        }
                    }
                    // 隐藏 Media3 的多级设置入口，用一个倍速按钮直接打开速率列表。
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    val basicControls = findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls)
                    var insertAt = 0
                    val speedButton = TextView(context).apply {
                        id = View.generateViewId()
                        tag = "speed_control"
                        text = "$playbackSpeed×"
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_player_control_text_size))
                        setTextColor(android.graphics.Color.WHITE)
                        gravity = Gravity.CENTER
                        isFocusable = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setOnFocusChangeListener { target, focused ->
                            target.applyTvControlFocus(focused)
                            updateControlHint(target, focused, "倍速")
                        }
                        setOnClickListener {
                            popupReturnTag = "speed_control"
                            hideController()
                            showSpeedMenu = true
                        }
                    }
                    fun addControl(icon: Int, description: String, enabled: Boolean = true, action: () -> Unit) {
                        val button = ImageButton(context).apply {
                            setImageResource(icon)
                            setColorFilter(android.graphics.Color.WHITE)
                            contentDescription = description
                            tag = if (description == "选集") "episode_control" else "next_control"
                            isEnabled = enabled
                            alpha = if (enabled) 1f else .4f
                            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setOnFocusChangeListener { target, focused ->
                                target.applyTvControlFocus(focused)
                                updateControlHint(target, focused, description)
                            }
                            setOnClickListener { action() }
                        }
                        basicControls.addView(button, insertAt++, LinearLayout.LayoutParams(buttonSize, buttonSize))
                    }
                    addControl(R.drawable.tv_next_episode, "下一集", hasNext) {
                        reportPosition()
                        onNextEpisode()
                    }
                    // 按视图顺序排列“下一集、倍速、选集”，遥控器左右焦点随之保持一致。
                    basicControls.addView(speedButton, insertAt++, LinearLayout.LayoutParams((104 * resources.displayMetrics.density).roundToInt(), buttonSize))
                    if (hasEpisodeSelection) addControl(R.drawable.tv_episode_list, "选集") {
                        openEpisodeMenu()
                    }
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
        if (buffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                Spacer(Modifier.height(12.dp))
                Label("加载中", 22, Color.White)
            }
        } else if (playbackPaused || showPlayFeedback) {
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
        if (heldSpeed != null || seekFeedback.isNotEmpty()) {
            Label(
                if (heldSpeed != null) "2× 快进播放 · 松开恢复" else seekFeedback, 24, Color.White,
                // 留出底栏、进度条和焦点提示的空间，快进反馈显示在它们上方。
                Modifier.align(Alignment.BottomCenter).padding(bottom = 216.dp)
                    .background(Color(0xDD101014), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp), bold = true,
            )
        }
        if (showEpisodes) {
            // Dialog 独占遥控器焦点，方向键不会从选集面板移回播放器底栏。
            Dialog(onDismissRequest = { showEpisodes = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
                val dialogView = LocalView.current
                LaunchedEffect(restoringEpisodes, currentLineIndex, currentEpisodes, currentEpisodeIndex) {
                    if (!restoringEpisodes) return@LaunchedEffect
                    // 等 Dialog 获得窗口焦点，防止系统稍后的默认焦点覆盖当前集。
                    while (!dialogView.hasWindowFocus()) withFrameNanos { }
                    val episodes = currentEpisodes
                    if (episodes.isNotEmpty()) {
                        val target = currentEpisodeIndex.coerceIn(episodes.indices)
                        episodeGridState.scrollToItem(target)
                        snapshotFlow { episodeGridState.layoutInfo.visibleItemsInfo.any { it.index == target } }.first { it }
                        withFrameNanos { }
                        episodeFocus.requestFocus()
                    }
                    restoringEpisodes = false
                }
                Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
                    Column(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(480.dp)
                            .background(Color(0xF51C1C21)).padding(22.dp),
                    ) {
                        Label("选集", 25, Color.White, bold = true)
                        Spacer(Modifier.height(18.dp))
                        val episodes = currentEpisodes
                        val focusIndex = if (episodes.isNotEmpty()) {
                            currentEpisodeIndex.coerceIn(episodes.indices)
                        } else 0
                        val playingLabel = currentEpisodes.getOrNull(currentEpisodeIndex)?.label
                        Label(if (playingLabel != null) "正在播放：$playingLabel · 共 ${episodes.size} 集"
                            else "${episodes.size} 集 · 选择后立即播放", 16, Color(0xFFA6A6AF))
                        Spacer(Modifier.height(12.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = episodeGridState,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(episodes.indices.toList(), key = { "$currentLineIndex:$it" }) { index ->
                                val playing = index == currentEpisodeIndex
                                FocusButton(
                                    episodes[index].label,
                                    selected = playing,
                                    tab = true,
                                    icon = if (playing) R.drawable.tv_player_play else null,
                                    modifier = if (index == focusIndex) Modifier.focusRequester(episodeFocus) else Modifier,
                                    onClick = {
                                        reportPosition()
                                        showEpisodes = false
                                        onSelectEpisode(currentLineIndex, index)
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

// Media3 使用原生 View，集中同步其颜色和缩放，避免与页面按钮产生两套高亮语言。
private fun View.applyTvControlFocus(focused: Boolean) {
    foreground = null
    val density = resources.displayMetrics.density
    background = if (focused) StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            cornerRadius = 36 * density; setColor(0xFFD3D3DB.toInt())
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = 36 * density; setColor(0xFFF7F7F9.toInt())
        })
    } else null
    val color = if (focused) 0xFF17171C.toInt() else android.graphics.Color.WHITE
    if (this is ImageButton) setColorFilter(color)
    if (this is TextView) setTextColor(color)
    animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(150).start()
}
