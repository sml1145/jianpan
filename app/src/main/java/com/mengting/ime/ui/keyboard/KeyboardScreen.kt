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
import kotlin.math.roundToInt

// ---------- 布局数据 ----------

private val QWERTY = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m")
)
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

/** 键高倍率与键色 */
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

    // 短信验证码：进入键盘时读取一次并监听变化
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
        // 全删气泡
        if (state.showDeleteBubble) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 74.dp, bottom = 118.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("⌫ 左滑全删", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        // 短信验证码气泡
        state.smsCode?.let { code ->
            Box(
                Modifier.align(Alignment.TopStart).padding(top = 84.dp, start = 8.dp)
                    .background(Color(0xFF2E7D32), RoundedCornerShape(16.dp))
                    .tapOnce {
                        host.commitText(code)
                        host.playKeySound(KeySoundManager.KIND_TAP)
                        SmsCodeHolder.consume()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("📩 验证码 $code · 点击输入", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        // 语音听写提示
        if (state.listening || state.voiceStatus.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 46.dp)
                    .background(Color(0xDD2B1B33), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    when {
                        state.voiceStatus.isNotEmpty() -> state.voiceStatus
                        else -> "🎤 听写中，松开空格结束…"
                    },
                    fontSize = 13.sp, color = Color.White
                )
            }
        }
        if (landscape && !state.floating) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(Color(0x66FFFFFF), RoundedCornerShape(6.dp))
                    .tapOnce { state.floating = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("悬浮", fontSize = 11.sp, color = Color(0xFF4A2B5A)) }
        }
    }
}

/** 标准点击手势：抬起触发一次，避免按下即触发导致翻页跳页 */
private fun Modifier.tapOnce(block: () -> Unit): Modifier = this.pointerInput(Unit) {
    detectTapGestures(onTap = { block() })
}

/** 点击+长按手势 */
private fun Modifier.tapLong(onLong: (() -> Unit)?, block: () -> Unit): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onTap = { block() },
        onLongPress = onLong?.let { { _ -> it() } }
    )
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
                    .background(
                        if (i == current) Color(0xFFE86AC0) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .tapOnce { onPick(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(label, fontSize = 14.sp,
                    color = if (i == current) Color.White else Color(0xFF4A2B5A))
            }
        }
        Box(
            Modifier.fillMaxWidth().tapOnce { onDismiss() }
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) { Text("收起", fontSize = 12.sp, color = Color(0xFF8A6B7A)) }
    }
}

@Composable
private fun BackgroundLayer(modifier: Modifier = Modifier) {
    val uri = AppPrefs.customBackgroundUri
    if (uri.isNotEmpty()) {
        val context = LocalContext.current
        val bitmap = remember(uri) {
            try {
                context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
        }
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
        } else PixelArtBackground(modifier = modifier, animOn = AppPrefs.bgAnimationOn)
    } else {
        PixelArtBackground(modifier = modifier, animOn = AppPrefs.bgAnimationOn)
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
            Text("－", Modifier.tapOnce { scale = (scale - 0.1f).coerceAtLeast(0.5f) }, color = Color.White, fontSize = 13.sp)
            Text("＋", Modifier.tapOnce { scale = (scale + 0.1f).coerceAtMost(1.2f) }, color = Color.White, fontSize = 13.sp)
            Text("退出", Modifier.tapOnce { host.state.floating = false }, color = Color.White, fontSize = 10.sp)
        }
        KeyboardColumn(host, Modifier.fillMaxWidth(), onSwitch)
    }
}

@Composable
private fun KeyboardColumn(host: KeyboardHost, modifier: Modifier, onSwitch: () -> Unit) {
    val state = host.state
    Column(modifier) {
        TopBar(host, onSwitch)
        CandidateBar(host)
        if (state.panel == Panel.TRANSLATE) TranslatePanel(host)
        when (state.panel) {
            Panel.EMOJI -> EmojiPanel(host)
            Panel.SYMBOLS -> SymbolPanel(host)
            Panel.CLIPBOARD -> ClipboardPanel(host)
            Panel.CALCULATOR -> CalculatorPanel(host)
            Panel.HANDWRITING -> HandwritingWrap(host)
            else -> when (state.layoutVersion % 3) {
                1 -> T9Keyboard(host)
                2 -> HandwritingWrap(host)
                else -> QwertyKeyboard(host)
            }
        }
    }
}

// ---------- 顶部功能条 ----------

@Composable
private fun TopBar(host: KeyboardHost, onSwitch: () -> Unit) {
    val state = host.state
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(Color(0x55FFFFFF)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopBtn("剪", active = state.panel == Panel.CLIPBOARD) {
            state.panel = if (state.panel == Panel.CLIPBOARD) Panel.NONE else Panel.CLIPBOARD
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("译", active = state.panel == Panel.TRANSLATE) {
            state.panel = if (state.panel == Panel.TRANSLATE) Panel.NONE else Panel.TRANSLATE
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("表", active = state.panel == Panel.EMOJI) {
            state.panel = if (state.panel == Panel.EMOJI) Panel.NONE else Panel.EMOJI
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("算", active = state.panel == Panel.CALCULATOR) {
            state.panel = if (state.panel == Panel.CALCULATOR) Panel.NONE else Panel.CALCULATOR
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        Spacer(Modifier.weight(1f))
        TopBtn("⌄", active = false, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            host.hideKeyboard()
        })
        TopBtn("⌨", active = false, onClick = onSwitch)
        TopBtn(if (state.isChinese) "中" else "英", active = false,
            onLong = { host.onLangLongPress() }, onClick = { host.toggleLang() })
    }
}

@Composable
private fun TopBtn(label: String, active: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .background(if (active) Color(0x66E86AC0) else Color.Transparent, RoundedCornerShape(8.dp))
            .tapLong(onLong, onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 15.sp, color = Color(0xFF4A2B5A), fontWeight = FontWeight.Medium) }
}

// ---------- 候选栏 ----------

@Composable
private fun CandidateBar(host: KeyboardHost) {
    val state = host.state
    // 词库后台加载完成后自动补刷当前组合的候选
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
                if (!state.isChinese && state.capsMode > 0) state.composing.uppercase() else state.composing,
                fontSize = 15.sp, color = Color(0xFF5B3A6E),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        if (state.candidates.isEmpty() && state.composing.isEmpty()) {
            Text("梦婷输入法", fontSize = 12.sp, color = Color(0x774A2B5A),
                modifier = Modifier.padding(horizontal = 10.dp))
        }
        LazyRow(Modifier.weight(1f)) {
            items(state.candidates.toList()) { c ->
                Text(
                    displayCase(state, c), fontSize = 16.sp, color = Color(0xFF2B1B33),
                    modifier = Modifier
                        .tapOnce { onCandidateClick(host, c) }
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
    if (state.panel == Panel.TRANSLATE) {
        state.translateInput += c
        state.clearComposition()
    } else {
        host.commitText(displayCase(state, c))
    }
}

// ---------- 翻译面板 ----------

@Composable
private fun TranslatePanel(host: KeyboardHost) {
    val state = host.state
    LaunchedEffect(state.translateInput) {
        if (state.translateInput.isBlank()) { state.translateResult = emptyList(); return@LaunchedEffect }
        delay(700)
        host.translateText(state.translateInput) { list -> state.translateResult = list }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🌐 ", fontSize = 14.sp)
            Text(
                if (state.translateInput.isEmpty()) "输入中文或英文自动翻译…" else state.translateInput,
                fontSize = 15.sp,
                color = if (state.translateInput.isEmpty()) Color(0x884A2B5A) else Color(0xFF2B1B33),
                modifier = Modifier.weight(1f)
            )
            Text("✕", fontSize = 15.sp, color = Color(0xFFB23A8F),
                modifier = Modifier.tapOnce {
                    state.translateInput = ""; state.translateResult = emptyList(); state.panel = Panel.NONE
                }.padding(horizontal = 6.dp))
        }
        if (state.translateResult.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (r in state.translateResult.take(3)) {
                    Box(
                        Modifier.background(Color(0xFFE86AC0), RoundedCornerShape(10.dp))
                            .tapOnce {
                                host.commitText(r)
                                host.playKeySound(KeySoundManager.KIND_TAP)
                                state.translateInput = ""
                                state.translateResult = emptyList()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text(r, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium) }
                }
            }
        } else if (state.translateInput.isNotBlank()) {
            Text("翻译中…", fontSize = 12.sp, color = Color(0xFF8A6B7A), modifier = Modifier.padding(top = 4.dp))
        }
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
            Text("刷新", fontSize = 12.sp, color = Color(0xFFB23A8F), modifier = Modifier.tapOnce {
                ClipboardHistory.captureCurrent(host.context())
                items = ClipboardHistory.load(host.context())
            }.padding(horizontal = 8.dp))
            Text("返回", fontSize = 12.sp, color = Color(0xFFB23A8F), modifier = Modifier.tapOnce {
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
                items(items) { item ->
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .background(Color(0xAAFFFFFF), RoundedCornerShape(8.dp))
                            .tapOnce {
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
    // 实时计算预览
    LaunchedEffect(state.calcExpr) {
        state.calcResult = if (state.calcExpr.isBlank()) "" else {
            CalcEval.eval(state.calcExpr)?.let { CalcEval.format(it) } ?: ""
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // 显示区（点击结果一键输入）
        Box(
            Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp)
                .background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                .tapOnce {
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
                for (k in row) {
                    KeyBox(k, baseHeight = 44, onClick = {
                        host.playKeySound(KeySoundManager.KIND_TAP)
                        when (k) {
                            "C" -> { state.calcExpr = ""; state.calcResult = "" }
                            "⌫" -> { state.calcExpr = state.calcExpr.dropLast(1) }
                            else -> state.calcExpr += k
                        }
                    })
                }
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
                    val text = CalcEval.format(v)
                    host.commitText(text)
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
            Text(
                "${page + 1}/${pages.size}", fontSize = 11.sp, color = Color(0xFF4A2B5A),
                modifier = Modifier.width(44.dp).padding(top = 14.dp), textAlign = TextAlign.Center
            )
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                    .tapOnce { state.panel = Panel.NONE }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("返回", fontSize = 12.sp, color = Color(0xFF4A2B5A)) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                    .tapOnce { if (page > 0) state.emojiPage = page - 1 }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("‹ 上页", fontSize = 12.sp, color = Color(0xFF4A2B5A)) }
            Text(" ${page + 1}/${pages.size} ", fontSize = 11.sp, color = Color(0xFF8A6B7A))
            Box(
                Modifier.background(Color(0xAAFFFFFF), RoundedCornerShape(10.dp))
                    .tapOnce { if (page < pages.size - 1) state.emojiPage = page + 1 }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("下页 ›", fontSize = 12.sp, color = Color(0xFF4A2B5A)) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.background(Color(0xFFB23A8F), RoundedCornerShape(10.dp))
                    .tapOnce { host.sendEnter(); host.playKeySound(KeySoundManager.KIND_TAP) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text("⏎ 回车", fontSize = 12.sp, color = Color.White) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun HandwritingWrap(host: KeyboardHost) {
    Column(Modifier.fillMaxWidth()) {
        HandwritingKeyboard(host)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            Text("⏎ 回车", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.background(Color(0xFF4A2B5A), RoundedCornerShape(10.dp))
                    .tapOnce { host.sendEnter() }
                    .padding(horizontal = 14.dp, vertical = 7.dp))
            Spacer(Modifier.width(10.dp))
            Text("返回键盘", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.background(Color(0xFFB23A8F), RoundedCornerShape(10.dp))
                    .tapOnce {
                        host.state.panel = Panel.NONE
                        if (host.state.layoutVersion % 3 == 2) host.setLayout(0)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

// ---------- 按键组件 ----------

/** 普通按键：抬起触发点击；带 onLong 时支持长按与长按松开（空格语音用） */
@Composable
private fun RowScope.KeyBox(
    label: String, weight: Float = 1f, baseHeight: Int = 46,
    onLong: (() -> Unit)? = null,
    onLongRelease: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val h = (baseHeight * KeyMetrics.heightScale).roundToInt()
    val bg = if (KeyMetrics.keyColor != 0) Color(KeyMetrics.keyColor) else Color(0xAAFFFFFF)
    val gesture: Modifier = if (onLong == null) {
        Modifier.tapLong(null, onClick)
    } else {
        Modifier.pointerInput(Unit) {
            kotlinx.coroutines.coroutineScope {
                while (true) {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var longFired = false
                        val job = launch {
                            delay(420)
                            longFired = true
                            onLong()
                        }
                        var pressed = true
                        while (pressed) {
                            val ev = awaitPointerEvent()
                            pressed = ev.changes.any { it.id == down.id && it.pressed }
                        }
                        job.cancel()
                        if (longFired) onLongRelease?.invoke() else onClick()
                    }
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
        Text(label, fontSize = if (label.length > 2) 12.sp else 16.sp, color = Color(0xFF2B1B33),
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
    }
}

/** 带滑动手势的删除键（长按+左滑全删） */
@Composable
private fun RowScope.DeleteKey(host: KeyboardHost, weight: Float = 1.6f, baseHeight: Int = 46) {
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
            .pointerInput(Unit) {
                kotlinx.coroutines.coroutineScope {
                    while (true) {
                        awaitPointerEventScope {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            host.onDeleteDown(down.position.x, down.position.y)
                            var longFired = false
                            var consumedByMove = false
                            val job = launch {
                                delay(400)
                                longFired = true
                            }
                            var pressed = true
                            while (pressed) {
                                val ev = awaitPointerEvent()
                                for (ch in ev.changes) {
                                    if (ch.id != down.id) continue
                                    if (!ch.pressed) { pressed = false; continue }
                                    if (longFired && host.onDeleteMove(ch.position.x, ch.position.y)) {
                                        consumedByMove = true
                                        ch.consume()
                                    }
                                }
                            }
                            job.cancel()
                            host.onDeleteUp()
                            if (!consumedByMove && !longFired) {
                                host.playKeySound(KeySoundManager.KIND_DELETE)
                                panelDelete(host)
                            } else if (longFired && !consumedByMove) {
                                // 长按但没滑动：执行一次普通删除
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

// ---------- 26 键 ----------

@Composable
private fun QwertyKeyboard(host: KeyboardHost) {
    val state = host.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        // 三行字母：统一 10 等分宽度，26 个字母键大小一致
        Row(Modifier.fillMaxWidth()) {
            for (k in QWERTY[0]) LetterKey(host, k)
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(0.5f))
            for (k in QWERTY[1]) LetterKey(host, k)
            Spacer(Modifier.weight(0.5f))
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.5f))
            for (k in QWERTY[2]) LetterKey(host, k)
            Spacer(Modifier.weight(1.5f))
        }
        // 功能行：大写 全选 复制 + 删除
        Row(Modifier.fillMaxWidth()) {
            KeyBox(capsLabel(state), onClick = {
                state.capsMode = when (state.capsMode) { 0 -> 1; 1 -> 2; else -> 0 }
                host.playKeySound(KeySoundManager.KIND_TAP)
            })
            KeyBox("全选", onClick = { host.selectAll(); host.playKeySound(KeySoundManager.KIND_TAP) })
            KeyBox("复制", onClick = { host.copySelection(); host.playKeySound(KeySoundManager.KIND_TAP) })
            Spacer(Modifier.weight(4.4f))
            DeleteKey(host)
        }
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
    2 -> "⇪ caps"
    1 -> "⇧ 大写"
    else -> "⇧"
}

@Composable
private fun ColumnScope.BottomRow(host: KeyboardHost) {
    val state = host.state
    Row(Modifier.fillMaxWidth()) {
        KeyBox(if (state.singleHand) "单手✓" else "单手", weight = 1.2f, onClick = {
            state.singleHand = !state.singleHand; host.playKeySound(KeySoundManager.KIND_TAP)
        })
        KeyBox("123", weight = 1.1f, onClick = {
            state.panel = if (state.panel == Panel.SYMBOLS) Panel.NONE else Panel.SYMBOLS
            host.playKeySound(KeySoundManager.KIND_TAP)
        })
        KeyBox(
            "空格", weight = 3.2f,
            onLong = { host.onSpaceLongPress() },
            onLongRelease = { host.onSpaceRelease() },
            onClick = {
                host.playKeySound(KeySoundManager.KIND_SPACE)
                onSpaceTap(host)
            }
        )
        KeyBox("，", weight = 0.9f, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            if (state.panel == Panel.TRANSLATE) { state.translateInput += "，"; return@KeyBox }
            flushComposition(host)
            host.commitText("，")
        })
        KeyBox("。", weight = 0.9f, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            if (state.panel == Panel.TRANSLATE) { state.translateInput += "。"; return@KeyBox }
            flushComposition(host)
            host.commitText("。")
        })
        KeyBox("⏎", weight = 1.3f, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            flushComposition(host)
            host.sendEnter()
        })
    }
    // 空格长按语音：释放监听
    LaunchedEffect(state.listening) {
        if (state.listening) {
            delay(60_000) // 兜底自动停止
            host.onSpaceRelease()
        }
    }
}

private fun onSpaceTap(host: KeyboardHost) {
    val state = host.state
    if (state.listening) { host.onSpaceRelease(); return }
    if (state.panel == Panel.TRANSLATE) {
        state.translateInput += " "
        return
    }
    if (state.composing.isNotEmpty()) {
        val word = state.candidates.firstOrNull()
        if (word != null) host.commitText(displayCase(state, word))
        else host.commitText(displayCase(state, state.composing))
        host.commitText(" ")
    } else {
        host.commitText(" ")
    }
}

private fun panelDelete(host: KeyboardHost) {
    val state = host.state
    if (state.panel == Panel.TRANSLATE && state.composing.isEmpty() && state.translateInput.isNotEmpty()) {
        state.translateInput = state.translateInput.dropLast(1)
        return
    }
    if (state.panel == Panel.CALCULATOR) {
        state.calcExpr = state.calcExpr.dropLast(1)
        return
    }
    host.deleteBackward()
}

private fun flushComposition(host: KeyboardHost) {
    val s = host.state
    if (s.composing.isNotEmpty()) {
        if (s.candidates.isNotEmpty()) host.commitText(displayCase(s, s.candidates.first()))
        else host.commitText(displayCase(s, s.composing))
    }
}

private fun onChar(host: KeyboardHost, c: String) {
    val state = host.state
    if (state.panel == Panel.TRANSLATE) {
        state.translateInput += if (state.capsMode > 0) c.uppercase() else c
        return
    }
    // 中英文都进入组合态：中文出拼音候选，英文出拼词候选
    state.composing += c.lowercase()
    host.setComposing(state.composing)
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
            Spacer(Modifier.weight(4f))
            DeleteKey(host)
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
                // 不足 6 键的行补齐占位，保证键宽一致
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox(capsLabel(state), onClick = {
                state.capsMode = when (state.capsMode) { 0 -> 1; 1 -> 2; else -> 0 }
            })
            KeyBox("符", onClick = { state.panel = Panel.SYMBOLS })
            Spacer(Modifier.weight(2f))
            DeleteKey(host, weight = 1.4f)
        }
    }
}

@Composable
private fun OneHandBottom(host: KeyboardHost) {
    val state = host.state
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        KeyBox("，", onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP); flushComposition(host); host.commitText("，")
        })
        KeyBox(
            "空格", weight = 2.6f,
            onLong = { host.onSpaceLongPress() },
            onLongRelease = { host.onSpaceRelease() },
            onClick = { host.playKeySound(KeySoundManager.KIND_SPACE); onSpaceTap(host) }
        )
        KeyBox("。", onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP); flushComposition(host); host.commitText("。")
        })
        KeyBox("⏎", onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP); flushComposition(host); host.sendEnter()
        })
        KeyBox("退出", onClick = { state.singleHand = false })
    }
}
