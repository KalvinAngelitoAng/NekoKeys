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
    // ---- emoji suggestions for a typed word (assets/emoji_words.tsv)
    val words = File("./app/src/main/assets/emoji_words.tsv").readLines()
    EmojiData.load(emoji.asSequence()) { true }
    EmojiData.loadWords(words.asSequence())
    check("love gives a heart", EmojiData.emojiFor("love")?.startsWith("\u2764") == true)
    check("Indonesian: kucing gives a cat", EmojiData.emojiFor("kucing") == "\uD83D\uDC31")
    check("single-name noun: pizza", EmojiData.emojiFor("pizza") == "\uD83C\uDF55")
    check("curated beats raw keywords: home is a house", EmojiData.emojiFor("home") == "\uD83C\uDFE0")
    check("ordinary words get nothing", EmojiData.emojiFor("the") == null && EmojiData.emojiFor("xyzzy") == null)
    val all = emoji.map { it.split('\t')[1] }.toSet()
    check("every offered emoji exists in emoji.tsv", words.all { it.split('\t')[1] in all })
    EmojiData.load(emoji.asSequence()) { it != "\uD83C\uDF55" }
    EmojiData.loadWords(words.asSequence())
    check("emoji the phone cannot draw are not offered", EmojiData.emojiFor("pizza") == null)
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
    if (fails > 0) System.exit(1)
}
