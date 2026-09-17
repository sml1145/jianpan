package com.mengting.ime.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.core.TypingStats
import com.mengting.ime.feature.audio.KeySoundManager
import com.mengting.ime.feature.ocr.OcrLauncher
import com.mengting.ime.feature.translate.TranslateHelper
import com.mengting.ime.feature.voice.VoiceInputController
import com.mengting.ime.ui.keyboard.KeyboardHost
import com.mengting.ime.ui.keyboard.KeyboardScreen
import com.mengting.ime.ui.keyboard.KeyboardState

/**
 * 梦婷输入法主服务。
 * 手势约定：
 *  - 长按空格：启动语音转文字
 *  - 长按删除并向左滑动：清空输入框全部内容
 *  - 长按中英切换：全局翻译当前输入框文本
 *  - 长按句号：提取屏幕文字
 */
class MengtingIME : InputMethodService(), KeyboardHost {

    override val state = KeyboardState()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sound: KeySoundManager
    private lateinit var voice: VoiceInputController
    private val imeLifecycle = ImeLifecycleOwner()

    // 长按删除左滑全删
    private var deleteLongFired = false
    private var deleteDownX = 0f
    private var deleteDownY = 0f

    override fun onCreate() {
        super.onCreate()
        sound = KeySoundManager(this)
        voice = VoiceInputController(this)
        OcrLauncher.pendingInsert = { text -> commitText(text) }
        imeLifecycle.moveTo(Lifecycle.State.CREATED)
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this)
        // 关键：Compose 的 windowRecomposer 从窗口根子节点向上找 ViewTreeLifecycleOwner，
        // 必须设置在窗口 decorView 上（IME 的 parentPanel 是系统容器，向上必经 decorView）
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(imeLifecycle)
            decor.setViewTreeViewModelStoreOwner(imeLifecycle)
            decor.setViewTreeSavedStateRegistryOwner(imeLifecycle)
        }
        view.setViewTreeLifecycleOwner(imeLifecycle)
        view.setViewTreeViewModelStoreOwner(imeLifecycle)
        view.setViewTreeSavedStateRegistryOwner(imeLifecycle)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { KeyboardScreen(this) }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        imeLifecycle.moveTo(Lifecycle.State.RESUMED)
        state.reset()
        sound.load()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voice.stop()
        imeLifecycle.moveTo(Lifecycle.State.CREATED)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        voice.release()
        sound.release()
        imeLifecycle.destroy()
        super.onDestroy()
    }

    // ---------- KeyboardHost 实现 ----------

    override fun playKeySound(kind: Int) {
        if (AppPrefs.soundOn) sound.play(kind)
        if (AppPrefs.vibrateOn) vibrate(kind)
    }

    private fun vibrate(kind: Int) {
        val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        val ms = if (kind == KeySoundManager.KIND_TAP) 18L else 30L
        @Suppress("DEPRECATION")
        vib.vibrate(ms)
    }

    override fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
        TypingStats.onWordCommitted(text)
        state.clearComposition()
    }

    override fun setComposing(text: String) {
        currentInputConnection?.setComposingText(text, 1)
    }

    override fun deleteBackward() {
        val ic = currentInputConnection ?: return
        if (state.composing.isNotEmpty()) {
            state.composing = state.composing.dropLast(1)
            if (state.composing.isEmpty()) ic.finishComposingText() else ic.setComposingText(state.composing, 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    override fun deleteAll() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        state.clearComposition()
        var guard = 0
        while (guard++ < 100) {
            val before = ic.getTextBeforeCursor(512, 0)
            val after = ic.getTextAfterCursor(512, 0)
            val bl = before?.length ?: 0
            val al = after?.length ?: 0
            if (bl == 0 && al == 0) break
            ic.deleteSurroundingText(bl, al)
        }
    }

    override fun moveCursor(delta: Int) {
        if (delta < 0) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    override fun sendEnter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    override fun onSpaceLongPress() {
        voice.start { text -> if (text.isNotBlank()) commitText(text) }
    }

    override fun onDeleteDown(x: Float, y: Float) {
        deleteLongFired = false
        deleteDownX = x; deleteDownY = y
        handler.postDelayed({ deleteLongFired = true }, 450)
    }

    override fun onDeleteMove(x: Float, y: Float): Boolean {
        if (deleteLongFired && (deleteDownX - x) > 80f && Math.abs(y - deleteDownY) < 120f) {
            deleteLongFired = false
            deleteAll()
            playKeySound(KeySoundManager.KIND_DELETE)
            return true
        }
        return false
    }

    override fun onDeleteUp() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun onLangLongPress() {
        TranslateHelper.translateCurrent(this) { translated ->
            if (translated != null) {
                currentInputConnection?.let { ic ->
                    ic.performContextMenuAction(android.R.id.selectAll)
                    ic.commitText(translated, 1)
                }
            }
        }
    }

    override fun onPeriodLongPress() {
        OcrLauncher.start(this)
    }

    override fun toggleLayout() {
        AppPrefs.layoutMode = if (AppPrefs.layoutMode == 0) 1 else 0
        state.layoutVersion++
    }

    override fun toggleLang() {
        state.isChinese = !state.isChinese
        state.clearComposition()
    }
}
