package com.mengting.ime.feature.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.mengting.ime.core.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 全局翻译：长按中英切换键时，把当前输入框整段文本翻译（中→英 / 英→中自动判断）。
 * 联网功能；无网络时提示。
 */
object TranslateHelper {

    fun translateCurrent(ctx: Context, done: (String?) -> Unit) {
        if (!AppPrefs.netBoost) {
            toast(ctx, "翻译需开启联网，请在设置中打开联网增强")
            done(null)
            return
        }
        val ic = (ctx as? android.inputmethodservice.InputMethodService)?.currentInputConnection
        val text = ic?.getTextBeforeCursor(500, 0)?.toString().orEmpty() +
            ic?.getTextAfterCursor(500, 0)?.toString().orEmpty()
        if (text.isBlank()) {
            toast(ctx, "输入框没有可翻译的内容")
            done(null)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                translate(text.trim())
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (result == null) toast(ctx, "翻译失败，请检查网络")
                done(result)
            }
        }
    }

    private fun isChinese(s: String) = s.any { it in '一'..'鿿' }

    private fun translate(text: String): String? {
        val pair = if (isChinese(text)) "zh|en" else "en|zh"
        val q = URLEncoder.encode(text.take(450), "UTF-8")
        val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=$pair")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(body)
            val data = obj.optJSONObject("responseData") ?: return null
            data.optString("translatedText").takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
