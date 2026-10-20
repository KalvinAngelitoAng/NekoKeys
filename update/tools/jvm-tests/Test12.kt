import com.nekokeys.keyboard.*
import java.io.File

/** Emoji search: names and keywords in English and Indonesian, ranking, limits, and undrawable emoji. */
fun main() {
    var fails = 0
    fun check(n: String, ok: Boolean, x: String = "") { if (!ok) fails++; println((if (ok) "PASS " else "FAIL ") + n + (if (x.isEmpty()) "" else "  [$x]")) }
    val emoji = File("./app/src/main/assets/emoji.tsv").readLines()
    val names = File("./app/src/main/assets/emoji_names.tsv").readLines()
    check("names file has one line per emoji", names.size == emoji.size, "${names.size} vs ${emoji.size}")
    EmojiData.load(emoji.asSequence()) { true }
    EmojiData.loadNames(names.asSequence())
    check("names ready", EmojiData.namesReady())
    check("English: smile finds grinning face", "\uD83D\uDE00" in EmojiData.search("smile"))
    check("Indonesian: senyum finds grinning face", "\uD83D\uDE00" in EmojiData.search("senyum"))
    check("Indonesian: kucing finds cat face", "\uD83D\uDC31" in EmojiData.search("kucing"))
    check("exact word ranks first: pizza", EmojiData.search("pizza").firstOrNull() == "\uD83C\uDF55")
    check("fire: flame in top 3", EmojiData.search("fire").indexOf("\uD83D\uDD25") in 0..2)
    check("every word must match: thumbs up", "\uD83D\uDC4D" in EmojiData.search("thumbs up"))
    check("flag lookup: indonesia", "\uD83C\uDDEE\uD83C\uDDE9" in EmojiData.search("indonesia"))
    check("nonsense and blank give nothing", EmojiData.search("xyzzy").isEmpty() && EmojiData.search("   ").isEmpty())
    check("limit is respected", EmojiData.search("a", 10).size == 10)
    check("case-insensitive", EmojiData.search("PIZZA") == EmojiData.search("pizza"))
    EmojiData.releaseNames()
    check("released index finds nothing", !EmojiData.namesReady() && EmojiData.search("pizza").isEmpty())
    EmojiData.load(emoji.asSequence()) { it != "\uD83C\uDF55" }
    EmojiData.loadNames(names.asSequence())
    check("emoji the phone cannot draw are not searchable", "\uD83C\uDF55" !in EmojiData.search("pizza"))
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
    if (fails > 0) System.exit(1)
}
