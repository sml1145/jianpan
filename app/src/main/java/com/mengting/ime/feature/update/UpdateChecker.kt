package com.mengting.ime.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val source: String = ""
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
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
            }
        }
        return Remote(tag, apkUrl, obj.optString("body"), source = "GitHub API")
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
            source = "jsdelivr CDN"
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

    // ---------------- 下载（断点续传 + 校验） ----------------

    class UpdateException(msg: String) : Exception(msg)

    suspend fun download(
        ctx: Context, url: String, sha256: String?,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val part = File(dir, "update.apk.part")
        try {
            var resumeFrom = if (part.exists()) part.length() else 0L
            var conn = open(url)
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            var code = conn.responseCode
            if (code == 416) { // 续传位置越界，重来
                part.delete(); resumeFrom = 0
                conn.disconnect()
                conn = open(url)
                code = conn.responseCode
            }
            if (code == 206) {
                // 服务端支持续传
            } else if (code in 200..299) {
                part.delete(); resumeFrom = 0
            } else {
                conn.disconnect()
                throw UpdateException("下载 HTTP $code")
            }
            val total = conn.contentLengthLong.let { if (it > 0) it + resumeFrom else -1L }
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
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt()
                            if (p != lastReport && p % 2 == 0) { lastReport = p; onProgress(p) }
                        }
                    }
                }
            }
            conn.disconnect()
            // 校验 1：大小合理（本项目 APK > 100MB）
            if (part.length() < 10L * 1024 * 1024) {
                part.delete()
                throw UpdateException("下载文件过小，可能不完整")
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
            out
        } catch (e: Exception) {
            null
        }
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
