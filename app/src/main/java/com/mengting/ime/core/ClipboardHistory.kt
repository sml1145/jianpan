package com.mengting.ime.core

import android.content.ClipboardManager
import android.content.Context

/** 剪贴板历史：保存最近 20 条复制的文字，供键盘剪切板面板一键输入 */
object ClipboardHistory {
    private const val NAME = "mt_clipboard"
    private const val MAX = 20

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 记录一条（去重置顶） */
    fun record(ctx: Context, text: String) {
        if (text.isBlank() || text.length > 4000) return
        val list = load(ctx).toMutableList()
        list.remove(text)
        list.add(0, text)
        while (list.size > MAX) list.removeAt(list.size - 1)
        sp(ctx).edit().putString("items", encode(list)).apply()
    }

    /** 把系统剪贴板当前内容并入历史 */
    fun captureCurrent(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            if (!cm.hasPrimaryClip()) return
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return
            record(ctx, t)
        } catch (_: Exception) {}
    }

    fun load(ctx: Context): List<String> {
        val raw = sp(ctx).getString("items", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("\u0001\u0001").filter { it.isNotEmpty() }
    }

    private fun encode(list: List<String>) =
        list.joinToString("\u0001\u0001") { it.replace("\u0001\u0001", " ") }
}
