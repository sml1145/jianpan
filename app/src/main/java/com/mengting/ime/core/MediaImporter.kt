package com.mengting.ime.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * 用户自选媒体（背景图 / 按键音效）的导入工具。
 *
 * **为什么必须复制文件，而不是直接存 URI**：
 * 设置页用 `ActivityResultContracts.GetContent()` 选文件，它发出的 `ACTION_GET_CONTENT`
 * 授予的是**临时**读权限，且**不支持** `takePersistableUriPermission`（调用必抛
 * SecurityException）。原实现把该异常静默吞掉后照样弹「已应用」，于是：
 *   设置页当场能读（临时授权还在）→ 输入法服务稍后读 → 授权已失效 → 解码得 null
 *   → 静默回退默认背景，用户看到的就是「提示已上传，但背景没变」。
 * 复制到 filesDir 后，读取走应用自己的私有目录，不再依赖任何 URI 授权，永久有效。
 *
 * **为什么用固定文件名**：换图/换音效时直接覆盖，避免私有目录累积无人清理的垃圾文件。
 */
object MediaImporter {

    /** 背景图存为 JPEG，最长边限制到该值：键盘区域很小，原图分辨率纯属浪费且易 OOM */
    private const val BG_MAX_EDGE = 1080
    private const val BG_JPEG_QUALITY = 88
    /** 单文件导入上限：图片 20MB、音频 8MB，超出视为误选 */
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MAX_AUDIO_BYTES = 8L * 1024 * 1024

    private val SUPPORTED_AUDIO = setOf("wav", "mp3", "ogg", "m4a", "aac", "flac")

    /** 导入结果：成功给出私有目录绝对路径，失败给出可直接展示给用户的原因 */
    sealed class Result {
        data class Ok(val path: String) : Result()
        data class Fail(val reason: String) : Result()
    }

    private fun mediaDir(ctx: Context): File =
        File(ctx.filesDir, "media").apply { mkdirs() }

    /** 从 Uri 推断扩展名（type 优先，其次 path 后缀） */
    private fun extOf(ctx: Context, uri: Uri): String {
        val type = try { ctx.contentResolver.getType(uri) } catch (e: Exception) { null }
        MimeTypeMap.getSingleton().getExtensionFromMimeType(type)?.let { return it.lowercase() }
        val last = uri.lastPathSegment ?: return ""
        val dot = last.lastIndexOf('.')
        return if (dot >= 0 && dot < last.length - 1) last.substring(dot + 1).lowercase() else ""
    }

    private fun sizeOf(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (e: Exception) { -1L }

    /**
     * 导入背景图：降采样解码后另存为 JPEG。
     *
     * 降采样的必要性：手机原图动辄 4000×3000，直接 decodeStream 会占用数十 MB 内存，
     * 在输入法这种内存受限进程里极易 OOM 崩溃；键盘背景区又只有几百 dp 高，
     * 1080 长边完全够用。
     */
    fun importBackground(ctx: Context, uri: Uri): Result {
        return try {
            val size = sizeOf(ctx, uri)
            if (size in 1 until 1024) return Result.Fail("所选文件过小，可能不是图片")
            if (size > MAX_IMAGE_BYTES) return Result.Fail("图片过大（超过 ${MAX_IMAGE_BYTES / 1048576}MB），请换一张")

            // 先只读尺寸，据此算 inSampleSize，避免一次性把原图解进内存
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return Result.Fail("无法识别该图片格式，请换 JPG/PNG 等常见格式")
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= BG_MAX_EDGE) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return Result.Fail("读取图片失败，请重试")

            // 按最长边等比缩到 BG_MAX_EDGE（inSampleSize 只按 2 的幂粗缩，这里做精确缩放）
            val scale = BG_MAX_EDGE.toFloat() / maxOf(src.width, src.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else src

            val out = File(mediaDir(ctx), "bg.jpg")
            FileOutputStream(out).use { fos ->
                // JPEG 无透明通道，先铺白底再画，避免透明 PNG 变黑底
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(flat)
                c.drawColor(android.graphics.Color.WHITE)
                c.drawBitmap(bmp, 0f, 0f, null)
                flat.compress(Bitmap.CompressFormat.JPEG, BG_JPEG_QUALITY, fos)
                c.setBitmap(null)
                flat.recycle()
            }
            // compress 已完成，位图不再需要；scale>=1f 时 bmp===src，只需回收一次，避免泄漏
            if (bmp !== src) bmp.recycle()
            src.recycle()

            if (!out.exists() || out.length() <= 0) return Result.Fail("背景保存失败，请重试")
            Result.Ok(out.absolutePath)
        } catch (e: SecurityException) {
            Result.Fail("没有读取该图片的权限，请重新选择")
        } catch (e: OutOfMemoryError) {
            Result.Fail("图片过大导致内存不足，请换一张小一些的")
        } catch (e: Exception) {
            Result.Fail("导入失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 导入按键音效：直接复制到私有目录（音频文件本身就小，无需转码）。
     *
     * 校验 mime/扩展名，避免用户误选一个非音频文件后 SoundPool 静默加载失败——
     * 那又会变成「上传了但没声音」的空壳体验。
     */
    fun importSound(ctx: Context, uri: Uri): Result {
        return try {
            val ext = extOf(ctx, uri)
            val type = (try { ctx.contentResolver.getType(uri) } catch (e: Exception) { null }) ?: ""
            val looksAudio = SUPPORTED_AUDIO.contains(ext) || type.startsWith("audio/")
            if (!looksAudio) {
                return Result.Fail("请选择音频文件（支持 ${SUPPORTED_AUDIO.joinToString("/")}）")
            }
            val size = sizeOf(ctx, uri)
            if (size in 1 until 512) return Result.Fail("所选文件过小，可能不是音频")
            if (size > MAX_AUDIO_BYTES) return Result.Fail("音频过大（超过 ${MAX_AUDIO_BYTES / 1048576}MB），按键音效建议 1 秒内的短音")

            val out = File(mediaDir(ctx), "tap_custom.$ext")
            // 先写临时文件再改名：避免复制中途失败留下半截文件被当成有效音效
            val tmp = File(mediaDir(ctx), "tap_custom.$ext.tmp")
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { os -> ins.copyTo(os, 64 * 1024) }
            } ?: return Result.Fail("读取音频失败，请重试")
            if (!tmp.exists() || tmp.length() <= 0) {
                tmp.delete()
                return Result.Fail("音频复制失败，请重试")
            }
            // 清掉可能存在的其他扩展名旧文件，避免同名不同扩展的残留
            mediaDir(ctx).listFiles()?.forEach {
                if (it.name.startsWith("tap_custom.") && it.name != tmp.name) it.delete()
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.delete()
                return Result.Fail("音频保存失败，请重试")
            }
            Result.Ok(out.absolutePath)
        } catch (e: SecurityException) {
            Result.Fail("没有读取该音频的权限，请重新选择")
        } catch (e: Exception) {
            Result.Fail("导入失败：${e.javaClass.simpleName}")
        }
    }

    /** 删除已导入的自定义音效（恢复内置音效包） */
    fun clearCustomSound(ctx: Context) {
        mediaDir(ctx).listFiles()?.forEach { if (it.name.startsWith("tap_custom.")) it.delete() }
    }
}
