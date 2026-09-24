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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea

/**
 * 手写键盘：画板 + MLKit 数字墨水识别（简体中文 ZH_HANI_CN，模型按需下载）。
 * 稳定性：MLKit 依赖 Google Play 服务，无 GMS 或创建失败时全程降级为提示，绝不崩溃；
 * 识别在后台线程回调，不阻塞绘制，避免死机。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HandwritingKeyboard(host: KeyboardHost, commitOnPick: Boolean = true, onPickChar: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val points = remember { mutableStateListOf<Offset>() }
    var cands by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("正在初始化手写…") }

    // GMS 可用性 + MLKit 客户端：全程 try-catch，失败降级
    val gmsAvailable = remember(context) {
        try {
            val code = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            code == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (e: Throwable) { false }
    }

    val model = remember {
        try {
            DigitalInkRecognitionModel
                .builder(DigitalInkRecognitionModelIdentifier.ZH_HANI_CN)
                .build()
        } catch (e: Throwable) { null }
    }
    val recognizer = remember(model) {
        if (!gmsAvailable || model == null) null
        else try {
            val opts = DigitalInkRecognizerOptions.builder(model).build()
            DigitalInkRecognition.getClient(opts)
        } catch (e: Throwable) { null }
    }
    val modelManager = remember {
        if (!gmsAvailable) null else try { RemoteModelManager.getInstance() } catch (e: Throwable) { null }
    }
    val strokesRef = remember { mutableListOf<Ink.Stroke>() }
    var recognizing by remember { mutableStateOf(false) }

    // 初始化状态提示
    LaunchedEffect(gmsAvailable, recognizer, model) {
        status = when {
            !gmsAvailable -> "本设备缺少 Google Play 服务，手写识别不可用"
            model == null || recognizer == null -> "手写组件初始化失败"
            else -> "在此手写，抬笔自动识别"
        }
    }

    fun recognizeAll() {
        val r = recognizer
        val m = model
        val mm = modelManager
        if (r == null || m == null || mm == null) return
        if (strokesRef.isEmpty()) return
        recognizing = true
        try {
            val inkBuilder = Ink.builder()
            for (s in strokesRef) inkBuilder.addStroke(s)
            val ink = inkBuilder.build()
            val ctx = RecognitionContext.builder()
                .setWritingArea(WritingArea(1080f, 600f))
                .build()
            r.recognize(ink, ctx)
                .addOnSuccessListener { result ->
                    recognizing = false
                    try {
                        cands = result.candidates.take(8).map { it.text }
                        status = if (cands.isEmpty()) "无识别结果，请重写" else "点选候选上屏"
                    } catch (e: Throwable) { status = "结果解析失败" }
                }
                .addOnFailureListener { e ->
                    recognizing = false
                    val msg = e.message ?: ""
                    if (msg.contains("download", true) || msg.contains("model", true) || msg.contains("install", true)) {
                        status = "正在下载手写模型（首次需联网）…"
                        try {
                            mm.download(m, DownloadConditions.Builder().build())
                                .addOnSuccessListener { status = "模型就绪，请重新书写" }
                                .addOnFailureListener { d -> status = "模型下载失败：${d.message?.take(30)}" }
                        } catch (ex: Throwable) { status = "模型下载异常" }
                    } else {
                        status = "识别失败：${msg.take(30)}"
                    }
                }
        } catch (e: Throwable) {
            recognizing = false
            status = "识别异常：${e.message?.take(30)}"
        }
    }

    // 进入时静默预下载模型（仅 GMS 可用时）
    LaunchedEffect(modelManager, model) {
        val mm = modelManager; val m = model
        if (mm == null || m == null) return@LaunchedEffect
        try {
            mm.isModelDownloaded(m).addOnSuccessListener { downloaded ->
                if (!downloaded) {
                    status = "首次使用正在下载手写模型…"
                    try {
                        mm.download(m, DownloadConditions.Builder().build())
                            .addOnSuccessListener { status = "手写模型就绪，请书写" }
                            .addOnFailureListener { status = "模型下载失败（需联网），请检查网络" }
                    } catch (e: Throwable) { status = "模型下载异常" }
                }
            }.addOnFailureListener { status = "手写模型状态获取失败" }
        } catch (e: Throwable) { status = "手写初始化异常" }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // 候选行
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cands.isEmpty()) {
                Text(status, fontSize = 12.sp, color = Color(0xFF4A2B5A),
                    modifier = Modifier.padding(horizontal = 10.dp), maxLines = 1)
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
                                strokesRef.clear()
                                status = "在此手写，抬笔自动识别"
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text(c, fontSize = 18.sp, color = Color(0xFF2B1B33)) }
                }
                Spacer(Modifier.weight(1f))
                Text("✕", fontSize = 14.sp, color = Color(0xFFB23A8F),
                    modifier = Modifier.pointerTapSafe("clear") { cands = emptyList(); strokesRef.clear() }
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
                            if (points.size >= 2) {
                                try {
                                    val sb = Ink.Stroke.builder()
                                    for (p in points) sb.addPoint(Ink.Point.create(p.x, p.y))
                                    strokesRef.add(sb.build())
                                } catch (e: Throwable) {}
                            }
                            points.clear()
                            if (recognizer != null) recognizeAll()
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Canvas(Modifier.fillMaxWidth().height(200.dp)) {
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
                        if (strokesRef.isNotEmpty()) {
                            strokesRef.removeAt(strokesRef.size - 1)
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
                        strokesRef.clear(); points.clear(); cands = emptyList()
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
