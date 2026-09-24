package com.mengting.ime.ui.keyboard

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengting.ime.feature.handwriting.HandwritingModelStore
import com.mengting.ime.feature.handwriting.HandwritingRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手写键盘：画板 + 本地 ONNX 离线识别（达摩院中英文手写模型）。
 *
 * 为什么不再用 MLKit：MLKit Digital Ink 的模型只能经 Google Play 服务动态下发，
 * 不支持随包内置，无 GMS 设备（国内大量机型）根本拿不到模型，只能显示"无法识别"。
 * 现在改为本地推理，模型下到 filesDir 后永久离线可用，与 Google 服务完全解耦。
 *
 * 稳定性：识别在后台线程执行（单次约 150~400ms），绘制不阻塞；模型加载/推理全程
 * try-catch，失败降级为文字提示，绝不崩溃。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HandwritingKeyboard(host: KeyboardHost, commitOnPick: Boolean = true, onPickChar: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val points = remember { mutableStateListOf<Offset>() }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var cands by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("正在准备手写…") }
    var recognizing by remember { mutableStateOf(false) }

    val modelStatus by HandwritingModelStore.status.collectAsState()

    // 进入面板：若模型缺失则后台拉取，若已就绪则预加载会话（首次加载 73MB 权重需数秒）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (!HandwritingModelStore.isReady(context)) {
                HandwritingModelStore.download(context, scope)
                status = "首次使用，正在下载手写模型…"
            } else if (!HandwritingRecognizer.ensureLoaded(context)) {
                status = "手写组件初始化失败：" + (HandwritingRecognizer.lastError ?: "未知原因")
            }
        }
    }

    // 下载进度实时反馈
    LaunchedEffect(modelStatus) {
        when (val st = modelStatus) {
            is HandwritingModelStore.Status.Downloading ->
                status = "正在下载手写模型 ${st.percent}%…"
            is HandwritingModelStore.Status.Failed ->
                status = st.msg
            HandwritingModelStore.Status.Ready -> {
                if (!HandwritingRecognizer.ensureLoaded(context)) {
                    status = "手写模型加载失败：" + (HandwritingRecognizer.lastError ?: "未知原因")
                } else {
                    status = "在此手写，抬笔自动识别"
                }
            }
            HandwritingModelStore.Status.Unknown -> { /* 保持当前提示 */ }
        }
    }

    fun recognizeAll() {
        if (strokes.isEmpty()) return
        if (!HandwritingRecognizer.ensureLoaded(context)) {
            status = "手写模型未就绪：" + (HandwritingRecognizer.lastError ?: "请先联网下载模型")
            return
        }
        recognizing = true
        val snapshot = strokes.toList()
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                try {
                    HandwritingRecognizer.recognize(snapshot)
                } catch (t: Throwable) {
                    ""
                }
            }
            recognizing = false
            if (text.isBlank()) {
                cands = emptyList()
                status = "无识别结果，请重写"
            } else {
                // 单字场景通常整串就是结果；多字时按字符拆开供逐字点选
                val chars = text.map { it.toString() }
                cands = if (chars.size == 1) chars else listOf(text) + chars
                status = "点选候选上屏"
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // 候选行
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cands.isEmpty()) {
                Text(
                    if (recognizing) "识别中…" else status,
                    fontSize = 12.sp, color = Color(0xFF4A2B5A),
                    modifier = Modifier.padding(horizontal = 10.dp), maxLines = 1
                )
            } else {
                for (c in cands.take(6)) {
                    Box(
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            .background(Color(0xAAFFFFFF), RoundedCornerShape(8.dp))
                            .pointerTapSafe(c) {
                                if (commitOnPick) host.commitText(c)
                                host.playKeySound(com.mengting.ime.feature.audio.KeySoundManager.KIND_TAP)
                                onPickChar?.invoke(c)
                                cands = emptyList()
                                strokes.clear()
                                status = "在此手写，抬笔自动识别"
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text(c, fontSize = 18.sp, color = Color(0xFF2B1B33), maxLines = 1) }
                }
                Spacer(Modifier.weight(1f))
                Text("✕", fontSize = 14.sp, color = Color(0xFFB23A8F),
                    modifier = Modifier.pointerTapSafe("clear") { cands = emptyList(); strokes.clear() }
                        .padding(horizontal = 10.dp))
            }
        }
        // 画板
        Box(
            Modifier.fillMaxWidth().height(200.dp).padding(vertical = 4.dp)
                .background(Color(0x55FFFFFF), RoundedCornerShape(12.dp))
                .pointerInteropFilter { ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { points.add(Offset(ev.x, ev.y)); true }
                        MotionEvent.ACTION_MOVE -> { points.add(Offset(ev.x, ev.y)); true }
                        MotionEvent.ACTION_UP -> {
                            if (points.size >= 2) strokes.add(points.toList())
                            points.clear()
                            recognizeAll()
                            true
                        }
                        else -> false
                    }
                }
        ) {
            // 已完成的笔画 + 当前正在画的笔画都要显示
            Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                for (st in strokes) {
                    if (st.size < 2) continue
                    val path = Path()
                    path.moveTo(st[0].x, st[0].y)
                    for (i in 1 until st.size) path.lineTo(st[i].x, st[i].y)
                    drawPath(path, Color(0xFF2B1B33),
                        style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (points.size > 1) {
                    val path = Path()
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                    drawPath(path, Color(0xFF2B1B33),
                        style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        // 操作行
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.weight(1f).height(42.dp).padding(2.dp)
                    .background(Color(0xAAFFFFFF), RoundedCornerShape(8.dp))
                    .pointerTapSafe("undo") {
                        if (strokes.isNotEmpty()) {
                            strokes.removeAt(strokes.size - 1)
                            cands = emptyList()
                            status = "已撤销一笔"
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("撤销", fontSize = 14.sp, color = Color(0xFF2B1B33)) }
            Box(
                Modifier.weight(1f).height(42.dp).padding(2.dp)
                    .background(Color(0xAAFFFFFF), RoundedCornerShape(8.dp))
                    .pointerTapSafe("clr") {
                        strokes.clear(); points.clear(); cands = emptyList()
                        status = "在此手写，抬笔自动识别"
                    },
                contentAlignment = Alignment.Center
            ) { Text("清空", fontSize = 14.sp, color = Color(0xFF2B1B33)) }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 点击手势：用 rememberUpdatedState 保证回调读最新闭包 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.pointerTapSafe(key: Any?, block: () -> Unit): Modifier {
    val cur by androidx.compose.runtime.rememberUpdatedState(block)
    return this.pointerInteropFilter { ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> { cur(); true }
            MotionEvent.ACTION_DOWN -> true
            else -> false
        }
    }
}
