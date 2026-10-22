import com.nekokeys.keyboard.*
import java.io.File
import kotlin.math.*
import java.util.Random

fun dictOf(path: String): Predictor.Dict {
    val m = LinkedHashMap<String, Int>(); var rank = 0
    for (l in File(path).readLines()) { val w = l.trim().lowercase(); if (w.isEmpty()) continue; rank++; m[w] = 1_000_000 / (rank + 9) }
    return Predictor.Dict(m)
}

fun main() {
    val assets = "./app/src/main/assets/"
    var fails = 0
    fun check(name: String, ok: Boolean, extra: String = "") { if (!ok) fails++; println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isEmpty()) "" else "  [$extra]")) }

    // 1) every layout row must sum to weight 10 (otherwise keys overflow / leave gaps)
    val pages = mapOf("letters" to Layouts.letters(false), "letters+num" to Layouts.letters(true),
        "sym1" to Layouts.symbols1(), "sym2" to Layouts.symbols2(), "numpad" to Layouts.numpad())
    for ((n, rows) in pages) rows.forEachIndexed { i, r ->
        val sum = r.sumOf { it.weight.toDouble() }
        check("layout $n row$i weight=10", abs(sum - 10.0) < 0.001, "sum=$sum")
    }

    // 2) predictor with real dictionaries (private ctor + injected dicts, no Android needed)
    val ctor = Predictor::class.java.getDeclaredConstructor(android.content.Context::class.java).also { it.isAccessible = true }
    val pr = ctor.newInstance(null)
    val f = Predictor::class.java.getDeclaredField("dicts").also { it.isAccessible = true }
    f.set(pr, listOf(dictOf(assets + "words_en.txt"), dictOf(assets + "words_id.txt")))

    for ((typed, want) in listOf("thnks" to "thanks", "helo" to "hello", "selmat" to "selamat", "dont" to "don't", "yuo" to "you", "wiht" to "with", "becuase" to "because", "tomorow" to "tomorrow", "frend" to "friend", "pelase" to "please", "helllo" to "hello", "thier" to "their", "jgn" to null))
        check("best candidate $typed -> $want", pr.correction(typed, "", 2)?.word == want, "got=${pr.correction(typed, "", 2)?.word}")
    // Autocorrect only applies a correction it is sure about (Tune.minConf); otherwise the word is left and offered in the bar.
    for ((typed, want) in listOf("becuase" to "because", "tomorow" to "tomorrow", "dont" to "don't", "yuo" to "you", "wiht" to "with", "pelase" to "please", "helllo" to "hello"))
        check("applied: $typed -> $want", pr.correct(typed, "", 2) == want, "got=${pr.correct(typed, "", 2)}  conf=${pr.correction(typed, "", 2)?.confidence}")
    check("unsure typo is left alone but offered", pr.correct("frend", "", 2, 0.99) == null && pr.correction("frend", "", 2)?.word == "friend")
    check("a rare valid word is not forced into another word", pr.correct("Kalvin", "", 0) == null && pr.correct("gojek", "", 2) == null,
        "Kalvin=${pr.correct("Kalvin", "", 0)} gojek=${pr.correct("gojek", "", 2)}")
    check("teh -> the (English mode)", pr.correct("teh", "", 0) == "the", "got=${pr.correct("teh", "", 0)}")
    check("teh untouched in EN+ID mode (it is Indonesian for tea)", pr.correct("teh", "", 2) == null)
    check("valid word untouched", pr.correct("hello", "", 2) == null && pr.correct("makan", "", 2) == null)
    check("name mid-sentence untouched", pr.correct("Budiman", "hi", 2) == null)
    check("caps kept", pr.correct("Teh", "", 0) == "The")
    check("suggest th", pr.suggest("th", "", 0, 3).containsAll(listOf("the")), pr.suggest("th", "", 0, 3).toString())
    check("suggest sel (ID)", pr.suggest("sel", "", 1, 3).contains("selamat"), pr.suggest("sel", "", 1, 3).toString())
    run {
        val pb = pr
        repeat(8) { pb.observe("hello"); pb.observe("thanks"); pb.observe("friend") }
        val enCtx = pb.suggest("ma", "", 2, 3)
        repeat(12) { pb.observe("makan"); pb.observe("malam"); pb.observe("terima") }
        val idCtx = pb.suggest("ma", "", 2, 3)
        check("mixed mode follows English context", enCtx.any { it in setOf("make", "man", "many", "may", "maybe", "made", "mean", "matter", "mark") }, enCtx.toString())
        check("mixed mode follows Indonesian context", idCtx.any { it in setOf("mau", "makan", "masih", "mana", "malam", "maaf", "mas") }, idCtx.toString())
        repeat(30) { pb.observe("ok") }
    }

    // 3) glide typing: synthetic finger paths over a 1080px-wide QWERTY with noise
    val unit = 108f; val rowH = 150f
    val cx = FloatArray(26); val cy = FloatArray(26)
    fun place(row: String, r: Int, x0: Float) = row.forEachIndexed { i, ch -> cx[ch - 'a'] = unit * (x0 + i + 0.5f); cy[ch - 'a'] = rowH * (r + 0.5f) }
    place("qwertyuiop", 0, 0f); place("asdfghjkl", 1, 0.5f); place("zxcvbnm", 2, 1.5f)
    val rnd = Random(7)
    val words = listOf("hello", "thanks", "please", "selamat", "terima", "kasih", "makan", "malam", "tidur", "friend", "morning", "sorry",
        "tomorrow", "where", "what", "would", "dengan", "untuk", "meeting", "weekend", "birthday", "message", "pulang", "sekarang")
    var top1 = 0; var top3 = 0; var ms = 0L
    for (w in words) {
        val ids = ArrayList<Int>(); for (ch in w) { val i = ch - 'a'; if (ids.isEmpty() || ids.last() != i) ids.add(i) }
        val pts = ArrayList<FloatArray>()
        for (k in 0 until ids.size - 1) for (s in 0 until 14) { val t = s / 14f
            pts.add(floatArrayOf(cx[ids[k]] + (cx[ids[k + 1]] - cx[ids[k]]) * t, cy[ids[k]] + (cy[ids[k + 1]] - cy[ids[k]]) * t)) }
        pts.add(floatArrayOf(cx[ids.last()], cy[ids.last()]))
        val n = pts.size; val xs = FloatArray(n); val ys = FloatArray(n)
        val jx = (rnd.nextGaussian() * 0.12 * unit).toFloat(); val jy = (rnd.nextGaussian() * 0.12 * unit).toFloat()
        for (i in 0 until n) { xs[i] = pts[i][0] + jx + (rnd.nextGaussian() * 0.10 * unit).toFloat(); ys[i] = pts[i][1] + jy + (rnd.nextGaussian() * 0.10 * unit).toFloat() }
        val t0 = System.nanoTime()
        val res = pr.glide(xs, ys, n, cx, cy, unit, 2, "")
        ms += (System.nanoTime() - t0) / 1_000_000
        if (res.firstOrNull() == w) top1++
        if (w in res) top3++ else println("   miss: $w -> $res")
    }
    println("glide top1 $top1/${words.size}, top3 $top3/${words.size}, avg ${ms / words.size} ms")
    check("glide top-3 >= 90%", top3 >= (words.size * 0.9).toInt())
    check("glide top-1 >= 70%", top1 >= (words.size * 0.7).toInt())
    check("tap-like path rejected", pr.glide(floatArrayOf(100f, 101f, 100f), floatArrayOf(100f, 100f, 101f), 3, cx, cy, unit, 2, "").isEmpty())

    println(if (fails == 0) "ALL TESTS PASSED" else "$fails TEST(S) FAILED")
    System.exit(if (fails == 0) 0 else 1)
}
