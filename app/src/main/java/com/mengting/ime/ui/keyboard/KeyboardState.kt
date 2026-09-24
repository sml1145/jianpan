package com.mengting.ime.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.core.EnglishEngine
import com.mengting.ime.core.PinyinEngine

/** 键盘面板 */
enum class Panel { NONE, SYMBOLS, EMOJI, CLIPBOARD, TRANSLATE, HANDWRITING, CALCULATOR, VOICE, DICT }

/** 键盘运行时状态 */
class KeyboardState {
    private var composingState by mutableStateOf("")
    var composing: String
        get() = composingState
        set(value) {
            composingState = value
            refreshCandidates()
        }

    var candidates = mutableStateListOf<String>()

    private var isChineseState by mutableStateOf(true)
    var isChinese: Boolean
        get() = isChineseState
        set(value) {
            isChineseState = value
            clearComposition()
        }

    /** 大写状态：0=小写 1=临时大写(下一次输入后回到小写) 2=锁定大写 */
    var capsMode by mutableIntStateOf(0)

    /** 布局：0=26键 1=九宫格 2=手写 */
    var layoutVersion by mutableIntStateOf(AppPrefs.layoutMode)

    var symbolPage by mutableIntStateOf(0)
    var emojiPage by mutableIntStateOf(0)

    private var panelState by mutableStateOf(Panel.NONE)
    var panel: Panel
        get() = panelState
        set(value) {
            if (value == Panel.NONE && panelState == Panel.TRANSLATE) translateInput = ""
            panelState = value
        }

    var singleHand by mutableStateOf(false)
    var floating by mutableStateOf(false)
    var listening by mutableStateOf(false)

    /** 语音状态提示（权限缺失/加载中等），空串表示无提示 */
    var voiceStatus by mutableStateOf("")

    /** 翻译面板（旧字段保留兼容，双框见下） */
    var translateInput by mutableStateOf("")
    var translateResult by mutableStateOf<List<String>>(emptyList())

    /** 翻译双框：左中文右英文，互为翻译结果 */
    var translateCn by mutableStateOf("")
    var translateEn by mutableStateOf("")

    /** 当前激活的翻译框：0=未激活（正常打字） 1=中文框 2=英文框 */
    var translateFocus by mutableIntStateOf(0)

    /** 翻译进行中，避免结果回填触发反向翻译的回环 */
    var translating by mutableStateOf(false)

    /** 翻译框激活（任一框聚焦） */
    val translateActive: Boolean
        get() = translateFocus != 0

    /** 关闭翻译：清空双框与焦点 */
    fun closeTranslate() {
        translateFocus = 0
        translateCn = ""
        translateEn = ""
        translateInput = ""
        translateResult = emptyList()
    }

    /** 长按删除时的全删气泡 */
    var showDeleteBubble by mutableStateOf(false)

    /** 捕获到的短信验证码（气泡提示，点击直接输入） */
    var smsCode by mutableStateOf<String?>(null)

    /** 计算器表达式与结果 */
    var calcExpr by mutableStateOf("")
    var calcResult by mutableStateOf("")

    /** 当前翻译目标是否英文框 */
    fun isEnTarget() = translateFocus == 2

    /** 把文本追加到当前聚焦的翻译框，并按语言过滤（中文框留汉字、英文框留字母） */
    fun appendTrans(text: String) {
        when (translateFocus) {
            1 -> translateCn += text.filter { it in '\u4e00'..'\u9fff' }
            2 -> translateEn += text.filter { (it.isLetter() && it.code < 128) || it == ' ' || it == '\'' }
        }
    }

    /** 翻译框退格 */
    fun transBackspace() {
        when (translateFocus) {
            1 -> if (translateCn.isNotEmpty()) translateCn = translateCn.dropLast(1)
            2 -> if (translateEn.isNotEmpty()) translateEn = translateEn.dropLast(1)
        }
    }

    fun refreshCandidates() {
        candidates.clear()
        if (composing.isEmpty()) return
        // 英文翻译框聚焦时强制走英文词表
        val useEnglish = !isChinese || isEnTarget()
        if (useEnglish) {
            candidates.addAll(EnglishEngine.candidates(composing))
        } else {
            val layout = layoutVersion % 3
            val list = if (layout == 1) PinyinEngine.candidatesT9(composing) else PinyinEngine.candidates(composing)
            candidates.addAll(list)
        }
    }

    fun clearComposition() {
        composingState = ""
        candidates.clear()
    }

    fun reset() {
        clearComposition()
        panel = Panel.NONE
        symbolPage = 0
        emojiPage = 0
        showDeleteBubble = false
        closeTranslate()
    }
}

/** IME 服务对键盘 UI 暴露的能力 */
interface KeyboardHost {
    val state: KeyboardState
    fun playKeySound(kind: Int)
    fun commitText(text: String)
    fun setComposing(text: String)
    fun deleteBackward()
    fun deleteAll()
    fun selectAll()
    fun copySelection()
    fun moveCursor(delta: Int)
    fun sendEnter()
    fun enterKeyLabel(): String
    fun onSpaceLongPress()
    fun onSpaceRelease()
    fun voiceStart()
    fun voiceStop()
    fun onDeleteDown(x: Float, y: Float)
    fun onDeleteMove(x: Float, y: Float): Boolean
    fun onDeleteUp()
    fun onLangLongPress()
    fun translateText(text: String, onResult: (List<String>) -> Unit)
    fun toggleLayout()
    fun setLayout(mode: Int)
    fun toggleLang()
    fun hideKeyboard()
    fun context(): android.content.Context
}
