package com.mengting.ime.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内自更新（多源容错版）。
 * 更新源：GitHub 仓库 sml1145/jianpan（公开）。
 * 依次尝试 4 条通道，任一成功即返回：
 *   1. GitHub API  releases/latest          （权威，含附件直链）
 *   2. GitHub 网页 releases/latest 302 跳转  （不占 API 限额）
 *   3. Atom 订阅源 releases.atom             （取最新 entry 的 tag）
 *   4. jsdelivr CDN version.json             （国内网络友好）
 * 每通道失败自动重试一次；全部失败时返回每个通道的具体错误，便于诊断。
 */
object UpdateChecker {
    private const val OWNER = "sml1145"
    private const val REPO = "jianpan"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val WEB_LATEST = "https://github.com/$OWNER/$REPO/releases/latest"
    private const val ATOM = "https://github.com/$OWNER/$REPO/releases.atom"
    private const val CDN_VERSION = "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@main/version.json"
    private const val UA = "mengting-ime-update/1.0"

    data class Remote(
        val tag: String,
        val apkUrl: String?,
        val notes: String,
        val sha256: String? = null,
        val source: String = "",
        /** 期望安装包字节数（发布元数据提供，用于完整性校验），0 表示未知 */
        val apkSize: Long = 0L
    )

    data class CheckResult(
        val remote: Remote?,
        val errors: List<String>
    )

    /** 本地版本号 */
    fun localVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    /** 语义化版本比较：a>b 返回正数 */
    fun compare(a: String, b: String): Int {
        val pa = a.removePrefix("v").split('.').map { it.substringBefore('-').toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").split('.').map { it.substringBefore('-').toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun hasNew(remote: Remote?, local: String): Boolean =
        remote != null && compare(remote.tag, local) > 0

    // ---------------- HTTP 基础 ----------------

    private fun open(url: String, followRedirects: Boolean = true, timeoutMs: Int = 12000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs + 3000
        conn.instanceFollowRedirects = followRedirects
        return conn
    }

    private fun httpGet(url: String, followRedirects: Boolean = true): Pair<Int, String> {
        val conn = open(url, followRedirects)
        return try {
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
            } catch (e: Exception) { "" }
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun withRetry(block: suspend () -> Remote?): Remote? {
        block()?.let { return it }
        delay(600)
        return block()
    }

    // ---------------- 通道 1：GitHub API ----------------

    private fun fromApi(): Remote? {
        val (code, body) = httpGet(API_LATEST)
        if (code != 200) throw UpdateException("API 通道 HTTP $code")
        val obj = JSONObject(body)
        val tag = obj.optString("tag_name")
        if (tag.isBlank()) throw UpdateException("API 通道：无 tag_name")
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size", 0L)
                    break
                }
            }
        }
        return Remote(tag, apkUrl, obj.optString("body"), source = "GitHub API", apkSize = apkSize)
    }

    // ---------------- 通道 2：网页 302 跳转 ----------------

    private fun fromRedirect(): Remote? {
        val conn = open(WEB_LATEST, followRedirects = false)
        try {
            val code = conn.responseCode
            if (code !in 300..399) throw UpdateException("跳转通道 HTTP $code（可能尚无 Release）")
            val loc = conn.getHeaderField("Location") ?: throw UpdateException("跳转通道：无 Location")
            val tag = loc.substringAfterLast("/tag/", "")
            if (tag.isBlank()) throw UpdateException("跳转通道：Location 无 tag")
            return Remote(tag, guessApkUrl(tag), "（来自跳转通道，无说明）", source = "GitHub 网页跳转")
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 通道 3：Atom ----------------

    private fun fromAtom(): Remote? {
        val (code, body) = httpGet(ATOM)
        if (code != 200) throw UpdateException("Atom 通道 HTTP $code")
        val m = Regex("releases/tag/([^\"<]+)").find(body)
            ?: throw UpdateException("Atom 通道：未解析到 tag")
        val tag = m.groupValues[1]
        return Remote(tag, guessApkUrl(tag), "（来自 Atom 通道，无说明）", source = "Atom 订阅源")
    }

    // ---------------- 通道 4：jsdelivr version.json ----------------

    private fun fromCdn(): Remote? {
        val (code, body) = httpGet(CDN_VERSION)
        if (code != 200) throw UpdateException("CDN 通道 HTTP $code")
        val obj = JSONObject(body)
        val v = obj.optString("version")
        if (v.isBlank()) throw UpdateException("CDN 通道：无 version 字段")
        val tag = if (v.startsWith("v")) v else "v$v"
        val sha = obj.optString("sha256").ifBlank { null }
        val validSha = sha?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        return Remote(
            tag,
            obj.optString("apkUrl").ifBlank { guessApkUrl(tag) },
            obj.optString("notes"),
            validSha,
            source = "jsdelivr CDN",
            apkSize = obj.optLong("apkSize", 0L)
        )
    }

    /** 按发布命名约定构造下载直链 */
    private fun guessApkUrl(tag: String): String =
        "https://github.com/$OWNER/$REPO/releases/download/$tag/mengting-ime-$tag.apk"

    // ---------------- 汇总检测 ----------------

    suspend fun check(ctx: Context): CheckResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val channels: List<Pair<String, () -> Remote?>> = listOf(
            "GitHub API" to { fromApi() },
            "GitHub 网页跳转" to { fromRedirect() },
            "Atom 订阅源" to { fromAtom() },
            "jsdelivr CDN" to { fromCdn() }
        )
        for ((name, block) in channels) {
            try {
                val r = withRetry { block() }
                if (r != null && r.tag.isNotBlank()) {
                    // API 通道若无 apk 附件，用约定直链兜底
                    val fixed = if (r.apkUrl.isNullOrBlank()) r.copy(apkUrl = guessApkUrl(r.tag)) else r
                    return@withContext CheckResult(fixed, errors)
                }
                errors.add("$name：返回为空")
            } catch (e: Exception) {
                errors.add("$name：${e.message ?: e.javaClass.simpleName}")
            }
        }
        CheckResult(null, errors)
    }

    // ---------------- 下载（多通道回退 + 断点续传 + 校验 + 空间预检） ----------------

    class UpdateException(msg: String) : Exception(msg)

    /** 最近一次下载失败原因，供界面展示诊断 */
    @Volatile var lastDownloadError: String? = null
        private set

    /** 独立单例作用域：下载不因设置页退出/切后台被取消 */
    private val downloadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    @Volatile private var downloadJob: kotlinx.coroutines.Job? = null

    /** 启动下载（幂等：已在下载则忽略）；onProgress 回调进度，onSlow 回调"缓慢"提示 */
    fun startDownload(
        ctx: Context, remote: Remote,
        onProgress: (Int) -> Unit,
        onSlow: (String) -> Unit = {},
        onDone: (File?) -> Unit
    ) {
        downloadJob?.takeIf { it.isActive }?.let { return }
        downloadJob = downloadScope.launch {
            val f = download(ctx, remote, onProgress, onSlow)
            withContext(Dispatchers.Main) { onDone(f) }
        }
    }

    /** 下载通道：直连 + 国内加速镜像（按实测可用性排序），下载前还会探测选优 */
    private fun candidateUrls(url: String): List<String> = listOf(
        "https://gh-proxy.com/$url",
        url,
        "https://mirror.ghproxy.com/$url",
        "https://ghproxy.net/$url",
        "https://gh.llkk.cc/$url",
        "https://github.moeyy.xyz/$url"
    )

    /**
     * 通道探测：并发用 Range 小请求验证每个通道（状态码 + PK 魔数 + 总大小匹配），
     * 把可用通道排前，避免在坏通道上浪费整次下载。
     */
    private suspend fun probeChannels(url: String, expectSize: Long): List<String> {
        val all = candidateUrls(url)
        val results = kotlinx.coroutines.coroutineScope {
            all.map { u ->
                async {
                    val ok = try {
                        val conn = open(u, timeoutMs = 8000)
                        conn.setRequestProperty("Range", "bytes=0-1023")
                        val code = conn.responseCode
                        var magicOk = false
                        if (code == 200 || code == 206) {
                            val head = ByteArray(2)
                            conn.inputStream.use { it.read(head) }
                            magicOk = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                        }
                        // 期望大小匹配（Content-Range: bytes 0-1023/86000000）
                        var sizeOk = expectSize <= 0L
                        val cr = conn.getHeaderField("Content-Range")
                        if (cr != null && cr.contains("/")) {
                            val total = cr.substringAfterLast("/").trim().toLongOrNull() ?: 0L
                            if (expectSize > 0 && total > 0) sizeOk = (total == expectSize)
                        } else if (code == 200) {
                            val len = conn.contentLengthLong
                            if (expectSize > 0 && len > 0) sizeOk = (len == expectSize)
                        }
                        conn.disconnect()
                        (code == 200 || code == 206) && magicOk && sizeOk
                    } catch (e: Exception) {
                        android.util.Log.w("MTUpdate", "probe failed $u: ${e.message}")
                        false
                    }
                    u to ok
                }
            }.awaitAll()
        }
        val good = results.filter { it.second }.map { it.first }
        val bad = results.filter { !it.second }.map { it.first }
        android.util.Log.i("MTUpdate", "probe: good=${good.size}/${all.size}")
        return good + bad // 可用通道在前，其余保留兜底
    }

    suspend fun download(
        ctx: Context, remote: Remote,
        onProgress: (Int) -> Unit,
        onSlow: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        lastDownloadError = null
        val url = remote.apkUrl
        if (url.isNullOrBlank()) {
            lastDownloadError = "发布页没有安装包附件"
            return@withContext null
        }
        // 空间预检：期望大小 + 20MB 余量
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates").apply { mkdirs() }
        val free = try { android.os.StatFs(dir.absolutePath).availableBytes } catch (e: Exception) { -1L }
        val needMin = (if (remote.apkSize > 0) remote.apkSize * 2 else 60L * 1024 * 1024) + 20L * 1024 * 1024
        if (free in 0 until needMin) {
            lastDownloadError = "手机存储空间不足（可用 ${free / 1048576}MB，需约 ${needMin / 1048576}MB）"
            return@withContext null
        }
        // 探测选优后按序尝试
        val urls = try { probeChannels(url, remote.apkSize) } catch (e: Exception) { candidateUrls(url) }
        var lastErr: String? = null
        for ((i, u) in urls.withIndex()) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                try {
                    val f = downloadOnce(ctx, u, remote.sha256, remote.apkSize, onProgress, onSlow)
                    if (f != null) return@withContext f
                } catch (e: Exception) {
                    lastErr = "通道${i + 1}第${attempt}次：${e.message ?: e.javaClass.simpleName}"
                    android.util.Log.e("MTUpdate", "download $u attempt $attempt failed", e)
                }
            }
            // 换通道前把进度归零提示（part 保留，同通道续传仍有效）
            onProgress(0)
        }
        lastDownloadError = lastErr ?: "未知错误"
        null
    }

    private fun downloadOnce(
        ctx: Context, url: String, sha256: String?, expectSize: Long,
        onProgress: (Int) -> Unit,
        onSlow: (String) -> Unit
    ): File? {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val part = File(dir, "update.apk.part")
        var resumeFrom = if (part.exists()) part.length() else 0L
        // 读超时 12 秒：通道停滞时快速抛错自动切换，不让进度条假死
        var conn = open(url, timeoutMs = 15000)
        conn.readTimeout = 12000
        conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        var code = conn.responseCode
        if (code == 416) { // 续传位置越界，重来
            part.delete(); resumeFrom = 0
            conn.disconnect()
            conn = open(url, timeoutMs = 15000)
            conn.readTimeout = 12000
            code = conn.responseCode
        }
        if (code == 206) {
            // 服务端支持续传
        } else if (code in 200..299) {
            if (resumeFrom > 0) { part.delete(); resumeFrom = 0 }
        } else {
            conn.disconnect()
            throw UpdateException("下载请求失败 HTTP $code")
        }
        val reported = conn.contentLengthLong
        val total = if (reported > 0) reported + resumeFrom else -1L
        // 速率监控：每秒采样，低于 80KB/s 持续 4 秒提示一次"缓慢"
        var speedWindowStart = System.currentTimeMillis()
        var speedWindowBytes = 0L
        var lastSlowNotify = 0L
        conn.inputStream.use { input ->
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(resumeFrom)
                val buf = ByteArray(64 * 1024)
                var done = resumeFrom
                var n: Int
                var lastReport = -1
                while (input.read(buf).also { n = it } != -1) {
                    raf.write(buf, 0, n)
                    done += n
                    speedWindowBytes += n
                    val now = System.currentTimeMillis()
                    val elapsed = now - speedWindowStart
                    if (elapsed >= 1000) {
                        val kbps = speedWindowBytes / 1024 * 1000 / elapsed
                        if (kbps < 80 && now - lastSlowNotify > 5000) {
                            lastSlowNotify = now
                            onSlow("当前下载速度较慢（约 ${kbps}KB/s），正在自动切换更快的通道，请耐心等待…")
                            // 持续龟速时主动断开，让外层切换通道续传
                            if (kbps < 15) {
                                try { input.close() } catch (_: Exception) {}
                                throw UpdateException("通道速度过慢（${kbps}KB/s），自动切换")
                            }
                        }
                        speedWindowStart = now
                        speedWindowBytes = 0L
                    }
                    if (total > 0) {
                        val p = ((done * 100) / total).toInt()
                        if (p != lastReport) { lastReport = p; onProgress(p) }
                    }
                }
            }
        }
        conn.disconnect()
        // 校验 1：大小合理（下限 3MB 防半截文件；有期望大小时精确比对）
        if (part.length() < 3L * 1024 * 1024) {
            part.delete()
            throw UpdateException("下载文件过小（${part.length()} 字节），可能不完整")
        }
        if (expectSize > 0 && part.length() != expectSize) {
            part.delete()
            throw UpdateException("文件大小不符（得到 ${part.length()}，期望 $expectSize），已丢弃可重试")
        }
        // 校验 2：APK 魔数 PK
        RandomAccessFile(part, "r").use { raf ->
            val head = ByteArray(2); raf.readFully(head)
            if (!(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) {
                part.delete()
                throw UpdateException("文件不是有效的 APK")
            }
        }
        // 校验 3：SHA256（若发布方提供）
        if (!sha256.isNullOrBlank()) {
            val actual = sha256Of(part)
            if (!actual.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw UpdateException("SHA256 校验失败")
            }
        }
        if (out.exists()) out.delete()
        if (!part.renameTo(out)) throw UpdateException("无法保存安装包")
        onProgress(100)
        return out
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------- 安装 ----------------

    /** 兜底：用系统浏览器打开发布页，让用户手动下载 APK */
    fun openInBrowser(ctx: Context) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$OWNER/$REPO/releases/latest"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {}
    }

    fun canInstallUnknown(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val i = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (_: Exception) {}
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
