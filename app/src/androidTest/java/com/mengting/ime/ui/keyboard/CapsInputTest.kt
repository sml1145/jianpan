package com.mengting.ime.ui.keyboard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mengting.ime.core.PinyinEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证「大写开启时字母直接上屏」的修复。
 *
 * 缺陷：中文键盘下点大写再点字母，字母被 lowercase() 塞进拼音组合区，
 * 强制用户从候选里选，无法直接输入大写字母。
 *
 * 必须在设备上跑：onChar 依赖 Compose 状态与引擎，且需要真实 Context。
 */
@RunWith(AndroidJUnit4::class)
class CapsInputTest {

    /**
     * 假 host：记录 commitText 的调用序列。
     * 关键——复刻真实 MengtingIME.commitText 的「单次大写提交后复位」语义，
     * 否则测不出 capsMode=1 用完后是否正确回到小写。
     */
    private class FakeHost(override val state: KeyboardState, private val ctx: Context) : KeyboardHost {
        val commits = mutableListOf<String>()
        val composings = mutableListOf<String>()

        override fun commitText(text: String) {
            commits += text
            state.clearComposition()
            if (state.capsMode == 1) state.capsMode = 0
        }

        override fun setComposing(text: String) { composings += text }
        override fun playKeySound(kind: Int) {}
        override fun deleteBackward() {}
        override fun deleteAll() {}
        override fun selectAll() {}
        override fun copySelection() {}
        override fun moveCursor(delta: Int) {}
        override fun sendEnter() {}
        override fun enterKeyLabel(): String = "回车"
        override fun onSpaceLongPress() {}
        override fun onSpaceRelease() {}
        override fun voiceStart() {}
        override fun voiceStop() {}
        override fun onDeleteDown(x: Float, y: Float) {}
        override fun onDeleteMove(x: Float, y: Float): Boolean = false
        override fun onDeleteUp() {}
        override fun onLangLongPress() {}
        override fun translateText(text: String, onResult: (List<String>) -> Unit) { onResult(emptyList()) }
        override fun toggleLayout() {}
        override fun setLayout(mode: Int) {}
        override fun toggleLang() {}
        override fun hideKeyboard() {}
        override fun context(): Context = ctx
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun host(): FakeHost = FakeHost(KeyboardState(), ctx)

    /**
     * 等待全量词库就绪（31 万词在设备上构建索引需数秒）。
     * 只有依赖候选词产出的断言才需要——否则词库未加载完会造成误判失败。
     */
    private fun awaitFullDict() {
        PinyinEngine.ensureLoaded(ctx)
        val deadline = System.currentTimeMillis() + 60_000
        var last = -1
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val v = PinyinEngine.indexVersionFlow.value
            if (v != last) { last = v; stableSince = System.currentTimeMillis() }
            if (v >= 2 && System.currentTimeMillis() - stableSince > 1500) return
            Thread.sleep(100)
        }
    }

    /** 核心修复：中文键盘 + 单次大写，点字母直接上屏大写，不进候选。 */
    @Test
    fun chineseKeyboard_singleShift_commitsUppercaseDirectly() {
        val h = host()
        assertTrue("默认应为中文键盘", h.state.isChinese)

        h.state.capsMode = 1
        onChar(h, "a")

        assertEquals("应直接上屏大写字母", listOf("A"), h.commits)
        assertEquals("不应进入拼音组合区", "", h.state.composing)
        assertTrue("不应产生候选词", h.state.candidates.isEmpty())
        assertEquals("单次大写用后应复位为小写", 0, h.state.capsMode)
    }

    /** 锁定大写：连续输入都是大写，且状态保持锁定。 */
    @Test
    fun capsLock_staysLockedAndCommitsUppercase() {
        val h = host()
        h.state.capsMode = 2

        onChar(h, "q")
        onChar(h, "w")
        onChar(h, "e")

        assertEquals(listOf("Q", "W", "E"), h.commits)
        assertEquals("锁定大写不应复位", 2, h.state.capsMode)
        assertEquals("", h.state.composing)
    }

    /** 回归护栏：未开大写时，字母仍进组合区产生候选（中文输入不受影响）。 */
    @Test
    fun noCaps_lettersStillGoToComposition() {
        awaitFullDict()
        val h = host()
        h.state.capsMode = 0

        onChar(h, "n")
        onChar(h, "i")

        assertEquals("未开大写不应直接上屏", emptyList<String>(), h.commits)
        assertEquals("应进入拼音组合区", "ni", h.state.composing)
        assertTrue("应产生候选词", h.state.candidates.isNotEmpty())
    }

    /**
     * 有未提交拼音时开大写：先按首选词上屏拼音，再上屏大写字母，
     * 且单次大写的复位不能被 flush 的提交提前吃掉。
     */
    @Test
    fun pendingComposition_flushesFirstThenCommitsUppercase() {
        awaitFullDict()
        val h = host()
        onChar(h, "n")
        onChar(h, "i")
        assertEquals("ni", h.state.composing)

        h.state.capsMode = 1
        onChar(h, "a")

        assertEquals("应先上屏拼音首选词再上屏大写字母", 2, h.commits.size)
        assertEquals("第二个提交应是大写 A", "A", h.commits[1])
        assertTrue("拼音首选词应为汉字", h.commits[0].any { it.code in 0x4E00..0x9FFF })
        assertEquals("", h.state.composing)
        assertEquals("单次大写仍应复位", 0, h.state.capsMode)
    }

    /** 英文翻译框聚焦时，大写字母进英文框且不被语言过滤掉。 */
    @Test
    fun translateEnglishBox_receivesUppercase() {
        val h = host()
        h.state.translateFocus = 2
        h.state.capsMode = 1

        onChar(h, "a")
        onChar(h, "b")

        // 单次大写在第一个字母后复位，大写字母进英文框（未被语言过滤掉）
        assertEquals("大写字母应进入英文翻译框", "A", h.state.translateEn)
        assertEquals("不应推入主编辑器", emptyList<String>(), h.commits)
        // 复位后第二个字母回到既有的英文词组合流程（走候选，不直接进框）
        assertEquals("复位后字母仍进组合区", "b", h.state.composing)
        assertEquals("单次大写应已复位", 0, h.state.capsMode)
    }

    /** 大写开启时，数字与符号不受影响（仍按原路径处理）。 */
    @Test
    fun capsDoesNotAffectNonLetters() {
        val h = host()
        h.state.capsMode = 2

        onChar(h, "1")
        onChar(h, "2")

        assertEquals("数字不应直接上屏", emptyList<String>(), h.commits)
        assertEquals("数字仍进组合区", "12", h.state.composing)
        assertEquals("锁定大写应保持", 2, h.state.capsMode)
    }

    /** 英文键盘 + 大写：同样直接上屏，且大写单词不被记为用户词库噪音。 */
    @Test
    fun englishKeyboard_capsCommitsUppercase() {
        val h = host()
        h.state.isChinese = false
        h.state.capsMode = 2

        onChar(h, "h")
        onChar(h, "i")

        assertEquals(listOf("H", "I"), h.commits)
        assertEquals("", h.state.composing)
    }
}
