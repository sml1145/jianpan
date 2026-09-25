package com.mengting.ime.ui.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.core.CalcEval
import com.mengting.ime.core.ClipboardHistory
import com.mengting.ime.core.PinyinEngine
import com.mengting.ime.feature.audio.KeySoundManager
import com.mengting.ime.feature.sms.SmsCodeHolder
import com.mengting.ime.ui.background.PixelArtBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------- 布局数据 ----------

private val ROW1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
private val ROW2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
private val ROW3 = listOf("z", "x", "c", "v", "b", "n", "m")
private val T9 = listOf(
    listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")
)
private val ONE_HAND = listOf(
    listOf("q", "w", "e", "r", "t", "y"),
    listOf("u", "i", "o", "p", "a", "s"),
    listOf("d", "f", "g", "h", "j", "k"),
    listOf("l", "z", "x", "c", "v", "b"),
    listOf("n", "m")
)

private val SYMBOL_PAGES = listOf(
    listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "￥", "%", "&", "*", "-", "+", "=", "_"),
        listOf("(", ")", "【", "】", "「", "」", "《", "》", "!", "?")
    ),
    listOf(
        listOf(",", ".", ":", ";", "'", "\"", "/", "\\", "|", "~"),
        listOf("、", "。", "？", "！", "；", "：", "“", "”", "‘", "’"),
        listOf("…", "—", "～", "·", "＾", "＄", "％", "＊", "＋", "－")
    ),
    listOf(
        listOf("^", "$", "€", "£", "§", "¶", "†", "‡", "©", "®"),
        listOf("℃", "°", "±", "×", "÷", "≠", "≈", "≤", "≥", "∞"),
        listOf("∑", "∏", "∫", "√", "∂", "∆", "π", "Ω", "µ", "∴")
    ),
    listOf(
        listOf("α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "λ", "σ"),
        listOf("Δ", "Φ", "Ψ", "Ξ", "Σ", "Θ", "Λ", "Π", "♀", "♂"),
        listOf("←", "↑", "→", "↓", "↔", "↕", "↖", "↗", "↘", "↙")
    ),
    listOf(
        listOf("♥", "★", "☆", "♪", "♫", "☀", "☁", "☂", "☃", "✿"),
        listOf("✓", "✔", "✗", "✘", "☎", "✉", "✂", "➤", "➔", "❖"),
        listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
    ),
    listOf(
        listOf("Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ"),
        listOf("㎡", "㎥", "㎝", "㎞", "㎏", "㏄", "¤", "¢", "₩", "₪"),
        listOf("【", "】", "｛", "｝", "〔", "〕", "〖", "〗", "〈", "〉")
    )
)

private val EMOJI_PAGES = listOf(
    listOf("😀", "😁", "😂", "🤣", "😃", "😄", "😅", "😆", "😉", "😊", "😋", "😎", "😍", "😘", "🥰", "😗", "🙂", "🤗", "🤩", "🤔", "🤨", "😐", "😑", "😶"),
    listOf("🙄", "😏", "😣", "😥", "😮", "🤐", "😯", "😪", "😫", "🥱", "😴", "😌", "😛", "😜", "😝", "🤤", "😒", "😓", "😔", "😕", "🙃", "🤑", "😲", "☹️"),
    listOf("🙁", "😖", "😞", "😟", "😤", "😢", "😭", "😦", "😧", "😨", "😩", "🤯", "😬", "😰", "😱", "🥵", "🥶", "😳", "🤪", "😵", "😡", "😠", "🤬", "😷"),
    listOf("🤒", "🤕", "🤢", "🤮", "🤧", "😇", "🥳", "🥺", "🤠", "🤡", "🤥", "🤫", "🤭", "🧐", "🤓", "😈", "👿", "👹", "👺", "💀", "☠️", "👻", "👽", "🤖"),
    listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "👍", "👎", "👌", "✌️", "🤞"),
    listOf("🤟", "🤘", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐", "🖖", "👋", "🤙", "💪", "🙏", "👏", "🤝", "✍️", "🎉", "🎊", "🎁", "🌹", "🌸", "🍀"),
    listOf("🔥", "⭐", "🌟", "✨", "⚡", "☀️", "🌈", "🌙", "☁️", "❄️", "💧", "🍎", "🍉", "🍓", "🍑", "🍒", "🥝", "🍔", "🍟", "🍕", "🍰", "🍦", "☕", "🍺")
)

object KeyMetrics {
    var heightScale by mutableFloatStateOf(1f)
    var keyColor by mutableIntStateOf(0)
}

// ---------- 主入口 ----------

@Composable
fun KeyboardScreen(host: KeyboardHost) {
    val state = host.state
    KeyMetrics.keyColor = AppPrefs.keyColor

    val config = LocalConfiguration.current
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showLayoutPicker by remember { mutableStateOf(false) }

    val sms by SmsCodeHolder.codeFlow.collectAsState()
    LaunchedEffect(sms) { state.smsCode = sms }

    Box(Modifier.fillMaxWidth().wrapContentHeight()) {
        BackgroundLayer(Modifier.matchParentSize())
        when {
            landscape && state.floating -> FloatingKeyboard(host) { showLayoutPicker = !showLayoutPicker }
            state.singleHand -> SingleHandKeyboard(host) { showLayoutPicker = !showLayoutPicker }
            else -> KeyboardColumn(host, Modifier.fillMaxWidth()) { showLayoutPicker = !showLayoutPicker }
        }
        if (showLayoutPicker) {
            LayoutPicker(
                current = state.layoutVersion % 3,
                onPick = { mode -> host.setLayout(mode); showLayoutPicker = false },
                onDismiss = { showLayoutPicker = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 6.dp)
            )
        }
        if (state.showDeleteBubble) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 96.dp, bottom = 150.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("⌫ 左滑全删", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        state.smsCode?.let { code ->
            Box(
                Modifier.align(Alignment.TopStart).padding(top = 84.dp, start = 8.dp)
                    .background(Color(0xFF2E7D32), RoundedCornerShape(16.dp))
                    .tapOnce(code) {
                        host.commitText(code)
                        host.playKeySound(KeySoundManager.KIND_TAP)
                        SmsCodeHolder.consume()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("📩 验证码 $code · 点击输入", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        if (state.listening || state.voiceStatus.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 46.dp)
                    .background(Color(0xDD2B1B33), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    if (state.voiceStatus.isNotEmpty()) state.voiceStatus else "🎤 听写中，松开空格结束…",
                    fontSize = 13.sp, color = Color.White
                )
            }
        }
        if (landscape && !state.floating) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(Color(0x66FFFFFF), RoundedCornerShape(6.dp))
                    .tapOnce("float") { state.floating = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("悬浮", fontSize = 11.sp, color = Color(0xFF4A2B5A)) }
        }
    }
}

/** 点击手势：用 rememberUpdatedState 保证回调永远读到最新闭包，杜绝点上屏错/翻页跳级 */
@Composable
private fun Modifier.tapOnce(key: Any?, block: () -> Unit): Modifier {
    val cur by rememberUpdatedState(block)
    return this.pointerInput(key) {
        detectTapGestures(onTap = { cur() })
    }
}

@Composable
private fun LayoutPicker(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    Column(
        modifier.background(Color(0xF5FFF3F8), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x55B23A8F), RoundedCornerShape(12.dp))
            .padding(6.dp)
    ) {
        for ((i, label) in listOf("26键", "九宫格", "手写").withIndex()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .background(if (i == current) Color(0xFFE86AC0) else Color.Transparent, RoundedCornerShape(8.dp))
                    .tapOnce("pick$i") { onPick(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(label, fontSize = 14.sp, color = if (i == current) Color.White else Color(0xFF4A2B5A))
            }
        }
        Box(
            Modifier.fillMaxWidth().tapOnce("dismiss") { onDismiss() }
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) { Text("收起", fontSize = 12.sp, color = Color(0xFF8A6B7A)) }
    }
}

@Composable
private fun BackgroundLayer(modifier: Modifier = Modifier) {
    // 订阅可观察状态：用户在设置页换背景后键盘立即重组，无需重启输入法进程
    val bgPath by AppPrefs.customBackgroundPath.collectAsState()
    val animOn by AppPrefs.bgAnimationOn.collectAsState()

    // 异步解码：图片解码是重活，放在组合里同步做会阻塞主线程，
    // 大图（即使已降采样到 1080）仍可能卡顿甚至 ANR。
    var bitmap by remember(bgPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(bgPath) {
        bitmap = if (bgPath.isEmpty()) null
        else withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val f = java.io.File(bgPath)
                if (!f.exists() || f.length() <= 0) null
                else android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    val bmp = bitmap
    if (bgPath.isNotEmpty() && bmp != null) {
        Image(bmp.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        PixelArtBackground(modifier = modifier, animOn = animOn)
    }
}

@Composable
private fun SingleHandKeyboard(host: KeyboardHost, onSwitch: () -> Unit) {
    val context = LocalContext.current
    val side = remember(context) { GravitySideResolver(context) }
    DisposableEffect(side) { onDispose { side.unregister() } }
    val alignLeft = side.isLeft()
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignLeft) Alignment.BottomStart else Alignment.BottomEnd) {
        Column(Modifier.fillMaxWidth(0.62f)) {
            TopBar(host, onSwitch)
            TranslateBox(host)
            CandidateBar(host)
            OneHandKeys(host)
            OneHandBottom(host)
        }
    }
}

@Composable
private fun FloatingKeyboard(host: KeyboardHost, onSwitch: () -> Unit) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(0.8f) }
    Column(
        Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .fillMaxWidth(scale)
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).background(Color(0x88B23A8F))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offsetX += drag.x
                        offsetY += drag.y
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("  ⠿ 拖拽移动", fontSize = 10.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("－", Modifier.tapOnce("shrink") { scale = (scale - 0.1f).coerceAtLeast(0.5f) }, color = Color.White, fontSize = 13.sp)
            Text("＋", Modifier.tapOnce("grow") { scale = (scale + 0.1f).coerceAtMost(1.2f) }, color = Color.White, fontSize = 13.sp)
            Text("退出", Modifier.tapOnce("exitFloat") { host.state.floating = false }, color = Color.White, fontSize = 10.sp)
        }
        KeyboardColumn(host, Modifier.fillMaxWidth(), onSwitch)
    }
}

@Composable
private fun KeyboardColumn(host: KeyboardHost, modifier: Modifier, onSwitch: () -> Unit) {
    val state = host.state
    Column(modifier) {
        TopBar(host, onSwitch)
        TranslateBox(host)
        CandidateBar(host)
        when (state.panel) {
            Panel.EMOJI -> EmojiPanel(host)
            Panel.SYMBOLS -> SymbolPanel(host)
            Panel.CLIPBOARD -> ClipboardPanel(host)
            Panel.CALCULATOR -> CalculatorPanel(host)
            Panel.VOICE -> VoicePanel(host)
            Panel.DICT -> DictPanel(host)
            Panel.HANDWRITING -> HandwritingWrap(host)
            else -> when (state.layoutVersion % 3) {
                1 -> T9Keyboard(host)
                2 -> HandwritingWrap(host)
                else -> QwertyKeyboard(host)
            }
        }
    }
}

// ---------- 顶部功能条（文字标签，从左到右排开） ----------

@Composable
private fun TopBar(host: KeyboardHost, onSwitch: () -> Unit) {
    val state = host.state
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(Color(0x66FFFFFF)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TopBtn("剪切板", active = state.panel == Panel.CLIPBOARD, key = "clip") {
            state.panel = if (state.panel == Panel.CLIPBOARD) Panel.NONE else Panel.CLIPBOARD
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("表情", active = state.panel == Panel.EMOJI, key = "emoji") {
            state.panel = if (state.panel == Panel.EMOJI) Panel.NONE else Panel.EMOJI
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("计算器", active = state.panel == Panel.CALCULATOR, key = "calc") {
            state.panel = if (state.panel == Panel.CALCULATOR) Panel.NONE else Panel.CALCULATOR
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("关闭", active = false, key = "hide") {
            host.playKeySound(KeySoundManager.KIND_TAP)
            host.hideKeyboard()
        }
        TopBtn("⌨", active = false, key = "layout") { onSwitch() }
        TopBtn(if (state.isChinese) "中" else "英", active = false, key = "lang${state.isChinese}",
            onLong = { host.onLangLongPress() }, onClick = { host.toggleLang() })
    }
}

@Composable
private fun RowScope.TopBtn(
    label: String, active: Boolean, key: String,
    onLong: (() -> Unit)? = null, onClick: () -> Unit
) {
    Box(
        Modifier.weight(1f).height(34.dp)
            .background(if (active) Color(0x66E86AC0) else Color.Transparent, RoundedCornerShape(8.dp))
            .tapLong(key, onLong, onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = if (label.length > 2) 11.sp else 13.sp,
            color = Color(0xFF4A2B5A), fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 点击/长按手势：用 rememberUpdatedState 保证回调读最新闭包 */
@Composable
private fun Modifier.tapLong(key: Any?, onLong: (() -> Unit)?, onClick: () -> Unit): Modifier {
    val curClick by rememberUpdatedState(onClick)
    val curLong by rememberUpdatedState(onLong)
    return this.pointerInput(key, onLong != null) {
        kotlinx.coroutines.coroutineScope {
            while (true) {
                awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var longFired = false
                    val job = if (curLong != null) launch {
                        delay(420); longFired = true; curLong?.invoke()
                    } else null
                    var pressed = true
                    while (pressed) {
                        val ev = awaitPointerEvent()
                        pressed = ev.changes.any { it.id == down.id && it.pressed }
                    }
                    job?.cancel()
                    if (!longFired) curClick()
                }
            }
        }
    }
}

// ---------- 常驻翻译输入框（中英双框互译） ----------

@Composable
private fun TranslateBox(host: KeyboardHost) {
    val state = host.state

    // 中文框变化 → 翻译成英文；英文框变化 → 翻译成中文（防抖 + 防回环）
    LaunchedEffect(state.translateCn, state.translateFocus) {
        if (state.translateFocus != 1 || state.translateCn.isBlank()) return@LaunchedEffect
        delay(700)
        if (state.translating) return@LaunchedEffect
        state.translating = true
        host.translateText(state.translateCn) { list ->
            list.firstOrNull()?.let { en ->
                state.translateEn = en.filter { it.isLetter() || it == ' ' }
            }
            state.translating = false
        }
    }
    LaunchedEffect(state.translateEn, state.translateFocus) {
        if (state.translateFocus != 2 || state.translateEn.isBlank()) return@LaunchedEffect
        delay(700)
        if (state.translating) return@LaunchedEffect
        state.translating = true
        host.translateText(state.translateEn) { list ->
            list.firstOrNull()?.let { cn ->
                state.translateCn = cn.filter { it in '\u4e00'..'\u9fff' || "，。！？、".contains(it) }
            }
            state.translating = false
        }
    }

    val anyActive = state.translateActive
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // 左：中文框
            TranslateField(
                modifier = Modifier.weight(1f),
                placeholder = "中文",
                text = state.translateCn,
                active = state.translateFocus == 1,
                flag = "🇨🇳",
                onClick = {
                    state.translateFocus = if (state.translateFocus == 1) 0 else 1
                    host.playKeySound(KeySoundManager.KIND_TAP)
                }
            )
            // 右：英文框
            TranslateField(
                modifier = Modifier.weight(1f),
                placeholder = "English",
                text = state.translateEn,
                active = state.translateFocus == 2,
                flag = "🇬🇧",
                onClick = {
                    state.translateFocus = if (state.translateFocus == 2) 0 else 2
                    host.playKeySound(KeySoundManager.KIND_TAP)
                }
            )
        }
        // 激活时的一行操作提示 + 上屏 + 关闭
        if (anyActive) {
            Row(
                Modifier.fillMaxWidth().padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.translateFocus == 1) "正在输入中文，右侧自动显示英文" else "正在输入英文，左侧自动显示中文",
                    fontSize = 11.sp, color = Color(0xFF8A6B7A), modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier.background(Color(0xFFB23A8F), RoundedCornerShape(8.dp))
                        .tapOnce("tsend") {
                            val out = if (state.translateFocus == 1) state.translateCn else state.translateEn
                            if (out.isNotBlank()) {
                                host.commitText(out)
                                host.playKeySound(KeySoundManager.KIND_TAP)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) { Text("上屏", fontSize = 12.sp, color = Color.White) }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.background(Color(0x33B23A8F), RoundedCornerShape(8.dp))
                        .tapOnce("tclose") {
                            state.closeTranslate()
                            host.playKeySound(KeySoundManager.KIND_TAP)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) { Text("关闭", fontSize = 12.sp, color = Color(0xFF4A2B5A)) }
            }
        }
    }
}

@Composable
private fun TranslateField(
    modifier: Modifier, placeholder: String, text: String, active: Boolean,
    flag: String, onClick: () -> Unit
) {
    Row(
        modifier
            .height(40.dp)
            .background(if (active) Color(0xCCFFE9F6) else Color(0x88FFFFFF), RoundedCornerShape(10.dp))
            .border(
                if (active) 2.dp else 1.dp,
                if (active) Color(0xFFE86AC0) else Color(0x33B23A8F),
                RoundedCornerShape(10.dp)
            )
            .tapOnce("tf$flag") { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(flag, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            if (text.isEmpty()) placeholder else text,
            fontSize = 13.sp,
            color = if (text.isEmpty()) Color(0x994A2B5A) else Color(0xFF2B1B33),
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------- 候选栏 ----------

@Composable
private fun CandidateBar(host: KeyboardHost) {
    val state = host.state
    val dictVer by PinyinEngine.indexVersionFlow.collectAsState()
    LaunchedEffect(dictVer) {
        if (state.composing.isNotEmpty()) state.refreshCandidates()
    }
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Color(0x66FFFFFF)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.composing.isNotEmpty()) {
            Text(
                if (state.capsMode > 0) state.composing.uppercase() else state.composing,
                fontSize = 15.sp, color = Color(0xFF5B3A6E),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        if (state.candidates.isEmpty() && state.composing.isEmpty()) {
            Text("梦婷输入法", fontSize = 12.sp, color = Color(0x774A2B5A),
                modifier = Modifier.padding(horizontal = 10.dp))
        }
        LazyRow(Modifier.weight(1f)) {
            // 关键：item key 绑定候选内容，避免复用旧闭包导致上屏错词
            items(state.candidates.toList(), key = { it }) { c ->
                Text(
                    displayCase(state, c), fontSize = 16.sp, color = Color(0xFF2B1B33),
                    modifier = Modifier
                        .tapOnce(c) { onCandidateClick(host, c) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun displayCase(state: KeyboardState, c: String): String =
    if (state.capsMode > 0) c.uppercase() else c

private fun onCandidateClick(host: KeyboardHost, c: String) {
    val state = host.state
    host.playKeySound(KeySoundManager.KIND_TAP)
    if (state.translateActive) {
        // 英文框聚焦时选英文词，中文框聚焦时选中文词，按语言过滤后追加
        state.appendTrans(displayCase(state, c))
        state.clearComposition()
    } else {
        host.commitText(displayCase(state, c))
    }
}

// ---------- 剪贴板面板 ----------

@Composable
private fun ClipboardPanel(host: KeyboardHost) {
    var items by remember { mutableStateOf(ClipboardHistory.load(host.context())) }
    val state = host.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("剪贴板（最近20条）", fontSize = 12.sp, color = Color(0xFF4A2B5A), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("刷新", fontSize = 12.sp, color = Color(0xFFB23A8F), modifier = Modifier.tapOnce("cliprefresh") {
                ClipboardHistory.captureCurrent(host.context())
                items = ClipboardHistory.load(host.context())
            }.padding(horizontal = 8.dp))
            Text("返回", fontSize = 12.sp, color = Color(0xFFB23A8F), modifier = Modifier.tapOnce("clipback") {
                state.panel = Panel.NONE
            }.padding(horizontal = 8.dp))
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text("暂无复制记录\n复制文字后会自动出现在这里", fontSize = 13.sp,
                    color = Color(0xFF8A6B7A), textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
                items(items, key = { it.hashCode() }) { item ->
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .background(Color(0xAAFFFFFF), RoundedCornerShape(8.dp))
                            .tapOnce(item) {
                                host.commitText(item)
                                host.playKeySound(KeySoundManager.KIND_TAP)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(item.replace("\n", " ").take(60), fontSize = 14.sp, color = Color(0xFF2B1B33))
                    }
                }
            }
        }
    }
}

// ---------- 计算器面板 ----------

@Composable
private fun CalculatorPanel(host: KeyboardHost) {
    val state = host.state
    LaunchedEffect(state.calcExpr) {
        state.calcResult = if (state.calcExpr.isBlank()) "" else {
            CalcEval.eval(state.calcExpr)?.let { CalcEval.format(it) } ?: ""
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(60.dp).padding(vertical = 4.dp)
                .background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                .tapOnce("calcresult") {
                    val text = if (state.calcResult.isNotBlank()) state.calcResult else state.calcExpr
                    if (text.isNotBlank()) {
                        host.commitText(text)
                        host.playKeySound(KeySoundManager.KIND_TAP)
                    }
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(state.calcExpr.ifBlank { "0" }, fontSize = 18.sp, color = Color(0xFF2B1B33))
                if (state.calcResult.isNotBlank()) {
                    Text("= ${state.calcResult}（点击输入）", fontSize = 14.sp, color = Color(0xFFB23A8F))
                }
            }
        }
        val rows = listOf(
            listOf("7", "8", "9", "÷", "%"),
            listOf("4", "5", "6", "×", "("),
            listOf("1", "2", "3", "-", ")"),
            listOf("0", ".", "+", "C", "⌫")
        )
        for (row in rows) {
            Row(Modifier.fillMaxWidth()) {
                for (k in row) KeyBox(k, baseHeight = 44, onClick = {
                    host.playKeySound(KeySoundManager.KIND_TAP)
                    when (k) {
                        "C" -> { state.calcExpr = ""; state.calcResult = "" }
                        "⌫" -> { state.calcExpr = state.calcExpr.dropLast(1) }
                        else -> state.calcExpr += k
                    }
                })
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox("返回", weight = 1.6f, onClick = {
                state.panel = Panel.NONE
                host.playKeySound(KeySoundManager.KIND_TAP)
            })
            KeyBox("＝ 计算并输入", weight = 3f, onClick = {
                val v = CalcEval.eval(state.calcExpr)
                if (v != null) {
                    host.commitText(CalcEval.format(v))
                    host.playKeySound(KeySoundManager.KIND_TAP)
                    state.calcExpr = ""; state.calcResult = ""
                }
            })
            KeyBox("⏎", weight = 1.4f, onClick = {
                host.playKeySound(KeySoundManager.KIND_TAP)
                host.sendEnter()
            })
        }
    }
}

// ---------- 符号面板 ----------

@Composable
private fun SymbolPanel(host: KeyboardHost) {
    val state = host.state
    val pages = SYMBOL_PAGES
    val page = state.symbolPage.coerceIn(0, pages.size - 1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        for (row in pages[page]) {
            Row(Modifier.fillMaxWidth()) {
                for (k in row) KeyBox(k, baseHeight = 42, onClick = {
                    host.playKeySound(KeySoundManager.KIND_TAP)
                    host.commitText(k)
                })
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox("返回", weight = 1.4f, onClick = {
                state.panel = Panel.NONE
                host.playKeySound(KeySoundManager.KIND_TAP)
            })
            KeyBox("‹ 上页", weight = 1.3f, onClick = { if (page > 0) state.symbolPage = page - 1 })
            Box(Modifier.weight(0.9f), contentAlignment = Alignment.Center) {
                Text("${page + 1}/${pages.size}", fontSize = 11.sp, color = Color(0xFF4A2B5A))
            }
            KeyBox("下页 ›", weight = 1.3f, onClick = { if (page < pages.size - 1) state.symbolPage = page + 1 })
            KeyBox("⌫", weight = 1.1f, onClick = {
                host.playKeySound(KeySoundManager.KIND_DELETE); panelDelete(host)
            })
            KeyBox("⏎", weight = 1.2f, onClick = {
                host.playKeySound(KeySoundManager.KIND_TAP); host.sendEnter()
            })
        }
    }
}

// ---------- 表情面板 ----------

@Composable
private fun EmojiPanel(host: KeyboardHost) {
    val state = host.state
    val pages = EMOJI_PAGES
    val page = state.emojiPage.coerceIn(0, pages.size - 1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        for (row in pages[page].chunked(8)) {
            Row(Modifier.fillMaxWidth()) {
                for (e in row) KeyBox(e, baseHeight = 40, onClick = {
                    host.playKeySound(KeySoundManager.KIND_TAP)
                    host.commitText(e)
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                    .tapOnce("emojiback") { state.panel = Panel.NONE }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("返回", fontSize = 12.sp, color = Color(0xFF4A2B5A)) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                    .tapOnce("emojiprev$page") { if (page > 0) state.emojiPage = page - 1 }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("‹ 上页", fontSize = 12.sp, color = Color(0xFF4A2B5A)) }
            Text(" ${page + 1}/${pages.size} ", fontSize = 11.sp, color = Color(0xFF8A6B7A))
            Box(
                Modifier.background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                    .tapOnce("emojinext$page") { if (page < pages.size - 1) state.emojiPage = page + 1 }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("下页 ›", fontSize = 12.sp, color = Color(0xFF4A2B5A)) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.background(Color(0xFFB23A8F), RoundedCornerShape(10.dp))
                    .tapOnce("emojienter") { host.sendEnter(); host.playKeySound(KeySoundManager.KIND_TAP) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text("⏎ 回车", fontSize = 12.sp, color = Color.White) }
        }
    }
}

@Composable
private fun HandwritingWrap(host: KeyboardHost, dictMode: Boolean = false, onDictChar: ((Char) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        HandwritingKeyboard(host, commitOnPick = !dictMode, onPickChar = { s ->
            if (dictMode && s.isNotEmpty()) onDictChar?.invoke(s[0])
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            if (!dictMode) {
                Text("⏎ 回车", fontSize = 13.sp, color = Color.White,
                    modifier = Modifier.background(Color(0xFF4A2B5A), RoundedCornerShape(10.dp))
                        .tapOnce("hwenter") { host.sendEnter() }
                        .padding(horizontal = 14.dp, vertical = 7.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text("返回键盘", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.background(Color(0xFFB23A8F), RoundedCornerShape(10.dp))
                    .tapOnce("hwback") {
                        host.state.panel = Panel.NONE
                        if (!dictMode && host.state.layoutVersion % 3 == 2) host.setLayout(0)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

// ---------- 语音面板（第8项：大麦克风，点按开始/停止长时听写） ----------

@Composable
private fun VoicePanel(host: KeyboardHost) {
    val state = host.state
    Column(
        Modifier.fillMaxWidth().height(220.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 大麦克风按钮
        Box(
            Modifier.size(96.dp)
                .background(
                    if (state.listening) Color(0xFFE53935) else Color(0xFFB23A8F),
                    RoundedCornerShape(48.dp)
                )
                .tapOnce("voicemic") {
                    if (state.listening) host.voiceStop() else host.voiceStart()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(if (state.listening) "■" else "🎤", fontSize = 40.sp, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                state.voiceStatus.isNotEmpty() -> state.voiceStatus
                state.listening -> "正在听写…再次点击麦克风结束（可长时间连续输入）"
                else -> "点击麦克风开始语音转文字"
            },
            fontSize = 12.sp, color = Color(0xFF4A2B5A), textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Box(
                Modifier.background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                    .tapOnce("voiceback") { state.panel = Panel.NONE }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) { Text("返回键盘", fontSize = 13.sp, color = Color(0xFF4A2B5A)) }
        }
    }
}

// ---------- 字典面板（第7项：手写生僻字 → 读音+组词，本地离线） ----------

@Composable
private fun DictPanel(host: KeyboardHost) {
    val state = host.state
    var picked by remember { mutableStateOf<Char?>(null) }
    Column(Modifier.fillMaxWidth()) {
        // 释义展示区
        Column(
            Modifier.fillMaxWidth().padding(6.dp)
                .background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            val ch = picked
            if (ch == null) {
                Text("在下方手写一个生僻字，这里显示它的读音与常见组词", fontSize = 12.sp, color = Color(0xFF8A6B7A))
            } else {
                val pys = remember(ch) { PinyinEngine.charPinyin(ch) }
                val words = remember(ch) { PinyinEngine.wordsContaining(ch, 12) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.toString(), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color(0xFFB23A8F))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("读音：${if (pys.isEmpty()) "（本地无记录）" else pys.joinToString(" / ")}",
                            fontSize = 14.sp, color = Color(0xFF2B1B33))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("常见组词：", fontSize = 12.sp, color = Color(0xFF6B5670))
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    if (words.isEmpty()) {
                        Text("（本地词库暂无组词）", fontSize = 13.sp, color = Color(0xFF8A6B7A))
                    } else {
                        for (w in words.take(8)) {
                            Box(
                                Modifier.padding(end = 6.dp, bottom = 4.dp)
                                    .background(Color(0xFFE86AC0), RoundedCornerShape(8.dp))
                                    .tapOnce(w) { host.commitText(w); host.playKeySound(KeySoundManager.KIND_TAP) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text(w, fontSize = 13.sp, color = Color.White) }
                        }
                    }
                }
                // 单字上屏
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.background(Color(0xFF4A2B5A), RoundedCornerShape(8.dp))
                            .tapOnce("dictcommit") { host.commitText(ch.toString()); host.playKeySound(KeySoundManager.KIND_TAP) }
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) { Text("输入「$ch」", fontSize = 12.sp, color = Color.White) }
                }
            }
        }
        // 手写板（字典模式：选字不上屏，仅查询）
        HandwritingWrap(host, dictMode = true, onDictChar = { c -> picked = c })
    }
}

// ---------- 按键组件（手势 key 绑定 label，杜绝旧闭包） ----------

@Composable
private fun RowScope.KeyBox(
    label: String, weight: Float = 1f, baseHeight: Int = 46,
    onLong: (() -> Unit)? = null,
    onLongRelease: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val h = (baseHeight * KeyMetrics.heightScale).roundToInt()
    val bg = if (KeyMetrics.keyColor != 0) Color(KeyMetrics.keyColor) else Color(0xAAFFFFFF)
    // 关键：回调用 rememberUpdatedState 包装，pointerInput 只启动一次但永远读到最新闭包，
    // 彻底根治翻页/切页后点击仍执行旧页码逻辑的 bug
    val curClick by rememberUpdatedState(onClick)
    val curLong by rememberUpdatedState(onLong)
    val curLongRel by rememberUpdatedState(onLongRelease)
    val gesture: Modifier = Modifier.pointerInput(Unit) {
        kotlinx.coroutines.coroutineScope {
            while (true) {
                awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var longFired = false
                    val job = if (curLong != null) launch {
                        delay(420); longFired = true; curLong?.invoke()
                    } else null
                    var pressed = true
                    while (pressed) {
                        val ev = awaitPointerEvent()
                        pressed = ev.changes.any { it.id == down.id && it.pressed }
                    }
                    job?.cancel()
                    if (longFired) curLongRel?.invoke() else curClick()
                }
            }
        }
    }
    Box(
        Modifier
            .weight(weight)
            .height(h.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, Color(0x33B23A8F), RoundedCornerShape(8.dp))
            .then(gesture),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = if (label.length > 2) 11.sp else 16.sp, color = Color(0xFF2B1B33),
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 删除键：长按左滑全删（手势 key 稳定，逻辑走 host） */
@Composable
private fun RowScope.DeleteKey(host: KeyboardHost, weight: Float = 2f, baseHeight: Int = 46) {
    val h = (baseHeight * KeyMetrics.heightScale).roundToInt()
    val bg = if (KeyMetrics.keyColor != 0) Color(KeyMetrics.keyColor) else Color(0xAAFFFFFF)
    Box(
        Modifier
            .weight(weight)
            .height(h.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, Color(0x33B23A8F), RoundedCornerShape(8.dp))
            .pointerInput("del") {
                kotlinx.coroutines.coroutineScope {
                    while (true) {
                        awaitPointerEventScope {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            host.onDeleteDown(down.position.x, down.position.y)
                            var consumedByMove = false
                            val job = launch { delay(400) }
                            var longFired = false
                            var pressed = true
                            while (pressed) {
                                val ev = awaitPointerEvent()
                                for (ch in ev.changes) {
                                    if (ch.id != down.id) continue
                                    if (!ch.pressed) { pressed = false; continue }
                                    if (!longFired && job.isCompleted) longFired = true
                                    if (longFired && host.onDeleteMove(ch.position.x, ch.position.y)) {
                                        consumedByMove = true
                                        ch.consume()
                                    }
                                }
                            }
                            job.cancel()
                            host.onDeleteUp()
                            if (!consumedByMove) {
                                host.playKeySound(KeySoundManager.KIND_DELETE)
                                panelDelete(host)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text("⌫", fontSize = 16.sp, color = Color(0xFF2B1B33), fontWeight = FontWeight.Medium)
    }
}

// ---------- 26 键（三行各 10 键等宽） ----------

@Composable
private fun QwertyKeyboard(host: KeyboardHost) {
    val state = host.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        // 第一行：10 键
        Row(Modifier.fillMaxWidth()) {
            for (k in ROW1) LetterKey(host, k)
        }
        // 第二行：分词 + 9 字母 = 10 键
        Row(Modifier.fillMaxWidth()) {
            KeyBox("'", onClick = {
                host.playKeySound(KeySoundManager.KIND_TAP)
                // 分词符：进组合区用于拼音切分歧义（xi'an）
                state.composing += "'"
                host.setComposing(state.composing)
            })
            for (k in ROW2) LetterKey(host, k)
        }
        // 第三行：大写 + 7 字母 + 删除(双宽) = 10 单位
        Row(Modifier.fillMaxWidth()) {
            KeyBox(capsLabel(state), onClick = {
                state.capsMode = when (state.capsMode) { 0 -> 1; 1 -> 2; else -> 0 }
                host.playKeySound(KeySoundManager.KIND_TAP)
            })
            for (k in ROW3) LetterKey(host, k)
            DeleteKey(host, weight = 2f)
        }
        // 第四行：语音 | 字典 | 全选 | 复制 | 单手（单手在最右）
        Row(Modifier.fillMaxWidth()) {
            KeyBox("语音", weight = 2f, onClick = {
                state.panel = if (state.panel == Panel.VOICE) Panel.NONE else Panel.VOICE
                host.playKeySound(KeySoundManager.KIND_TAP)
            })
            KeyBox("字典", weight = 2f, onClick = {
                state.panel = if (state.panel == Panel.DICT) Panel.NONE else Panel.DICT
                host.playKeySound(KeySoundManager.KIND_TAP)
            })
            KeyBox("全选", weight = 2f, onClick = { host.selectAll(); host.playKeySound(KeySoundManager.KIND_TAP) })
            KeyBox("复制", weight = 2f, onClick = { host.copySelection(); host.playKeySound(KeySoundManager.KIND_TAP) })
            KeyBox(if (state.singleHand) "单手✓" else "单手", weight = 2f, onClick = {
                state.singleHand = !state.singleHand
                host.playKeySound(KeySoundManager.KIND_TAP)
            })
        }
        // 第五行：123 | ， | 空格(居中) | 。 | ⏎
        BottomRow(host)
    }
}

@Composable
private fun RowScope.LetterKey(host: KeyboardHost, k: String) {
    val state = host.state
    KeyBox(displayCase(state, k), onClick = {
        host.playKeySound(KeySoundManager.KIND_TAP)
        onChar(host, k)
    })
}

private fun capsLabel(state: KeyboardState) = when (state.capsMode) {
    2 -> "⇪CAPS"
    1 -> "⇧"
    else -> "⇧"
}

@Composable
private fun ColumnScope.BottomRow(host: KeyboardHost) {
    val state = host.state
    val enterLabel = host.enterKeyLabel()
    Row(Modifier.fillMaxWidth()) {
        KeyBox("123", weight = 1.1f, onClick = {
            state.panel = if (state.panel == Panel.SYMBOLS) Panel.NONE else Panel.SYMBOLS
            host.playKeySound(KeySoundManager.KIND_TAP)
        })
        KeyBox("，", weight = 1f, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            if (state.composing.isNotEmpty()) { flushComposition(host); return@KeyBox }
            if (state.translateActive) { state.appendTrans("，"); return@KeyBox }
            host.commitText("，")
        })
        KeyBox(
            "空格", weight = 3f,
            onLong = { host.onSpaceLongPress() },
            onLongRelease = { host.onSpaceRelease() },
            onClick = {
                host.playKeySound(KeySoundManager.KIND_SPACE)
                onSpaceTap(host)
            }
        )
        KeyBox("。", weight = 1f, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            if (state.composing.isNotEmpty()) { flushComposition(host); return@KeyBox }
            if (state.translateActive) { state.appendTrans("。"); return@KeyBox }
            host.commitText("。")
        })
        KeyBox(enterLabel, weight = 1.1f, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            flushComposition(host)
            host.sendEnter()
        })
    }
}

private fun onSpaceTap(host: KeyboardHost) {
    val state = host.state
    if (state.listening) { host.onSpaceRelease(); return }
    if (state.composing.isNotEmpty()) {
        // 有组合：优先提交首选词（翻译态进对应框，普通态进输入框）
        val word = state.candidates.firstOrNull() ?: state.composing
        val text = displayCase(state, word)
        if (state.translateActive) state.appendTrans(text) else host.commitText(text)
        state.clearComposition()
        if (!state.translateActive) host.commitText(" ")
        return
    }
    if (state.translateActive) {
        // 英文框补空格分隔单词
        if (state.isEnTarget()) state.appendTrans(" ")
        return
    }
    host.commitText(" ")
}

private fun panelDelete(host: KeyboardHost) {
    val state = host.state
    if (state.panel == Panel.CALCULATOR) {
        state.calcExpr = state.calcExpr.dropLast(1)
        return
    }
    if (state.composing.isNotEmpty()) { host.deleteBackward(); return }
    if (state.translateActive) {
        state.transBackspace()
        return
    }
    host.deleteBackward()
}

private fun flushComposition(host: KeyboardHost) {
    val s = host.state
    if (s.composing.isNotEmpty()) {
        val text = if (s.candidates.isNotEmpty()) displayCase(s, s.candidates.first()) else displayCase(s, s.composing)
        if (s.translateActive) s.appendTrans(text) else host.commitText(text)
        s.clearComposition()
    }
}

/**
 * 字母键输入入口。
 *
 * 声明为 public 是因为 androidTest 是独立编译模块，Kotlin 的 internal（模块级可见性）
 * 无法被测试 APK 访问，会抛 IllegalAccessError；本模块是 app 而非 library，公开它无 API 契约负担。
 */
fun onChar(host: KeyboardHost, c: String) {
    val state = host.state
    // 大写开启时字母直接上屏，不进拼音组合区。
    // 否则中文键盘下点大写再点字母会被强制要求选候选词，无法直接输入大写字母。
    if (state.capsMode > 0 && c.length == 1 && c[0].isLetter()) {
        // 先算好大写字符：commitText() 内部会把「单次大写」复位为 0，
        // 若放在 flushComposition 之后再取值，单次大写会被 flush 的提交提前吃掉。
        val upper = c.uppercase()
        // 有未提交的拼音组合时先按首选词上屏，避免大写字母与拼音串混杂
        flushComposition(host)
        if (state.translateActive) {
            // 翻译框不经 commitText()，单次大写需在此复位，否则会退化成锁死大写
            state.appendTrans(upper)
            if (state.capsMode == 1) state.capsMode = 0
        } else {
            host.commitText(upper)
        }
        return
    }
    // 字母进组合区以产生候选；翻译框聚焦时不把拼音推入主编辑器（避免泄漏到应用输入框）
    state.composing += c.lowercase()
    if (!state.translateActive) host.setComposing(state.composing)
}

// ---------- 九宫格 ----------

@Composable
private fun T9Keyboard(host: KeyboardHost) {
    val state = host.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
        for (row in T9) {
            Row(Modifier.fillMaxWidth()) {
                for (k in row) {
                    val label = k + when (k) {
                        "2" -> " ABC"; "3" -> " DEF"; "4" -> " GHI"; "5" -> " JKL"
                        "6" -> " MNO"; "7" -> " PQRS"; "8" -> " TUV"; "9" -> " WXYZ"
                        else -> ""
                    }
                    KeyBox(label, baseHeight = 50, onClick = {
                        host.playKeySound(KeySoundManager.KIND_TAP)
                        if (state.isChinese && k in "23456789") {
                            state.composing += k
                            host.setComposing(state.composing)
                        } else if (k == "0") onSpaceTap(host)
                        else host.commitText(k)
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox("符", onClick = { state.panel = Panel.SYMBOLS })
            Spacer(Modifier.weight(3f))
            DeleteKey(host, weight = 2f)
        }
        BottomRow(host)
    }
}

// ---------- 单手键盘 ----------

@Composable
private fun OneHandKeys(host: KeyboardHost) {
    val state = host.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        for (row in ONE_HAND) {
            Row(Modifier.fillMaxWidth()) {
                for (k in row) {
                    KeyBox(displayCase(state, k), onClick = {
                        host.playKeySound(KeySoundManager.KIND_TAP)
                        onChar(host, k)
                    })
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox(capsLabel(state), onClick = {
                state.capsMode = when (state.capsMode) { 0 -> 1; 1 -> 2; else -> 0 }
            })
            KeyBox("符", onClick = { state.panel = Panel.SYMBOLS })
            Spacer(Modifier.weight(2f))
            DeleteKey(host, weight = 2f)
        }
    }
}

@Composable
private fun OneHandBottom(host: KeyboardHost) {
    val state = host.state
    val enterLabel = host.enterKeyLabel()
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        KeyBox("123", onClick = {
            state.panel = if (state.panel == Panel.SYMBOLS) Panel.NONE else Panel.SYMBOLS
        })
        KeyBox("，", onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            if (state.composing.isNotEmpty()) { flushComposition(host); return@KeyBox }
            if (state.translateActive) { state.appendTrans("，"); return@KeyBox }
            host.commitText("，")
        })
        KeyBox(
            "空格", weight = 2.6f,
            onLong = { host.onSpaceLongPress() },
            onLongRelease = { host.onSpaceRelease() },
            onClick = { host.playKeySound(KeySoundManager.KIND_SPACE); onSpaceTap(host) }
        )
        KeyBox("。", onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            if (state.composing.isNotEmpty()) { flushComposition(host); return@KeyBox }
            if (state.translateActive) { state.appendTrans("。"); return@KeyBox }
            host.commitText("。")
        })
        KeyBox(enterLabel, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP); flushComposition(host); host.sendEnter()
        })
        KeyBox("退出", onClick = { state.singleHand = false })
    }
}
