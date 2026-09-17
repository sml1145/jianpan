package com.mengting.ime.core

import android.content.Context

/**
 * 拼音引擎（分级加载版）：
 *  - 阶段1：字音表+字频（<1秒），单字候选立即可用
 *  - 阶段2：先建高频前 5 万词索引并原子切换，词候选尽快可用
 *  - 阶段3：后台补全至 30 万词，再次原子切换
 * 所有索引以不可变快照发布，读取无锁。
 */
object PinyinEngine {
    @Volatile var charsReady = false
        private set
    @Volatile var wordsReady = false
        private set
    @Volatile var ready = false
        private set

    /** 字 -> 拼音（无声调，多音） */
    private val charPinyins = HashMap<Char, Array<String>>(30000)

    /** 拼音 -> 单字（按字频排序） */
    private val pyChars = HashMap<String, ArrayList<Char>>(500)

    /** 字频 */
    private val charFreq = HashMap<Char, Int>(20000)

    /** 词索引快照（不可变，原子替换） */
    class WordIndex(
        val words: Array<String>,
        val freqs: IntArray,
        val byPy: Map<String, IntArray>
    )

    @Volatile private var index: WordIndex? = null

    /** 词库发布版本流：界面订阅后在索引就绪时补刷候选 */
    private val _indexVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val indexVersionFlow: kotlinx.coroutines.flow.StateFlow<Int> = _indexVersion

    /** 合法音节集合（用于九宫格展开剪枝） */
    private val syllables = HashSet<String>(500)

    /** 用户词典：词 -> 加分 */
    private val userBoost = HashMap<String, Int>()

    private const val STAGE1_WORDS = 30000
    private const val MAX_WORDS = 250000

    fun ensureLoaded(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            try {
                loadChars(ctx)
                charsReady = true
                ready = true
            } catch (e: Throwable) {
                // 加载失败不置 ready，下次调用会重试；记录日志便于诊断
                android.util.Log.e("MTDict", "loadChars failed", e)
            }
        }
        if (ready && !wordsLoading) {
            wordsLoading = true
            Thread({
                try { loadWordsStaged(ctx) } finally { wordsLoading = false }
            }, "mt-dict").start()
        }
    }

    @Volatile private var wordsLoading = false

    private fun loadChars(ctx: Context) {
        ctx.assets.open("dict/char_pinyin.txt").bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (ln.length < 3) continue
                val ch = ln[0]
                val pys = ln.substring(2).split(',')
                charPinyins[ch] = pys.toTypedArray()
                for (p in pys) {
                    val list = pyChars.getOrPut(p) { ArrayList() }
                    list.add(ch)
                    syllables.add(p)
                }
            }
        }
        ctx.assets.open("dict/char_freq.txt").bufferedReader().useLines { lines ->
            for (ln in lines) {
                val p = ln.split('\t')
                if (p.size < 2 || p[0].isEmpty()) continue
                charFreq[p[0][0]] = p[1].toIntOrNull() ?: 0
            }
        }
        for ((_, list) in pyChars) list.sortByDescending { charFreq[it] ?: 0 }
    }

    private fun loadWordsStaged(ctx: Context) {
        val words = ArrayList<String>(MAX_WORDS)
        val freqs = ArrayList<Int>(MAX_WORDS)
        val byPy = HashMap<String, ArrayList<Int>>(MAX_WORDS)
        var publishedStage1 = false
        var count = 0
        try {
            ctx.assets.open("dict/word_freq_py.txt").bufferedReader().useLines { lines ->
                for (ln in lines) {
                    if (count >= MAX_WORDS) break
                    val p = ln.split('\t')
                    if (p.size < 3) continue
                    val key = p[0]
                    val w = p[1]
                    val fr = p[2].toIntOrNull() ?: continue
                    val idx = words.size
                    words.add(w); freqs.add(fr)
                    byPy.getOrPut(key) { ArrayList() }.add(idx)
                    count++
                    // 阶段1：前 3 万高频词先发布
                    if (!publishedStage1 && count >= STAGE1_WORDS) {
                        publish(words, freqs, byPy)
                        publishedStage1 = true
                        wordsReady = true
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MTDict", "load failed", e)
        }
        if (words.isNotEmpty()) {
            publish(words, freqs, byPy)
            wordsReady = true
        }
    }

    private fun publish(
        words: ArrayList<String>, freqs: ArrayList<Int>, byPy: HashMap<String, ArrayList<Int>>
    ) {
        val wArr = words.toTypedArray()
        val fArr = freqs.toIntArray()
        val mMap = HashMap<String, IntArray>(byPy.size)
        for ((k, v) in byPy) mMap[k] = v.toIntArray()
        index = WordIndex(wArr, fArr, mMap)
        _indexVersion.value = _indexVersion.value + 1
    }

    /** 词的拼音串联（每字取首读音），含未知字返回 null */
    private fun pinyinOf(word: String): String? {
        val sb = StringBuilder()
        for (ch in word) {
            val pys = charPinyins[ch] ?: return null
            sb.append(pys[0])
        }
        return sb.toString()
    }

    fun addUserWord(word: String) {
        userBoost[word] = (userBoost[word] ?: 0) + 100
    }

    private fun fuzzyVariants(input: String): List<String> {
        val set = LinkedHashSet<String>()
        set.add(input)
        val pairs = arrayOf("zh" to "z", "ch" to "c", "sh" to "s", "eng" to "en", "ing" to "in")
        var cur = listOf(input)
        for ((a, b) in pairs) {
            val next = ArrayList<String>()
            for (s in cur) {
                if (s.contains(a)) next.add(s.replace(a, b))
                if (s.contains(b)) next.add(s.replace(b, a))
            }
            cur = next
            set.addAll(next)
        }
        return set.toList().take(12)
    }

    /** 全拼输入串 -> 候选词列表 */
    fun candidates(input: String, limit: Int = 30): List<String> {
        if (input.isEmpty() || !charsReady) return emptyList()
        val out = LinkedHashSet<String>()
        // 1) 整串为单音节：单字候选
        for (v in fuzzyVariants(input)) {
            pyChars[v]?.let { chars ->
                for (c in chars) if (out.size < limit) out.add(c.toString())
            }
        }
        val idx = index
        // 2) 词库切分
        if (idx != null) {
            for (w in segmentTop(input, idx, 4)) if (out.size < limit) out.add(w)
        } else {
            // 3) 词库未就绪：音节切分 + 每音节最高频字拼串兜底
            for (w in fallbackSplit(input)) if (out.size < limit) out.add(w)
        }
        return out.toList()
    }

    /** 词库未就绪时的兜底：把输入串切成音节，各取最高频字 */
    private fun fallbackSplit(input: String): List<String> {
        val sylls = splitSyllables(input) ?: return emptyList()
        val sb = StringBuilder()
        for (s in sylls) {
            val ch = pyChars[s]?.firstOrNull() ?: return emptyList()
            sb.append(ch)
        }
        return listOf(sb.toString())
    }

    /** 贪心最长匹配切分音节 */
    private fun splitSyllables(input: String): List<String>? {
        val res = ArrayList<String>()
        var pos = 0
        while (pos < input.length) {
            var matched: String? = null
            for (len in 6 downTo 1) {
                if (pos + len > input.length) continue
                val s = input.substring(pos, pos + len)
                if (syllables.contains(s)) { matched = s; break }
            }
            if (matched == null) return null
            res.add(matched)
            pos += matched.length
        }
        return res
    }

    /** 记忆化切分：返回 input 的 top-K 组合词串 */
    private fun segmentTop(input: String, idx: WordIndex, k: Int): List<String> {
        val memo = HashMap<Int, List<Pair<String, Long>>>()
        fun dfs(pos: Int): List<Pair<String, Long>> {
            memo[pos]?.let { return it }
            if (pos == input.length) return listOf("" to 1L)
            val res = ArrayList<Pair<String, Long>>()
            val maxEnd = minOf(input.length, pos + 24)
            for (end in maxEnd downTo pos + 1) {
                val key = input.substring(pos, end)
                for (v in fuzzyVariants(key)) {
                    val arr = idx.byPy[v] ?: continue
                    val take = minOf(3, arr.size)
                    for (i in 0 until take) {
                        val wi = arr[i]
                        val w = idx.words[wi]
                        val fr = idx.freqs[wi].toLong() + (userBoost[w] ?: 0)
                        for ((tail, score) in dfs(end)) {
                            if (tail.length + w.length > 32) continue
                            res.add((w + tail) to (score * (fr + 1)))
                        }
                        if (res.size > 400) break
                    }
                    if (res.size > 400) break
                }
                if (res.size > 400) break
            }
            // 单字路径
            for (v in fuzzyVariants(input.substring(pos, minOf(input.length, pos + 7)))) {
                val chars = pyChars[v] ?: continue
                val c = chars.firstOrNull() ?: continue
                val fr = (charFreq[c] ?: 0).toLong()
                for ((tail, score) in dfs(pos + v.length)) {
                    res.add((c + tail) to (score * (fr + 1)))
                }
                break
            }
            val top = res.sortedByDescending { it.second }.distinctBy { it.first }.take(k)
            memo[pos] = top
            return top
        }
        return dfs(0).map { it.first }
    }

    /** 九宫格：数字串 -> 候选 */
    fun candidatesT9(digits: String, limit: Int = 30): List<String> {
        val pinyins = expandT9(digits)
        val out = LinkedHashSet<String>()
        for (py in pinyins) {
            for (c in candidates(py, limit)) if (out.size < limit * 2) out.add(c)
        }
        return out.toList().take(limit)
    }

    private val t9map = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
    )

    private fun expandT9(digits: String): List<String> {
        val results = LinkedHashSet<String>()
        fun isPrefix(s: String): Boolean = syllables.any { it.startsWith(s) }
        fun dfs(pos: Int, cur: StringBuilder, consumed: Int) {
            if (results.size > 40) return
            if (pos == digits.length) {
                results.add(cur.toString())
                return
            }
            val letters = t9map[digits[pos]] ?: return
            for (ch in letters) {
                cur.append(ch)
                val tail = cur.substring(consumed)
                if (syllables.contains(tail)) {
                    dfs(pos + 1, cur, cur.length)
                } else if (isPrefix(tail)) {
                    dfs(pos + 1, cur, consumed)
                }
                cur.deleteCharAt(cur.length - 1)
            }
        }
        dfs(0, StringBuilder(), 0)
        return results.toList()
    }

    fun charCandidates(pinyin: String): List<Char> = pyChars[pinyin] ?: emptyList()
}
