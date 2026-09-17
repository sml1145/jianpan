package com.mengting.ime.core

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * 拼音引擎：字音表 + 词频表 + 用户词典。
 * 支持全拼、九宫格数字串、模糊音、词组切分与词频排序。
 */
object PinyinEngine {
    @Volatile var ready = false
        private set

    /** 字 -> 拼音（无声调，多音） */
    private val charPinyins = HashMap<Char, Array<String>>(30000)

    /** 拼音 -> 单字（按字频排序） */
    private val pyChars = HashMap<String, ArrayList<Char>>(500)

    /** 字频 */
    private val charFreq = HashMap<Char, Int>(20000)

    /** 词表与索引 */
    private val wordList = ArrayList<String>(400000)
    private val wordFreq = ArrayList<Int>(400000)
    private val pyWords = HashMap<String, ArrayList<Int>>(400000)

    /** 合法音节集合（用于九宫格展开剪枝） */
    private val syllables = HashSet<String>(500)

    /** 用户词典：词 -> 加分 */
    private val userBoost = HashMap<String, Int>()

    private const val MAX_WORD_LEN = 6

    fun ensureLoaded(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            load(ctx)
            ready = true
        }
    }

    private fun load(ctx: Context) {
        // 1. 字音表
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
        // 2. 字频：给 pyChars 排序
        ctx.assets.open("dict/char_freq.txt").bufferedReader().useLines { lines ->
            for (ln in lines) {
                val p = ln.split('\t')
                if (p.size < 2 || p[0].isEmpty()) continue
                charFreq[p[0][0]] = p[1].toIntOrNull() ?: 0
            }
        }
        for ((_, list) in pyChars) list.sortByDescending { charFreq[it] ?: 0 }

        // 3. 词表（已按词频降序），建拼音串联索引，取前 30 万条
        var count = 0
        ctx.assets.open("dict/word_freq.txt").bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (count >= 300000) break
                val p = ln.split('\t')
                if (p.size < 2) continue
                val w = p[0]
                val fr = p[1].toIntOrNull() ?: continue
                if (w.length < 2 || w.length > MAX_WORD_LEN) continue
                val key = pinyinOf(w) ?: continue
                val idx = wordList.size
                wordList.add(w); wordFreq.add(fr)
                pyWords.getOrPut(key) { ArrayList() }.add(idx)
                count++
            }
        }
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

    /** 模糊音归一：把输入串的可能变体列出 */
    private fun fuzzyVariants(input: String): List<String> {
        val set = LinkedHashSet<String>()
        set.add(input)
        val pairs = arrayOf("zh" to "z", "ch" to "c", "sh" to "s", "eng" to "en", "ing" to "in", "l" to "n")
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
        if (input.isEmpty()) return emptyList()
        if (!ready) return emptyList()
        val out = LinkedHashSet<String>()
        // 1) 整串为单个音节时的单字
        for (v in fuzzyVariants(input)) {
            pyChars[v]?.let { chars ->
                for (c in chars) if (out.size < limit) out.add(c.toString())
            }
        }
        // 2) 词组：DFS 切分，按词频取优
        val best = segmentTop(input, 4)
        for (w in best) if (out.size < limit) out.add(w)
        return out.toList()
    }

    /** 记忆化切分：返回 input 的 top-K 组合词串 */
    private fun segmentTop(input: String, k: Int): List<String> {
        val memo = HashMap<Int, List<Pair<String, Long>>>()
        fun dfs(pos: Int): List<Pair<String, Long>> {
            memo[pos]?.let { return it }
            if (pos == input.length) return listOf("" to 1L)
            val res = ArrayList<Pair<String, Long>>()
            val maxEnd = minOf(input.length, pos + 24)
            for (end in maxEnd downTo pos + 1) {
                val key = input.substring(pos, end)
                val variants = fuzzyVariants(key)
                for (v in variants) {
                    val idxs = pyWords[v] ?: continue
                    // 只取该键下前 3 高频词
                    val take = minOf(3, idxs.size)
                    for (i in 0 until take) {
                        val wi = idxs[i]
                        val w = wordList[wi]
                        val fr = wordFreq[wi].toLong() + (userBoost[w] ?: 0)
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

    /** 九宫格：数字串 -> 候选（先展开为可能拼音串） */
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

    /** 数字串展开为合法拼音串（DFS，音节集合剪枝） */
    private fun expandT9(digits: String): List<String> {
        val results = LinkedHashSet<String>()
        fun isPrefix(s: String): Boolean = syllables.any { it.startsWith(s) }
        fun dfs(pos: Int, cur: StringBuilder, sylls: MutableList<String>) {
            if (results.size > 40) return
            if (pos == digits.length) {
                results.add(cur.toString())
                return
            }
            val letters = t9map[digits[pos]] ?: return
            for (ch in letters) {
                cur.append(ch)
                val s = cur.toString()
                val tail = s.substring(sylls.fold(0) { a, b -> a + b.length })
                if (syllables.contains(tail)) {
                    sylls.add(tail)
                    dfs(pos + 1, cur, sylls)
                    sylls.removeAt(sylls.size - 1)
                } else if (isPrefix(tail)) {
                    dfs(pos + 1, cur, sylls)
                }
                cur.deleteCharAt(cur.length - 1)
            }
        }
        dfs(0, StringBuilder(), ArrayList())
        return results.toList()
    }

    /** 单字候选（整串为单音节时） */
    fun charCandidates(pinyin: String): List<Char> = pyChars[pinyin] ?: emptyList()
}
