package com.mengting.ime.feature.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 长按句号触发：截取当前屏幕并用 MLKit 中文 OCR 提取文字，
 * 结果以列表形式展示，用户点选后复制并回填输入框。
 */
class OcrCaptureActivity : Activity() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCapture(this)
    }

    @Deprecated("deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE || resultCode != RESULT_OK || data == null) {
            finish()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        capture()
    }

    private fun capture() {
        val p = projection ?: return finish()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels; val dpi = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = p.createVirtualDisplay(
            "mt-ocr", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        Handler(Looper.getMainLooper()).postDelayed({
            val image = reader.acquireLatestImage()
            if (image == null) { finish(); return@postDelayed }
            val bitmap = imageToBitmap(image, w, h)
            image.close()
            recognize(bitmap)
        }, 600)
    }

    private fun imageToBitmap(image: android.media.Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (pixelStride == 4 && rowStride == w * 4) {
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        } else {
            val argb = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val offset = y * rowStride + x * pixelStride
                    val r = bytes[offset].toInt() and 0xFF
                    val g = bytes[offset + 1].toInt() and 0xFF
                    val b = bytes[offset + 2].toInt() and 0xFF
                    argb[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bmp.setPixels(argb, 0, w, 0, 0, w, h)
        }
        return bmp
    }

    private fun recognize(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.flatMap { it.lines }.map { it.text }.filter { it.isNotBlank() }
                val full = result.text
                releaseResources()
                OcrResultActivity.show(this, blocks.ifEmpty { listOf(full) })
                finish()
            }
            .addOnFailureListener {
                releaseResources()
                finish()
            }
    }

    private fun releaseResources() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null; projection = null
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }
}

/** 截屏前台服务声明占位（Android14 要求类型声明，实际截屏走活动内授权） */
class ScreenCaptureService : android.app.Service() {
    override fun onBind(intent: Intent?) = null
}
