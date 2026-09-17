package com.mengting.ime.core

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/** 今日打字量与情绪占比统计，供桌面小组件展示；每天凌晨 5 点重置重算 */
object TypingStats {
    private const val NAME = "typing_stats"
    private lateinit var sp: SharedPreferences

    // 情绪词表（本地词典法，不上传任何文本）
    private val HAPPY = setOf(
        "开心", "快乐", "高兴", "哈哈", "喜欢", "爱", "棒", "赞", "好耶", "幸福", "甜", "暖", "笑",
        "兴奋", "满意", "惊喜", "庆祝", "加油", "胜利", "成功", "美", "爽", "乐", "嗨", "么么", "比心"
    )
    private val SAD = setOf(
        "难过", "伤心", "哭", "烦", "累", "痛苦", "失望", "生气", "讨厌", "郁闷", "焦虑", "压力",
        "崩溃", "无语", "唉", "惨", "苦", "悲", "孤独", "害怕", "担心", "糟", "烦死", "心累"
    )

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        rollIfNeeded()
    }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 凌晨 5 点滚动：当前时间在 5 点之后且记录日期不是今天则清零 */
    private fun rollIfNeeded() {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val day = todayKey()
        val saved = sp.getString("day", "") ?: ""
        if (saved != day && hour >= 5) {
            sp.edit().putString("day", day).putInt("chars", 0)
                .putInt("happy", 0).putInt("sad", 0).putInt("other", 0).apply()
        } else if (saved.isEmpty()) {
            sp.edit().putString("day", day).apply()
        }
    }

    fun onCharCommitted(count: Int) {
        rollIfNeeded()
        sp.edit().putInt("chars", sp.getInt("chars", 0) + count).apply()
    }

    /** 提交词时做情绪归类计数（只存计数，不落盘原文） */
    fun onWordCommitted(word: String) {
        rollIfNeeded()
        val e = sp.edit()
        e.putInt("chars", sp.getInt("chars", 0) + word.length)
        val hitHappy = HAPPY.any { word.contains(it) }
        val hitSad = SAD.any { word.contains(it) }
        when {
            hitHappy -> e.putInt("happy", sp.getInt("happy", 0) + 1)
            hitSad -> e.putInt("sad", sp.getInt("sad", 0) + 1)
            else -> e.putInt("other", sp.getInt("other", 0) + 1)
        }
        e.apply()
    }

    fun todayChars(): Int {
        rollIfNeeded()
        return sp.getInt("chars", 0)
    }

    /** 返回 开心/不开心/其他 三个百分比 */
    fun moodPercents(): Triple<Int, Int, Int> {
        rollIfNeeded()
        val h = sp.getInt("happy", 0)
        val s = sp.getInt("sad", 0)
        val o = sp.getInt("other", 0)
        val total = h + s + o
        if (total == 0) return Triple(0, 0, 100)
        val hp = Math.round(h * 100f / total)
        val spp = Math.round(s * 100f / total)
        var op = 100 - hp - spp
        if (op < 0) op = 0
        return Triple(hp, spp, op)
    }
}
