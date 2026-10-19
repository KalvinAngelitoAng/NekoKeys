import android.content.Context
import com.nekokeys.keyboard.*
import java.io.File
import java.util.Random

fun dictOf(path: String): Predictor.Dict {
    val m = LinkedHashMap<String, Int>(); var rank = 0
    for (l in File(path).readLines()) { val w = l.trim().lowercase(); if (w.isEmpty()) continue; rank++; m[w] = 1_000_000 / (rank + 9) }
    return Predictor.Dict(m)
}
class G(val xs: FloatArray, val ys: FloatArray, val word: String, val lang: Int)

fun main() {
    val assets = "./app/src/main/assets/"
    val pr = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }.newInstance(null)
    val en = dictOf(assets + "words_en.txt"); val id = dictOf(assets + "words_id.txt")
    Predictor::class.java.getDeclaredField("dicts").also { it.isAccessible = true }.set(pr, listOf(en, id))
    val unit = 108f; val rowH = 150f
    val cx = FloatArray(26); val cy = FloatArray(26)
    fun place(row: String, r: Int, x0: Float) = row.forEachIndexed { i, ch -> cx[ch - 'a'] = unit * (x0 + i + 0.5f); cy[ch - 'a'] = rowH * (r + 0.5f) }
    place("qwertyuiop", 0, 0f); place("asdfghjkl", 1, 0.5f); place("zxcvbnm", 2, 1.5f)
    val rnd = Random(33)
    fun make(noise: Double, jitter: Double, n: Int): List<G> {
        val out = ArrayList<G>()
        for ((lang, d) in listOf(0 to en, 1 to id)) {
            val pool = d.words.filter { it.length in 3..10 && it.all { c -> c in 'a'..'z' } }
            val w = DoubleArray(pool.size) { d.freqOf(pool[it]).toDouble() }; val cum = DoubleArray(w.size); var t = 0.0; for (i in w.indices) { t += w[i]; cum[i] = t }
            var made = 0
            while (made < n) {
                val x = rnd.nextDouble() * t; var lo = 0; var hi = cum.size - 1
                while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < x) lo = m + 1 else hi = m }
                val word = pool[lo]
                val ids = ArrayList<Int>(); for (ch in word) { val i = ch - 'a'; if (ids.isEmpty() || ids.last() != i) ids.add(i) }
                if (ids.size < 2) continue
                val pts = ArrayList<FloatArray>()
                val speed = 8 + rnd.nextInt(14)   // varying finger speed -> varying sample density
                for (k in 0 until ids.size - 1) for (s in 0 until speed) { val tt = s / speed.toFloat(); pts.add(floatArrayOf(cx[ids[k]] + (cx[ids[k + 1]] - cx[ids[k]]) * tt, cy[ids[k]] + (cy[ids[k + 1]] - cy[ids[k]]) * tt)) }
                pts.add(floatArrayOf(cx[ids.last()], cy[ids.last()]))
                val m = pts.size; val xs = FloatArray(m); val ys = FloatArray(m)
                val jx = (rnd.nextGaussian() * jitter * unit).toFloat(); val jy = (rnd.nextGaussian() * jitter * unit).toFloat()
                for (i in 0 until m) { xs[i] = pts[i][0] + jx + (rnd.nextGaussian() * noise * unit).toFloat(); ys[i] = pts[i][1] + jy + (rnd.nextGaussian() * noise * unit).toFloat() }
                out.add(G(xs, ys, word, lang)); made++
            }
        }
        return out
    }
    val easy = make(0.10, 0.12, 200); val hard = make(0.18, 0.20, 200)
    fun eval(set: List<G>, lang2: Boolean): Pair<Double, Double> {
        var t1 = 0; var t3 = 0
        for (g in set) { val r = pr.glide(g.xs, g.ys, g.xs.size, cx, cy, unit, if (lang2) 2 else g.lang, ""); if (r.firstOrNull() == g.word) t1++; if (g.word in r) t3++ }
        return Pair(100.0 * t1 / set.size, 100.0 * t3 / set.size)
    }
    fun report(tag: String) {
        val e = eval(easy, false); val h = eval(hard, false); val b = eval(easy, true)
        println(String.format("%-34s easy top1 %.1f top3 %.1f | hard top1 %.1f top3 %.1f | EN+ID easy top1 %.1f top3 %.1f", tag, e.first, e.second, h.first, h.second, b.first, b.second))
    }
    Tune.missMax = 99.0; report("no near-path rule (before)")
    for (m in listOf(1.6, 1.2, 0.9, 0.7)) { Tune.missMax = m; report("missMax=$m") }
    Tune.missMax = 1.2
    for (gd in listOf(3.0, 4.0, 6.0, 8.0)) { Tune.glideDist = gd; report("glideDist=$gd") }
    Tune.glideDist = 4.0
    for (gp in listOf(0.2, 0.35, 0.5, 0.7)) { Tune.glidePrior = gp; report("glidePrior=$gp") }
}
