package com.mengting.ime.core

import android.content.Context
import android.content.SharedPreferences

/** 全局设置（按键颜色、背景、单手、音效、联网增强等） */
object AppPrefs {
    private const val NAME = "mengting_prefs"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    // 按键颜色（ARGB），0 表示跟随主题
    var keyColor: Int
        get() = sp.getInt("key_color", 0)
        set(v) = sp.edit().putInt("key_color", v).apply()

    // 自定义背景图 Uri 字符串，空串表示使用随时间变化的像素背景
    var customBackgroundUri: String
        get() = sp.getString("custom_bg_uri", "") ?: ""
        set(v) = sp.edit().putString("custom_bg_uri", v).apply()

    // 单手模式默认偏向：0=重力感应 1=左 2=右
    var singleHandSide: Int
        get() = sp.getInt("single_hand_side", 0)
        set(v) = sp.edit().putInt("single_hand_side", v).apply()

    // 重力感应偏向逆转
    var singleHandInvert: Boolean
        get() = sp.getBoolean("single_hand_invert", false)
        set(v) = sp.edit().putBoolean("single_hand_invert", v).apply()

    var soundOn: Boolean
        get() = sp.getBoolean("sound_on", true)
        set(v) = sp.edit().putBoolean("sound_on", v).apply()

    var vibrateOn: Boolean
        get() = sp.getBoolean("vibrate_on", true)
        set(v) = sp.edit().putBoolean("vibrate_on", v).apply()

    // 联网增强词库，默认开启
    var netBoost: Boolean
        get() = sp.getBoolean("net_boost", true)
        set(v) = sp.edit().putBoolean("net_boost", v).apply()

    var bgAnimationOn: Boolean
        get() = sp.getBoolean("bg_anim_on", true)
        set(v) = sp.edit().putBoolean("bg_anim_on", v).apply()

    // 键盘布局：0=全键盘 1=九宫格
    var layoutMode: Int
        get() = sp.getInt("layout_mode", 0)
        set(v) = sp.edit().putInt("layout_mode", v).apply()
}
