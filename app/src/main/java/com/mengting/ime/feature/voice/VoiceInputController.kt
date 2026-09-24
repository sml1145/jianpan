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
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlin.concurrent.thread
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
            // 解码线程数随核心数放开：解码快于实时，才不会在采集循环里堆积导致丢音
            numThreads = minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(2)),
            provider = "cpu",
            modelType = "paraformer"
        )
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = model,
            enableEndpoint = true,
            // 端点判定：默认 rule2 仅需 1.2s 静音就切句，长句听写常被腰斩。
            // 放宽到 1.8s（有语音）/ 3.0s（纯静音），并保留 30s 兜底强制切分。
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 3.0f, minUtteranceLength = 0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.8f, minUtteranceLength = 0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 30f)
            ),
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
        recordThread = thread(name = "mt-voice-decode") {
            try {
                val rec = recognizer ?: createRecognizer().also { recognizer = it }
                val minBuf = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                // 缓冲区放大到至少 2 秒：解码偶发变慢时先由缓冲区兜住，不至于丢样本
                val record = if (minBuf > 0) createAudioRecord(maxOf(minBuf * 4, 65536)) else null
                if (record == null) {
                    running = false
                    notify("无法启动麦克风")
                    return@thread
                }

                // 采集与解码分离：旧实现两者在同一线程串行，解码一旦跟不上实时速率，
                // AudioRecord 缓冲区就会溢出丢样本，直接损伤识别准确率。
                val queue = LinkedBlockingQueue<FloatArray>()
                val captureDone = AtomicBoolean(false)
                val frame = 1600 // 100ms @16kHz，流式模型友好的切片长度
                val capture = thread(name = "mt-voice-capture") {
                    val pcm = ShortArray(frame)
                    try {
                        record.startRecording()
                        while (running) {
                            val n = record.read(pcm, 0, frame)
                            if (n <= 0) continue
                            val f = FloatArray(n)
                            for (i in 0 until n) f[i] = pcm[i] / 32768f
                            queue.put(f)
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("MTVoice", "capture failed", e)
                    } finally {
                        captureDone.set(true)
                    }
                }

                val stream: OnlineStream = rec.createStream("")
                val sb = StringBuilder()
                var lastSent = ""
                notify("") // 开始听写，清除状态
                try {
                    while (true) {
                        val chunk = queue.poll(120, TimeUnit.MILLISECONDS)
                        if (chunk != null) {
                            stream.acceptWaveform(chunk, 16000)
                            while (rec.isReady(stream)) rec.decode(stream)
                            val text = rec.getResult(stream).text
                            if (text.isNotBlank() && text != lastSent) {
                                lastSent = text
                                val snapshot = sb.toString() + text
                                Handler(Looper.getMainLooper()).post { onResult(snapshot) }
                            }
                            if (rec.isEndpoint(stream)) {
                                if (text.isNotBlank()) sb.append(text)
                                rec.reset(stream)
                                lastSent = ""
                            }
                        } else if ((captureDone.get() || !running) && queue.isEmpty()) {
                            break // 采集已结束且队列排空，收尾
                        }
                    }
                    stream.inputFinished()
                    while (rec.isReady(stream)) rec.decode(stream)
                    val tail = rec.getResult(stream).text
                    if (tail.isNotBlank()) sb.append(tail)
                    val final = sb.toString()
                    if (final.isNotBlank()) Handler(Looper.getMainLooper()).post { onResult(final) }
                } finally {
                    running = false
                    captureDone.set(true)
                    try { capture.join(800) } catch (_: Exception) {}
                    try { record.stop(); record.release() } catch (_: Exception) {}
                    try { stream.release() } catch (_: Exception) {}
                }
            } catch (e: Throwable) {
                running = false
                android.util.Log.e("MTVoice", "voice failed", e)
                notify("语音模型加载失败：${e.message?.take(80)}")
            }
        }
    }

    /**
     * 优先用 VOICE_RECOGNITION 音源：不带自动增益/降噪等后处理，频谱更平坦，
     * 对声学模型更友好（MIC 的 AGC 会压平音量动态，损伤准确率）。
     * 个别设备不支持时按序回退。
     */
    private fun createAudioRecord(bufBytes: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (src in sources) {
            try {
                val rec = AudioRecord(
                    src, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                try { rec.release() } catch (_: Exception) {}
            } catch (e: Throwable) {
                android.util.Log.w("MTVoice", "audio source $src unavailable: ${e.message}")
            }
        }
        return null
    }

    fun stop() {
        running = false
        recordThread = null
    }

    fun release() {
        running = false
        val t = recordThread
        recordThread = null
        try { t?.join(1000) } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }
}
