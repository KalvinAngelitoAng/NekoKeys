import android.content.Context
import com.nekokeys.keyboard.*
import java.io.File
import java.util.Random

fun dictOf9(path: String): Predictor.Dict {
    val m = LinkedHashMap<String, Int>(); var rank = 0
    for (l in File(path).readLines()) { val w = l.trim().lowercase(); if (w.isEmpty()) continue; rank++; m[w] = 1_000_000 / (rank + 9) }
    return Predictor.Dict(m)
}
fun field(o: Any, n: String): Any { val f = o.javaClass.getDeclaredField(n); f.isAccessible = true; return f.get(o) }

fun main(args: Array<String>) {
    val perDay = (args.getOrNull(0) ?: "2000").toInt()
    val years = (args.getOrNull(1) ?: "10").toInt()
    val assets = "./app/src/main/assets/"
    val pr = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }.newInstance(null)
    val en = dictOf9(assets + "words_en.txt"); val id = dictOf9(assets + "words_id.txt")
    Predictor::class.java.getDeclaredField("dicts").also { it.isAccessible = true }.set(pr, listOf(en, id))
    val rnd = Random(99)

    // vocabulary: 110k real words + 15k names/slang/typos the dictionary does not know
    val vocab = ArrayList<String>(); val w = ArrayList<Double>()
    for (d in listOf(en, id)) for (i in d.words.indices) { vocab.add(d.words[i]); w.add(d.freq[i].toDouble()) }
    for (i in 0 until 15000) { vocab.add("zq" + Integer.toString(i, 36)); w.add(1_000_000.0 / (i + 400)) }
    val cum = DoubleArray(vocab.size); var t = 0.0; for (i in cum.indices) { t += w[i]; cum[i] = t }
    fun pick(): String { val x = rnd.nextDouble() * t; var lo = 0; var hi = cum.size - 1; while (lo < hi) { val m = (lo + hi) ushr 1; if (cum[m] < x) lo = m + 1 else hi = m }; return vocab[lo] }
    // 3000 habitual phrases that get reused for years; the rest of the traffic is mostly unique pairs
    val habits = (0 until 3000).map { pick() to pick() }
    val hcum = DoubleArray(habits.size); var ht = 0.0; for (i in hcum.indices) { ht += 1.0 / (i + 3); hcum[i] = ht }
    fun habit(): Pair<String, String> { val x = rnd.nextDouble() * ht; var lo = 0; var hi = hcum.size - 1; while (lo < hi) { val m = (lo + hi) ushr 1; if (hcum[m] < x) lo = m + 1 else hi = m }; return habits[lo] }

    fun sizes(): Triple<Int, Int, Long> {
        @Suppress("UNCHECKED_CAST") val bg = field(pr, "bigrams") as HashMap<String, HashMap<String, Int>>
        @Suppress("UNCHECKED_CAST") val uc = field(pr, "userCounts") as HashMap<String, Int>
        var pairs = 0; var bytes = 0L
        for ((p, m) in bg) { pairs += m.size; for ((x, c) in m) bytes += p.length + x.length + 6 }
        for ((x, c) in uc) bytes += x.length + 5
        return Triple(uc.size, pairs, bytes)
    }
    val oneTimePair = "zorblax" to "quuxen"
    println("simulating $years years at $perDay words/day = ${years.toLong() * 365 * perDay} typed words")
    println(String.format("%-6s %10s %12s %10s %12s %14s", "year", "new words", "word pairs", "file (MB)", "learn() us", "longest follow-list"))
    var year1Us = 0.0; var lastUs = 0.0
    for (y in 1..years) {
        val n = 365 * perDay
        val t0 = System.nanoTime()
        for (i in 0 until n) {
            if (y == 1 && i < 20 * perDay && i % (perDay / 3) == 0) pr.learn(oneTimePair.first, oneTimePair.second) // typed ~60x in month 1, never again
            if (rnd.nextDouble() < 0.4) { val h = habit(); pr.learn(h.first, h.second) } else pr.learn(pick(), pick())
        }
        val us = (System.nanoTime() - t0) / 1000.0 / n
        if (y == 1) year1Us = us; lastUs = us
        val (wc, pc, by) = sizes()
        @Suppress("UNCHECKED_CAST") val bg = field(pr, "bigrams") as HashMap<String, HashMap<String, Int>>
        println(String.format("%-6d %10d %12d %10.2f %12.2f %14d", y, wc, pc, by / 1048576.0, us, bg.values.maxOf { it.size }))
    }
    // does the useful stuff survive, and does the old stuff fade?
    var kept = 0; for ((a, b) in habits.take(300)) if (pr.suggest("", a, 0, 3).contains(b) || pr.suggest(b.take(2), a, 0, 5).contains(b)) kept++
    println("top-300 daily habits still predicted after $years years: $kept/300")
    val old = pr.suggest("", oneTimePair.first, 0, 3).contains(oneTimePair.second)
    println("pair typed 60x in month 1 and never again still remembered: $old")
    val t1 = System.nanoTime(); repeat(200) { pr.suggest("th", "of", 0, 3); pr.suggest("", "yang", 1, 3) }
    println(String.format("suggest() latency after %d years: %.2f ms", years, (System.nanoTime() - t1) / 1e6 / 400))
    println(String.format("learn() cost year1 vs last year: %.2f -> %.2f us", year1Us, lastUs))
}
