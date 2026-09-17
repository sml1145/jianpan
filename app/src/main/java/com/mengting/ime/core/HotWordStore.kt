package com.mengting.ime.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 联网增强（真实实现）：
 * 从仓库 CDN 拉取「热词表」(hotwords.txt)，格式每行 `拼音键<TAB>词<TAB>加权`，
 * 合并进 PinyinEngine 的热词覆盖层，使新词/网络热词在发版外即可更新。
 * 结果缓存到本地，联网失败时用上次缓存，绝不影响离线输入。
 * 通道：jsdelivr CDN 主，GitHub raw 备。
 */
object HotWordStore {
    private const val CDN = "https://cdn.jsdelivr.net/gh/sml1145/jianpan@main/dict/hotwords.txt"
    private const val RAW = "https://raw.githubusercontent.com/sml1145/jianpan/main/dict/hotwords.txt"
    private const val CACHE = "hotwords_cache.txt"

    @Volatile var lastSyncOk = false
        private set
    @Volatile var syncedCount = 0
        private set

    /** 应用启动/联网增强开启时调用：先加载缓存，再后台拉取最新 */
    suspend fun sync(ctx: Context, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        // 先用本地缓存即时可用
        try {
            val cache = ctx.getFileStreamPath(CACHE)
            if (cache.exists()) apply(cache.readText())
        } catch (_: Exception) {}
        // 拉取最新
        val body = fetch(CDN) ?: fetch(RAW)
        if (body != null) {
            try {
                ctx.openFileOutput(CACHE, Context.MODE_PRIVATE).bufferedWriter().use { it.write(body) }
            } catch (_: Exception) {}
            apply(body)
            lastSyncOk = true
            syncedCount = hotCount(body)
            return@withContext syncedCount
        }
        lastSyncOk = false
        syncedCount
    }

    private fun hotCount(body: String): Int = body.lineSequence().count { it.contains('\t') }

    private fun apply(body: String) {
        val entries = parse(body)
        PinyinEngine.addHotWords(entries)
    }

    private fun parse(body: String): List<Triple<String, String, Int>> {
        val list = ArrayList<Triple<String, String, Int>>()
        for (ln in body.lineSequence()) {
            val p = ln.split('\t')
            if (p.size < 2) continue
            val key = p[0].trim()
            val word = p[1].trim()
            if (key.isEmpty() || word.isEmpty()) continue
            val boost = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            list.add(Triple(key, word, boost))
        }
        return list
    }

    private fun fetch(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "mengting-ime-hotword")
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); null }
        else {
            val t = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (t.isBlank()) null else t
        }
    } catch (e: Exception) { null }
}
