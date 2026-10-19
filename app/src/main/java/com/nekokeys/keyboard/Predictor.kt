package com.nekokeys.keyboard

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Autocorrect / glide error-model weights. Tuned on thousands of synthetic typos (see README). */
object Tune {
    @JvmField var distW = 5.0     // score penalty per unit of edit distance
    @JvmField var prior = 0.6     // weight of ln(word frequency)
    @JvmField var adjSub = 0.5    // substituting a neighbouring key
    @JvmField var otherSub = 1.0  // substituting a far key
    @JvmField var indel = 0.9     // missing / extra letter
    @JvmField var dbl = 0.4       // doubled letter missed / doubled by mistake
    @JvmField var trans = 0.7     // swapped neighbouring letters
    @JvmField var glideDist = 4.0
    @JvmField var glidePrior = 0.35
    @JvmField var missMax = 0.9   // glide: every letter of a word must be within this many key widths of the path
}

/**
 * 100% on-device prediction, autocorrect and glide decoding.
 *  - bundled word lists (assets/words_*.txt): sorted array + binary search for prefix completion
 *  - keyboard-layout-aware edit distance (adjacent-key typos are cheap) for autocorrect
 *  - SHARK2-style shape matching for glide typing
 *  - tiny local user model (new words + word pairs) in app-private storage
 * It never touches the network (the app has no INTERNET permission).
 */
class Predictor private constructor(private val ctx: Context) {

    /** Sorted word array + binary search: no per-word hash entries, so ~60k words cost only a few MB. */
    class Dict(entries: Map<String, Int>) {
        val words: Array<String>
        val freq: IntArray
        val byLen: Array<IntArray>
        val byFirst: Array<IntArray>
        val top: List<String>

        init {
            val sorted = entries.entries.sortedBy { it.key }
            words = Array(sorted.size) { sorted[it].key }
            freq = IntArray(sorted.size) { sorted[it].value }
            val maxLen = (words.maxOfOrNull { it.length } ?: 0).coerceAtMost(40)
            // two-pass counting sort into IntArrays (no boxed Integer lists)
            val lenCount = IntArray(maxLen + 1)
            val firstCount = IntArray(26)
            for (w in words) {
                if (w.length <= maxLen) lenCount[w.length]++
                if (w[0] in 'a'..'z') firstCount[w[0] - 'a']++
            }
            byLen = Array(maxLen + 1) { IntArray(lenCount[it]) }
            byFirst = Array(26) { IntArray(firstCount[it]) }
            val li = IntArray(maxLen + 1)
            val fi = IntArray(26)
            for (i in words.indices) {
                val w = words[i]
                if (w.length <= maxLen) byLen[w.length][li[w.length]++] = i
                if (w[0] in 'a'..'z') byFirst[w[0] - 'a'][fi[w[0] - 'a']++] = i
            }
            top = entries.entries.sortedByDescending { it.value }.take(30).map { it.key }
        }

        fun lowerBound(p: String): Int {
            var lo = 0
            var hi = words.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (words[mid] < p) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun freqOf(w: String): Int {
            val i = lowerBound(w)
            return if (i < words.size && words[i] == w) freq[i] else 0
        }
    }

    companion object {
        @Volatile private var inst: Predictor? = null

        fun get(ctx: Context): Predictor = inst ?: synchronized(this) {
            inst ?: Predictor(ctx.applicationContext).also { p ->
                inst = p
                Thread { p.load() }.start()
            }
        }

        fun properCase(s: String, typed: String, cap: Boolean = false): String {
            var r = when {
                typed.length > 1 && typed.all { it.isUpperCase() } -> s.uppercase()
                (typed.isNotEmpty() && typed[0].isUpperCase()) || (typed.isEmpty() && cap) ->
                    s.replaceFirstChar { it.uppercase() }
                else -> s
            }
            if (r == "i" || r.startsWith("i'")) r = r.replaceFirstChar { it.uppercase() }
            return r
        }

        private const val G = 32 // glide resample points

        // Learned data is bounded no matter how long the keyboard is used.
        private const val MAX_WORDS = 5_000        // new words the dictionary does not know
        private const val MAX_PAIRS = 40_000       // remembered "word -> next word" pairs
        private const val MAX_FOLLOW = 30          // followers kept per word
        private const val DECAY_EVERY = 200_000L   // learned words between "forgetting" passes (about 2-4 months)
        private const val MAX_COUNT = 1_000_000
    }

    @Volatile private var dicts: List<Dict?> = listOf(null, null)
    private val userCounts = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    @Volatile private var dirty = false
    private var events = 0L
    private var pairCount = 0
    @Volatile private var saveQueued = false
    private val writeLock = Any()
    private val saveExec: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "nekokeys-save").apply { isDaemon = true } }
    }

    class Stats(val words: Int, val pairs: Int, val bytes: Long)
    private val mat = Array(34) { DoubleArray(34) }

    private val pos: Map<Char, Pair<Float, Float>> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, Pair(i.toFloat(), 0f)) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, Pair(i + 0.25f, 1f)) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, Pair(i + 0.75f, 2f)) }
    }

    /** bit j of adjMask[i] set when letter j is a neighbour of (or equal to) letter i on QWERTY. */
    private val adjMask = IntArray(26).also { m ->
        for (a in 0 until 26) for (b in 0 until 26) {
            val pa = pos['a' + a]!!
            val pb = pos['a' + b]!!
            val dx = pa.first - pb.first
            val dy = pa.second - pb.second
            if (dx * dx + dy * dy <= 2.0f) m[a] = m[a] or (1 shl b)
        }
    }

    // ---------- loading / saving ----------

    private fun load() {
        dicts = listOf("words_en.txt", "words_id.txt").map { n ->
            try { buildDict(ctx.assets.open(n).bufferedReader().readLines()) } catch (e: Exception) { null }
        }
        synchronized(this) { loadUser() }
    }

    private fun buildDict(lines: List<String>): Dict {
        val m = LinkedHashMap<String, Int>()
        var rank = 0
        for (line in lines) {
            val t = line.trim().split(' ', '\t')
            val w = t[0].lowercase()
            if (w.isEmpty() || w.startsWith("#")) continue
            val f = t.getOrNull(1)?.toLongOrNull()?.coerceAtMost(1_000_000_000L)?.toInt()
                ?: (1_000_000 / (rank + 10))
            rank++
            if ((m[w] ?: 0) < f) m[w] = f
        }
        return Dict(m)
    }

    private fun loadUser() {
        val f = File(ctx.filesDir, "userdata.v2")
        try {
            if (f.exists()) {
                f.forEachLine { l ->
                    val p = l.split('\t')
                    when (p[0]) {
                        "H" -> { if (p.size >= 3) events = p[2].toLongOrNull() ?: 0L }
                        "W" -> { if (p.size == 3) userCounts[p[1]] = (p[2].toIntOrNull() ?: 1).coerceIn(1, MAX_COUNT) }
                        "B" -> { if (p.size == 4) bigrams.getOrPut(p[1]) { HashMap() }[p[2]] = (p[3].toIntOrNull() ?: 1).coerceIn(1, MAX_COUNT) }
                    }
                }
            } else {
                // data written by older versions (two plain files)
                File(ctx.filesDir, "user_words.tsv").takeIf { it.exists() }?.forEachLine { l ->
                    val p = l.split('\t')
                    if (p.size == 2) userCounts[p[0]] = (p[1].toIntOrNull() ?: 1).coerceIn(1, MAX_COUNT)
                }
                File(ctx.filesDir, "bigrams.tsv").takeIf { it.exists() }?.forEachLine { l ->
                    val p = l.split('\t')
                    if (p.size == 3) bigrams.getOrPut(p[0]) { HashMap() }[p[1]] = (p[2].toIntOrNull() ?: 1).coerceIn(1, MAX_COUNT)
                }
                if (userCounts.isNotEmpty() || bigrams.isNotEmpty()) dirty = true
            }
        } catch (e: Exception) { }
        pairCount = bigrams.values.sumOf { it.size }
        enforceCaps() // a damaged or very old file can never push us past the limits
    }

    @Synchronized private fun snapshot(): String? {
        if (!dirty) return null
        dirty = false
        return buildString {
            append("H\t2\t").append(events).append('\n')
            for ((w, c) in userCounts) append("W\t").append(w).append('\t').append(c).append('\n')
            for ((p, m) in bigrams) for ((w, c) in m) append("B\t").append(p).append('\t').append(w).append('\t').append(c).append('\n')
        }
    }

    /** Temp file + fsync + rename: a crash or power loss can never leave a half-written file behind. */
    private fun writeAtomic(data: String): Boolean = synchronized(writeLock) {
        try {
            val dst = File(ctx.filesDir, "userdata.v2")
            val tmp = File(ctx.filesDir, "userdata.v2.tmp")
            java.io.FileOutputStream(tmp).use { it.write(data.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) return false }
            File(ctx.filesDir, "user_words.tsv").delete()
            File(ctx.filesDir, "bigrams.tsv").delete()
            true
        } catch (e: Exception) { false }
    }

    /** One writer thread, never more than one save queued. */
    fun saveAsync() {
        if (!dirty || saveQueued) return
        saveQueued = true
        saveExec.execute {
            saveQueued = false
            val data = snapshot() ?: return@execute
            if (!writeAtomic(data)) dirty = true
        }
    }

    /** Synchronous variant for onDestroy, where a background thread might not get to finish. */
    fun saveBlocking() {
        val data = snapshot() ?: return
        if (!writeAtomic(data)) dirty = true
    }

    @Synchronized fun stats(): Stats {
        val f = File(ctx.filesDir, "userdata.v2")
        return Stats(userCounts.size, pairCount, if (f.exists()) f.length() else 0L)
    }

    @Synchronized fun clearAll() {
        userCounts.clear()
        bigrams.clear()
        pairCount = 0
        events = 0
        dirty = false
        synchronized(writeLock) {
            for (n in listOf("userdata.v2", "userdata.v2.tmp", "user_words.tsv", "bigrams.tsv")) File(ctx.filesDir, n).delete()
        }
    }

    // ---------- learning ----------

    private fun inDict(w: String) = dicts.any { it != null && it.freqOf(w) > 0 }

    /** lang: 0 = English, 1 = Indonesia, 2 = both. */
    private fun dictsFor(lang: Int): List<Pair<Int, Dict>> = when (lang) {
        0 -> listOfNotNull(dicts[0]?.let { 0 to it })
        1 -> listOfNotNull(dicts[1]?.let { 1 to it })
        else -> listOfNotNull(dicts[0]?.let { 0 to it }, dicts[1]?.let { 1 to it })
    }

    /** > 0 means the user has recently been typing Indonesian, < 0 English. Only used in mixed (EN • ID) mode. */
    @Volatile private var bias = 0.0

    private fun boost(dictIdx: Int, lang: Int): Double =
        if (lang != 2) 0.0 else (if (dictIdx == 1) bias else -bias) * 1.6

    /** Tells the predictor which language the user just typed in (kept in memory only, never stored). */
    fun observe(word: String) {
        val w = word.lowercase()
        val inEn = (dicts[0]?.freqOf(w) ?: 0) > 0
        val inId = (dicts[1]?.freqOf(w) ?: 0) > 0
        bias = when {
            inEn == inId -> bias * 0.95
            inId -> bias * 0.8 + 0.2
            else -> bias * 0.8 - 0.2
        }
    }

    @Synchronized fun learn(prev: String, word: String) {
        val w = word.lowercase().trim('\'')
        if (w.length < 2 || w.length > 30 || w.any { it.isDigit() }) return
        if (!inDict(w)) userCounts[w] = min(MAX_COUNT, (userCounts[w] ?: 0) + 1)
        if (prev.isNotEmpty() && prev.length <= 30) {
            val m = bigrams.getOrPut(prev) { HashMap() }
            val old = m[w]
            if (old == null) { m[w] = 1; pairCount++ } else { m[w] = min(MAX_COUNT, old + 1) }
            if (m.size > MAX_FOLLOW * 2) trimFollowers(m)
        }
        dirty = true
        events++
        if (events % DECAY_EVERY == 0L) decay()
        if (pairCount > MAX_PAIRS || userCounts.size > MAX_WORDS) enforceCaps()
    }

    /** A single word keeps only its MAX_FOLLOW most used followers. */
    private fun trimFollowers(m: HashMap<String, Int>) {
        val keep = m.entries.sortedByDescending { it.value }.take(MAX_FOLLOW).map { it.key to it.value }
        pairCount -= m.size - keep.size
        m.clear()
        for ((k, v) in keep) m[k] = v
    }

    /** "Forgetting": every count is halved, so habits you stop having fade away on their own. */
    private fun decay() {
        val bi = bigrams.entries.iterator()
        while (bi.hasNext()) {
            val m = bi.next().value
            val mi = m.entries.iterator()
            while (mi.hasNext()) {
                val e = mi.next()
                val nc = e.value / 2
                if (nc == 0) mi.remove() else e.setValue(nc)
            }
            if (m.isEmpty()) bi.remove()
        }
        val ui = userCounts.entries.iterator()
        while (ui.hasNext()) {
            val e = ui.next()
            val nc = e.value / 2
            if (nc == 0) ui.remove() else e.setValue(nc)
        }
        pairCount = bigrams.values.sumOf { it.size }
        dirty = true
    }

    /**
     * Over a limit: drop exactly enough of the least-used entries to get back to 80% of it.
     * Entries tied at the cut-off are removed only as far as needed (never the whole tier).
     */
    private fun enforceCaps() {
        pairCount = bigrams.values.sumOf { it.size }
        if (pairCount > MAX_PAIRS) {
            val counts = IntArray(pairCount)
            var i = 0
            for (m in bigrams.values) for (c in m.values) counts[i++] = c
            counts.sort()
            val remove = pairCount - MAX_PAIRS * 4 / 5
            val cut = counts[remove - 1]
            var ties = remove - counts.count { it < cut }
            val bi = bigrams.entries.iterator()
            while (bi.hasNext()) {
                val m = bi.next().value
                val mi = m.entries.iterator()
                while (mi.hasNext()) {
                    val c = mi.next().value
                    if (c < cut) mi.remove() else if (c == cut && ties > 0) { mi.remove(); ties-- }
                }
                if (m.isEmpty()) bi.remove()
            }
            pairCount = bigrams.values.sumOf { it.size }
        }
        if (userCounts.size > MAX_WORDS) {
            val counts = userCounts.values.toIntArray()
            counts.sort()
            val remove = counts.size - MAX_WORDS * 4 / 5
            val cut = counts[remove - 1]
            var ties = remove - counts.count { it < cut }
            val ui = userCounts.entries.iterator()
            while (ui.hasNext()) {
                val c = ui.next().value
                if (c < cut) ui.remove() else if (c == cut && ties > 0) { ui.remove(); ties-- }
            }
        }
    }

    @Synchronized fun accept(word: String) {
        val w = word.lowercase().trim('\'')
        if (w.length >= 2 && !inDict(w)) {
            userCounts[w] = max(userCounts[w] ?: 0, 2)
            dirty = true
            if (userCounts.size > MAX_WORDS) enforceCaps()
        }
    }

    /** Valid in the selected language(s): single-language mode does not accept the other language's words. */
    @Synchronized fun isKnown(word: String, lang: Int = 2): Boolean {
        val w = word.lowercase()
        return dictsFor(lang).any { it.second.freqOf(w) > 0 } || (userCounts[w] ?: 0) >= 2
    }

    // ---------- suggestions ----------

    @Synchronized fun suggest(prefix: String, prev: String, lang: Int, n: Int): List<String> {
        val ds = dictsFor(lang)
        val p = prefix.lowercase()
        val scored = HashMap<String, Double>()
        if (p.isEmpty()) {
            bigrams[prev]?.forEach { (w, c) -> scored[w] = 6.0 + ln(c + 1.0) }
            for ((di, d) in ds) {
                val k = if (ds.size > 1) 8 else 30
                d.top.take(k).forEachIndexed { i, w -> if (w != prev) scored.putIfAbsent(w, 2.0 - i * 0.05 + boost(di, lang)) }
            }
        } else {
            for ((di, d) in ds) {
                var i = d.lowerBound(p)
                while (i < d.words.size && d.words[i].startsWith(p)) {
                    val s = ln(d.freq[i] + 1.0) + boost(di, lang)
                    if ((scored[d.words[i]] ?: -1.0) < s) scored[d.words[i]] = s
                    i++
                }
            }
            for ((w, c) in userCounts) if (c >= 1 && w.startsWith(p)) {
                scored[w] = max(scored[w] ?: 0.0, 6.0 + ln(c + 1.0))
            }
            bigrams[prev]?.let { m ->
                for ((w, c) in m) if (w.startsWith(p)) scored[w] = (scored[w] ?: 0.0) + 4.0 + ln(c + 1.0)
            }
            if (scored.size < n && p.length >= 3 && ds.isNotEmpty()) {
                for (t in fuzzy(p, prev, ds, lang, 1.6)) scored.putIfAbsent(t.first, t.second)
            }
        }
        return scored.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    /** Returns the corrected word (case-matched) or null when the word should be left alone. */
    @Synchronized fun correct(word: String, prev: String, lang: Int): String? {
        val lw = word.lowercase()
        if (lw.length < 2 || lw.length > 30 || lw.any { it.isDigit() }) return null
        if (word.drop(1).any { it.isUpperCase() }) return null          // iPhone, NASA, ...
        if (word[0].isUpperCase() && prev.isNotEmpty()) return null     // probably a name
        if (lw.endsWith("'")) return null
        val ds = dictsFor(lang)
        if (ds.isEmpty()) return null
        if (ds.any { it.second.freqOf(lw) > 0 } || (userCounts[lw] ?: 0) >= 2) return null // already a valid word
        val maxD = if (lw.length <= 3) 1.0 else 1.6
        val best = fuzzy(lw, prev, ds, lang, maxD).maxByOrNull { it.second } ?: return null
        return properCase(best.first, word)
    }

    private fun bigramBoost(prev: String, w: String): Double {
        val c = bigrams[prev]?.get(w) ?: return 0.0
        return 2.0 + ln(c + 1.0)
    }

    private fun fuzzy(lw: String, prev: String, ds: List<Pair<Int, Dict>>, lang: Int, maxD: Double): List<Triple<String, Double, Double>> {
        val out = ArrayList<Triple<String, Double, Double>>()
        val n = lw.length
        for ((di, d) in ds) {
            for (len in max(1, n - 2)..min(d.byLen.size - 1, n + 2)) {
                for (idx in d.byLen[len]) {
                    val w = d.words[idx]
                    if (lw[0] != w[0] && !(n > 1 && w.length > 1 && lw[1] == w[1])) continue
                    val dist = editDist(lw, w)
                    if (dist <= maxD) out.add(Triple(w, Tune.prior * ln(d.freq[idx] + 1.0) - Tune.distW * dist + bigramBoost(prev, w) + boost(di, lang), dist))
                }
            }
        }
        for ((w, c) in userCounts) {
            if (c < 2 || abs(w.length - n) > 2) continue
            val dist = editDist(lw, w)
            if (dist <= maxD) out.add(Triple(w, 8.0 + ln(c + 1.0) - Tune.distW * dist, dist))
        }
        return out
    }

    // ---------- keyboard-aware Damerau-Levenshtein ----------

    /** Extra letter in the dictionary word that the typed word is missing (cheap for apostrophes and doubled letters). */
    private fun insCost(b: String, j: Int): Double {
        val c = b[j - 1]
        if (c == '\'') return 0.15
        if (j > 1 && b[j - 2] == c) return Tune.dbl // doubled letter omitted: helo -> hello
        return Tune.indel
    }

    /** Extra letter in the typed word. */
    private fun delCost(a: String, i: Int): Double {
        val c = a[i - 1]
        if (c == '\'') return 0.15
        if (i > 1 && a[i - 2] == c) return Tune.dbl // doubled by mistake: helllo -> hello
        return Tune.indel
    }

    private fun subCost(a: Char, b: Char): Double {
        val pa = pos[a]
        val pb = pos[b]
        if (pa == null || pb == null) return Tune.otherSub
        val dx = pa.first - pb.first
        val dy = pa.second - pb.second
        return if (dx * dx + dy * dy <= 2.0f) Tune.adjSub else Tune.otherSub
    }

    private fun editDist(a: String, b: String): Double {
        val n = a.length
        val m = b.length
        if (n > 32 || m > 32 || abs(n - m) > 2) return 99.0
        mat[0][0] = 0.0
        for (i in 1..n) mat[i][0] = mat[i - 1][0] + delCost(a, i)
        for (j in 1..m) mat[0][j] = mat[0][j - 1] + insCost(b, j)
        for (i in 1..n) for (j in 1..m) {
            val ca = a[i - 1]
            val cb = b[j - 1]
            val sub = if (ca == cb) 0.0 else subCost(ca, cb)
            var v = min(mat[i - 1][j - 1] + sub, min(mat[i - 1][j] + delCost(a, i), mat[i][j - 1] + insCost(b, j)))
            if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, mat[i - 2][j - 2] + Tune.trans)
            mat[i][j] = v
        }
        return mat[n][m]
    }

    // ---------- glide typing (shape matching) ----------

    private val ux = FloatArray(G)
    private val uy = FloatArray(G)
    private val ix = FloatArray(G)
    private val iy = FloatArray(G)
    private val px = FloatArray(40)
    private val py = FloatArray(40)

    /** Resamples a polyline of m points into G equally spaced points; returns the path length. */
    private fun resample(x: FloatArray, y: FloatArray, m: Int, ox: FloatArray, oy: FloatArray): Float {
        var total = 0f
        for (i in 1 until m) total += hyp(x[i] - x[i - 1], y[i] - y[i - 1])
        if (total <= 0f || m < 2) return 0f
        val step = total / (G - 1)
        ox[0] = x[0]
        oy[0] = y[0]
        var k = 1
        var seg = 0
        var segStart = 0f
        while (k < G - 1 && seg < m - 1) {
            val segLen = hyp(x[seg + 1] - x[seg], y[seg + 1] - y[seg])
            val target = k * step
            if (segStart + segLen >= target && segLen > 0f) {
                val t = (target - segStart) / segLen
                ox[k] = x[seg] + (x[seg + 1] - x[seg]) * t
                oy[k] = y[seg] + (y[seg + 1] - y[seg]) * t
                k++
            } else {
                segStart += segLen
                seg++
            }
        }
        while (k < G) { ox[k] = x[m - 1]; oy[k] = y[m - 1]; k++ }
        return total
    }

    private fun hyp(a: Float, b: Float) = sqrt(a * a + b * b)

    private fun nearest(x: Float, y: Float, cx: FloatArray, cy: FloatArray): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in 0 until 26) {
            if (cx[i].isNaN()) continue
            val d = (cx[i] - x) * (cx[i] - x) + (cy[i] - y) * (cy[i] - y)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /**
     * xs/ys: finger path in pixels. cx/cy: pixel centre of letters a..z. keyW: key width in pixels.
     * Returns up to 3 candidate words, best first.
     */
    @Synchronized fun glide(
        xs: FloatArray, ys: FloatArray, n: Int, cx: FloatArray, cy: FloatArray,
        keyW: Float, lang: Int, prev: String
    ): List<String> {
        if (n < 3 || keyW <= 0f) return emptyList()
        val ulen = resample(xs, ys, n, ux, uy)
        if (ulen < keyW * 1.2f) return emptyList()
        val s = nearest(xs[0], ys[0], cx, cy)
        val e = nearest(xs[n - 1], ys[n - 1], cx, cy)
        if (s < 0 || e < 0) return emptyList()
        val startMask = adjMask[s]
        val endMask = adjMask[e]
        val scored = HashMap<String, Double>()

        fun consider(w: String, base: Double) {
            if (w.length < 2 || w.length > 24) return
            // letters only (an apostrophe is skipped, accents / digits disqualify)
            var m = 0
            var last = -1
            for (ch in w) {
                if (ch == '\'') continue
                if (ch !in 'a'..'z') return
                val li = ch - 'a'
                if (cx[li].isNaN()) return
                if (li != last) { px[m] = cx[li]; py[m] = cy[li]; m++; last = li }
            }
            if (m < 2) return
            if ((endMask shr last) and 1 == 0) return
            // the finger must have passed near every letter of the word
            val lim = Tune.missMax * keyW
            val lim2 = lim * lim
            for (k in 0 until m) {
                var near = false
                for (u in 0 until G) {
                    val dx = ux[u] - px[k]
                    val dy = uy[u] - py[k]
                    if (dx * dx + dy * dy <= lim2) { near = true; break }
                }
                if (!near) return
            }
            var ilen = 0f
            for (i in 1 until m) ilen += hyp(px[i] - px[i - 1], py[i] - py[i - 1])
            val ratio = ilen / ulen
            if (ratio < 0.5f || ratio > 2.0f) return
            resample(px, py, m, ix, iy)
            var sum = 0f
            for (i in 0 until G) sum += hyp(ux[i] - ix[i], uy[i] - iy[i])
            val dist = sum / G / keyW
            if (dist > 1.1f) return
            val sc = base - Tune.glideDist * dist + bigramBoost(prev, w)
            if ((scored[w] ?: -1e9) < sc) scored[w] = sc
        }

        for ((di, d) in dictsFor(lang)) {
            val bb = boost(di, lang)
            for (li in 0 until 26) {
                if ((startMask shr li) and 1 == 0) continue
                for (idx in d.byFirst[li]) consider(d.words[idx], Tune.glidePrior * (ln(d.freq[idx] + 1.0) + bb))
            }
        }
        for ((w, c) in userCounts) {
            if (c < 2 || w.isEmpty() || w[0] !in 'a'..'z') continue
            if ((startMask shr (w[0] - 'a')) and 1 == 0) continue
            consider(w, Tune.glidePrior * (8.0 + ln(c + 1.0)))
        }
        return scored.entries.sortedByDescending { it.value }.take(3).map { it.key }
    }
}
