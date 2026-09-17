package com.mengting.ime.feature.ocr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 屏幕取字结果选择页：点选复制 */
class OcrResultActivity : Activity() {
    companion object {
        private const val KEY = "lines"
        fun show(ctx: Context, lines: List<String>) {
            val i = Intent(ctx, OcrResultActivity::class.java)
            i.putStringArrayListExtra(KEY, ArrayList(lines))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lines = intent.getStringArrayListExtra(KEY) ?: ArrayList()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 48, 32, 32)
        }
        val title = TextView(this).apply {
            text = "提取到的屏幕文字（点选复制）"
            textSize = 17f
            setTextColor(Color.parseColor("#4A2B5A"))
        }
        root.addView(title)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (ln in lines.take(60)) {
            val b = Button(this).apply {
                text = ln
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(Color.parseColor("#2B1B33"))
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("mt-ocr", ln))
                    OcrLauncher.pendingInsert?.invoke(ln)
                    Toast.makeText(this@OcrResultActivity, "已复制", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            list.addView(b, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val copyAll = Button(this).apply {
            text = "复制全部"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mt-ocr", lines.joinToString("\n")))
                OcrLauncher.pendingInsert?.invoke(lines.joinToString("\n"))
                Toast.makeText(this@OcrResultActivity, "已复制全部", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(copyAll)
        setContentView(root)
    }
}
