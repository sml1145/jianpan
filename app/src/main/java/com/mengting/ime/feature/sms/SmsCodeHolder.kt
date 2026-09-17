package com.mengting.ime.feature.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 短信验证码捕获：监听系统短信广播，用正则提取 4-8 位验证码，
 * 通过 StateFlow 通知键盘显示"点击输入"气泡。
 * 验证码仅在内存中保留 60 秒，绝不落盘、绝不上传。
 */
object SmsCodeHolder {
    private val _codeFlow = MutableStateFlow<String?>(null)
    val codeFlow: StateFlow<String?> = _codeFlow

    private var registered = false
    private var expireJob: Thread? = null

    /** 常见验证码模式：数字串前后带"验证码/code/校验码"等提示，或纯 4-8 位独立数字串 */
    private val patterns = listOf(
        Regex("(?:验证码|校验码|动态码|确认码|随机码|安全码|code|Code|CODE)[^0-9a-zA-Z]{0,12}([0-9]{4,8})"),
        Regex("([0-9]{4,8})[^0-9]{0,6}(?:为|是|为您的)?[^0-9]{0,10}(?:验证码|校验码|动态码|code)"),
        Regex("(?<![0-9])([0-9]{6})(?![0-9])")
    )

    fun extractCode(body: String): String? {
        for (p in patterns) {
            val m = p.find(body)
            if (m != null) {
                val code = m.groupValues[1]
                if (code.length in 4..8) return code
            }
        }
        return null
    }

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val bundle: Bundle = intent?.extras ?: return
                val pdus = bundle.get("pdus") as? Array<*> ?: return
                val format = bundle.getString("format") ?: "3gpp"
                val sb = StringBuilder()
                for (pdu in pdus) {
                    try {
                        val msg = if (Build.VERSION.SDK_INT >= 23)
                            SmsMessage.createFromPdu(pdu as ByteArray, format)
                        else
                            @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu as ByteArray)
                        sb.append(msg?.messageBody ?: "")
                    } catch (_: Exception) {}
                }
                val body = sb.toString()
                val code = extractCode(body) ?: return
                _codeFlow.value = code
                // 60 秒后过期清除
                expireJob?.interrupt()
                expireJob = Thread {
                    try { Thread.sleep(60_000) } catch (_: Exception) {}
                    if (_codeFlow.value == code) _codeFlow.value = null
                }.also { it.start() }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    /** 用户点击气泡输入后清除 */
    fun consume() {
        _codeFlow.value = null
    }
}
