package com.mengting.ime.feature.handwriting

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手写识别模型外置存储与下载。
 *
 * 为什么外置：模型 73MB，随包内置会让 APK 暴涨；且只有真正用手写/字典的用户才需要。
 * 与语音模型同样的策略——安装后按需从国内可直连的 ModelScope 拉取，支持断点续传。
 *
 * 为什么不用 MLKit：MLKit Digital Ink 只支持动态下载、不支持随包内置，模型必须经
 * Google Play 服务下发，无 GMS 设备（国内大量机型）完全不可用。改用本地 ONNX 推理后，
 * 模型一旦下到本地即永久离线可用，不再依赖任何 Google 服务。
 */
object HandwritingModelStore {
    sealed class Status {
        object Unknown : Status()
        data class Downloading(val percent: Int) : Status()
        object Ready : Status()
        data class Failed(val msg: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Unknown)
    val status: StateFlow<Status> = _status

    // 达摩院中英文手写识别（ConvNext+ViTSTR），Apache-2.0，ModelScope 国内直连
    private const val BASE =
        "https://www.modelscope.cn/models/iic/cv_convnextTiny_ocr-recognition-handwritten_damo/resolve/master"
    private val FILES = listOf(
        "model.onnx" to 76_859_029L,
        "vocab.txt" to 30_333L
    )

    @Volatile private var downloading = false

    fun modelDir(ctx: Context): File = File(ctx.filesDir, "models/hw").apply { mkdirs() }

    fun isReady(ctx: Context): Boolean = FILES.all { (n, min) ->
        val f = File(modelDir(ctx), n)
        f.exists() && f.length() >= min / 2
    }

    fun refresh(ctx: Context) {
        _status.value = if (isReady(ctx)) Status.Ready else Status.Unknown
    }

    fun download(ctx: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        if (downloading) return
        if (isReady(ctx)) { _status.value = Status.Ready; return }
        downloading = true
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val total = FILES.sumOf { (_, s) -> s }
                    var doneBytes = 0L
                    for ((name, expect) in FILES) {
                        val out = File(modelDir(ctx), name)
                        val part = File(modelDir(ctx), "$name.part")
                        if (out.exists() && out.length() >= expect / 2) {
                            doneBytes += out.length()
                            continue
                        }
                        var ok = false
                        var attempt = 0
                        while (!ok && attempt < 3) {
                            attempt++
                            ok = downloadOne("$BASE/$name", part, expect) { cur ->
                                val overall = ((doneBytes + cur) * 100 / total).toInt()
                                _status.value = Status.Downloading(overall.coerceIn(0, 99))
                            }
                            if (!ok) part.delete()
                        }
                        if (!ok) {
                            _status.value = Status.Failed("手写模型 $name 下载失败（网络或镜像源不可达），请重试")
                            downloading = false
                            return@withContext
                        }
                        if (!part.renameTo(out)) {
                            _status.value = Status.Failed("手写模型保存失败")
                            downloading = false
                            return@withContext
                        }
                        doneBytes += out.length()
                    }
                    _status.value = if (isReady(ctx)) Status.Ready
                    else Status.Failed("手写模型文件不完整，请重新下载")
                } catch (e: Exception) {
                    _status.value = Status.Failed(e.message ?: "下载异常")
                } finally {
                    downloading = false
                }
            }
        }
    }

    private fun downloadOne(url: String, part: File, expect: Long, onProgress: (Long) -> Unit): Boolean {
        return try {
            var resume = if (part.exists()) part.length() else 0L
            var conn = open(url)
            if (resume > 0) conn.setRequestProperty("Range", "bytes=$resume-")
            var code = conn.responseCode
            if (code == 416) {
                part.delete(); resume = 0
                conn.disconnect(); conn = open(url); code = conn.responseCode
            }
            if (code == 206 || code in 200..299) {
                if (code in 200..299 && resume > 0) { part.delete(); resume = 0 }
            } else {
                conn.disconnect(); return false
            }
            conn.inputStream.use { ins ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(resume)
                    val buf = ByteArray(64 * 1024)
                    var done = resume
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) {
                        raf.write(buf, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
            }
            conn.disconnect()
            part.length() >= expect / 2
        } catch (e: Exception) {
            false
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        // ModelScope 对无 UA 的请求返回 403，必须带浏览器 UA
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) mengting-ime")
        return conn
    }
}
