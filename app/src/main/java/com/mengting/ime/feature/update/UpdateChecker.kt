package com.mengting.ime.feature.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内自更新：对比 GitHub 仓库 sml1145/jianpan 的最新 release 版本号；
 * 有新版则下载 APK 并引导安装。
 */
object UpdateChecker {
    private const val REPO = "sml1145/jianpan"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Remote(val tag: String, val apkUrl: String?, val notes: String)

    fun localVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    private fun compare(a: String, b: String): Int {
        val pa = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    suspend fun check(ctx: Context): Remote? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "mengting-ime")
            conn.connectTimeout = 8000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name")
            if (tag.isBlank()) return@withContext null
            val assets = obj.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
                }
            }
            Remote(tag, apkUrl, obj.optString("body"))
        } catch (e: Exception) { null }
    }

    fun hasNew(remote: Remote?, local: String): Boolean =
        remote != null && compare(remote.tag, local) > 0

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val out = File(dir, "mengting-update.apk")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            val total = conn.contentLength
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(8192)
                    var read = 0L; var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        o.write(buf, 0, n); read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
            conn.disconnect()
            out
        } catch (e: Exception) { null }
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

    fun canInstallUnknown(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
}
