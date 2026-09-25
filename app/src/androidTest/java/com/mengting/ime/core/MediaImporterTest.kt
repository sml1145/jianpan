package com.mengting.ime.core

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mengting.ime.feature.audio.KeySoundManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 验证「上传的自定义背景/音效真正落盘到应用私有目录并可被读取」，以及背景与动画的互斥。
 *
 * 为什么必须在设备上测：缺陷的根因是 GetContent 的 URI 只有临时授权，
 * 设置页当场能读、输入法服务稍后读就失效——这种「跨组件、跨时间」的权限问题
 * 桌面 JVM 完全模拟不出来。这里通过真实写入私有文件 + 重新打开来证明
 * 复制方案确实摆脱了 URI 授权依赖。
 */
@RunWith(AndroidJUnit4::class)
class MediaImporterTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        // 复位为默认，避免污染其它测试与后续手动验证
        AppPrefs.setCustomBackground("")
        AppPrefs.setCustomSound("")
        MediaImporter.clearCustomSound(ctx)
    }

    /** 造一张真实的 PNG 图片写到应用可读的临时文件，返回其 file:// Uri */
    private fun makeImageUri(w: Int, h: Int, color: Int): android.net.Uri {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val f = File(ctx.cacheDir, "test_bg.png")
        FileOutputStream(f).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos) }
        bmp.recycle()
        return android.net.Uri.fromFile(f)
    }

    /** 造一段极短的合法 WAV（静音）用于音效导入测试 */
    private fun makeWavUri(): android.net.Uri {
        val sampleRate = 8000
        val seconds = 0.05
        val numSamples = (sampleRate * seconds).toInt()
        val data = ByteArray(numSamples * 2) // 16-bit mono
        val header = ByteArrayOutputStream()
        fun w4(s: String) { header.write(s.toByteArray()) }
        fun w32(v: Int) {
            header.write(v and 0xFF); header.write((v shr 8) and 0xFF)
            header.write((v shr 16) and 0xFF); header.write((v shr 24) and 0xFF)
        }
        fun w16(v: Int) { header.write(v and 0xFF); header.write((v shr 8) and 0xFF) }
        val dataLen = data.size
        w4("RIFF"); w32(36 + dataLen); w4("WAVE")
        w4("fmt "); w32(16); w16(1); w16(1); w32(sampleRate); w32(sampleRate * 2); w16(2); w16(16)
        w4("data"); w32(dataLen)
        val f = File(ctx.cacheDir, "test_tap.wav")
        FileOutputStream(f).use { fos -> fos.write(header.toByteArray()); fos.write(data) }
        return android.net.Uri.fromFile(f)
    }

    /** 第 1 项：导入背景后，私有目录内应出现可再次打开解码的 JPEG，且不再依赖原始 Uri。 */
    @Test
    fun importBackground_copiesToPrivateAndReadable() {
        val uri = makeImageUri(600, 400, 0xFF3366CC.toInt())
        val r = MediaImporter.importBackground(ctx, uri)
        assertTrue("背景导入应成功，实际：$r", r is MediaImporter.Result.Ok)
        val path = (r as MediaImporter.Result.Ok).path
        val f = File(path)
        assertTrue("背景文件应存在于私有目录", f.exists() && f.length() > 0)
        assertTrue("应落在应用私有 filesDir 内", f.absolutePath.contains(ctx.filesDir.absolutePath))
        // 关键：不靠原 Uri，仅凭私有路径就能重新解码 —— 证明摆脱了临时授权依赖
        val bmp = android.graphics.BitmapFactory.decodeFile(path)
        assertTrue("私有副本应能被重新解码", bmp != null && bmp.width > 0)
        assertTrue("最长边应被降采样到 <=1080", maxOf(bmp.width, bmp.height) <= 1080)
        bmp.recycle()
    }

    /** 大图应被降采样，避免输入法进程 OOM。 */
    @Test
    fun importBackground_downscalesLargeImage() {
        val uri = makeImageUri(3000, 2000, 0xFFCC6633.toInt())
        val r = MediaImporter.importBackground(ctx, uri)
        assertTrue("大图导入应成功", r is MediaImporter.Result.Ok)
        val bmp = android.graphics.BitmapFactory.decodeFile((r as MediaImporter.Result.Ok).path)
        assertTrue("最长边应 <=1080", maxOf(bmp.width, bmp.height) <= 1080)
        bmp.recycle()
    }

    /** 第 2 项：导入音效后，私有目录内应出现音频副本，且能被 SoundPool 真正加载就绪。 */
    @Test
    fun importSound_copiesAndLoads() {
        val uri = makeWavUri()
        val r = MediaImporter.importSound(ctx, uri)
        assertTrue("音效导入应成功，实际：$r", r is MediaImporter.Result.Ok)
        val path = (r as MediaImporter.Result.Ok).path
        val f = File(path)
        assertTrue("音效文件应存在于私有目录", f.exists() && f.length() > 0)

        // 设为当前自定义音效后，KeySoundManager 应能加载就绪（证明不是「上传了没声音」）
        AppPrefs.setCustomSound(path)
        val mgr = KeySoundManager(ctx)
        mgr.load()
        // SoundPool 异步加载，轮询等待就绪
        val deadline = System.currentTimeMillis() + 5000
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (mgr.isTapSampleReady()) { ready = true; break }
            Thread.sleep(50)
        }
        mgr.release()
        assertTrue("自定义音效应能被 SoundPool 加载就绪", ready)
    }

    /** 非音频文件应被拒绝，且给出可读原因（而非静默失败）。 */
    @Test
    fun importSound_rejectsNonAudio() {
        val uri = makeImageUri(100, 100, 0xFF00FF00.toInt()) // 这是图片不是音频
        val r = MediaImporter.importSound(ctx, uri)
        assertTrue("非音频应被拒绝", r is MediaImporter.Result.Fail)
    }

    /** 背景与动画互斥：设自定义背景后动画应自动关闭。 */
    @Test
    fun backgroundAndAnimation_areMutuallyExclusive() {
        AppPrefs.setCustomBackground("/some/path.jpg")
        assertEquals("设背景后动画应关闭", false, AppPrefs.bgAnimationOn.value)
        assertTrue("背景路径应被记录", AppPrefs.customBackgroundPath.value.isNotEmpty())

        // 反过来：开启动画应清除自定义背景
        AppPrefs.setBgAnimation(true)
        assertEquals("开动画后自定义背景应被清除", "", AppPrefs.customBackgroundPath.value)
        assertEquals("动画应为开", true, AppPrefs.bgAnimationOn.value)
    }

    /** 音效包与自定义音效互斥：选内置包应清除自定义音效。 */
    @Test
    fun soundPackAndCustomSound_areMutuallyExclusive() {
        AppPrefs.setCustomSound("/some/tap_custom.wav")
        assertTrue("自定义音效应被记录", AppPrefs.customSoundPath.value.isNotEmpty())
        AppPrefs.setSoundPack(1)
        assertEquals("选内置包后自定义音效应被清除", "", AppPrefs.customSoundPath.value)
        assertEquals("音效包应为 1", 1, AppPrefs.soundPack.value)
    }
}
