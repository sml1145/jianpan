package com.mengting.ime.core

import android.content.Context

/**
 * 英文输入引擎：内置英文常用词表，按前缀返回补全候选（实现英文自动拼词）。
 */
object EnglishEngine {
    @Volatile var ready = false
        private set
    private var words = arrayOf<String>()
    private var lower = arrayOf<String>()

    fun ensureLoaded(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val list = ArrayList<String>(12000)
            try {
                ctx.assets.open("dict/en_words.txt").bufferedReader().useLines { lines ->
                    for (ln in lines) {
                        val w = ln.trim()
                        if (w.length in 1..20 && w.all { it.isLetter() }) list.add(w)
                    }
                }
            } catch (_: Exception) {}
            words = list.toTypedArray()
            java.util.Arrays.sort(words)
            lower = Array(words.size) { words[it].lowercase() }
            ready = true
        }
    }

    /** 前缀匹配，返回按词频（词表顺序≈常用度，但已排序）+ 短词优先的候选 */
    fun candidates(prefix: String, limit: Int = 30): List<String> {
        if (prefix.isEmpty() || !ready) return emptyList()
        val p = prefix.lowercase()
        val start = lower.binarySearchFirst(p)
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        var i = start
        while (i < lower.size && lower[i].startsWith(p) && out.size < limit) {
            out.add(words[i])
            i++
        }
        // 短词、完全匹配优先
        return out.sortedWith(compareBy({ it.length }, { it })).take(limit)
    }

    private fun Array<String>.binarySearchFirst(key: String): Int {
        var lo = 0; var hi = size - 1; var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (this[mid] >= key) { res = mid; hi = mid - 1 } else lo = mid + 1
        }
        return res
    }
}
