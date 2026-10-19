
import com.nekokeys.keyboard.*
import java.io.File
fun dictOf7(path: String): Predictor.Dict {
    val m = LinkedHashMap<String, Int>(); var rank = 0
    for (l in File(path).readLines()) { val w = l.trim().lowercase(); if (w.isEmpty()) continue; rank++; m[w] = 1_000_000 / (rank + 9) }
    return Predictor.Dict(m)
}
fun main() {
    val a = "./app/src/main/assets/"
    var fails = 0
    fun check(n: String, ok: Boolean, x: String = "") { if (!ok) fails++; println((if (ok) "PASS " else "FAIL ") + n + (if (x.isEmpty()) "" else "  [$x]")) }
    for (n in listOf("en", "id")) {
        val lines = File(a + "words_$n.txt").readLines().filter { it.isNotBlank() }
        check("$n: no duplicate words", lines.size == lines.toSet().size, "${lines.size} lines")
        check("$n: all lowercase, no spaces", lines.all { it == it.lowercase() && !it.contains(' ') })
        check("$n: sane size", lines.size in 40000..70000)
    }
    System.gc(); Thread.sleep(200)
    val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    val t0 = System.nanoTime()
    val en = dictOf7(a + "words_en.txt"); val id = dictOf7(a + "words_id.txt")
    val ms = (System.nanoTime() - t0) / 1_000_000
    System.gc(); Thread.sleep(300); System.gc()
    val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    println(String.format("both dictionaries (%d + %d words): build %d ms (JVM, desktop CPU), retained heap ~%.1f MB", en.words.size, id.words.size, ms, (after - before) / 1048576.0))
    check("retained memory under 20 MB", (after - before) < 20L * 1048576)
    for (w in listOf("hello", "don't", "it's", "i'm", "thanks", "okay", "weekend")) check("en has $w", en.freqOf(w) > 0)
    for (w in listOf("makan", "memakan", "terima", "kasih", "nggak", "gue", "banget", "gimana", "selamat", "yg")) check("id has $w", id.freqOf(w) > 0)
    println(if (fails == 0) "ALL DICTIONARY TESTS PASSED" else "$fails FAILED")
}
