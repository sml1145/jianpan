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
        val byInitials: Map<String, IntArray>,
        /** 混合简拼缩写分桶：key = 首字母序号*(MAX_SYL+1)+音节数 → 按词频降序的词下标 */
        val abbrBuckets: Map<Int, IntArray>,
        /** 每个词的音节拼接串（如 "nihao"），用于缩写匹配 */
        val wordSylJoined: Array<String?>,
        /** 每个词的音节长度数组，用于缩写匹配 */
        val wordSylLens: Array<IntArray?>,
        /** log(词频总和)，用于对数概率打分 */
        val logWordTotal: Double
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
    /** 词库全量加载上限（用户要求强化词库、不限制应用体积，故留足余量） */
    private const val MAX_WORDS = 1200000
    /** 混合简拼 beam 切分参数 */
    private const val MAX_SYL = 8
    private const val BEAM = 5
    private const val CHAR_MIX = 1.2
    @Volatile private var logCharTotal = 0.0

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
        var charTotal = 0L
        ctx.assets.open("dict/char_freq.txt").bufferedReader().useLines { lines ->
            for (ln in lines) {
                val p = ln.split('\t')
                if (p.size < 2 || p[0].isEmpty()) continue
                val f = p[1].toIntOrNull() ?: 0
                charFreq[p[0][0]] = f
                charTotal += f
            }
        }
        logCharTotal = kotlin.math.ln(charTotal.coerceAtLeast(1).toDouble())
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

        // 混合简拼缩写索引：按「首字母 × 音节数」分桶，词下标只存一份（内存友好）。
        // 有了它 nhao(n+全拼hao)、jttqzmy(全首字母接力) 这类输入才能命中词。
        var freqTotal = 0L
        for (f in fArr) freqTotal += f.toLong()
        val logWTotal = kotlin.math.ln(freqTotal.coerceAtLeast(1).toDouble())

        val sylJoined = arrayOfNulls<String>(wArr.size)
        val sylLens = arrayOfNulls<IntArray>(wArr.size)
        val bucketTmp = HashMap<Int, ArrayList<Int>>(26 * (MAX_SYL + 1))
        for (id in wArr.indices) {
            val w = wArr[id]
            if (w.length < 2) continue
            val syl = wordSyllables(w) ?: continue
            val n = syl.size
            if (n > MAX_SYL) continue
            val lens = IntArray(n)
            val joined = StringBuilder()
            for (i in 0 until n) { lens[i] = syl[i].length; joined.append(syl[i]) }
            sylJoined[id] = joined.toString()
            sylLens[id] = lens
            val key = (syl[0][0] - 'a') * (MAX_SYL + 1) + n
            bucketTmp.getOrPut(key) { ArrayList() }.add(id)
        }
        val buckets = HashMap<Int, IntArray>(bucketTmp.size)
        for ((k, v) in bucketTmp) {
            v.sortByDescending { fArr[it] }
            buckets[k] = v.toIntArray()
        }

        index = WordIndex(
            wArr, fArr, mMap, keysSorted, idxSorted, initFinal,
            buckets, sylJoined, sylLens, logWTotal
        )
        _indexVersion.value = _indexVersion.value + 1
    }

    /** 逐字取首读音，返回音节列表；含未知字或非汉字返回 null */
    private fun wordSyllables(word: String): List<String>? {
        if (word.isEmpty()) return null
        val out = ArrayList<String>(word.length)
        for (ch in word) {
            if (ch.code < 0x4E00 || ch.code > 0x9FFF) return null
            val pys = charPinyins[ch] ?: return null
            if (pys.isEmpty()) return null
            out.add(pys[0])
        }
        return out
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

    fun addUserWord(word: String, count: Int = 1) {
        userBoost[word] = count
        // 按拼音键与简拼键建索引，供候选时把用户词强制前置
        val key = pinyinOf(word)
        if (key != null) {
            synchronized(userWordsByKey) {
                userWordsByKey.getOrPut(key) { ArrayList() }.let { if (!it.contains(word)) it.add(word) }
                for (init in initialsVariants(key)) {
                    if (init.length < 2) continue
                    userWordsByInitials.getOrPut(init) { ArrayList() }.let { if (!it.contains(word)) it.add(word) }
                }
            }
        }
    }

    /** 词 → 拼音键（逐字取首读音拼接）；含未知字或含非汉字返回 null */
    private fun pinyinOf(word: String): String? {
        if (word.isEmpty()) return null
        val sb = StringBuilder()
        for (ch in word) {
            if (ch.code < 0x4E00 || ch.code > 0x9FFF) return null
            val pys = charPinyins[ch] ?: return null
            if (pys.isEmpty()) return null
            sb.append(pys[0])
        }
        return sb.toString()
    }

    /** 用户词索引：拼音键 / 简拼键 → 词（用于候选前置提权） */
    private val userWordsByKey = HashMap<String, ArrayList<String>>()
    private val userWordsByInitials = HashMap<String, ArrayList<String>>()

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

    /** 全拼输入串 -> 候选词列表（前缀/简拼/混合简拼/整句 beam 全覆盖） */
    fun candidates(input: String, limit: Int = 30): List<String> {
        if (input.isEmpty() || !charsReady) return emptyList()
        // 分词符（撇号）仅用于切分歧义，不参与匹配
        val effective = input.replace("'", "").lowercase()
        if (effective.isEmpty() || effective.any { !it.isLetter() }) return emptyList()
        val out = LinkedHashSet<String>()

        // 0) 用户历史词最前置（越常用越靠前，第 1 项需求）
        synchronized(userWordsByKey) {
            userWordsByKey[effective]?.let { list ->
                list.sortedByDescending { userBoost[it] ?: 0 }.forEach { out.add(it) }
            }
            if (effective.length >= 2) {
                userWordsByInitials[effective]?.let { list ->
                    list.sortedByDescending { userBoost[it] ?: 0 }.forEach { if (out.size < 6) out.add(it) }
                }
            }
        }

        // 1) 云端热词（联网增强）：精确键 → 简拼
        synchronized(hotByKey) {
            hotByKey[effective]?.let { out.addAll(it) }
            if (effective.length >= 2) hotByInitials[effective]?.let { out.addAll(it) }
        }

        val idx = index

        // 2) 输入恰好是一个完整音节 → 单字最优先（第 7 项需求：pai 先出 牌/拍/排，
        //    再出 排队/拍照 这类前缀词）。
        if (syllables.contains(effective)) {
            for (v in fuzzyVariants(effective)) {
                pyChars[v]?.let { chars ->
                    for (c in chars) if (out.size < limit) out.add(c.toString())
                }
            }
        }

        // 3) beam DP 整句/混合简拼切分（第 1、4 项需求）
        if (idx != null && effective.length >= 2) {
            for (w in segmentBeam(effective, idx, BEAM)) if (out.size < limit) out.add(w)
        }

        // 4) 兜底与补充：短输入补单字，长输入补前缀词
        if (idx != null) {
            if (effective.length <= 2) {
                addCharsFor(out, effective, limit)
                idx.byInitials[effective]?.let { arr ->
                    for (i in arr) if (out.size < limit) idx.words[i].let { out.add(it) }
                }
                addPrefixWords(idx, out, effective, limit, 6)
            } else {
                addPrefixWords(idx, out, effective, limit, 10)
                idx.byInitials[effective]?.let { arr ->
                    for (i in arr.take(6)) if (out.size < limit) out.add(idx.words[i])
                }
                if (out.size < limit) addCharsFor(out, effective, limit)
            }
        } else {
            for (w in fallbackSplit(effective)) if (out.size < limit) out.add(w)
            addCharsFor(out, effective, limit)
        }
        return out.toList().take(limit)
    }

    /**
     * beam 切分：把输入拆成若干段，每段可以是
     *   a) 词的全拼精确键
     *   b) 词的混合简拼缩写（每个音节取全拼或首字母，如 n+hao=nhao、全首字母=jttqzmy）
     *   c) 单字（仅当该段是一个完整音节）
     * 打分用各自分布内的对数概率，段越多总分越低，因此整词天然优于单字堆叠。
     */
    private fun segmentBeam(input: String, idx: WordIndex, beam: Int): List<String> {
        val n = input.length
        val beams = Array(n + 1) { ArrayList<SegPath>(beam) }
        beams[0].add(SegPath("", 0.0))
        val logWTotal = idx.logWordTotal

        val abbrBuf = ArrayList<Int>(16)
        for (pos in 0 until n) {
            if (beams[pos].isEmpty()) continue
            for (end in pos + 1..minOf(n, pos + 10)) {
                val key = input.substring(pos, end)
                val span = end - pos

                // a) 全拼精确词
                idx.byPy[key]?.let { ids ->
                    val take = minOf(ids.size, 3)
                    for (i in 0 until take) {
                        val id = ids[i]
                        extendBeam(beams, pos, end, idx.words[id],
                            kotlin.math.ln(idx.freqs[id] + 1.0) - logWTotal, beam)
                    }
                }
                // b) 混合简拼缩写词
                if (span in 2..MAX_SYL) {
                    abbrBuf.clear()
                    collectAbbr(idx, input, pos, end, 4, abbrBuf)
                    for (id in abbrBuf) {
                        val w = idx.words[id]
                        extendBeam(beams, pos, end, w,
                            kotlin.math.ln(idx.freqs[id] + 1.0) - logWTotal, beam)
                    }
                }
                // c) 单字：仅完整音节，且带额外惩罚避免压过整词
                if (syllables.contains(key)) {
                    pyChars[key]?.let { chars ->
                        val take = minOf(2, chars.size)
                        for (i in 0 until take) {
                            val c = chars[i]
                            extendBeam(beams, pos, end, c.toString(),
                                kotlin.math.ln((charFreq[c] ?: 1) + 1.0) - logCharTotal - CHAR_MIX, beam)
                        }
                    }
                }
            }
        }
        return beams[n].map { it.text }.filter { it.isNotEmpty() }
    }

    private class SegPath(val text: String, val score: Double)

    private fun extendBeam(
        beams: Array<ArrayList<SegPath>>, pos: Int, end: Int,
        text: String, add: Double, beam: Int
    ) {
        val target = beams[end]
        for (base in beams[pos]) {
            target.add(SegPath(base.text + text, base.score + add))
        }
        if (target.size > beam) {
            target.sortByDescending { it.score }
            while (target.size > beam) target.removeAt(target.size - 1)
        }
    }

    /** 在缩写分桶里找能匹配 [qStart,qEnd) 的词，按词频取前 want 个 */
    private fun collectAbbr(
        idx: WordIndex, q: String, qStart: Int, qEnd: Int, want: Int, out: ArrayList<Int>
    ) {
        val first = q[qStart]
        if (first !in 'a'..'z') return
        val li = first - 'a'
        val qlen = qEnd - qStart
        for (cnt in 2..minOf(MAX_SYL, qlen)) {
            val arr = idx.abbrBuckets[li * (MAX_SYL + 1) + cnt] ?: continue
            var found = 0
            for (id in arr) {
                if (matchAbbr(idx, id, q, qStart, qEnd)) {
                    out.add(id)
                    if (++found >= want) break
                }
            }
        }
        out.sortByDescending { idx.freqs[it] }
    }

    /** q 的 [qStart,qEnd) 是否能表示该词的缩写（每音节取全拼或首字母） */
    private fun matchAbbr(idx: WordIndex, id: Int, q: String, qStart: Int, qEnd: Int): Boolean {
        val lens = idx.wordSylLens[id] ?: return false
        val joined = idx.wordSylJoined[id] ?: return false
        val qlen = qEnd - qStart
        if (qlen < lens.size || qlen > joined.length) return false
        return matchAbbrRec(joined, lens, 0, 0, q, qStart, qEnd)
    }

    private fun matchAbbrRec(
        joined: String, lens: IntArray, sylIdx: Int, jPos: Int,
        q: String, qStart: Int, qEnd: Int
    ): Boolean {
        if (sylIdx == lens.size) return jPos == joined.length && qStart == qEnd
        if (qStart >= qEnd) return false
        val l = lens[sylIdx]
        // 选项1：吃掉完整音节
        if (qEnd - qStart >= l) {
            var eq = true
            for (k in 0 until l) {
                if (q[qStart + k] != joined[jPos + k]) { eq = false; break }
            }
            if (eq && matchAbbrRec(joined, lens, sylIdx + 1, jPos + l, q, qStart + l, qEnd)) return true
        }
        // 选项2：只吃首字母
        if (q[qStart] == joined[jPos] &&
            matchAbbrRec(joined, lens, sylIdx + 1, jPos + l, q, qStart + 1, qEnd)
        ) return true
        return false
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

    /** 单字全部读音（无声调，去重） */
    fun charPinyin(ch: Char): List<String> = charPinyins[ch]?.toList() ?: emptyList()

    /**
     * 组词示例：从本地词库找包含该字的常见词（本地数据，离线可靠）。
     * 优先该字开头的词，其次该字在中间的词。
     */
    fun wordsContaining(ch: Char, limit: Int = 12): List<String> {
        val idx = index ?: return emptyList()
        val starts = ArrayList<String>()
        val contains = ArrayList<String>()
        for (w in idx.words) {
            if (w.length < 2 || w.length > 4) continue
            if (w[0] == ch) starts.add(w)
            else if (w.contains(ch)) contains.add(w)
            if (starts.size >= limit) break
        }
        val out = LinkedHashSet<String>()
        out.addAll(starts)
        for (w in contains) { if (out.size >= limit) break; out.add(w) }
        return out.toList().take(limit)
    }
}
