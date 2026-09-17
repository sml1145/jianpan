package com.mengting.ime.feature.voice

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
 * 语音模型外置存储与下载：
 * 模型不内置在安装包中，安装后从镜像源（hf-mirror，国内直连）后台拉取，
 * 支持断点续传与多文件并行状态跟踪；完成后语音功能自动可用。
 */
object ModelStore {
    sealed class Status {
        object Unknown : Status()
        data class Downloading(val percent: Int) : Status()
        object Ready : Status()
        data class Failed(val msg: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Unknown)
    val status: StateFlow<Status> = _status

    private const val BASE = "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main"
    private val FILES = listOf(
        "encoder.int8.onnx" to 165_453_000L,
        "decoder.int8.onnx" to 71_600_000L,
        "tokens.txt" to 60_000L
    )

    @Volatile private var downloading = false

    fun modelDir(ctx: Context): File = File(ctx.filesDir, "models/asr").apply { mkdirs() }

    fun pathOf(ctx: Context, name: String): String = File(modelDir(ctx), name).absolutePath

    fun isReady(ctx: Context): Boolean = FILES.all { (n, _) ->
        val f = File(modelDir(ctx), n)
        f.exists() && f.length() > 1024
    }

    /** 校验已下载文件大小是否合理（宽松下限，防半截文件） */
    private fun sizeOk(ctx: Context): Boolean = FILES.all { (n, min) ->
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
                            if (!ok) { part.delete() }
                        }
                        if (!ok) {
                            _status.value = Status.Failed("模型文件 $name 下载失败（网络或镜像源不可达），请重试")
                            downloading = false
                            return@withContext
                        }
                        if (!part.renameTo(out)) {
                            _status.value = Status.Failed("模型保存失败")
                            downloading = false
                            return@withContext
                        }
                        doneBytes += out.length()
                    }
                    _status.value = if (sizeOk(ctx)) Status.Ready else Status.Failed("模型文件不完整，请重新下载")
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
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "mengting-ime-model")
        return conn
    }
}
