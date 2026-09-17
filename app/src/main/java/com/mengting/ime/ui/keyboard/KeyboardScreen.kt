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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.feature.audio.KeySoundManager
import com.mengting.ime.ui.background.PixelArtBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val QWERTY = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m")
)
private val T9 = listOf(
    listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")
)
private val SYM1 = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("@", "#", "￥", "%", "&", "*", "-", "+", "=", "_"),
    listOf("(", ")", "【", "】", "「", "」", "《", "》", "!", "?")
)
private val SYM2 = listOf(
    listOf(",", ".", ":", ";", "'", "\"", "/", "\\", "|", "~"),
    listOf("^", "$", "€", "£", "§", "¶", "†", "‡", "©", "®"),
    listOf("℃", "°", "±", "×", "÷", "≠", "≈", "≤", "≥", "∞")
)
private val EMOJI = listOf("😀", "😂", "🥰", "", "😎", "", "😭", "😡", "👍", "🙏", "❤️", "💔", "✨", "☀️", "🌈", "🍀", "⭐", "🔥", "🎉", "", "")

/** 键高倍率与键色（单手模式纵向拉长） */
object KeyMetrics {
    var heightScale by mutableFloatStateOf(1f)
    var keyColor by mutableIntStateOf(0)
}

@Composable
fun KeyboardScreen(host: KeyboardHost) {
    val state = host.state
    KeyMetrics.keyColor = AppPrefs.keyColor
    KeyMetrics.heightScale = if (state.singleHand) 1.55f else 1f

    val config = LocalConfiguration.current
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer()
        when {
            landscape && state.floating -> FloatingKeyboard(host)
            state.singleHand -> SingleHandKeyboard(host)
            else -> KeyboardColumn(host, Modifier.fillMaxSize())
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

/** 在 PointerInputScope 内做"按下即触发" */
private suspend fun PointerInputScope.forEachGestureSafe(block: () -> Unit) {
    while (true) {
        awaitPointerEventScope {
            awaitFirstDown(requireUnconsumed = false)
            block()
        }
    }
}

@Composable
private fun BackgroundLayer() {
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
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else PixelArtBackground(animOn = AppPrefs.bgAnimationOn)
    } else {
        PixelArtBackground(animOn = AppPrefs.bgAnimationOn)
    }
}

@Composable
private fun SingleHandKeyboard(host: KeyboardHost) {
    val context = LocalContext.current
    val side = remember { GravitySideResolver(context) }
    val alignLeft = side.isLeft()
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = if (alignLeft) Alignment.BottomStart else Alignment.BottomEnd
    ) {
        KeyboardColumn(host, Modifier.fillMaxHeight().fillMaxWidth(0.62f))
    }
}

@Composable
private fun FloatingKeyboard(host: KeyboardHost) {
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
        KeyboardColumn(host, Modifier.fillMaxWidth())
    }
}

@Composable
private fun KeyboardColumn(host: KeyboardHost, modifier: Modifier) {
    val state = host.state
    Column(modifier) {
        CandidateBar(host)
        when {
            state.showEmoji -> EmojiPanel(host)
            state.showSymbols -> SymbolKeyboard(host)
            state.layoutVersion % 2 == 1 -> T9Keyboard(host)
            else -> QwertyKeyboard(host)
        }
    }
}

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
        LazyRow(Modifier.weight(1f)) {
            items(state.candidates.toList()) { c ->
                Text(
                    c, fontSize = 16.sp, color = Color(0xFF2B1B33),
                    modifier = Modifier
                        .pointerTap { host.commitText(c); host.playKeySound(KeySoundManager.KIND_TAP) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        FuncBtn("⇄", onClick = { host.toggleLayout() })
        FuncBtn(if (state.isChinese) "中" else "英", onLong = { host.onLangLongPress() }, onClick = { host.toggleLang() })
    }
}

@Composable
private fun FuncBtn(label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).pointerInput(Unit) {
            kotlinx.coroutines.coroutineScope {
                while (true) {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var longFired = false
                        val job = launch {
                            delay(420)
                            longFired = true
                            onLong?.invoke()
                        }
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
        },
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 14.sp, color = Color(0xFF4A2B5A)) }
}

@Composable
private fun RowScope.KeyBox(
    label: String, weight: Float = 1f, baseHeight: Int = 46,
    onLong: (() -> Unit)? = null,
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
                            if (!consumedByMove && !longFired) onClick()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 16.sp, color = Color(0xFF2B1B33), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QwertyKeyboard(host: KeyboardHost) {
    val state = host.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        for ((ri, row) in QWERTY.withIndex()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (ri == 1) Spacer(Modifier.width(14.dp))
                for (k in row) {
                    KeyBox(if (state.isChinese) k.uppercase() else k, onClick = {
                        host.playKeySound(KeySoundManager.KIND_TAP)
                        onChar(host, k)
                    })
                }
                if (ri == 1) Spacer(Modifier.width(14.dp))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox("符", onClick = { state.showSymbols = true; host.playKeySound(KeySoundManager.KIND_TAP) })
            KeyBox("☺", onClick = { state.showEmoji = !state.showEmoji; host.playKeySound(KeySoundManager.KIND_TAP) })
            Spacer(Modifier.weight(5f))
            KeyBox(
                "⌫", weight = 1.6f,
                onDown = { x, y -> host.onDeleteDown(x, y) },
                onMove = { x, y -> host.onDeleteMove(x, y) },
                onUp = { host.onDeleteUp() },
                onClick = { host.playKeySound(KeySoundManager.KIND_DELETE); host.deleteBackward() }
            )
        }
        BottomRow(host)
    }
}

@Composable
private fun ColumnScope.BottomRow(host: KeyboardHost) {
    val state = host.state
    Row(Modifier.fillMaxWidth()) {
        KeyBox(if (state.singleHand) "单手✓" else "单手", weight = 1.2f, onClick = {
            state.singleHand = !state.singleHand; host.playKeySound(KeySoundManager.KIND_TAP)
        })
        KeyBox("123", weight = 1.1f, onClick = { state.showSymbols = !state.showSymbols; host.playKeySound(KeySoundManager.KIND_TAP) })
        KeyBox(
            "空格", weight = 3.2f,
            onLong = { host.onSpaceLongPress() },
            onClick = {
                host.playKeySound(KeySoundManager.KIND_SPACE)
                if (state.composing.isNotEmpty() && state.candidates.isNotEmpty()) host.commitText(state.candidates.first())
                else host.commitText(" ")
            }
        )
        KeyBox("，", weight = 0.9f, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
            flushComposition(host)
            host.commitText("，")
        })
        KeyBox("。", weight = 0.9f, onLong = { host.onPeriodLongPress() }, onClick = {
            host.playKeySound(KeySoundManager.KIND_TAP)
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

private fun flushComposition(host: KeyboardHost) {
    val s = host.state
    if (s.composing.isNotEmpty() && s.candidates.isNotEmpty()) host.commitText(s.candidates.first())
    else if (s.composing.isNotEmpty()) s.clearComposition()
}

private fun onChar(host: KeyboardHost, c: String) {
    val state = host.state
    if (!state.isChinese) { host.commitText(c); return }
    state.composing += c
    host.setComposing(state.composing)
}

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
                        } else if (k == "0") host.commitText(" ")
                        else host.commitText(k)
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox("符", onClick = { state.showSymbols = true })
            Spacer(Modifier.weight(4f))
            KeyBox(
                "⌫", weight = 1.6f,
                onDown = { x, y -> host.onDeleteDown(x, y) },
                onMove = { x, y -> host.onDeleteMove(x, y) },
                onUp = { host.onDeleteUp() },
                onClick = { host.playKeySound(KeySoundManager.KIND_DELETE); host.deleteBackward() }
            )
        }
        BottomRow(host)
    }
}

@Composable
private fun SymbolKeyboard(host: KeyboardHost) {
    val rows = if (host.state.symbolPage == 0) SYM1 else SYM2
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth()) {
                for (k in row) KeyBox(k, baseHeight = 40, onClick = {
                    host.playKeySound(KeySoundManager.KIND_TAP); host.commitText(k)
                })
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox(if (host.state.symbolPage == 0) "#=<" else "123", weight = 1.6f, onClick = {
                host.state.symbolPage = if (host.state.symbolPage == 0) 1 else 0
            })
            Spacer(Modifier.weight(4f))
            KeyBox(
                "⌫", weight = 1.6f,
                onDown = { x, y -> host.onDeleteDown(x, y) },
                onMove = { x, y -> host.onDeleteMove(x, y) },
                onUp = { host.onDeleteUp() },
                onClick = { host.deleteBackward() }
            )
        }
        BottomRow(host)
    }
}

@Composable
private fun EmojiPanel(host: KeyboardHost) {
    Column(Modifier.fillMaxWidth().padding(6.dp)) {
        for (row in EMOJI.chunked(7)) {
            Row(Modifier.fillMaxWidth()) {
                for (e in row) KeyBox(e, baseHeight = 42, onClick = {
                    host.playKeySound(KeySoundManager.KIND_TAP); host.commitText(e)
                })
            }
        }
        BottomRow(host)
    }
}
