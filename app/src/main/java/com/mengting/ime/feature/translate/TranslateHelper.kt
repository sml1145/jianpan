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
 * 全局翻译：
 *  - translateCurrent：长按中英切换键，翻译当前输入框整段文本
 *  - translateText：翻译面板逐词翻译（中→英 / 英→中自动判断），返回多条候选
 */
object TranslateHelper {

    fun translateCurrent(ctx: Context, done: (String?) -> Unit) {
        val ic = (ctx as? android.inputmethodservice.InputMethodService)?.currentInputConnection
        val text = ic?.getTextBeforeCursor(500, 0)?.toString().orEmpty() +
            ic?.getTextAfterCursor(500, 0)?.toString().orEmpty()
        if (text.isBlank()) {
            toast(ctx, "输入框没有可翻译的内容")
            done(null)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = try { translate(text.trim()) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (result == null) toast(ctx, "翻译失败，请检查网络")
                done(result)
            }
        }
    }

    /** 面板翻译：返回结果列表（主翻译 + 备选） */
    fun translateText(text: String, onResult: (List<String>) -> Unit) {
        if (text.isBlank()) {
            onResult(emptyList())
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val list = mutableListOf<String>()
            try {
                translate(text.trim())?.let { list.add(it) }
            } catch (_: Exception) {}
            // 备用翻译源
            if (list.isEmpty()) {
                try {
                    translateBackup(text.trim())?.let { list.add(it) }
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) { onResult(list) }
        }
    }

    private fun isChinese(s: String) = s.any { it in '一'..'鿿' }

    private fun translate(text: String): String? {
        val pair = if (isChinese(text)) "zh|en" else "en|zh"
        val q = URLEncoder.encode(text.take(300), "UTF-8")
        val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=$pair")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(body)
            val data = obj.optJSONObject("responseData") ?: return null
            data.optString("translatedText").takeIf { it.isNotBlank() && it != text }
        } finally {
            conn.disconnect()
        }
    }

    /** 备用源：lingva（Google 翻译镜像） */
    private fun translateBackup(text: String): String? {
        val lang = if (isChinese(text)) "en" else "zh"
        val src = if (isChinese(text)) "zh" else "en"
        val q = URLEncoder.encode(text.take(300), "UTF-8")
        val url = URL("https://lingva.ml/api/v1/$src/$lang/$q")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        return try {
            val obj = JSONObject(conn.inputStream.bufferedReader().readText())
            obj.optString("translation").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
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
