package com.mengting.ime.feature.handwriting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.charset.StandardCharsets

/**
 * 本地离线手写识别（ONNX Runtime CPU）。
 *
 * 模型：达摩院 cv_convnextTiny_ocr-recognition-handwritten（ConvNext+ViTSTR），Apache-2.0。
 * 运行时首次使用从 ModelScope 拉取到 filesDir，之后永久离线，不依赖任何 Google 服务。
 *
 * 以下全部参数均为「桌面 ONNX Runtime 实测确定」，非文档猜测——
 * 输入 [3,3,32,300]：ViT 位置编码固定 75 patch ⇒ 宽必须 300（300/4=75）；
 *   图内 Reshape 硬编码为 {1,3,75,192}，batch=1 会因元素数不符直接失败，必须 batch=3。
 * 归一化 像素/255（0..1），灰度复制到 RGB 三通道——实测 4 种归一化里该方式置信度最高。
 * 输出 [1,201,7644]，CTC 贪心解码：blank=0，vocabIndex = classIndex − 2（7644 类 − 3 特殊符）。
 *
 * 单例持有 OrtSession，避免每次抬笔都重建（重建要重新加载 73MB 权重，代价极高）。
 */
object HandwritingRecognizer {

    private const val IMG_H = 32
    private const val IMG_W = 300
    private const val BATCH = 3
    private const val NUM_CLASSES = 7644
    private const val BLANK = 0
    private const val VOCAB_OFFSET = 2

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var vocab: List<String>? = null
    @Volatile private var inputName: String = "input_images"

    /** 加载失败时保存原因，供 UI 展示，不抛出以免崩溃。 */
    @Volatile var lastError: String? = null
        private set

    /**
     * 确保会话已加载。线程安全，可在后台协程调用。
     * @return 是否就绪
     */
    @Synchronized
    fun ensureLoaded(ctx: Context): Boolean {
        if (session != null && vocab != null) return true
        return try {
            if (!HandwritingModelStore.isReady(ctx)) {
                lastError = "手写模型未就绪"
                return false
            }
            val modelFile = File(HandwritingModelStore.modelDir(ctx), "model.onnx")
            val vocabFile = File(HandwritingModelStore.modelDir(ctx), "vocab.txt")

            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // 手机 CPU 核数有限，2 线程在功耗与延迟间较均衡；桌面 4 线程实测 48ms
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(modelFile.absolutePath, opts)
            val v = vocabFile.readLines(StandardCharsets.UTF_8).let { lines ->
                // 去掉尾部空行（与桌面验证一致）
                val out = ArrayList<String>(lines.size)
                out.addAll(lines)
                while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
                out as List<String>
            }

            env = e
            session = s
            vocab = v
            inputName = s.inputNames.firstOrNull() ?: "input_images"
            lastError = null
            true
        } catch (t: Throwable) {
            // 完整堆栈进 logcat，lastError 也放宽到 600 字符，避免截断掩盖真因
            android.util.Log.e("MTHw", "ensureLoaded failed", t)
            lastError = (t.message ?: t.javaClass.simpleName).take(600)
            false
        }
    }

    /**
     * 识别一组笔画（所有笔顺的点，坐标为画板像素，画板逻辑尺寸约 1080×600）。
     * @return 识别文本（可能为空串表示无结果）
     */
    fun recognize(strokes: List<List<Offset>>): String {
        val s = session
        val e = env
        val v = vocab
        if (s == null || e == null || v == null || strokes.isEmpty()) return ""
        return try {
            val bitmap = renderStrokes(strokes) ?: return ""
            val input = bitmapToTensor(bitmap)
            bitmap.recycle()

            val shape = longArrayOf(BATCH.toLong(), 3, IMG_H.toLong(), IMG_W.toLong())
            OnnxTensor.createTensor(e, FloatBuffer.wrap(input), shape).use { tensor ->
                s.run(mapOf(inputName to tensor)).use { res ->
                    // 输出 [1, 201, 7644]
                    val out = res[0].value as Array<Array<FloatArray>>
                    val logits = out[0] // [201][7644]
                    ctcDecode(logits, v)
                }
            }
        } catch (t: Throwable) {
            lastError = t.message?.take(60) ?: "识别异常"
            ""
        }
    }

    /**
     * 把笔画渲染成模型输入位图：黑字白底，等比缩放到高 32，贴到宽 300 画布水平居中。
     * 与桌面 renderText 的几何保持一致（先大图渲染再缩放，保证抗锯齿质量）。
     */
    private fun renderStrokes(strokes: List<List<Offset>>): Bitmap? {
        // 1. 计算所有点的包围盒
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (st in strokes) for (p in st) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        if (maxX <= minX || maxY <= minY) return null
        val boxW = maxX - minX
        val boxH = maxY - minY
        if (boxW < 2 || boxH < 2) return null

        // 2. 先在大画布（高 256）上按原比例绘制，抗锯齿更好，再整体缩到 32 高
        val bigH = 256
        val scale = (bigH - 24) / boxH // 留白，字形占高约 90%
        val bigW = (boxW * scale).toInt().coerceAtLeast(1) + 48
        val big = Bitmap.createBitmap(bigW, bigH, Bitmap.Config.ARGB_8888)
        val bc = Canvas(big)
        bc.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (8f * scale).coerceAtLeast(4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        for (st in strokes) {
            if (st.size < 2) continue
            val path = Path()
            path.moveTo((st[0].x - minX) * scale + 24, (st[0].y - minY) * scale + 12)
            for (i in 1 until st.size) {
                path.lineTo((st[i].x - minX) * scale + 24, (st[i].y - minY) * scale + 12)
            }
            bc.drawPath(path, paint)
        }
        bc.setBitmap(null)

        // 3. 等比缩放到目标高度 32
        val targetW = (bigW * IMG_H.toFloat() / bigH).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(big, targetW, IMG_H, true)
        big.recycle()

        // 4. 贴到白底 300×32 画布，水平居中（超出则从左贴）
        val canvasBmp = Bitmap.createBitmap(IMG_W, IMG_H, Bitmap.Config.ARGB_8888)
        val cc = Canvas(canvasBmp)
        cc.drawColor(Color.WHITE)
        val dx = if (targetW >= IMG_W) 0 else (IMG_W - targetW) / 2
        cc.drawBitmap(scaled, dx.toFloat(), 0f, null)
        cc.setBitmap(null)
        scaled.recycle()
        return canvasBmp
    }

    /** 位图 → CHW float[3*3*32*300]，灰度值/255 复制到三通道，batch 维复制 3 份。 */
    private fun bitmapToTensor(bmp: Bitmap): FloatArray {
        val plane = IMG_H * IMG_W
        val one = FloatArray(3 * plane)
        val px = IntArray(plane)
        bmp.getPixels(px, 0, IMG_W, 0, 0, IMG_W, IMG_H)
        for (i in 0 until plane) {
            // 位图为白底黑字，取红色通道即灰度
            val gray = (px[i] and 0xFF).toFloat() / 255f
            one[i] = gray
            one[plane + i] = gray
            one[2 * plane + i] = gray
        }
        val out = FloatArray(BATCH * one.size)
        for (b in 0 until BATCH) System.arraycopy(one, 0, out, b * one.size, one.size)
        return out
    }

    /** CTC 贪心解码：折叠重复、去 blank、classIndex−2 映射到 vocab。 */
    private fun ctcDecode(logits: Array<FloatArray>, vocab: List<String>): String {
        val sb = StringBuilder()
        var prev = -1
        for (row in logits) {
            val idx = argmax(row)
            if (idx != prev) {
                if (idx != BLANK) {
                    val vi = idx - VOCAB_OFFSET
                    if (vi in vocab.indices) {
                        val ch = vocab[vi]
                        if (ch.isNotEmpty()) sb.append(ch)
                    }
                }
                prev = idx
            }
        }
        return sb.toString()
    }

    private fun argmax(v: FloatArray): Int {
        var best = 0
        for (i in 1 until v.size) if (v[i] > v[best]) best = i
        return best
    }
}
