package com.mengting.ime.feature.handwriting

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真机/模拟器进程内验证本地 ONNX 手写识别的运行时兼容性。
 *
 * 为什么必须要这个测试：桌面侧已用独立 ONNX Runtime 验证过模型的预处理几何、
 * batch=3、blank=0、offset=2、归一化 0..1 等全部参数，识别「你好中」置信度 ≥0.98。
 * 但 App 里 ORT Java 层（1.28.0）的 libonnxruntime4j_jni.so 要动态链接的是
 * sherpa-onnx AAR 经 pickFirst 保留的 libonnxruntime.so（1.28.2）。这两者能否
 * 符号兼容、createSession 会不会抛 UnsatisfiedLinkError，桌面侧完全测不到，
 * 只能在设备进程内实跑。
 *
 * 前置：模型与 vocab 已就位于 App 的 files/models/hw（测试与 App 同包，filesDir 一致）。
 */
@RunWith(AndroidJUnit4::class)
class HandwritingRecognizerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** 会话创建：这是 JNI 兼容性的决定性验证。失败会暴露 UnsatisfiedLinkError 或版本不符。 */
    @Test
    fun sessionLoads() {
        val ok = HandwritingRecognizer.ensureLoaded(ctx)
        assertTrue(
            "ORT 会话创建失败（JNI 与 sherpa-onnx 内置 native 库符号可能不兼容）：" +
                HandwritingRecognizer.lastError,
            ok
        )
    }

    /**
     * 完整推理链路：喂一组合成笔画，确认 recognize 不崩溃、返回字符串。
     * 笔画是合成的直线，识别结果可能为空或不准，这里只验证「推理能跑通不抛异常」，
     * 识别准确率已由桌面侧对真实渲染字形的验证保证（置信度 ≥0.98）。
     */
    @Test
    fun inferenceRuns() {
        assertTrue(
            "会话未就绪，无法测推理：" + HandwritingRecognizer.lastError,
            HandwritingRecognizer.ensureLoaded(ctx)
        )
        // 合成两笔：一横一竖（模拟"十"的笔画），坐标落在 1080x600 画板范围内
        val strokes = listOf(
            listOf(Offset(300f, 300f), Offset(500f, 300f), Offset(700f, 300f)),
            listOf(Offset(500f, 150f), Offset(500f, 300f), Offset(500f, 450f))
        )
        val result = HandwritingRecognizer.recognize(strokes)
        // 不崩溃即通过；结果字符串非 null（可能为空串，合成笔画未必对应真实字）
        assertTrue("recognize 返回 null", result != null)
    }
}
