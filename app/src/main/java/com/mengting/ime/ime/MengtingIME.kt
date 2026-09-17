package com.mengting.ime.ime

import android.content.Context
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
import com.mengting.ime.core.ClipboardHistory
import com.mengting.ime.core.TypingStats
import com.mengting.ime.feature.audio.KeySoundManager
import com.mengting.ime.feature.translate.TranslateHelper
import com.mengting.ime.feature.voice.VoiceInputController
import com.mengting.ime.ui.keyboard.KeyboardHost
import com.mengting.ime.ui.keyboard.KeyboardScreen
import com.mengting.ime.ui.keyboard.KeyboardState

/**
 * 梦婷输入法主服务。
 * 手势约定：
 *  - 长按空格：启动语音转文字（松开停止）
 *  - 长按删除并向左滑动：清空输入框全部内容（显示全删气泡）
 *  - 长按中英切换：全局翻译当前输入框文本
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
    private val deleteLongRunnable = Runnable {
        deleteLongFired = true
        state.showDeleteBubble = true
    }

    override fun onCreate() {
        super.onCreate()
        sound = KeySoundManager(this)
        voice = VoiceInputController(this)
        voice.preload()
        imeLifecycle.moveTo(Lifecycle.State.CREATED)
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this)
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
        ClipboardHistory.captureCurrent(this)
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

    override fun context(): Context = this

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
        if (state.capsMode == 1) state.capsMode = 0
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
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
        var guard = 0
        while (guard++ < 60) {
            val before = ic.getTextBeforeCursor(1024, 0)
            val after = ic.getTextAfterCursor(1024, 0)
            val bl = before?.length ?: 0
            val al = after?.length ?: 0
            if (bl == 0 && al == 0) break
            ic.deleteSurroundingText(bl, al)
        }
    }

    override fun selectAll() {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    override fun copySelection() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.copy)
        // 复制结果进剪贴板历史（延迟读系统剪贴板）
        handler.postDelayed({ ClipboardHistory.captureCurrent(this) }, 300)
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
        state.listening = true
        var committed = 0
        voice.start(
            onResult = { text ->
                // 增量结果：回退已提交部分再提交新文本
                val ic = currentInputConnection ?: return@start
                if (committed > 0) {
                    ic.deleteSurroundingText(committed, 0)
                    committed = 0
                }
                if (text.isNotBlank()) {
                    ic.commitText(text, 1)
                    committed = text.length
                }
            },
            onStatus = { msg -> state.voiceStatus = msg }
        )
    }

    override fun onSpaceRelease() {
        if (state.listening) {
            state.listening = false
            voice.stop()
            TypingStats.onCharCommitted(0)
        }
    }

    override fun onDeleteDown(x: Float, y: Float) {
        deleteLongFired = false
        deleteDownX = x; deleteDownY = y
        handler.postDelayed(deleteLongRunnable, 400)
    }

    override fun onDeleteMove(x: Float, y: Float): Boolean {
        if (deleteLongFired && (deleteDownX - x) > 60f && Math.abs(y - deleteDownY) < 160f) {
            deleteLongFired = false
            state.showDeleteBubble = false
            deleteAll()
            playKeySound(KeySoundManager.KIND_DELETE)
            return true
        }
        return false
    }

    override fun onDeleteUp() {
        handler.removeCallbacks(deleteLongRunnable)
        deleteLongFired = false
        state.showDeleteBubble = false
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

    override fun translateText(text: String, onResult: (List<String>) -> Unit) {
        TranslateHelper.translateText(text, onResult)
    }

    override fun toggleLayout() {
        val next = (AppPrefs.layoutMode + 1) % 3
        AppPrefs.layoutMode = next
        state.layoutVersion = next
        state.clearComposition()
    }

    override fun setLayout(mode: Int) {
        AppPrefs.layoutMode = mode
        state.layoutVersion = mode
        state.clearComposition()
    }

    override fun toggleLang() {
        state.isChinese = !state.isChinese
    }
}
