import android.content.Context
import com.nekokeys.keyboard.*
import java.io.File
import java.util.Random
import kotlin.math.max

/**
 * The autocorrect "exam". It measures, for the confidence gating in Predictor.correct():
 *   - TYPOS:  of the misspelled words, how many are fixed correctly, fixed WRONGLY, or left alone
 *             (and for the left-alone ones, whether the right word is still offered in the suggestion bar);
 *   - VALID-BUT-UNKNOWN words (names, slang, rare words the dictionary lacks): how many are wrongly changed.
 * Test3 only checks words taken from the dictionary itself, so its "0 wrongly corrected" can never be anything else.
 *
 * Data (see tools/build_eval_data.py): real English misspellings, names, slang. Indonesian typos are synthetic
 * (no public real list exists), so treat the Indonesian numbers as optimistic.
 *
 *   kotlinc -no-stdlib -cp out:android.jar:kotlin-stdlib.jar -d testout14 tools/jvm-tests/Test14.kt
 *   java -Xmx2g -cp testout14:out:android.jar:kotlin-stdlib.jar Test14Kt [itemsPerSet]
 * Needs a stub android.content.Context on the classpath only if your android.jar cannot load the real one.
 */
const val A = "./app/src/main/assets/"
const val E = "./tools/eval-data/"
// How much effort each outcome costs the user (lower is better). Edit these to match your own taste.
const val COST_FIXED = 0.0        // typo fixed correctly: nothing to do
const val COST_OFFERED = 0.3      // typo left alone but the right word is one of the two suggestions: one tap
const val COST_LEFT = 0.7         // typo left alone and not offered: retype or fix by hand
const val COST_WRONG = 3.0        // typo "fixed" into a different word: notice it, undo, retype
const val COST_FP = 3.0           // a valid word the dictionary lacks gets changed: same story
const val FP_WEIGHT = 0.5         // such words are rarer than typos: one per two typos typed

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

fun readWords(path: String): List<String> = File(path).readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }

/** Same frequency model as the app and Test3: frequency from the rank in the file. */
fun dictOf(words: List<String>, drop: Set<String> = emptySet()): Predictor.Dict {
    val m = LinkedHashMap<String, Int>(); var rank = 0
    for (w in words) { rank++; if (w !in drop) m[w] = 1_000_000 / (rank + 9) }
    return Predictor.Dict(m)
}

fun predictorOf(dicts: List<Predictor.Dict?>): Predictor {
    val ctor = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
    val pr = ctor.newInstance(null)
    Predictor::class.java.getDeclaredField("dicts").also { it.isAccessible = true }.set(pr, dicts)
    return pr
}

class Item(val typed: String, val truth: String?, val cands: List<Predictor.Cand>) {
    val feats: DoubleArray? = if (cands.isEmpty()) null else Predictor.gateFeatures(typed, cands)
}
class DataSet(val name: String, val items: List<Item>) {
    val tune get() = items.filterIndexed { i, _ -> i % 2 == 0 }
    val hold get() = items.filterIndexed { i, _ -> i % 2 == 1 }
}

fun zipfPick(words: List<String>, d: Predictor.Dict, rnd: Random, n: Int): List<String> {
    val w = DoubleArray(words.size) { d.freqOf(words[it]).toDouble() }
    val cum = DoubleArray(w.size); var t = 0.0; for (i in w.indices) { t += w[i]; cum[i] = t }
    return (0 until n).map { val x = rnd.nextDouble() * t; var lo = 0; var hi = cum.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < x) lo = m + 1 else hi = m }; words[lo] }
}

/** One or two keyboard slips: adjacent key, missing/extra/doubled letter, swapped letters, wrong first letter. */
fun slip(w: String, rnd: Random): String {
    fun one(s: String): String {
        if (s.length < 3) return s
        val i = 1 + rnd.nextInt(s.length - 1)
        return when (rnd.nextInt(7)) {
            0 -> { val nb = neighbors(s[i]); if (nb.isEmpty()) s else s.substring(0, i) + nb[rnd.nextInt(nb.size)] + s.substring(i + 1) }
            1 -> s.removeRange(i, i + 1)
            2 -> { val nb = neighbors(s[i]); if (nb.isEmpty()) s else s.substring(0, i) + nb[rnd.nextInt(nb.size)] + s.substring(i) }
            3 -> if (i < s.length - 1 && s[i] != s[i + 1]) s.substring(0, i) + s[i + 1] + s[i] + s.substring(i + 2) else s
            4 -> s.substring(0, i) + s[i] + s.substring(i)                           // doubled letter
            5 -> { val nb = neighbors(s[0]); if (nb.isEmpty()) s else nb[rnd.nextInt(nb.size)] + s.substring(1) } // first letter
            else -> { val nb = neighbors(s[s.length - 1]); if (nb.isEmpty()) s else s.dropLast(1) + nb[rnd.nextInt(nb.size)] } // last letter
        }
    }
    var t = one(w)
    if (rnd.nextInt(100) < 15) t = one(t)   // about one typo in seven has two slips
    return t
}

fun main(args: Array<String>) {
    val n = args.getOrNull(0)?.toInt() ?: 2500
    val enWords = readWords(A + "words_en.txt"); val idWords = readWords(A + "words_id.txt")
    val en = dictOf(enWords); val id = dictOf(idWords)
    val full = predictorOf(listOf(en, id))
    run { val t0 = System.nanoTime(); var k = 0
        for (w in listOf("helo", "teh", "recieve", "wierd", "tomorow", "makn", "kmu", "gimna")) { full.rank(w, "", 2); k++ }
        println(String.format("rank() takes about %.1f ms per word on this computer (warm-up included)", (System.nanoTime() - t0) / 1e6 / k)) }
    val rnd = Random(2024)
    val sets = ArrayList<DataSet>()   // typo sets
    val fps = ArrayList<DataSet>()    // valid-but-unknown sets

    // About one word in five starts a sentence, and auto-capitalisation makes it start with a capital letter.
    // Without such examples the gate would learn "capital letter = a name, leave it" and never fix "Teh".
    fun maybeCap(t: String) = if (rnd.nextInt(100) < 20) t.replaceFirstChar { it.uppercase() } else t
    fun typoSet(name: String, lang: Int, pairs: List<Pair<String, String>>, pr: Predictor = full) {
        val items = pairs.map { (t0, w) -> val t = maybeCap(t0); Item(t, w, pr.rank(t, "", lang)) }
        sets.add(DataSet(name, items))
    }
    fun fpSet(name: String, lang: Int, words: List<String>, pr: Predictor, cap: Boolean) {
        val items = words.map { w -> val t = if (cap) w.replaceFirstChar { it.uppercase() } else maybeCap(w); Item(t, null, pr.rank(t, "", lang)) }
        fps.add(DataSet(name, items))
    }
    fun isKnownAny(w: String) = full.isKnown(w, 2)

    // ---- typos
    val real = File(E + "typos_en.tsv").readLines().map { it.split('\t') }.filter { it.size == 2 }
        .filter { (w, r) -> !isKnownAny(w) && en.freqOf(r) > 0 }.map { it[0] to it[1] }.take(n)
    typoSet("EN real misspellings (EN)", 0, real)
    typoSet("EN real misspellings (EN+ID)", 2, real)
    fun slips(words: List<String>, d: Predictor.Dict, count: Int): List<Pair<String, String>> {
        val pool = words.filter { it.length >= 4 && it.all { c -> c in 'a'..'z' } }
        val out = ArrayList<Pair<String, String>>()
        for (w in zipfPick(pool, d, rnd, count * 2)) { val t = slip(w, rnd); if (t != w && !isKnownAny(t)) out.add(t to w); if (out.size >= count) break }
        return out
    }
    typoSet("EN slips (EN)", 0, slips(enWords, en, n))
    val idSlips = slips(idWords, id, n)
    typoSet("ID slips (ID)", 1, idSlips)
    typoSet("ID slips (EN+ID)", 2, idSlips)

    // ---- valid words the dictionary does not know
    fun heldOut(words: List<String>, count: Int): List<String> {
        val pool = words.drop(3000).take(37000).filter { it.length >= 4 && it.all { c -> c in 'a'..'z' } }
        return pool.shuffled(rnd).take(count)
    }
    val hEn = heldOut(enWords, n); val hId = heldOut(idWords, n)
    fpSet("rare EN words, unseen (EN)", 0, hEn, predictorOf(listOf(dictOf(enWords, hEn.toSet()), id)), false)
    fpSet("rare ID words, unseen (ID)", 1, hId, predictorOf(listOf(en, dictOf(idWords, hId.toSet()))), false)
    fun names(file: String, lang: Int, name: String) {
        val ns = File(E + file).readLines().filter { it.isNotBlank() && !isKnownAny(it.lowercase()) }
        fpSet(name, lang, ns, full, true)
    }
    names("names_en.txt", 0, "EN first names at sentence start (EN)")
    names("names_id.txt", 1, "ID first names at sentence start (ID)")
    val slang = File(E + "slang.txt").readLines().filter { it.isNotBlank() && !isKnownAny(it) }
    fpSet("slang / brands (EN+ID)", 2, slang, full, false)

    // ---- scoring: a correction is applied when gateProb(features, w) >= tau
    class Res(var fixed: Int = 0, var wrong: Int = 0, var left: Int = 0, var offered: Int = 0, var n: Int = 0)
    fun applies(it: Item, w: DoubleArray, tau: Double) = it.feats != null && Predictor.gateProb(it.feats, w) >= tau
    fun typoRes(items: List<Item>, w: DoubleArray, tau: Double): Res {
        val r = Res()
        for (it in items) {
            r.n++
            if (it.cands.isEmpty()) { r.left++; continue }
            if (applies(it, w, tau)) { if (it.cands[0].word == it.truth) r.fixed++ else r.wrong++ }
            else { r.left++; if (it.cands.take(2).any { c -> c.word == it.truth }) r.offered++ }
        }
        return r
    }
    fun fpRate(items: List<Item>, w: DoubleArray, tau: Double): Double =
        100.0 * items.count { applies(it, w, tau) } / max(1, items.size)
    fun typoEffort(r: Res) = 100.0 * (r.fixed * COST_FIXED + r.offered * COST_OFFERED + (r.left - r.offered) * COST_LEFT + r.wrong * COST_WRONG) / max(1, r.n)
    /** Average effort per 100 events: typos plus FP_WEIGHT x valid-but-unknown words. Lower is better. */
    fun effort(pick: (DataSet) -> List<Item>, w: DoubleArray, tau: Double): Double {
        val typo = sets.map { s -> typoEffort(typoRes(pick(s), w, tau)) }.average()
        val fp = fps.map { s -> COST_FP * fpRate(pick(s), w, tau) }.average()
        return typo + FP_WEIGHT * fp
    }
    fun report(title: String, pick: (DataSet) -> List<Item>, w: DoubleArray, tau: Double) {
        println("\n== $title  (tau=$tau) ==")
        println(String.format("%-40s %6s %7s %7s %8s %9s", "TYPOS", "n", "fixed", "wrong", "left", "(offered)"))
        for (s in sets) {
            val items = pick(s); val r = typoRes(items, w, tau)
            println(String.format("%-40s %6d %6.1f%% %6.1f%% %7.1f%% %8.1f%%", s.name, r.n, 100.0 * r.fixed / r.n, 100.0 * r.wrong / r.n, 100.0 * r.left / r.n, 100.0 * r.offered / r.n))
        }
        println(String.format("%-40s %6s %9s", "VALID WORDS THE DICTIONARY LACKS", "n", "changed"))
        for (s in fps) { val items = pick(s); println(String.format("%-40s %6d %8.1f%%", s.name, items.size, fpRate(items, w, tau))) }
        println(String.format("  effort = %.1f  (lower is better)", effort(pick, w, tau)))
    }

    // ---- train the gate (logistic regression) on the tuning half only
    class Sample(val x: DoubleArray, val y: Double, val wt: Double)
    val data = ArrayList<Sample>()
    for (s in sets) for (it in s.tune) if (it.feats != null)
        data.add(Sample(it.feats, if (it.cands[0].word == it.truth) 1.0 else 0.0, 1.0 / s.tune.size / sets.size))
    for (s in fps) for (it in s.tune) if (it.feats != null)
        data.add(Sample(it.feats, 0.0, FP_WEIGHT / s.tune.size / fps.size))
    val wts = DoubleArray(Predictor.GATE_N)
    run {
        val total = data.sumOf { it.wt }
        for (iter in 0 until 6000) {
            val g = DoubleArray(wts.size)
            for (d in data) {
                var z = 0.0; for (i in wts.indices) z += wts[i] * d.x[i]
                val p = 1.0 / (1.0 + Math.exp(-z))
                for (i in wts.indices) g[i] += (p - d.y) * d.x[i] * d.wt / total
            }
            for (i in wts.indices) wts[i] -= 2.0 * (g[i] + 0.0005 * wts[i])
        }
    }
    println("\nlearned gate weights: " + wts.joinToString(", ") { String.format("%.3f", it) })

    val tuneP: (DataSet) -> List<Item> = { it.tune }
    val holdP: (DataSet) -> List<Item> = { it.hold }
    val zero = DoubleArray(Predictor.GATE_N)
    println("sets: " + sets.joinToString { "${it.name}=${it.items.size}" })
    report("BEFORE: always correct (old behaviour), held-out half", holdP, zero, 0.0)

    // ---- choose the threshold on the tuning half
    val taus = listOf(0.0, 0.3, 0.4, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)
    println("\n== threshold sweep (tuning half -> held-out half) ==")
    var bestTau = 0.0; var bestE = Double.MAX_VALUE
    for (t in taus) {
        val e = effort(tuneP, wts, t)
        val rs = sets.map { typoRes(holdP(it), wts, t) }
        val fixed = rs.map { 100.0 * it.fixed / it.n }.average(); val wrong = rs.map { 100.0 * it.wrong / it.n }.average()
        val off = rs.map { 100.0 * it.offered / it.n }.average()
        val fp = fps.map { fpRate(holdP(it), wts, t) }.average()
        println(String.format("  tau %.2f   fixed %5.1f%%  wrong %4.1f%%  offered %5.1f%%   valid words changed %5.1f%%   effort: tuning %.1f / held-out %.1f", t, fixed, wrong, off, fp, e, effort(holdP, wts, t)))
        if (e < bestE) { bestE = e; bestTau = t }
    }
    val shown = args.getOrNull(1)?.toDouble() ?: bestTau      // optional 2nd argument: show this tau instead of the best one
    report("AFTER: learned gate, held-out half (never used for training or choosing)", holdP, wts, shown)
    println("\nBEST tau=$bestTau")
    println("Tune.gateW = doubleArrayOf(" + wts.joinToString(", ") { String.format("%.3f", it) } + ")   Tune.minConf = $bestTau")
}
