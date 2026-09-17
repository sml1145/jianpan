package com.mengting.ime.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.core.PinyinEngine

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
    var isChinese by mutableStateOf(true)
    var layoutVersion by mutableIntStateOf(AppPrefs.layoutMode)
    var symbolPage by mutableIntStateOf(0)
    var showSymbols by mutableStateOf(false)
    var showEmoji by mutableStateOf(false)
    var singleHand by mutableStateOf(false)
    var floating by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var ocrText by mutableStateOf<String?>(null)

    fun refreshCandidates() {
        candidates.clear()
        if (!isChinese || composing.isEmpty()) return
        val layout = layoutVersion % 2
        val list = if (layout == 1) PinyinEngine.candidatesT9(composing) else PinyinEngine.candidates(composing)
        candidates.addAll(list)
    }

    fun clearComposition() {
        composing = ""
        candidates.clear()
    }

    fun reset() {
        clearComposition()
        showSymbols = false
        showEmoji = false
        symbolPage = 0
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
    fun moveCursor(delta: Int)
    fun sendEnter()
    fun onSpaceLongPress()
    fun onDeleteDown(x: Float, y: Float)
    fun onDeleteMove(x: Float, y: Float): Boolean
    fun onDeleteUp()
    fun onLangLongPress()
    fun onPeriodLongPress()
    fun toggleLayout()
    fun toggleLang()
}
