package com.mengting.ime.core

import android.content.Context

/**
 * 拼音引擎（分级加载 + 三层索引版）：
 *  - 字音表+字频 同步秒载；首批 3 万高频词同步加载（键盘弹出即有候选）
 *  - 索引1：全拼精确/切分（原有）
 *  - 索引2：音节前缀 → 单字（输入 s 即出 是/说/三…）
 *  - 索引3：简拼首字母 → 词（输入 sc 即出 生成/所以/市场…；DP 处理 xian→x/xa 歧义）
 *  - 索引4：拼音键有序数组 + 二分前缀检索（输入 sheng 出 生成/生活…）
 *  - 云端热词覆盖层（HotWordStore 合并，联网增强真实生效）
 */
object PinyinEngine {
    @Volatile var charsReady = false
        private set
    @Volatile var wordsReady = false
        private set
    @Volatile var ready = false
        private set

    private val charPinyins = HashMap<Char, Array<String>>(30000)
    private val pyChars = HashMap<String, ArrayList<Char>>(500)
    private val charFreq = HashMap<Char, Int>(20000)

    class WordIndex(
        val words: Array<String>,
        val freqs: IntArray,
        val byPy: Map<String, IntArray>,
        /** 键有序数组与对应词下标（二分前缀检索） */
        val keysSorted: Array<String>,
        val idxSorted: IntArray,
        /** 简拼首字母 → 词下标 */
        val byInitials: Map<String, IntArray>
    )

    @Volatile private var index: WordIndex? = null

    private val _indexVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val indexVersionFlow: kotlinx.coroutines.flow.StateFlow<Int> = _indexVersion

    private val syllables = HashSet<String>(500)
    private val userBoost = HashMap<String, Int>()

    /** 云端热词覆盖层：全拼键 → 词 */
    private val hotByKey = HashMap<String, ArrayList<String>>()

    /** 云端热词覆盖层：简拼 → 词 */
    private val hotByInitials = HashMap<String, ArrayList<String>>()

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
                android.util.Log.e("MTDict", "loadChars failed", e)
            }
            if (ready && !wordsReady) {
                try {
                    val loaded = loadWordsSync(ctx, STAGE1_WORDS)
                    if (loaded > 0) android.util.Log.i("MTDict", "stage1 sync loaded $loaded words")
                } catch (e: Throwable) {
                    android.util.Log.e("MTDict", "stage1 sync failed", e)
                }
            }
        }
        if (ready && wordsReady && !wordsLoading) {
            wordsLoading = true
            Thread({
                try { loadWordsStaged(ctx) } finally { wordsLoading = false }
            }, "mt-dict").start()
        }
    }

    @Volatile private var wordsLoading = false

    private fun loadWordsSync(ctx: Context, limit: Int): Int {
        val words = ArrayList<String>(limit)
        val freqs = ArrayList<Int>(limit)
        val byPy = HashMap<String, ArrayList<Int>>(limit)
        var count = 0
        ctx.assets.open("dict/word_freq_py.txt").bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (count >= limit) break
                val p = ln.split('\t')
                if (p.size < 3) continue
                val idx = words.size
                words.add(p[1])
                freqs.add(p[2].toIntOrNull() ?: 0)
                byPy.getOrPut(p[0]) { ArrayList() }.add(idx)
                count++
            }
        }
        if (count > 0) {
            publish(words, freqs, byPy)
            wordsReady = true
        }
        return count
    }

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
        val cur = index
        // 从已有快照继续补齐（stage1 已含前 3 万）
        val words = ArrayList<String>(MAX_WORDS)
        val freqs = ArrayList<Int>(MAX_WORDS)
        val byPy = HashMap<String, ArrayList<Int>>(MAX_WORDS)
        if (cur != null) {
            for (i in cur.words.indices) {
                words.add(cur.words[i]); freqs.add(cur.freqs[i])
            }
            for ((k, arr) in cur.byPy) byPy[k] = arr.toCollection(ArrayList())
        }
        var count = words.size
        try {
            ctx.assets.open("dict/word_freq_py.txt").bufferedReader().useLines { lines ->
                var lineNo = 0
                for (ln in lines) {
                    lineNo++
                    if (count >= MAX_WORDS) break
                    if (lineNo <= STAGE1_WORDS) continue // 已在 stage1
                    val p = ln.split('\t')
                    if (p.size < 3) continue
                    val idx = words.size
                    words.add(p[1])
                    freqs.add(p[2].toIntOrNull() ?: 0)
                    byPy.getOrPut(p[0]) { ArrayList() }.add(idx)
                    count++
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MTDict", "staged load failed", e)
        }
        if (words.size > (cur?.words?.size ?: 0)) {
            publish(words, freqs, byPy)
            android.util.Log.i("MTDict", "full dict loaded: ${words.size} words")
        }
        wordsReady = true
    }

    private fun publish(
        words: ArrayList<String>, freqs: ArrayList<Int>, byPy: HashMap<String, ArrayList<Int>>
    ) {
        val wArr = words.toTypedArray()
        val fArr = freqs.toIntArray()
        val mMap = HashMap<String, IntArray>(byPy.size)
        for ((k, v) in byPy) mMap[k] = v.toIntArray()

        // 有序键数组（二分前缀检索）
        val entries = ArrayList<Pair<String, Int>>(wArr.size)
        for ((k, arr) in byPy) for (i in arr) entries.add(k to i)
        entries.sortWith(compareBy({ it.first }, { -fArr[it.second] }))
        val keysSorted = Array(entries.size) { entries[it].first }
        val idxSorted = IntArray(entries.size) { entries[it].second }

        // 简拼首字母索引
        val initMap = HashMap<String, ArrayList<Int>>()
        for ((k, arr) in byPy) {
            for (init in initialsVariants(k)) {
                if (init.length < 2) continue
                initMap.getOrPut(init) { ArrayList() }.addAll(arr.toList())
            }
        }
        val initFinal = HashMap<String, IntArray>(initMap.size)
        for ((k, v) in initMap) {
            val sorted = v.sortedByDescending { fArr[it] }
            initFinal[k] = sorted.take(40).toIntArray()
        }

        index = WordIndex(wArr, fArr, mMap, keysSorted, idxSorted, initFinal)
        _indexVersion.value = _indexVersion.value + 1
    }

    /** 拼音键 → 所有可能的首字母串（DP 处理 xian→x/xa 类歧义，最多 4 组） */
    private fun initialsVariants(key: String): List<String> {
        val results = LinkedHashSet<String>()
        fun dfs(pos: Int, cur: StringBuilder) {
            if (results.size >= 4) return
            if (pos == key.length) { results.add(cur.toString()); return }
            for (len in 6 downTo 1) {
                if (pos + len > key.length) continue
                val s = key.substring(pos, pos + len)
                if (syllables.contains(s)) {
                    cur.append(s[0])
                    dfs(pos + len, cur)
                    cur.deleteCharAt(cur.length - 1)
                }
            }
        }
        dfs(0, StringBuilder())
        return results.toList()
    }

    fun addUserWord(word: String) {
        userBoost[word] = (userBoost[word] ?: 0) + 100
    }

    /** 云端热词合并（HotWordStore 调用） */
    fun addHotWords(entries: List<Triple<String, String, Int>>) {
        synchronized(hotByKey) {
            for ((key, word, boost) in entries) {
                if (key.isEmpty() || word.isEmpty()) continue
                hotByKey.getOrPut(key) { ArrayList() }.let { l ->
                    if (!l.contains(word)) l.add(word)
                }
                for (init in initialsVariants(key)) {
                    if (init.length < 2) continue
                    hotByInitials.getOrPut(init) { ArrayList() }.let { l ->
                        if (!l.contains(word)) l.add(word)
                    }
                }
            }
        }
    }

    fun hotWordCount(): Int = synchronized(hotByKey) { hotByKey.size }

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

    /** 全拼输入串 -> 候选词列表（前缀/简拼/整句全覆盖） */
    fun candidates(input: String, limit: Int = 30): List<String> {
        if (input.isEmpty() || !charsReady) return emptyList()
        // 分词符（撇号）仅用于切分歧义，不参与匹配
        val effective = input.replace("'", "")
        if (effective.isEmpty() || effective.any { !it.isLetter() }) return emptyList()
        val out = LinkedHashSet<String>()

        // 0) 云端热词（联网增强）：精确键 → 简拼
        synchronized(hotByKey) {
            hotByKey[effective]?.let { out.addAll(it) }
            if (effective.length >= 2) hotByInitials[effective]?.let { out.addAll(it) }
        }

        val idx = index
        if (effective.length <= 2) {
            // 短输入：简拼词优先，其次单字（精确音节 → 前缀音节）
            if (idx != null && effective.length == 2) {
                idx.byInitials[effective]?.let { arr ->
                    for (i in arr) if (out.size < limit) out.add(idx.words[i])
                }
            }
            addCharsFor(out, effective, limit)
            if (idx != null) addPrefixWords(idx, out, effective, limit, 6)
        } else {
            // 长输入：整句切分 → 前缀词 → 简拼词 → 单字
            if (idx != null) {
                for (w in segmentTop(effective, idx, 4)) if (out.size < limit) out.add(w)
                addPrefixWords(idx, out, effective, limit, 10)
                idx.byInitials[effective]?.let { arr ->
                    for (i in arr.take(6)) if (out.size < limit) out.add(idx.words[i])
                }
            } else {
                for (w in fallbackSplit(effective)) if (out.size < limit) out.add(w)
            }
            addCharsFor(out, effective, limit)
        }
        return out.toList().take(limit)
    }

    /** 单字候选：精确音节优先，其次前缀音节（按字频排序） */
    private fun addCharsFor(out: LinkedHashSet<String>, input: String, limit: Int) {
        for (v in fuzzyVariants(input)) {
            pyChars[v]?.let { chars ->
                for (c in chars) if (out.size < limit) out.add(c.toString())
            }
        }
        if (out.size >= limit) return
        // 前缀音节匹配（input 是音节前缀，如 s → sa/san/sha/…）
        if (input.length <= 4) {
            val merged = ArrayList<Char>()
            for (syl in syllables) {
                if (syl.length > input.length && syl.startsWith(input)) {
                    pyChars[syl]?.let { merged.addAll(it.take(4)) }
                }
            }
            val seen = HashSet<Char>()
            merged.sortedByDescending { charFreq[it] ?: 0 }
            for (c in merged) {
                if (out.size >= limit) break
                if (seen.add(c)) out.add(c.toString())
            }
        }
    }

    /** 前缀词检索：二分定位键区间，扫描并按词频取前 k */
    private fun addPrefixWords(
        idx: WordIndex, out: LinkedHashSet<String>, input: String, limit: Int, k: Int
    ) {
        val keys = idx.keysSorted
        if (keys.isEmpty() || input.isEmpty()) return
        var lo = 0; var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (keys[mid] >= input) hi = mid else lo = mid + 1
        }
        if (lo >= keys.size || !keys[lo].startsWith(input)) return
        val collected = ArrayList<Int>()
        var i = lo
        val cap = lo + 8000
        while (i < keys.size && i < cap && keys[i].startsWith(input)) {
            collected.add(idx.idxSorted[i])
            i++
        }
        val sorted = collected.sortedByDescending { idx.freqs[it] }
        for (wi in sorted.take(k)) {
            if (out.size >= limit) break
            out.add(idx.words[wi])
        }
    }

    private fun fallbackSplit(input: String): List<String> {
        val sylls = splitSyllables(input) ?: return emptyList()
        val sb = StringBuilder()
        for (s in sylls) {
            val ch = pyChars[s]?.firstOrNull() ?: return emptyList()
            sb.append(ch)
        }
        return listOf(sb.toString())
    }

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
