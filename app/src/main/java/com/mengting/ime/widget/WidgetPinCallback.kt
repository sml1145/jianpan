package com.mengting.ime.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 系统「添加小组件」确认框的结果回调：
 * 用户确认后系统会携带 EXTRA_APPWIDGET_ID 广播至此；
 * 无效 id（RESULT_CANCELED）表示用户取消。
 */
class WidgetPinCallback : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // 用户已确认：立即刷新该组件内容
            val mgr = AppWidgetManager.getInstance(ctx)
            TypingStatsWidget.refreshById(ctx, mgr, id)
            Toast.makeText(ctx, "梦婷打字统计组件已添加到桌面", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "已取消添加组件", Toast.LENGTH_SHORT).show()
        }
    }
}
