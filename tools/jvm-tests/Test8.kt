import com.nekokeys.keyboard.*
import java.io.File

fun main() {
    var fails = 0
    fun check(n: String, ok: Boolean, x: String = "") { if (!ok) fails++; println((if (ok) "PASS " else "FAIL ") + n + (if (x.isEmpty()) "" else "  [$x]")) }
    val lines = File("./app/src/main/assets/emoji.tsv").readLines()
    EmojiData.load(lines.asSequence()) { true }
    val cats = EmojiData.cats!!
    val all = cats.flatten()
    check("1914 emoji loaded", all.size == 1914, "${all.size}")
    check("9 non-empty groups", cats.size == 9 && cats.all { it.isNotEmpty() }, cats.map { it.size }.toString())
    check("no duplicates", all.size == all.toSet().size)
    check("330 accept a skin tone", EmojiData.toneable.size == 330, "${EmojiData.toneable.size}")

    fun cp(vararg c: Int) = String(c, 0, c.size)
    val T3 = 0x1F3FD
    val cases = listOf(
        "wave" to (cp(0x1F44B) to cp(0x1F44B, T3)),
        "point up (VS16 replaced)" to (cp(0x261D, 0xFE0F) to cp(0x261D, T3)),
        "woman health worker" to (cp(0x1F469, 0x200D, 0x2695, 0xFE0F) to cp(0x1F469, T3, 0x200D, 0x2695, 0xFE0F)),
        "woman running" to (cp(0x1F3C3, 0x200D, 0x2640, 0xFE0F) to cp(0x1F3C3, T3, 0x200D, 0x2640, 0xFE0F)),
        "couple with heart (both toned)" to (cp(0x1F469, 0x200D, 0x2764, 0xFE0F, 0x200D, 0x1F468) to cp(0x1F469, T3, 0x200D, 0x2764, 0xFE0F, 0x200D, 0x1F468, T3)),
        "people holding hands (handshake untoned)" to (cp(0x1F9D1, 0x200D, 0x1F91D, 0x200D, 0x1F9D1) to cp(0x1F9D1, T3, 0x200D, 0x1F91D, 0x200D, 0x1F9D1, T3)),
        "handshake alone" to (cp(0x1F91D) to cp(0x1F91D, T3)))
    for ((name, pair) in cases) {
        val (src, want) = pair
        val ok = src in EmojiData.toneable
        val got = EmojiData.withTone(src, 3)
        check("tone: $name", ok && got == want, "toneable=$ok got=${got.codePoints().toArray().joinToString(" ") { Integer.toHexString(it) }}")
    }
    val family = cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467)
    check("family is not toneable -> unchanged", EmojiData.withTone(family, 3) == family)
    check("smiley unchanged", EmojiData.withTone(cp(0x1F600), 3) == cp(0x1F600))
    check("tone 0 unchanged", EmojiData.withTone(cp(0x1F44B), 0) == cp(0x1F44B))

    // structural check over all 330 x 5 variants: every modifier must directly follow a person/hand base,
    // never follow ZWJ / VS16, and each variant must contain at least one modifier of the requested tone
    var bad = 0
    for (e in EmojiData.toneable) for (t in 1..5) {
        val out = EmojiData.withTone(e, t); val mod = 0x1F3FA + t
        val cps = out.codePoints().toArray()
        var mods = 0
        for (i in cps.indices) if (cps[i] in 0x1F3FB..0x1F3FF) { mods++; if (cps[i] != mod || i == 0 || cps[i - 1] == 0x200D || cps[i - 1] == 0xFE0F) bad++ }
        if (mods == 0) bad++
    }
    check("all 1650 toned variants well-formed", bad == 0, "bad=$bad")
    println(if (fails == 0) "ALL EMOJI TESTS PASSED" else "$fails FAILED")
}
