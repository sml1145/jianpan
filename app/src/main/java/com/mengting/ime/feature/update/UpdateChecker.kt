package com.mengting.ime.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内自更新（并发测速 + 分片并行版）。
 * 更新源：GitHub 仓库 sml1145/jianpan（公开）。
 *
 * 检测：4 条通道**并发竞速**，谁先返回用谁——
 *   1. jsdelivr CDN version.json（国内可达性最好，且带 sha256 与大小）
 *   2. GitHub API  releases/latest（权威，含附件直链）
 *   3. GitHub 网页 releases/latest 302 跳转（不占 API 限额）
 *   4. Atom 订阅源 releases.atom（取最新 entry 的 tag）
 *
 * 下载：先探测存活通道并**实测速度**排序（死通道直接出局），
 * 再用同通道 **4 分片并行**下载（实测 gh-proxy 单连接约 69KB/s，4 连接约 430KB/s）；
 * 分片失败只保留不删除进度、按分片续传重试，通道不支持 Range 时回退单连接续传。
 * 「慢」不再触发换通道：慢只如实提示并继续下，只有完全停滞或校验失败才放弃。
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

    /**
     * 并发竞速检测：同时发起全部通道，谁先返回可用结果就用谁。
     *
     * 旧实现是串行逐个试，每通道 12s 超时且失败重试一次。国内网络下 api.github.com
     * 与 github.com 经常直接不可达，仅前三个通道就要耗掉约 72 秒才轮到 jsdelivr，
     * 这正是"检测更新特别慢"的主因（比安装包体积影响更大）。
     * 改为并发后，可用通道通常 1~2 秒内返回；只有全部通道都失败才会等满超时。
     */
    suspend fun check(ctx: Context): CheckResult = withContext(Dispatchers.IO) {
        val channels: List<Pair<String, suspend () -> Remote?>> = listOf(
            // jsdelivr CDN 放首位：国内可达性最好，且 version.json 带 sha256 与大小
            "jsdelivr CDN" to { fromCdn() },
            "GitHub API" to { fromApi() },
            "GitHub 网页跳转" to { fromRedirect() },
            "Atom 订阅源" to { fromAtom() }
        )

        // 各通道跑在独立作用域里：落败通道多是阻塞式 HttpURLConnection，取消无法立刻中断，
        // 若用 coroutineScope 等它们收尾就会重新退化成"等最慢的那个"。独立作用域让它们在
        // 后台自行结束，本次检测在首个成功通道返回时立即结束。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val winner = CompletableDeferred<Pair<Remote, String>>()
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jobs = channels.map { (name, block) ->
            scope.launch {
                try {
                    val r = withRetry { block() }
                    if (r != null && r.tag.isNotBlank()) {
                        winner.complete(r to name)
                    } else {
                        errors.add("$name：返回为空")
                    }
                } catch (e: Exception) {
                    errors.add("$name：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        try {
            val (r, name) = withTimeout(CHECK_TIMEOUT_MS) { winner.await() }
            android.util.Log.i("MTUpdate", "check winner=$name tag=${r.tag}")
            // 补齐下载直链与校验信息：非 CDN 通道缺 sha256/大小，尽力从 CDN 取一次。
            // 这一步必须限时，否则会把"首个通道已成功"的速度优势又耗掉。
            val meta = if (r.sha256 == null || r.apkSize <= 0) {
                runCatching {
                    withTimeout(META_TIMEOUT_MS) { withContext(Dispatchers.IO) { fetchCdnMeta(r.tag) } }
                }.getOrNull()
            } else null
            val fixed = r.copy(
                apkUrl = r.apkUrl?.takeIf { it.isNotBlank() } ?: guessApkUrl(r.tag),
                sha256 = r.sha256 ?: meta?.first,
                apkSize = if (r.apkSize > 0) r.apkSize else (meta?.second ?: 0L),
                source = name
            )
            CheckResult(fixed, errors.toList())
        } catch (e: Exception) {
            errors.add("全部通道超时（${CHECK_TIMEOUT_MS / 1000}秒）")
            CheckResult(null, errors.toList())
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private const val CHECK_TIMEOUT_MS = 20_000L
    /** CDN 元数据补齐的限时：只为拿 sha256/大小，不能拖慢已经成功的检测 */
    private const val META_TIMEOUT_MS = 3_000L

    /** 带缓存的 CDN 元数据（sha256、大小），供非 CDN 通道补齐校验信息 */
    @Volatile private var cdnMetaCache: Triple<String, String?, Long>? = null
    private fun fetchCdnMeta(tag: String): Pair<String?, Long>? {
        val cached = cdnMetaCache
        if (cached != null && cached.first == tag) return cached.second to cached.third
        return try {
            val (code, body) = httpGet(CDN_VERSION)
            if (code != 200) return null
            val obj = JSONObject(body)
            val v = obj.optString("version")
            if (v.isBlank()) return null
            val sha = obj.optString("sha256").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            val size = obj.optLong("apkSize", 0L)
            cdnMetaCache = Triple(if (v.startsWith("v")) v else "v$v", sha, size)
            sha to size
        } catch (e: Exception) { null }
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

    /**
     * 下载通道：直连 + 国内加速镜像。此处顺序只是初始偏好，实际按探测实测速度重排。
     * 实测（2026-09）gh-proxy 与 cors.isteed.cc 可用，其余多数镜像已失效，
     * 保留它们是作为将来网络环境变化时的兜底。
     *
     * 注意各镜像的 URL 拼接格式不同：多数是「前缀 + 完整 URL」，
     * 而 isteed 实测要求「前缀 + 去掉 scheme 的路径」（github.com/...），
     * 拼错会直接 404，所以这里分开构造，不能统一用 $url。
     */
    private fun candidateUrls(url: String): List<String> {
        val bare = url.removePrefix("https://").removePrefix("http://")
        return listOf(
            "https://gh-proxy.com/$url",
            "https://cors.isteed.cc/$bare",
            url,
            "https://mirror.ghproxy.com/$url",
            "https://ghproxy.net/$url",
            "https://gh.llkk.cc/$url",
            "https://github.moeyy.xyz/$url"
        )
    }

    /**
     * 探测结果：通道地址 + 是否支持 Range（决定能否分片并行）+ 实测单连接速度 KB/s（0=未测）。
     */
    private data class Channel(val url: String, val rangeOk: Boolean, val kbps: Long)

    /**
     * 通道探测：可用性 + Range 支持 + **实测速度**。
     *
     * 旧实现只验证「能不能连上」（状态码/PK 魔数/总大小），却对用户宣称会「切换到更快的通道」。
     * 它从未测过速度，排序与快慢无关，于是慢了就盲目换通道——而实测六个通道里往往只有
     * gh-proxy 活着，其余全部超时，换来换去只会把时间耗在死通道上直到下载失败。
     *
     * 现在分两步：
     *   1) 并发用 1KB Range 请求筛掉死通道（代价极小，且不会触发镜像的并发限流）；
     *   2) 存活通道多于一个时，再逐个用 192KB 样本实测单连接速度并降序排列。
     *      只剩一个存活通道时直接跳过测速——国内常见情况就是只有 gh-proxy 可用，
     *      此时测速纯属浪费时间。
     */
    private suspend fun probeChannels(url: String, expectSize: Long): List<Channel> {
        val all = candidateUrls(url)
        val alive = coroutineScope {
            all.map { u ->
                async {
                    try {
                        val conn = open(u, timeoutMs = 8000)
                        conn.setRequestProperty("Range", "bytes=0-1023")
                        val code = conn.responseCode
                        var magicOk = false
                        if (code == 200 || code == 206) {
                            val head = ByteArray(2)
                            conn.inputStream.use { it.read(head) }
                            magicOk = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                        }
                        // 期望大小匹配（Content-Range: bytes 0-1023/64877862）
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
                        if ((code == 200 || code == 206) && magicOk && sizeOk) {
                            Channel(u, rangeOk = code == 206, kbps = 0)
                        } else {
                            android.util.Log.w("MTUpdate", "probe unusable $u: HTTP $code magic=$magicOk size=$sizeOk")
                            null
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MTUpdate", "probe failed $u: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        android.util.Log.i("MTUpdate", "probe alive=${alive.size}/${all.size}")
        if (alive.size <= 1) return alive
        val measured = alive.map { ch -> ch.copy(kbps = measureSpeed(ch.url)) }
        measured.forEach { android.util.Log.i("MTUpdate", "speed ${it.kbps}KB/s range=${it.rangeOk} ${it.url}") }
        return measured.sortedByDescending { it.kbps }
    }

    /** 测速样本大小：192KB，够测出稳定速率又不至于白等太久 */
    private const val SPEED_SAMPLE_BYTES = 196608L

    /** 用固定样本实测单连接速度（KB/s）；失败/超时返回 0，让该通道排到最后。 */
    private fun measureSpeed(url: String): Long {
        return try {
            val conn = open(url, timeoutMs = 6000)
            conn.readTimeout = 6000
            conn.setRequestProperty("Range", "bytes=0-${SPEED_SAMPLE_BYTES - 1}")
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return 0L }
            val t0 = System.nanoTime()
            val deadline = t0 + 6_000_000_000L
            var bytes = 0L
            conn.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (ins.read(buf).also { n = it } != -1) {
                    bytes += n
                    if (bytes >= SPEED_SAMPLE_BYTES || System.nanoTime() > deadline) break
                }
            }
            conn.disconnect()
            val ms = (System.nanoTime() - t0) / 1_000_000L
            if (ms <= 0 || bytes <= 0) 0L else bytes / 1024 * 1000 / ms
        } catch (e: Exception) {
            0L
        }
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
        // 探测选优：存活通道按实测速度降序，死通道直接出局（不再进重试列表白等）
        val channels = try {
            probeChannels(url, remote.apkSize)
        } catch (e: Exception) {
            android.util.Log.w("MTUpdate", "probe threw, fallback to static list", e)
            candidateUrls(url).map { Channel(it, rangeOk = true, kbps = 0) }
        }
        if (channels.isEmpty()) {
            lastDownloadError = "所有下载通道均不可达（网络受限或镜像故障），请用浏览器打开下载"
            return@withContext null
        }
        var lastErr: String? = null

        // 阶段 1：跨主机分片并行（最快路径）。
        // downloadSegmented 内部会自己挑前 MAX_HOSTS 个支持 Range 的通道轮流分配分片，
        // 所以这里只调一次、传入全部存活通道，不能在通道循环里重复调。
        if (remote.apkSize > 0 && channels.any { it.rangeOk }) {
            try {
                val f = downloadSegmented(ctx, channels, remote.sha256, remote.apkSize, onProgress, onSlow)
                if (f != null) return@withContext f
                android.util.Log.i("MTUpdate", "segmented incomplete, fall back to single-connection")
                // 注意：分片进度存在 seg_N.part，单连接进度存在 update.apk.part，两者**不互通**。
                // 这里保留 seg 文件是因为用户下次点「更新」时 downloadSegmented 会从断点续传，
                // 已下载的字节不会白费；但本次回退到单连接只能从 update.apk.part 重新开始。
            } catch (e: Exception) {
                // SHA256 校验失败等：说明某个通道内容不可信，丢弃进度后改走单连接逐个试
                lastErr = "分片下载：${e.message ?: e.javaClass.simpleName}"
                android.util.Log.w("MTUpdate", "segmented failed, fall back", e)
                File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates")
                    .listFiles { f -> f.name.startsWith("seg_") }?.forEach { it.delete() }
            }
        }

        // 阶段 2：逐通道单连接续传兜底（通道不支持 Range，或分片始终凑不齐）
        for ((i, ch) in channels.withIndex()) {
            val u = ch.url
            val label = "通道${i + 1}"
            var attempt = 0
            while (attempt < 2) {
                attempt++
                try {
                    val f = downloadOnce(ctx, u, remote.sha256, remote.apkSize, onProgress, onSlow)
                    if (f != null) {
                        // 单连接已成功，分片残留的 seg_*.part 不再需要，清掉以免白占最多一整包空间
                        dir.listFiles { file -> file.name.startsWith("seg_") }?.forEach { it.delete() }
                        return@withContext f
                    }
                } catch (e: Exception) {
                    lastErr = "$label 第${attempt}次：${e.message ?: e.javaClass.simpleName}"
                    android.util.Log.e("MTUpdate", "download $u attempt $attempt failed", e)
                }
            }
            // 换通道：进度归零提示（part 保留，同通道重试仍可续传）
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
        // 读超时 12 秒：只在「完全停滞」时才抛错，让外层换通道；
        // 速度偏慢但仍在收数据时不抛错、不换通道（旧实现慢就换通道，是死循环的根源）。
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
        // 速率监控与停滞检测：
        // 旧实现「低于 80KB/s 提示 + 低于 15KB/s 直接抛异常换通道」是死循环的根源——
        // 实测国内六个通道里常常只有 gh-proxy 活着，且单连接就只有 69KB/s 左右，
        // 一抛异常就换到死通道，换完更慢再抛，直到通道耗尽下载失败。
        // 慢并不等于失败：现在只在「完全停滞」时才放弃，速度正常偏低就如实告知并继续下。
        var speedWindowStart = System.currentTimeMillis()
        var speedWindowBytes = 0L
        var lastSlowNotify = 0L
        var stallRounds = 0
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
                        if (speedWindowBytes == 0L) {
                            // 一整秒零字节才算停滞；连续 3 秒才放弃（避免瞬时抖动误杀）
                            if (++stallRounds >= 3) {
                                try { input.close() } catch (_: Exception) {}
                                throw UpdateException("通道停滞（连续 ${stallRounds} 秒无数据），已保留进度")
                            }
                        } else {
                            stallRounds = 0
                        }
                        // 慢只提示，不换通道；且最多每 15 秒提示一次，避免刷屏
                        if (kbps in 1 until 80 && now - lastSlowNotify > 15000) {
                            lastSlowNotify = now
                            val remain = if (total > 0) (total - done) else -1L
                            val eta = if (remain > 0 && kbps > 0) remain / 1024 / kbps else -1L
                            onSlow(
                                if (eta > 0) "当前速度约 ${kbps}KB/s，预计还需 ${fmtEta(eta)}，正在继续下载…"
                                else "当前速度约 ${kbps}KB/s，正在继续下载…"
                            )
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
        return finalizeDownload(part, out, sha256, expectSize, onProgress)
    }

    /**
     * 下载收尾校验：大小、APK 魔数、SHA256 三重校验，全部通过才重命名为最终文件。
     * 单连接与分片并行下载共用，保证两条路径的完整性判定完全一致。
     */
    private fun finalizeDownload(
        part: File, out: File, sha256: String?, expectSize: Long, onProgress: (Int) -> Unit
    ): File {
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

    /** 秒数 → 「x分y秒」形式的可读剩余时间 */
    private fun fmtEta(sec: Long): String = when {
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec / 3600}小时${(sec % 3600) / 60}分"
    }

    /**
     * 分片并行下载的连接数。
     *
     * 实测（gh-proxy，18~20 秒窗口）单连接约 69KB/s，聚合速度随并发提升：
     *   2 连接 186KB/s、4 连接 412/437/452KB/s（三次稳定，约 6 倍）
     *   6 连接 0KB/s、8 连接 0KB/s（两次复测均为 0）——镜像对高并发直接限流封禁。
     * 原值 6 正好落在封禁区：分片全部拿不到数据 → 放弃分片 → 回退单连接 69KB/s →
     * 触发「慢速换通道」→ 换来的是死通道 → 最终下载失败。故取 4，既拿到约 6 倍提速又不触发限流。
     */
    private const val SEGMENTS = 4

    /** 每个分片的最大重试次数（失败后从已下载字节续传，不重头开始） */
    private const val SEG_RETRY = 3

    /** 参与分片下载的主机上限（实测跨主机并行比单主机多连接更快，因节流按主机计） */
    private const val MAX_HOSTS = 2

    /**
     * 分片并行下载：把安装包按字节切成 [SEGMENTS] 段，**分散到多个存活主机**同时下载。
     *
     * 为什么这么做（全部为实测结论，gh-proxy 下载 v1.1.1 的 62MB 包）：
     *  - 单连接约 54KB/s 且随流量衰减（121→26KB/s，按流量节流）；
     *  - 同一主机 4 连接：135 秒拿到 26.5MB（约 196KB/s）；
     *  - 跨 2 个主机各 2 连接：135 秒拿到 55.5MB（约 411KB/s）。
     * 说明节流按主机计，跨主机并行比单主机多连接更快。
     *  - 并发数不能贪多：同主机 6 连接实测直接归零（触发限流封禁），故 [SEGMENTS]=4。
     *
     * 进度保护：分片失败时**只保留、不删除**已下载的分片文件，重试从断点续传，
     * 且重试时轮换到下一个主机。旧实现在任一分片失败时删除全部分片，
     * 把已下载的几十 MB 清零重来，是「越下越慢最后失败」的放大器。
     * 只有 SHA256 校验失败（内容不可信）才丢弃全部进度。
     *
     * @param hosts 存活主机列表（已按实测速度降序），取前 [MAX_HOSTS] 个轮流分配分片。
     *              只有一个时退化为单主机多连接；为空则返回 null 让上层回退单连接。
     */
    private suspend fun downloadSegmented(
        ctx: Context, hosts: List<Channel>, sha256: String?, expectSize: Long,
        onProgress: (Int) -> Unit,
        onSlow: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // 太小没必要分片；大小未知无法分片
        if (expectSize < SEGMENTS * 256 * 1024L) return@withContext null
        if (hosts.isEmpty()) return@withContext null
        val usable = hosts.filter { it.rangeOk }.take(MAX_HOSTS)
        if (usable.isEmpty()) return@withContext null
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val part = File(dir, "update.apk.part")
        val segFiles = (0 until SEGMENTS).map { File(dir, "seg_$it.part") }
        val segSize = (expectSize + SEGMENTS - 1) / SEGMENTS
        val ranges = (0 until SEGMENTS).map { i ->
            val s = i * segSize
            val e = minOf(expectSize - 1, s + segSize - 1)
            i to (s to e)
        }.filter { it.second.first <= it.second.second }

        // 断点续传：已存在的分片文件按其长度计入进度
        val done = java.util.concurrent.atomic.AtomicLong(
            segFiles.sumOf { if (it.exists()) it.length().coerceAtMost(segSize) else 0L }
        )
        // 所有主机都不支持 Range → 整体放弃分片，回退单连接
        val noRangeVotes = java.util.concurrent.atomic.AtomicInteger(0)
        val failedSeg = java.util.concurrent.atomic.AtomicInteger(0)
        // 速度采样：所有分片共用一个聚合窗口
        val winStart = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val winBytes = java.util.concurrent.atomic.AtomicLong(0)
        val lastSlow = java.util.concurrent.atomic.AtomicLong(0)

        coroutineScope {
            ranges.map { (idx, range) ->
                val (s, e) = range
                async {
                    val segFile = segFiles[idx]
                    val wantLen = e - s + 1
                    if (segFile.exists() && segFile.length() == wantLen) return@async
                    val onBytes: (Long) -> Unit = { add ->
                        val cur = done.addAndGet(add)
                        onProgress(((cur * 100) / expectSize).toInt().coerceIn(0, 99))
                        // 聚合速度提示：慢只告知，绝不放弃下载
                        winBytes.addAndGet(add)
                        val now = System.currentTimeMillis()
                        val st = winStart.get()
                        if (now - st >= 1500) {
                            val kbps = winBytes.get() / 1024 * 1000 / (now - st)
                            winStart.set(now); winBytes.set(0)
                            if (kbps in 1 until 300 && now - lastSlow.get() > 15000) {
                                lastSlow.set(now)
                                val remain = expectSize - done.get()
                                val eta = if (kbps > 0) remain / 1024 / kbps else -1L
                                onSlow(
                                    if (eta > 0) "当前速度约 ${kbps}KB/s，预计还需 ${fmtEta(eta)}，正在继续下载…"
                                    else "当前速度约 ${kbps}KB/s，正在继续下载…"
                                )
                            }
                        }
                    }
                    var ok = false
                    var attempt = 0
                    while (!ok && attempt < SEG_RETRY) {
                        // 每次重试轮换主机：某个主机被节流或掉线时自动换一个
                        val host = usable[attempt % usable.size]
                        attempt++
                        when (downloadSegment(host.url, segFile, s, e, wantLen, onBytes)) {
                            SEG_RESULT_OK -> ok = true
                            SEG_RESULT_NO_RANGE -> {
                                noRangeVotes.incrementAndGet()
                                android.util.Log.w("MTUpdate", "seg $idx: host does not support Range")
                            }
                            else -> {
                                android.util.Log.w(
                                    "MTUpdate",
                                    "seg $idx attempt $attempt failed, have ${segFile.length()}/$wantLen, will resume"
                                )
                            }
                        }
                    }
                    if (!ok) failedSeg.incrementAndGet()
                }
            }.awaitAll()
        }

        if (noRangeVotes.get() >= usable.size) {
            android.util.Log.i("MTUpdate", "no host supports Range, abandon segmented")
            return@withContext null
        }
        if (failedSeg.get() > 0) {
            // 保留已完成/部分完成的分片文件：下次重试可续传，不浪费已下载的字节
            android.util.Log.w("MTUpdate", "${failedSeg.get()} segment(s) incomplete, progress kept for resume")
            return@withContext null
        }
        // 合并分片为完整 part 文件
        if (part.exists()) part.delete()
        RandomAccessFile(part, "rw").use { raf ->
            for ((idx, _) in ranges) {
                segFiles[idx].inputStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) raf.write(buf, 0, n)
                }
            }
        }
        segFiles.forEach { it.delete() }
        if (part.length() != expectSize) { part.delete(); return@withContext null }
        try {
            finalizeDownload(part, out, sha256, expectSize, onProgress)
        } catch (e: Exception) {
            // 只有校验失败（内容不可信）才真的丢弃进度，由上层换通道重下
            android.util.Log.e("MTUpdate", "segmented verify failed, discard progress", e)
            segFiles.forEach { it.delete() }
            if (part.exists()) part.delete()
            throw e
        }
    }

    /** 分片下载结果码 */
    private const val SEG_RESULT_OK = 0
    private const val SEG_RESULT_NO_RANGE = 1
    private const val SEG_RESULT_FAIL = 2

    /**
     * 下载单个分片到独立文件，从已下载字节续传。
     * @return [SEG_RESULT_OK] 完成 / [SEG_RESULT_NO_RANGE] 通道不支持 Range / [SEG_RESULT_FAIL] 需重试
     */
    private fun downloadSegment(
        url: String, segFile: File, start: Long, end: Long, wantLen: Long,
        onBytes: (Long) -> Unit
    ): Int {
        var resume = if (segFile.exists()) segFile.length().coerceAtMost(wantLen) else 0L
        if (resume >= wantLen) return SEG_RESULT_OK
        return try {
            var conn = open(url, timeoutMs = 15000)
            conn.readTimeout = 12000
            conn.setRequestProperty("Range", "bytes=${start + resume}-${end}")
            var code = conn.responseCode
            if (code == 416) { // 续传越界，整片重来
                segFile.delete(); resume = 0
                conn.disconnect()
                conn = open(url, timeoutMs = 15000); conn.readTimeout = 12000
                conn.setRequestProperty("Range", "bytes=${start}-${end}")
                code = conn.responseCode
            }
            // 必须支持 Range；返回 200 说明不支持，让上层回退单连接
            if (code != 206) { conn.disconnect(); return SEG_RESULT_NO_RANGE }
            conn.inputStream.use { input ->
                RandomAccessFile(segFile, "rw").use { raf ->
                    raf.seek(resume)
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        raf.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            conn.disconnect()
            if (segFile.length() == wantLen) SEG_RESULT_OK else SEG_RESULT_FAIL
        } catch (e: Exception) {
            SEG_RESULT_FAIL
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
