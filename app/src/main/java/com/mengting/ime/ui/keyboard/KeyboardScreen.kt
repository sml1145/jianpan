package com.mengting.ime.ui.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.PointerInputScope
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
import com.mengting.ime.core.ClipboardHistory
import com.mengting.ime.feature.audio.KeySoundManager
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
// 单手模式基础键（26字母+空格逗号句号回车删除+大小写）
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
    KeyMetrics.heightScale = if (state.singleHand) 1f else 1f

    val config = LocalConfiguration.current
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showLayoutPicker by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().wrapContentHeight()) {
        BackgroundLayer(Modifier.matchParentSize())
        when {
            landscape && state.floating -> FloatingKeyboard(host, { showLayoutPicker = !showLayoutPicker })
            state.singleHand -> SingleHandKeyboard(host)
            else -> KeyboardColumn(host, Modifier.fillMaxWidth(), { showLayoutPicker = !showLayoutPicker })
        }
        // 布局选择浮层
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
                    .pointerTap { state.floating = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("悬浮", fontSize = 11.sp, color = Color(0xFF4A2B5A)) }
        }
    }
}

private fun Modifier.pointerTap(block: () -> Unit): Modifier = this.pointerInput(Unit) {
    forEachGestureSafe { block() }
}

private suspend fun PointerInputScope.forEachGestureSafe(block: () -> Unit) {
    while (true) {
        awaitPointerEventScope {
            awaitFirstDown(requireUnconsumed = false)
            block()
        }
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
                    .background(
                        if (i == current) Color(0xFFE86AC0) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .pointerTap { onPick(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(label, fontSize = 14.sp,
                    color = if (i == current) Color.White else Color(0xFF4A2B5A))
            }
        }
        Box(
            Modifier.fillMaxWidth().pointerTap { onDismiss() }
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
private fun SingleHandKeyboard(host: KeyboardHost) {
    val context = LocalContext.current
    val side = remember(context) { GravitySideResolver(context) }
    DisposableEffect(side) { onDispose { side.unregister() } }
    val alignLeft = side.isLeft()
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignLeft) Alignment.BottomStart else Alignment.BottomEnd) {
        Column(Modifier.fillMaxWidth(0.58f)) {
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
            Text("－", Modifier.pointerTap { scale = (scale - 0.1f).coerceAtLeast(0.5f) }, color = Color.White, fontSize = 13.sp)
            Text("＋", Modifier.pointerTap { scale = (scale + 0.1f).coerceAtMost(1.2f) }, color = Color.White, fontSize = 13.sp)
            Text("退出", Modifier.pointerTap { host.state.floating = false }, color = Color.White, fontSize = 10.sp)
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
        TopBtn("📋", active = state.panel == Panel.CLIPBOARD) {
            state.panel = if (state.panel == Panel.CLIPBOARD) Panel.NONE else Panel.CLIPBOARD
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("译", active = state.panel == Panel.TRANSLATE) {
            state.panel = if (state.panel == Panel.TRANSLATE) Panel.NONE else Panel.TRANSLATE
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        TopBtn("☺", active = state.panel == Panel.EMOJI) {
            state.panel = if (state.panel == Panel.EMOJI) Panel.NONE else Panel.EMOJI
            host.playKeySound(KeySoundManager.KIND_TAP)
        }
        Spacer(Modifier.weight(1f))
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
            .pointerLongTap(onLong, onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 15.sp, color = Color(0xFF4A2B5A), fontWeight = FontWeight.Medium) }
}

// ---------- 候选栏 ----------

@Composable
private fun CandidateBar(host: KeyboardHost) {
    val state = host.state
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Color(0x66FFFFFF)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.composing.isNotEmpty()) {
            Text(state.composing, fontSize = 15.sp, color = Color(0xFF5B3A6E),
                modifier = Modifier.padding(horizontal = 8.dp))
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
                        .pointerTap { onCandidateClick(host, c) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun displayCase(state: KeyboardState, c: String): String =
    if (!state.isChinese && state.capsMode > 0) c.uppercase() else c

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
                modifier = Modifier.pointerTap {
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
                            .pointerTap {
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
            Text("刷新", fontSize = 12.sp, color = Color(0xFFB23A8F), modifier = Modifier.pointerTap {
                ClipboardHistory.captureCurrent(host.context())
                items = ClipboardHistory.load(host.context())
            }.padding(horizontal = 8.dp))
            Text("返回", fontSize = 12.sp, color = Color(0xFFB23A8F), modifier = Modifier.pointerTap {
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
                            .pointerTap {
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
            KeyBox(if (page == 0) "${pages.size}页" else "第${page + 1}页", weight = 1.4f, onClick = {
                state.symbolPage = (page + 1) % pages.size
            })
            KeyBox("‹", onClick = { if (page > 0) state.symbolPage = page - 1 })
            KeyBox("›", onClick = { if (page < pages.size - 1) state.symbolPage = page + 1 })
            KeyBox("⌫", weight = 1.2f, onClick = {
                host.playKeySound(KeySoundManager.KIND_DELETE); panelDelete(host)
            })
            KeyBox("返回", weight = 1.6f, onClick = {
                state.panel = Panel.NONE
                host.playKeySound(KeySoundManager.KIND_TAP)
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
            Text(" ${page + 1}/${pages.size}", fontSize = 11.sp, color = Color(0xFF8A6B7A))
            Spacer(Modifier.weight(1f))
            Text("‹ 上一页", fontSize = 12.sp, color = Color(0xFFB23A8F),
                modifier = Modifier.pointerTap { if (page > 0) state.emojiPage = page - 1 }.padding(horizontal = 10.dp, vertical = 8.dp))
            Text("下一页 ›", fontSize = 12.sp, color = Color(0xFFB23A8F),
                modifier = Modifier.pointerTap { if (page < pages.size - 1) state.emojiPage = page + 1 }.padding(horizontal = 10.dp, vertical = 8.dp))
            Text("返回键盘", fontSize = 12.sp, color = Color.White,
                modifier = Modifier.background(Color(0xFFB23A8F), RoundedCornerShape(10.dp))
                    .pointerTap { state.panel = Panel.NONE; host.playKeySound(KeySoundManager.KIND_TAP) }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun HandwritingWrap(host: KeyboardHost) {
    Column(Modifier.fillMaxWidth()) {
        HandwritingKeyboard(host)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            Text("返回键盘", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.background(Color(0xFFB23A8F), RoundedCornerShape(10.dp))
                    .pointerTap {
                        host.state.panel = Panel.NONE
                        if (host.state.layoutVersion % 3 == 2) host.setLayout(0)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

// ---------- 按键组件 ----------

@Composable
private fun RowScope.KeyBox(
    label: String, weight: Float = 1f, baseHeight: Int = 46,
    onLong: (() -> Unit)? = null,
    onLongRelease: (() -> Unit)? = null,
    onDown: ((Float, Float) -> Unit)? = null,
    onMove: ((Float, Float) -> Boolean)? = null,
    onUp: (() -> Unit)? = null,
    onClick: () -> Unit
) {
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
                            onDown?.invoke(down.position.x, down.position.y)
                            var longFired = false
                            var consumedByMove = false
                            val job = launch {
                                delay(420)
                                longFired = true
                                onLong?.invoke()
                            }
                            var pressed = true
                            while (pressed) {
                                val ev = awaitPointerEvent()
                                for (ch in ev.changes) {
                                    if (ch.id != down.id) continue
                                    if (!ch.pressed) { pressed = false; continue }
                                    if (longFired && onMove != null) {
                                        if (onMove(ch.position.x, ch.position.y)) {
                                            consumedByMove = true
                                            ch.consume()
                                        }
                                    }
                                }
                            }
                            job.cancel()
                            onUp?.invoke()
                            when {
                                consumedByMove -> {}
                                longFired -> onLongRelease?.invoke()
                                else -> onClick()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = if (label.length > 2) 12.sp else 16.sp, color = Color(0xFF2B1B33),
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Modifier.pointerLongTap(onLong: (() -> Unit)?, onClick: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        kotlinx.coroutines.coroutineScope {
            while (true) {
                awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var longFired = false
                    val job = launch { delay(420); longFired = true; onLong?.invoke() }
                    var pressed = true
                    while (pressed) {
                        val ev = awaitPointerEvent()
                        pressed = ev.changes.any { it.id == down.id && it.pressed }
                    }
                    job.cancel()
                    if (!longFired) onClick()
                }
            }
        }
    }

// ---------- 26 键 ----------

@Composable
private fun QwertyKeyboard(host: KeyboardHost) {
    val state = host.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        for ((ri, row) in QWERTY.withIndex()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (ri == 1) Spacer(Modifier.width(14.dp))
                for (k in row) {
                    KeyBox(displayCase(state, k), onClick = {
                        host.playKeySound(KeySoundManager.KIND_TAP)
                        onChar(host, k)
                    })
                }
                if (ri == 1) Spacer(Modifier.width(14.dp))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox(capsLabel(state), onClick = {
                state.capsMode = if (state.capsMode == 0) 1 else 0
                host.playKeySound(KeySoundManager.KIND_TAP)
            }, onLong = { state.capsMode = 2 })
            KeyBox("全选", onClick = { host.selectAll(); host.playKeySound(KeySoundManager.KIND_TAP) })
            KeyBox("复制", onClick = { host.copySelection(); host.playKeySound(KeySoundManager.KIND_TAP) })
            Spacer(Modifier.weight(2.4f))
            KeyBox(
                "⌫", weight = 1.6f,
                onDown = { x, y -> host.onDeleteDown(x, y) },
                onMove = { x, y -> host.onDeleteMove(x, y) },
                onUp = { host.onDeleteUp() },
                onClick = { host.playKeySound(KeySoundManager.KIND_DELETE); panelDelete(host) }
            )
        }
        BottomRow(host)
    }
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
}

private fun onSpaceTap(host: KeyboardHost) {
    val state = host.state
    if (state.panel == Panel.TRANSLATE) {
        state.translateInput += " "
        return
    }
    if (state.composing.isNotEmpty()) {
        val word = state.candidates.firstOrNull() ?: state.composing
        host.commitText(displayCase(state, word))
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
    host.deleteBackward()
}

private fun flushComposition(host: KeyboardHost) {
    val s = host.state
    if (s.composing.isNotEmpty()) {
        if (s.candidates.isNotEmpty()) host.commitText(displayCase(s, s.candidates.first()))
        else s.clearComposition()
    }
}

private fun onChar(host: KeyboardHost, c: String) {
    val state = host.state
    if (state.panel == Panel.TRANSLATE) {
        state.translateInput += if (!state.isChinese && state.capsMode > 0) c.uppercase() else c
        return
    }
    if (!state.isChinese) {
        // 英文也进入组合态，候选来自英文词表实现自动拼词
        state.composing += c.lowercase()
        host.setComposing(state.composing)
        return
    }
    if (state.layoutVersion % 3 == 2) return
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
            KeyBox(
                "⌫", weight = 1.6f,
                onDown = { x, y -> host.onDeleteDown(x, y) },
                onMove = { x, y -> host.onDeleteMove(x, y) },
                onUp = { host.onDeleteUp() },
                onClick = { host.playKeySound(KeySoundManager.KIND_DELETE); panelDelete(host) }
            )
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
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox(capsLabel(state), onClick = {
                state.capsMode = if (state.capsMode == 0) 1 else 0
            }, onLong = { state.capsMode = 2 })
            KeyBox("符", onClick = { state.panel = Panel.SYMBOLS })
            Spacer(Modifier.weight(2f))
            KeyBox(
                "⌫", weight = 1.4f,
                onDown = { x, y -> host.onDeleteDown(x, y) },
                onMove = { x, y -> host.onDeleteMove(x, y) },
                onUp = { host.onDeleteUp() },
                onClick = { host.playKeySound(KeySoundManager.KIND_DELETE); panelDelete(host) }
            )
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
