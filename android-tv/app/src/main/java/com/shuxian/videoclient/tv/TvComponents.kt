package com.shuxian.videoclient.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 电视端用深色内容背景与暖橙焦点色，保证远距离观看时文字和遥控器焦点清楚。
internal val background = Color(0xFF101014)
internal val panel = Color(0xFF202025)
internal val accent = Color(0xFFFF792C)
internal val muted = Color(0xFFA6A6AF)
internal val white = Color(0xFFF7F7F9)
internal val focusInk = Color(0xFF17171C)
internal val selectedPanel = Color(0xFF493126)
internal val danger = Color(0xFFFFAAA0)

@Composable
internal fun LoadingIndicator(modifier: Modifier = Modifier) {
    val rotation by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "rotation",
    )
    Canvas(modifier.requiredSize(28.dp)) {
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
fun FocusButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    tab: Boolean = false,
    onFocus: (() -> Unit)? = null,
    primary: Boolean = false,
    destructive: Boolean = false,
    fontSize: Int = 24,
    loading: Boolean = false,
    icon: Int? = null,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .98f else if (focused) 1.05f else 1f, tween(150), label = "button focus")
    val shape = RoundedCornerShape(if (tab) 16.dp else 32.dp)
    val ink = when {
        !enabled -> muted
        focused || primary -> if (destructive) Color(0xFF9C241B) else focusInk
        destructive -> danger
        selected -> accent
        else -> white
    }
    Box(
        modifier
            .onFocusChanged {
                val newlyFocused = it.isFocused && !focused
                focused = it.isFocused
                if (enabled && newlyFocused) onFocus?.invoke()
            }
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(when { focused -> white; primary -> accent; selected -> selectedPanel; else -> panel }, shape)
            .clip(shape)
            // 描边与底色共用缩放层，避免外部固定描边与放大后的圆角错位、露出白边。
            .then(if (outlined) Modifier.border(1.dp, if (focused) white else Color(0xFF63636F), shape) else Modifier)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) { if (!loading) onClick() }
            // TV 常规操作使用 24sp，按钮高度同时放大，为字形和焦点缩放留出空间。
            .heightIn(min = if (tab) 60.dp else 64.dp)
            .padding(horizontal = if (tab) 20.dp else 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) LoadingIndicator()
            else if (icon != null) Image(painterResource(icon), null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(28.dp))
            Label(label, fontSize, ink, bold = true, maxLines = 1)
        }
    }
}

@Composable
internal fun TvSwitchRow(title: String, description: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        .clip(shape).background(if (focused) white else panel)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            Label(title, 24, if (focused) focusInk else white, bold = true)
            Label(description, 20, if (focused) Color(0xFF55555F) else muted, Modifier.padding(top = 6.dp))
        }
        // 开关仅展示状态，整行是唯一的遥控器焦点。
        Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (checked) accent else Color(0xFF686874)).padding(4.dp)) {
            Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(28.dp)
                .background(white, RoundedCornerShape(14.dp)))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SearchInput(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, action: ImeAction, onDown: (() -> Unit)? = null,
    icon: Int? = null, onSubmit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var keyboardRequest by remember { mutableIntStateOf(0) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        // 输入法被返回键关闭后退出编辑态，下一次方向键可以直接移动到结果区。
        if (!imeVisible) editing = false
    }
    BackHandler(enabled = focused && editing) {
        editing = false
        keyboard?.hide()
    }
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
    val shape = RoundedCornerShape(16.dp)
    Box(
        // 输入框以描边表达焦点，保持深色底与固定尺寸，避免形成突兀的大块白色区域。
        modifier.heightIn(min = 64.dp).background(if (focused) Color(0xFF3A3A44) else Color(0xFF303039), shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) white else Color(0xFF63636F), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it; onChange(it.text) },
            readOnly = !editing,
            singleLine = true,
            textStyle = TextStyle(color = white, fontSize = 24.sp),
            cursorBrush = SolidColor(accent),
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
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up; Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft -> FocusDirection.Left; Key.DirectionRight -> FocusDirection.Right
                        else -> null
                    }
                    if (!editing && event.type == KeyEventType.KeyDown && direction != null) {
                        // 遥控器浏览时移动焦点，确认进入编辑后再让方向键控制光标。
                        if (direction == FocusDirection.Down && onDown != null) onDown()
                        else focusManager.moveFocus(direction)
                        true
                    } else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 &&
                        (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) {
                        beginEditing()
                        true
                    } else false
                },
            decorationBox = { input ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (icon != null) Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (focused) white else muted), modifier = Modifier.size(28.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Label(hint, 24, Color(0xFFD0D0DA), maxLines = 1)
                        input()
                    }
                }
            },
        )
    }
}

@Composable
internal fun Poster(
    address: String,
    repository: VodRepository,
    modifier: Modifier = Modifier,
    showLoading: Boolean = false,
    imageScale: () -> Float = { 1f },
    targetWidth: Int = 320,
    animateLoading: Boolean = true,
) {
    val cached = remember(repository, address, targetWidth) { repository.cachedPoster(address, targetWidth) }
    // 缓存命中直接作为首帧；屏幕外预取完成后，不再重新播放淡入动画。
    val posterState by key(address, targetWidth) {
        produceState(cached to (cached != null || address.isBlank()), animateLoading) {
            if (!value.second && address.isNotBlank()) {
                value = repository.poster(address, targetWidth, prefetch = showLoading && !animateLoading) to true
            }
        }
    }
    val (bitmap, complete) = posterState
    val imageAlpha = animateFloatAsState(
        targetValue = if (showLoading && bitmap == null) 0f else 1f,
        animationSpec = if (animateLoading && cached == null) tween(300) else snap(),
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
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = imageScale()
                    scaleY = scaleX
                    alpha = imageAlpha.value
                })
        } else if (showLoading && animateLoading && !complete && address.isNotBlank()) {
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
internal fun PosterCard(title: String, poster: String, subtitle: String, repository: VodRepository, modifier: Modifier = Modifier,
    targetWidth: Int = 320, visible: Boolean = true, movingQuickly: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    // 焦点区域放在缩放层外，避免列表按放大后的边界反复调整滚动位置。
    val focusProgress = animateFloatAsState(
        // 长按经过的卡片只轻微放大；松开后，最终停留的卡片再完成放大。
        targetValue = if (focused) { if (movingQuickly) .4f else 1f } else 0f,
        animationSpec = tween(160, easing = CubicBezierEasing(.165f, .84f, .44f, 1f)),
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
        Poster(poster, repository, Modifier.fillMaxWidth().aspectRatio(.72f), showLoading = true,
            // 卡片和图片共用 160ms 进度，只在绘制层读取，避免两套动画先后停下。
            imageScale = { 1f + .04f * focusProgress.value }, targetWidth = targetWidth, animateLoading = visible)
        Spacer(Modifier.height(10.dp))
        // 固定预留两行片名，放大字号后仍能多显示内容，也保持同一排卡片高度一致。
        BasicText(title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (focused) accent else white, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold))
        BasicText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = muted, fontSize = 18.sp, lineHeight = 24.sp), modifier = Modifier.padding(top = 4.dp))
    }
}
