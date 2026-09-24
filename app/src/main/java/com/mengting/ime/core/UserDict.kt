package com.mengting.ime.core

import android.content.Context

/**
 * 用户词典：记录用户输入过的词并持久化，用于候选提权（越常用越靠前）。
 * 词 -> 累计次数；加载时注入 PinyinEngine 的 userBoost，提交时实时加分。
 */
object UserDict {
    private const val NAME = "mt_userdict"
    private const val MAX_ENTRIES = 3000
    private val map = HashMap<String, Int>()
    @Volatile private var sp: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        sp = p
        synchronized(map) {
            map.clear()
            for ((k, v) in p.all) {
                if (v is Int) map[k] = v
            }
        }
        // 注入引擎提权
        synchronized(map) {
            for ((w, c) in map) PinyinEngine.addUserWord(w, c)
        }
    }

    /** 记录一次用户选词（仅记 2 字及以上中文词，单字无意义） */
    fun record(word: String) {
        if (word.length < 2 || word.length > 8) return
        if (word.any { it.code < 0x4E00 || it.code > 0x9FFF }) {
            // 允许纯英文单词也提权
            if (!word.all { it.isLetter() }) return
        }
        val s = sp ?: return
        val newCount: Int
        synchronized(map) {
            val c = (map[word] ?: 0) + 1
            map[word] = c
            newCount = c
            PinyinEngine.addUserWord(word, c)
            // 超出上限时裁剪低频项
            if (map.size > MAX_ENTRIES) {
                val drop = map.entries.sortedBy { it.value }.take(map.size - MAX_ENTRIES).map { it.key }
                for (d in drop) map.remove(d)
            }
        }
        s.edit().putInt(word, newCount).apply()
    }
}
