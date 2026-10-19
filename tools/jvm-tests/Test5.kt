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
val off = floatArrayOf(0f, 0.25f, 0.75f)
fun neighbors(c: Char): List<Char> {
    var r0 = -1; var c0 = -1
    rows.forEachIndexed { r, s -> val i = s.indexOf(c); if (i >= 0) { r0 = r; c0 = i } }
    if (r0 < 0) return emptyList()
    val out = ArrayList<Char>()
    rows.forEachIndexed { r, s -> s.forEachIndexed { i, ch -> val dx = (i + off[r]) - (c0 + off[r0]); val dy = (r - r0).toFloat(); if (ch != c && dx * dx + dy * dy <= 2.0f) out.add(ch) } }
    return out
}
class Case(val typo: String, val word: String, val lang: Int)

fun mutate(w: String, rnd: Random): String? {
    val i = 1 + rnd.nextInt(w.length - 1)
    return when (rnd.nextInt(10)) {
        0, 1, 2 -> { val nb = neighbors(w[i]); if (nb.isEmpty()) null else w.substring(0, i) + nb[rnd.nextInt(nb.size)] + w.substring(i + 1) }
        3 -> w.removeRange(i, i + 1)
        4 -> w.removeRange(i, i + 1)
        5, 6 -> { val nb = neighbors(w[i]); if (nb.isEmpty()) null else w.substring(0, i) + nb[rnd.nextInt(nb.size)] + w.substring(i) }
        7 -> if (i < w.length - 1 && w[i] != w[i + 1]) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else null
        8 -> { val c = ('a' + rnd.nextInt(26)); w.substring(0, i) + c + w.substring(i + 1) }       // far-key substitution
        else -> { val t = mutate(w, rnd); if (t != null && t.length > 3) mutate(t, rnd) else null }  // two errors (rarer, harder)
    }
}

fun main() {
    val assets = "./app/src/main/assets/"
    val pr = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }.newInstance(null)
    val en = dictOf(assets + "words_en.txt"); val id = dictOf(assets + "words_id.txt")
    Predictor::class.java.getDeclaredField("dicts").also { it.isAccessible = true }.set(pr, listOf(en, id))
    val rnd = Random(21)
    val cases = ArrayList<Case>()
    for ((lang, d) in listOf(0 to en, 1 to id)) {
        val pool = d.words.filter { it.length >= 4 && it.all { c -> c in 'a'..'z' } }
        val w = DoubleArray(pool.size) { d.freqOf(pool[it]).toDouble() }; val cum = DoubleArray(w.size); var t = 0.0; for (i in w.indices) { t += w[i]; cum[i] = t }
        var n = 0
        while (n < 350) {
            val x = rnd.nextDouble() * t; var lo = 0; var hi = cum.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < x) lo = m + 1 else hi = m }
            val word = pool[lo]; val typo = mutate(word, rnd) ?: continue
            if (typo == word || pr.isKnown(typo)) continue
            cases.add(Case(typo, word, lang)); n++
        }
    }
    fun eval(): Pair<Double, Double> {
        var fixed = 0; var wrong = 0
        for (c in cases) { val r = pr.correct(c.typo, "", c.lang); if (r == c.word) fixed++ else if (r != null) wrong++ }
        return Pair(100.0 * fixed / cases.size, 100.0 * wrong / cases.size)
    }
    Tune.distW = 5.0; Tune.prior = 0.6
    val base = eval(); println(String.format("baseline: fixed %.1f%%  wrong %.1f%%  (%d cases)", base.first, base.second, cases.size))

    // coordinate descent over the error-model weights
    data class P(val name: String, val vals: List<Double>, val get: () -> Double, val set: (Double) -> Unit)
    val params = listOf(
        P("adjSub", listOf(0.5, 0.7), { Tune.adjSub }, { Tune.adjSub = it }),
        P("indel", listOf(0.9, 1.1), { Tune.indel }, { Tune.indel = it }),
        P("dbl", listOf(0.3, 0.6), { Tune.dbl }, { Tune.dbl = it }),
        P("trans", listOf(0.5, 0.9), { Tune.trans }, { Tune.trans = it }))
    var best = base.first
    repeat(1) { round ->
        for (p in params) {
            var bv = p.get(); var bs = best
            for (v in p.vals) { p.set(v); val s = eval().first; if (s > bs + 0.05) { bs = s; bv = v } }
            p.set(bv); best = bs
            println(String.format("  round %d  %-7s -> %.2f   fixed %.1f%%", round + 1, p.name, bv, best))
        }
    }
    val fin = eval()
    println(String.format("FINAL: fixed %.1f%%  wrong %.1f%%   distW=%.2f prior=%.2f adjSub=%.2f indel=%.2f dbl=%.2f trans=%.2f",
        fin.first, fin.second, Tune.distW, Tune.prior, Tune.adjSub, Tune.indel, Tune.dbl, Tune.trans))
}
