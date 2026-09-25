package com.mengting.ime.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局设置（按键颜色、背景、单手、音效、联网增强等）。
 *
 * **为什么外观类设置要包成 StateFlow**：
 * 键盘 UI 与设置页在同一进程，但原先 `customBackgroundUri` 只是个普通 SharedPreferences
 * 属性读取，Compose 无从得知它变了 —— 用户在设置页换完图回到键盘，界面不会重组，
 * 必须杀掉输入法进程才生效，看起来就像「设置没起作用」。
 * 现在写入时同步更新 StateFlow，`BackgroundLayer` / `KeySoundManager` 订阅后立即响应。
 *
 * 初始值仍从 SharedPreferences 读取，保证进程重启后设置不丢。
 */
object AppPrefs {
    private const val NAME = "mengting_prefs"
    private lateinit var sp: SharedPreferences

    // ---------------- 可观察的外观设置 ----------------

    /** 自定义背景：应用私有目录内的文件路径，空串表示未设置（用像素背景） */
    private val _customBackgroundPath = MutableStateFlow("")
    val customBackgroundPath: StateFlow<String> = _customBackgroundPath

    /** 背景动画（像素动效）开关 */
    private val _bgAnimationOn = MutableStateFlow(true)
    val bgAnimationOn: StateFlow<Boolean> = _bgAnimationOn

    /** 自定义按键音效文件路径，空串表示用内置音效包 */
    private val _customSoundPath = MutableStateFlow("")
    val customSoundPath: StateFlow<String> = _customSoundPath

    /** 内置音效包：0=清脆 1=机械 2=泡泡 */
    private val _soundPack = MutableStateFlow(0)
    val soundPack: StateFlow<Int> = _soundPack

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        // 从持久化恢复可观察状态（兼容旧版本存的 custom_bg_uri 已不再读取）
        _customBackgroundPath.value = sp.getString("custom_bg_path", "") ?: ""
        _bgAnimationOn.value = sp.getBoolean("bg_anim_on", true)
        _customSoundPath.value = sp.getString("custom_sound_path", "") ?: ""
        _soundPack.value = sp.getInt("sound_pack", 0)
    }

    // 按键颜色（ARGB），0 表示跟随主题
    var keyColor: Int
        get() = sp.getInt("key_color", 0)
        set(v) = sp.edit().putInt("key_color", v).apply()

    // 单手模式默认偏向：0=重力感应 1=左 2=右
    var singleHandSide: Int
        get() = sp.getInt("single_hand_side", 0)
        set(v) = sp.edit().putInt("single_hand_side", v).apply()

    // 重力感应偏向逆转
    var singleHandInvert: Boolean
        get() = sp.getBoolean("single_hand_invert", false)
        set(v) = sp.edit().putBoolean("single_hand_invert", v).apply()

    // 按键音效/振动默认关闭
    var soundOn: Boolean
        get() = sp.getBoolean("sound_on", false)
        set(v) = sp.edit().putBoolean("sound_on", v).apply()

    var vibrateOn: Boolean
        get() = sp.getBoolean("vibrate_on", false)
        set(v) = sp.edit().putBoolean("vibrate_on", v).apply()

    // 联网增强词库，默认开启
    var netBoost: Boolean
        get() = sp.getBoolean("net_boost", true)
        set(v) = sp.edit().putBoolean("net_boost", v).apply()

    // 键盘类型：0=26键全键盘 1=九宫格 2=手写
    var layoutMode: Int
        get() = sp.getInt("layout_mode", 0)
        set(v) = sp.edit().putInt("layout_mode", v).apply()

    // ---------------- 外观设置的写入入口 ----------------

    /**
     * 设置自定义背景。传空串表示清除（恢复像素背景）。
     *
     * 与背景动画互斥：设了自定义背景就关掉动画，清除背景则恢复动画默认开启。
     * 两者本就是二选一的渲染分支（BackgroundLayer 里 if/else 只会画其一），
     * 让开关状态与实际渲染一致，避免用户看到「动画还开着但背景是图片」的矛盾提示。
     */
    fun setCustomBackground(path: String) {
        _customBackgroundPath.value = path
        // 与背景动画互斥：设了图就关动画，清除图则恢复动画默认开启
        val anim = path.isEmpty()
        _bgAnimationOn.value = anim
        sp.edit()
            .putString("custom_bg_path", path)
            .putBoolean("bg_anim_on", anim)
            .apply()
    }

    /** 单独设置背景动画开关；开启动画时清除自定义背景（互斥） */
    fun setBgAnimation(on: Boolean) {
        _bgAnimationOn.value = on
        sp.edit().putBoolean("bg_anim_on", on).apply()
        if (on && _customBackgroundPath.value.isNotEmpty()) {
            _customBackgroundPath.value = ""
            sp.edit().putString("custom_bg_path", "").apply()
        }
    }

    /** 设置自定义按键音效；传空串表示改回内置音效包 */
    fun setCustomSound(path: String) {
        _customSoundPath.value = path
        sp.edit().putString("custom_sound_path", path).apply()
    }

    /** 设置内置音效包（同时清除自定义音效，两者互斥） */
    fun setSoundPack(pack: Int) {
        _soundPack.value = pack
        sp.edit().putInt("sound_pack", pack).apply()
        if (_customSoundPath.value.isNotEmpty()) {
            _customSoundPath.value = ""
            sp.edit().putString("custom_sound_path", "").apply()
        }
    }
}
