package com.mengting.ime.feature.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 端到端验证「跨主机分片并行下载」的正确性与实际提速效果。
 *
 * 为什么必须跑这个测试：
 *  1. 分片算术与合并顺序若有 off-by-one，合并出的 APK 会损坏、用户根本装不上。
 *     上一版曾用 6 分片，实测同主机 6 连接被镜像直接限流封禁（0KB/s）。
 *  2. 改成跨主机并行后引入了**新的**正确性风险：不同镜像返回同一文件的不同字节段，
 *     若某个镜像返回错误内容（例如 206 状态但内容是错误页），合并结果就会损坏。
 *     只有真实下载 + SHA256 校验才能证明这条路走得通。
 *  3. 用户报告的现象是「慢→换通道→更慢→循环直至失败」。需要证明新策略真的能下完，
 *     而不是仍旧失败。桌面 curl 测速无法覆盖 App 内的实际行为。
 *
 * 下载 62MB，视镜像节流情况耗时数分钟，故只单独运行本类，不放进常规测试集。
 */
@RunWith(AndroidJUnit4::class)
class UpdateDownloadTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** 与已发布的 v1.1.1 完全一致的校验基准（用于证明下载内容字节级正确）。 */
    private val expectSize = 64877862L
    private val expectSha = "cfc2fd12515b6d954d661314074921564541cfbb19b19a760927c21dc30c355f"

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun downloadCompletesAndVerifies() {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates").apply { mkdirs() }
        // 清掉历史残留，确保这次是全新下载而非靠旧进度蒙混过关
        dir.listFiles()?.forEach { it.delete() }

        val remote = UpdateChecker.Remote(
            tag = "v1.1.1",
            apkUrl = "https://github.com/sml1145/jianpan/releases/download/v1.1.1/mengting-ime-v1.1.1.apk",
            notes = "test",
            sha256 = expectSha,
            source = "test",
            apkSize = expectSize
        )

        val speeds = StringBuilder()
        var lastPct = -1
        val t0 = System.currentTimeMillis()
        val file = runBlocking {
            UpdateChecker.download(
                ctx, remote,
                onProgress = { p ->
                    if (p / 10 != lastPct / 10) { lastPct = p; println("MTTest progress=$p%") }
                },
                onSlow = { msg -> speeds.appendLine(msg); println("MTTest slow: $msg") }
            )
        }
        val sec = (System.currentTimeMillis() - t0) / 1000.0

        assertNotNull(
            "下载失败：${UpdateChecker.lastDownloadError}\n提示记录：\n$speeds", file
        )
        val f = file!!
        assertTrue("文件不存在", f.exists())
        assertEquals("文件大小不符", expectSize, f.length())
        val sha = sha256Of(f)
        assertEquals("SHA256 不符（跨主机分片合并可能损坏内容）", expectSha, sha)

        val kbps = expectSize / 1024 / sec
        println("MTTest DONE: ${f.length()} bytes in ${"%.1f".format(sec)}s = ${"%.0f".format(kbps)} KB/s")
        println("MTTest slowNotifications=${speeds.lines().count { it.isNotBlank() }}")
        // 清理，避免占用设备存储
        f.delete()
        dir.listFiles()?.forEach { if (it.name.startsWith("seg_")) it.delete() }
    }
}
