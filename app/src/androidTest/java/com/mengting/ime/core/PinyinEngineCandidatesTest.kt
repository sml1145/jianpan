package com.mengting.ime.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端验证「真正打包进 App」的 PinyinEngine（而非独立原型），跑在真机/模拟器 + 真 31 万词库上。
 *
 * 覆盖本轮用户提的三个候选逻辑问题：
 *  - 第 1 条：nhao（简拼 n + 全拼 hao 混合）过去完全无候选，现在要能出「你好」。
 *  - 第 4 条：cmbjx 要出诗句「春眠不觉晓」、jttqzmy 要出整句「今天天气怎么样」。
 *  - 第 7 条：pai（恰好一个完整音节）要先出单字「牌/拍/排」，而不是「排队/拍摄/派对」。
 *
 * 引擎在 ensureLoaded 里同步加载前 3 万词（indexVersion→1），再用后台线程补齐全量
 * （indexVersion→2）。测试轮询等 indexVersion 稳定 ≥2，确保断言的是全量 31 万词库的结果，
 * 否则会因词库没加载完而误判。
 */
@RunWith(AndroidJUnit4::class)
class PinyinEngineCandidatesTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** 触发加载并等待全量词库就绪（最多 60 秒；31 万词在设备上构建索引需要数秒）。 */
    private fun awaitFullDict() {
        PinyinEngine.ensureLoaded(ctx)
        val deadline = System.currentTimeMillis() + 60_000
        var last = -1
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val v = PinyinEngine.indexVersionFlow.value
            if (v != last) { last = v; stableSince = System.currentTimeMillis() }
            // 全量加载后 indexVersion 会停在 ≥2 且不再变化；稳定 1.5 秒即认为就绪
            if (v >= 2 && System.currentTimeMillis() - stableSince > 1500) return
            Thread.sleep(100)
        }
    }

    private fun cands(input: String, n: Int = 8): List<String> {
        awaitFullDict()
        return PinyinEngine.candidates(input, n)
    }

    /** 第 7 条：单音节 pai 必须单字优先（牌/拍/排在前），不能被前缀词「排队」等占据头部。 */
    @Test
    fun singleSyllablePrefersChars() {
        val c = cands("pai")
        assertTrue("pai 无候选", c.isNotEmpty())
        val head = c.take(4).joinToString("")
        // 前 4 个里应包含高频单字（排/拍/派/牌 至少两个），且不应是「排队/拍摄/派对」这类双字词打头
        val singleCharHits = c.take(4).count { it.length == 1 && it[0] in "排拍派牌脾迫" }
        assertTrue("pai 前 4 个单字命中不足：$c", singleCharHits >= 2)
        assertTrue("pai 头部被双字词占据：$c", c.first().length == 1)
    }

    /** 第 4 条：诗句简拼 cmbjx 要出「春眠不觉晓」。 */
    @Test
    fun poemAbbreviationResolves() {
        val c = cands("cmbjx", 10)
        assertTrue("cmbjx 未出「春眠不觉晓」：$c", c.contains("春眠不觉晓"))
    }

    /** 第 4 条：整句简拼 jttqzmy 要出「今天天气怎么样」。 */
    @Test
    fun sentenceAbbreviationResolves() {
        val c = cands("jttqzmy", 10)
        assertTrue("jttqzmy 未出「今天天气怎么样」：$c", c.contains("今天天气怎么样"))
    }

    /** 第 1 条：混合简拼 nhao（n + hao）要出「你好」，过去这类输入完全无候选。 */
    @Test
    fun mixedAbbreviationResolves() {
        val c = cands("nhao", 10)
        assertTrue("nhao 无候选（回归）", c.isNotEmpty())
        assertTrue("nhao 未出「你好」：$c", c.contains("你好"))
    }

    /** 回归护栏：全拼 nihao 必须出「你好」（此前 v3 曾误出「你和安区」）。 */
    @Test
    fun fullPinyinStillWorks() {
        val c = cands("nihao")
        assertTrue("nihao 未出「你好」：$c", c.contains("你好"))
    }

    /** 回归护栏：常见全拼 shurufa 必须出「输入法」。 */
    @Test
    fun commonWordStillWorks() {
        val c = cands("shurufa")
        assertTrue("shurufa 未出「输入法」：$c", c.contains("输入法"))
    }
}
