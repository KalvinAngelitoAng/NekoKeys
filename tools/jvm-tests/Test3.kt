import android.content.Context
import com.nekokeys.keyboard.*
import java.io.File
import java.util.Random

fun dictOf(path: String): Predictor.Dict {
    val m = LinkedHashMap<String, Int>(); var rank = 0
    for (l in File(path).readLines()) { val w = l.trim().lowercase(); if (w.isEmpty()) continue; rank++; m[w] = 1_000_000 / (rank + 9) }
    return Predictor.Dict(m)
}
val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
fun neighbors(c: Char): List<Char> {
    var r0 = -1; var c0 = -1
    rows.forEachIndexed { r, s -> val i = s.indexOf(c); if (i >= 0) { r0 = r; c0 = i } }
    if (r0 < 0) return emptyList()
    val off = floatArrayOf(0f, 0.25f, 0.75f)
    val out = ArrayList<Char>()
    rows.forEachIndexed { r, s -> s.forEachIndexed { i, ch -> val dx = (i + off[r]) - (c0 + off[r0]); val dy = (r - r0).toFloat()
        if (ch != c && dx * dx + dy * dy <= 2.0f) out.add(ch) } }
    return out
}

fun zipfPick(words: List<String>, d: Predictor.Dict, rnd: Random, n: Int): List<String> {
    val w = DoubleArray(words.size) { d.freqOf(words[it]).toDouble() }
    val cum = DoubleArray(w.size); var t = 0.0; for (i in w.indices) { t += w[i]; cum[i] = t }
    return (0 until n).map { val x = rnd.nextDouble() * t; var lo = 0; var hi = cum.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < x) lo = m + 1 else hi = m }; words[lo] }
}

fun main(args: Array<String>) {
    val assets = "./app/src/main/assets/"
    val ctor = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
    val pr = ctor.newInstance(null)
    val en = dictOf(assets + "words_en.txt"); val id = dictOf(assets + "words_id.txt")
    Predictor::class.java.getDeclaredField("dicts").also { it.isAccessible = true }.set(pr, listOf(en, id))
    val rnd = Random(11)

    for ((lang, d, name) in listOf(Triple(0, en, "EN"), Triple(1, id, "ID"))) {
        // sample 500 words, rank 150..15000, length >= 5, letters only
        val pool = d.words.indices.map { d.words[it] }.filter { it.length >= 5 && it.all { c -> c in 'a'..'z' } }
        val sample = zipfPick(pool.filter { it.length >= 5 }, d, rnd, 800)
        val stats = linkedMapOf<String, IntArray>()  // type -> [tried, correct, wrongCorrection, untouched]
        for (w in sample) {
            val typos = ArrayList<Pair<String, String>>()
            val i = 1 + rnd.nextInt(w.length - 1)
            val nb = neighbors(w[i]); if (nb.isNotEmpty()) typos.add("substitute adjacent" to w.substring(0, i) + nb[rnd.nextInt(nb.size)] + w.substring(i + 1))
            typos.add("delete letter" to w.removeRange(i, i + 1))
            val nb2 = neighbors(w[i]); if (nb2.isNotEmpty()) typos.add("insert extra letter" to w.substring(0, i) + nb2[rnd.nextInt(nb2.size)] + w.substring(i))
            if (i < w.length - 1 && w[i] != w[i + 1]) typos.add("transpose letters" to w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
            for ((t, typo) in typos) {
                if (pr.isKnown(typo)) continue   // typo happens to be another real word: autocorrect must not touch it
                val s = stats.getOrPut(t) { IntArray(4) }
                s[0]++
                val r = pr.correct(typo, "", lang)
                when { r == w -> s[1]++; r == null -> s[3]++; else -> s[2]++ }
            }
        }
        println("== $name (single-language, Zipf-weighted by real usage), ${sample.size} words ==")
        var tt = 0; var tc = 0; var tw = 0
        for ((t, s) in stats) { println(String.format("  %-22s tried %3d  fixed %5.1f%%  wrong %4.1f%%  left alone %4.1f%%", t, s[0], 100.0 * s[1] / s[0], 100.0 * s[2] / s[0], 100.0 * s[3] / s[0])); tt += s[0]; tc += s[1]; tw += s[2] }
        println(String.format("  OVERALL fixed %.1f%%  wrong %.1f%%", 100.0 * tc / tt, 100.0 * tw / tt))
        // false positives: valid words must never be changed
        var fp = 0; for (w in sample) if (pr.correct(w, "", lang) != null) fp++
        println("  valid words wrongly corrected: $fp / ${sample.size}")
    }

    // glide accuracy on the big dictionaries
    val unit = 108f; val rowH = 150f
    val cx = FloatArray(26); val cy = FloatArray(26)
    fun place(row: String, r: Int, x0: Float) = row.forEachIndexed { i, ch -> cx[ch - 'a'] = unit * (x0 + i + 0.5f); cy[ch - 'a'] = rowH * (r + 0.5f) }
    place("qwertyuiop", 0, 0f); place("asdfghjkl", 1, 0.5f); place("zxcvbnm", 2, 1.5f)
    for ((lang, d, name) in listOf(Triple(0, en, "EN"), Triple(1, id, "ID"), Triple(2, en, "EN+ID"))) {
        val src = if (lang == 1) id else en
        val pool = src.words.filter { it.length in 4..10 && it.all { c -> c in 'a'..'z' } }
        val picks = zipfPick(pool, src, rnd, 400)
        var top1 = 0; var top3 = 0; var n = 0; var tms = 0L; var worst = 0L
        for (w in picks) run {
            val ids = ArrayList<Int>(); for (ch in w) { val i = ch - 'a'; if (ids.isEmpty() || ids.last() != i) ids.add(i) }
            if (ids.size < 2) return@run
            val pts = ArrayList<FloatArray>()
            for (k in 0 until ids.size - 1) for (s in 0 until 14) { val t = s / 14f; pts.add(floatArrayOf(cx[ids[k]] + (cx[ids[k + 1]] - cx[ids[k]]) * t, cy[ids[k]] + (cy[ids[k + 1]] - cy[ids[k]]) * t)) }
            pts.add(floatArrayOf(cx[ids.last()], cy[ids.last()]))
            val m = pts.size; val xs = FloatArray(m); val ys = FloatArray(m)
            val jx = (rnd.nextGaussian() * 0.12 * unit).toFloat(); val jy = (rnd.nextGaussian() * 0.12 * unit).toFloat()
            for (i in 0 until m) { xs[i] = pts[i][0] + jx + (rnd.nextGaussian() * 0.10 * unit).toFloat(); ys[i] = pts[i][1] + jy + (rnd.nextGaussian() * 0.10 * unit).toFloat() }
            val t0 = System.nanoTime(); val res = pr.glide(xs, ys, m, cx, cy, unit, lang, ""); val ms = (System.nanoTime() - t0) / 1_000_000
            tms += ms; worst = maxOf(worst, ms); n++
            if (res.firstOrNull() == w) top1++; if (w in res) top3++
        }
        println(String.format("glide %-5s n=%d  top1 %.1f%%  top3 %.1f%%  avg %d ms  worst %d ms", name, n, 100.0 * top1 / n, 100.0 * top3 / n, tms / n, worst))
    }
}
