package com.mengting.ime.feature.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlin.concurrent.thread

/**
 * 本地离线语音转文字：sherpa-onnx 流式 Paraformer 中英双语模型（中文识别准确率高）。
 * 长按空格触发（松开停止）；模型后台预加载，避免首次长按等待。
 */
class VoiceInputController(private val ctx: Context) {

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var loading = false
    @Volatile private var loadFailed = false
    private var recordThread: Thread? = null
    @Volatile private var running = false

    /** IME 启动时调用：模型就绪则后台预加载识别器 */
    fun preload() {
        if (recognizer != null || loading) return
        if (!ModelStore.isReady(ctx)) return
        loading = true
        thread(name = "mt-asr-load") {
            try {
                recognizer = createRecognizer()
            } catch (e: Throwable) {
                loadFailed = true
                android.util.Log.e("MTVoice", "model load failed", e)
            } finally {
                loading = false
            }
        }
    }

    private fun createRecognizer(): OnlineRecognizer {
        val dir = ModelStore.modelDir(ctx)
        val model = OnlineModelConfig(
            paraformer = OnlineParaformerModelConfig(
                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                decoder = File(dir, "decoder.int8.onnx").absolutePath
            ),
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "paraformer"
        )
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = model,
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        )
        // AssetManager 传 null：sherpa-onnx 从文件系统绝对路径加载
        return OnlineRecognizer(null, cfg)
    }

    /**
     * 开始听写。onResult 回调可能多次（增量结果）。
     * onStatus 回调状态文本，供 UI 展示（权限缺失/加载中/失败）。
     */
    fun start(onResult: (String) -> Unit, onStatus: ((String) -> Unit)? = null) {
        if (running) return
        val notify: (String) -> Unit = { msg ->
            Handler(Looper.getMainLooper()).post { onStatus?.invoke(msg) }
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notify("缺少麦克风权限，请到「梦婷输入法」App 授权")
            return
        }
        if (loadFailed) {
            loadFailed = false
            preload()
        }
        if (!ModelStore.isReady(ctx)) {
            notify("语音模型尚未下载完成，请在「梦婷输入法」App 中下载语音模型")
            return
        }
        running = true
        thread(name = "mt-voice") {
            try {
                val rec = recognizer ?: createRecognizer().also { recognizer = it }
                val bufSize = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val record = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
                    )
                } catch (e: Exception) { null }
                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    running = false
                    notify("无法启动麦克风")
                    return@thread
                }
                val stream: OnlineStream = rec.createStream("")
                val pcm = ShortArray(bufSize / 2)
                val floats = FloatArray(bufSize / 2)
                val sb = StringBuilder()
                var lastSent = ""
                record.startRecording()
                notify("") // 开始听写，清除状态
                try {
                    while (running) {
                        val n = record.read(pcm, 0, pcm.size)
                        if (n <= 0) continue
                        for (i in 0 until n) floats[i] = pcm[i] / 32768f
                        stream.acceptWaveform(floats.copyOf(n), 16000)
                        while (rec.isReady(stream)) rec.decode(stream)
                        val text = rec.getResult(stream).text
                        if (text.isNotBlank() && text != lastSent) {
                            lastSent = text
                            val snapshot = (sb.toString() + text)
                            Handler(Looper.getMainLooper()).post { onResult(snapshot) }
                        }
                        if (rec.isEndpoint(stream)) {
                            if (text.isNotBlank()) sb.append(text)
                            rec.reset(stream)
                            lastSent = ""
                        }
                    }
                    stream.inputFinished()
                    while (rec.isReady(stream)) rec.decode(stream)
                    val tail = rec.getResult(stream).text
                    if (tail.isNotBlank()) sb.append(tail)
                    val final = sb.toString()
                    if (final.isNotBlank()) Handler(Looper.getMainLooper()).post { onResult(final) }
                } finally {
                    try { record.stop(); record.release() } catch (_: Exception) {}
                    try { stream.release() } catch (_: Exception) {}
                    running = false
                }
            } catch (e: Throwable) {
                running = false
                android.util.Log.e("MTVoice", "voice failed", e)
                notify("语音模型加载失败：${e.message?.take(80)}")
            }
        }
    }

    fun stop() {
        running = false
        recordThread = null
    }

    fun release() {
        stop()
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }
}
