import android.content.Context
import com.nekokeys.keyboard.*
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

fun dictOf10(path: String): Predictor.Dict {
    val m = LinkedHashMap<String, Int>(); var rank = 0
    for (l in File(path).readLines()) { val w = l.trim().lowercase(); if (w.isEmpty()) continue; rank++; m[w] = 1_000_000 / (rank + 9) }
    return Predictor.Dict(m)
}

fun alpha(i: Int): String { var n = i; val sb = StringBuilder("zq"); do { sb.append('a' + n % 26); n /= 26 } while (n > 0); return sb.toString() }  // letters only: the keyboard never learns digits

fun main() {
    var fails = 0
    fun check(n: String, ok: Boolean, x: String = "") { if (!ok) fails++; println((if (ok) "PASS " else "FAIL ") + n + (if (x.isEmpty()) "" else "  [$x]")) }
    val assets = "./app/src/main/assets/"
    val en = dictOf10(assets + "words_en.txt"); val id = dictOf10(assets + "words_id.txt")
    val pc = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
    val dfield = Predictor::class.java.getDeclaredField("dicts").also { it.isAccessible = true }
    val loadUser = Predictor::class.java.getDeclaredMethod("loadUser").also { it.isAccessible = true }
    fun newPredictor(dir: File): Predictor { val p = pc.newInstance(fake(dir)); dfield.set(p, listOf(en, id)); return p }
    fun reload(dir: File): Predictor { val p = newPredictor(dir); loadUser.invoke(p); return p }
    fun tmp() = File(System.getProperty("java.io.tmpdir"), "nk-" + System.nanoTime()).also { it.mkdirs() }

    // 1) save -> reload round trip, no temp file left behind
    run {
        val d = tmp(); val p = newPredictor(d)
        for (i in 0 until 500) { p.learn("selamat", "pagi"); p.learn("hello", alpha(i)) }
        p.saveBlocking()
        val r = reload(d)
        check("round trip keeps words and pairs", r.stats().words == p.stats().words && r.stats().pairs == p.stats().pairs && p.stats().words == 500 && p.stats().pairs in 2..61, "${r.stats().words} words / ${r.stats().pairs} pairs (follow-list per word is capped at 30..60)")
        check("no temp file left after save", !File(d, "userdata.v2.tmp").exists())
        check("learned pair predicted after reload", r.suggest("", "selamat", 1, 3).contains("pagi"))
    }
    // 2) crash during a save: a half-written temp file must be ignored, the last good file wins
    run {
        val d = tmp(); val p = newPredictor(d); repeat(50) { p.learn("terima", "kasih") }; p.saveBlocking()
        File(d, "userdata.v2.tmp").writeText("B\tterima\tgar")                 // power cut while writing the next save
        val r = reload(d)
        check("leftover temp file ignored, last good data used", r.suggest("", "terima", 1, 3).contains("kasih"))
    }
    // 3) a truncated / damaged main file never crashes and still recovers what it can
    run {
        val d = tmp(); val p = newPredictor(d)
        for (i in 0 until 2000) p.learn(alpha(i), alpha(i + 50000))
        p.saveBlocking()
        val f = File(d, "userdata.v2"); val bytes = f.readBytes(); f.writeBytes(bytes.copyOf((bytes.size * 0.6).toInt()))
        val r = try { reload(d) } catch (e: Throwable) { null }
        check("truncated file loads without crashing", r != null)
        check("partial data recovered", r != null && r.stats().pairs in 1 until 2001, "${r?.stats()?.pairs}")
        val junk = Random(1).let { rnd -> ByteArray(5000) { rnd.nextInt(256).toByte() } }
        File(d, "userdata.v2").writeBytes(junk + "\nB\tx\ty\t99999999999\nW\tq\t-5\nB\t\t\t\n".toByteArray())
        val r2 = try { reload(d) } catch (e: Throwable) { null }
        check("random garbage / bad numbers do not crash", r2 != null)
        r2?.saveBlocking(); check("a clean file is written back afterwards", reload(d).stats().pairs >= 0)
    }
    // 4) data from the old version (two plain files, hugely oversized) is migrated and capped
    run {
        val d = tmp(); val rnd = Random(2)
        File(d, "user_words.tsv").bufferedWriter().use { w -> for (i in 0 until 30000) w.write("old$i\t${1 + rnd.nextInt(5)}\n") }
        File(d, "bigrams.tsv").bufferedWriter().use { w -> for (i in 0 until 400000) w.write("w${i % 5000}\tn${i}\t${1 + rnd.nextInt(9)}\n") }
        val r = reload(d); val st = r.stats()
        check("legacy oversized pairs capped at 40k yet NOT wiped", st.pairs in 25000..40000, "${st.pairs}")
        check("legacy oversized words capped at 5k yet NOT wiped", st.words in 3000..5000, "${st.words}")
        r.saveBlocking()
        check("legacy files removed, new file written", !File(d, "bigrams.tsv").exists() && !File(d, "user_words.tsv").exists() && File(d, "userdata.v2").exists())
        check("file stays small after migration", File(d, "userdata.v2").length() in 100_000..2_000_000, "${File(d, "userdata.v2").length()} bytes")
    }
    // 5) many threads learning, saving, reading and clearing at the same time
    run {
        val d = tmp(); val p = newPredictor(d); val errors = AtomicInteger(0); val stop = System.currentTimeMillis() + 4000
        val threads = (0 until 4).map { t -> Thread { val rnd = Random(t.toLong())
            try { while (System.currentTimeMillis() < stop) { p.learn(alpha(rnd.nextInt(300)), alpha(1000 + rnd.nextInt(3000))); if (rnd.nextInt(2000) == 0) p.saveAsync()
                if (rnd.nextInt(500) == 0) p.suggest("zq", alpha(1), 0, 3); if (rnd.nextInt(20000) == 0) p.stats() } } catch (e: Throwable) { errors.incrementAndGet(); e.printStackTrace() } } }
        val saver = Thread { try { while (System.currentTimeMillis() < stop) { p.saveBlocking(); Thread.sleep(20) } } catch (e: Throwable) { errors.incrementAndGet(); e.printStackTrace() } }
        (threads + saver).forEach { it.start() }; (threads + saver).forEach { it.join() }
        p.saveBlocking(); Thread.sleep(300)
        val r = reload(d)
        check("concurrent learn/save/suggest: no exceptions", errors.get() == 0)
        check("file written under load is complete and loadable", r.stats().pairs > 0 && !File(d, "userdata.v2.tmp").exists(), "${r.stats().pairs} pairs")
        p.clearAll(); check("clearAll removes every file", listOf("userdata.v2", "userdata.v2.tmp", "user_words.tsv", "bigrams.tsv").none { File(d, it).exists() })
        check("nothing learned from digits or too-short words", run { p.learn("a", "b1"); p.learn("a", "x"); p.stats().pairs == 0 })
    }
    // 6) clipboard store limits
    run {
        val d = tmp(); val cc = ClipStore::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
        val s = cc.newInstance(fake(d))
        var ok = 0; var refused = 0
        for (i in 0 until 160) { s.addText("pin me $i"); val id = s.all().first { it.data == "pin me $i" }.id; if (s.togglePin(id)) ok++ else refused++ }
        check("at most 150 items can be pinned", s.all().count { it.pinned } == 150 && refused == 10, "pinned=${s.all().count { it.pinned }} refused=$refused")
        s.addText("fresh copy")
        check("new copies stay visible even when 150 are pinned", s.all().any { it.data == "fresh copy" } && s.all().count { !it.pinned } in 1..20, "unpinned=${s.all().count { !it.pinned }}")
        check("huge text is ignored (limit 20k chars)", run { val n = s.all().size; s.addText("x".repeat(ClipStore.MAX_TEXT + 1)); s.all().size == n })
        val clips = File(d, "clips"); clips.mkdirs()
        fun fakeImage(name: String, mb: Int): File = File(clips, name).also { java.io.RandomAccessFile(it, "rw").use { f -> f.setLength(mb * 1024L * 1024L) } }
        val s2 = cc.newInstance(fake(tmp())); val clips2 = File(File(s2.javaClass.getDeclaredField("dir").also { it.isAccessible = true }.get(s2).toString()).path)
        for (i in 0 until 120) { val f = File(clips2, "${1000 + i}.png"); java.io.RandomAccessFile(f, "rw").use { r -> r.setLength(2L * 1024 * 1024 + i) }; s2.addImageFile(f, false); Thread.sleep(1) }
        val total = clips2.listFiles()!!.sumOf { it.length() }
        check("image storage capped at 150 MB", total <= 150L * 1024 * 1024, "${total / 1048576} MB in ${clips2.listFiles()!!.size} files")
        check("no orphan image files (disk matches the list)", clips2.listFiles()!!.map { it.name }.toSet() == s2.all().filter { it.type == 1 }.map { it.data }.toSet() )
        var pinnedOk = 0; for (i in 0 until 40) { val f = File(clips2, "${5000 + i}.png"); java.io.RandomAccessFile(f, "rw").use { r -> r.setLength(6L * 1024 * 1024 + i) }; if (s2.addImageFile(f, true)) pinnedOk++; Thread.sleep(1) }
        val total2 = clips2.listFiles()!!.sumOf { it.length() }
        check("pinned images cannot push storage past the cap", total2 <= 150L * 1024 * 1024 && pinnedOk < 40, "pinnedAdded=$pinnedOk total=${total2 / 1048576} MB")
        check("no orphan files after pinned overflow", clips2.listFiles()!!.map { it.name }.toSet() == s2.all().filter { it.type == 1 }.map { it.data }.toSet())
        check("no temp index left behind", !File(s2.javaClass.getDeclaredField("index").also { it.isAccessible = true }.get(s2).toString() + ".tmp").exists())
    }
    println(if (fails == 0) "ALL ROBUSTNESS TESTS PASSED" else "$fails FAILED")
    System.exit(if (fails == 0) 0 else 1)
}
