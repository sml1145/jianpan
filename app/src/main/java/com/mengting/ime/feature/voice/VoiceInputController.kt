package com.mengting.ime.feature.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * 本地离线语音转文字：sherpa-onnx 流式 zipformer 中英模型。
 * 长按空格触发；识别结束把文本回提交。
 */
class VoiceInputController(private val ctx: Context) {

    private var recognizer: OnlineRecognizer? = null
    private var recordThread: Thread? = null
    @Volatile private var running = false
    private var job: Job? = null

    private fun ensureRecognizer(): OnlineRecognizer? {
        recognizer?.let { return it }
        return try {
            val model = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "models/asr/encoder.int8.onnx",
                    decoder = "models/asr/decoder.int8.onnx",
                    joiner = "models/asr/joiner.int8.onnx"
                ),
                tokens = "models/asr/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer"
            )
            val cfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = model,
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )
            val r = OnlineRecognizer(ctx.assets, cfg)
            recognizer = r
            r
        } catch (e: Throwable) {
            null
        }
    }

    fun start(onResult: (String) -> Unit) {
        if (running) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // IME 无法直接请求权限：通过设置页 Activity 请求；此处提示由 UI 层处理
            onResult("")
            return
        }
        val rec = ensureRecognizer() ?: run { onResult(""); return }
        running = true
        recordThread = thread(name = "mt-voice") {
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
                return@thread
            }
            val stream: OnlineStream = rec.createStream("")
            val pcm = ShortArray(bufSize / 2)
            val floats = FloatArray(bufSize / 2)
            val sb = StringBuilder()
            record.startRecording()
            try {
                while (running) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    for (i in 0 until n) floats[i] = pcm[i] / 32768f
                    stream.acceptWaveform(floats.copyOf(n), 16000)
                    while (rec.isReady(stream)) rec.decode(stream)
                    val text = rec.getResult(stream).text
                    if (rec.isEndpoint(stream)) {
                        if (text.isNotBlank()) {
                            sb.append(text)
                            val snapshot = sb.toString()
                            CoroutineScope(Dispatchers.Main).launch { onResult(snapshot) }
                        }
                        rec.reset(stream)
                    }
                }
                stream.inputFinished()
                while (rec.isReady(stream)) rec.decode(stream)
                val tail = rec.getResult(stream).text
                if (tail.isNotBlank()) sb.append(tail)
            } catch (_: Exception) {
            } finally {
                try { record.stop(); record.release() } catch (_: Exception) {}
                try { stream.release() } catch (_: Exception) {}
                val final = sb.toString()
                running = false
                if (final.isNotBlank()) CoroutineScope(Dispatchers.Main).launch { onResult(final) }
            }
        }
    }

    fun stop() {
        running = false
        recordThread?.join(800)
        recordThread = null
    }

    fun release() {
        stop()
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }
}
