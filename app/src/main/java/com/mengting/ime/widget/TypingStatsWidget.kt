package com.mengting.ime.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.mengting.ime.R
import com.mengting.ime.core.TypingStats

/** 桌面小组件：今日码字数 + 情绪占比，凌晨 5 点由统计层滚动重算 */
class TypingStatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_typing_stats)
        val chars = TypingStats.todayChars()
        val (h, s, o) = TypingStats.moodPercents()
        views.setTextViewText(R.id.tv_char_count, "今天码了 $chars 字")
        views.setTextViewText(R.id.tv_mood, "开心的内容占比 $h%，不开心的内容占比 $s%，其他内容占比 $o%")
        views.setTextViewText(R.id.tv_update_time, "每日凌晨 5 点刷新")
        manager.updateAppWidget(id, views)
    }

    companion object {
        fun refreshById(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_typing_stats)
            val chars = TypingStats.todayChars()
            val (h, s, o) = TypingStats.moodPercents()
            views.setTextViewText(R.id.tv_char_count, "今天码了 $chars 字")
            views.setTextViewText(R.id.tv_mood, "开心的内容占比 $h%，不开心的内容占比 $s%，其他内容占比 $o%")
            views.setTextViewText(R.id.tv_update_time, "每日凌晨 5 点刷新")
            manager.updateAppWidget(id, views)
        }

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TypingStatsWidget::class.java))
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_typing_stats)
                val chars = TypingStats.todayChars()
                val (h, s, o) = TypingStats.moodPercents()
                views.setTextViewText(R.id.tv_char_count, "今天码了 $chars 字")
                views.setTextViewText(R.id.tv_mood, "开心的内容占比 $h%，不开心的内容占比 $s%，其他内容占比 $o%")
                views.setTextViewText(R.id.tv_update_time, "每日凌晨 5 点刷新")
                mgr.updateAppWidget(id, views)
            }
        }
    }
}
