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
enum class Panel { NONE, SYMBOLS, EMOJI, CLIPBOARD, TRANSLATE, HANDWRITING, CALCULATOR }

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

    /** 翻译面板 */
    var translateInput by mutableStateOf("")
    var translateResult by mutableStateOf<List<String>>(emptyList())

    /** 翻译输入框常驻：点击激活后键盘输入进入翻译框 */
    private var translateActiveState by mutableStateOf(false)
    var translateActive: Boolean
        get() = translateActiveState
        set(value) {
            translateActiveState = value
            if (!value) {
                translateInput = ""
                translateResult = emptyList()
            }
        }

    /** 长按删除时的全删气泡 */
    var showDeleteBubble by mutableStateOf(false)

    /** 捕获到的短信验证码（气泡提示，点击直接输入） */
    var smsCode by mutableStateOf<String?>(null)

    /** 计算器表达式与结果 */
    var calcExpr by mutableStateOf("")
    var calcResult by mutableStateOf("")

    fun refreshCandidates() {
        candidates.clear()
        if (composing.isEmpty()) return
        if (isChinese) {
            val layout = layoutVersion % 3
            val list = if (layout == 1) PinyinEngine.candidatesT9(composing) else PinyinEngine.candidates(composing)
            candidates.addAll(list)
        } else {
            candidates.addAll(EnglishEngine.candidates(composing))
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
        translateActive = false
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
    fun onSpaceLongPress()
    fun onSpaceRelease()
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
