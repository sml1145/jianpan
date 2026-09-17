package com.mengting.ime.feature.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent

/** 长按句号触发屏幕取字入口 */
object OcrLauncher {
    /** OCR 结果回传给 IME 的回调（插入输入框） */
    @Volatile var pendingInsert: ((String) -> Unit)? = null

    fun start(ctx: Context) {
        val i = Intent(ctx, OcrCaptureActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

const val REQ_CAPTURE = 9001

fun requestCapture(activity: Activity) {
    val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    activity.startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
}
