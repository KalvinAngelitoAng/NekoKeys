import android.content.Context
import android.content.ContextWrapper
import com.nekokeys.keyboard.*
import java.io.File

class FakeCtx(private val dir: File) : ContextWrapper(null) {
    override fun getFilesDir(): File = dir
    override fun getApplicationContext(): Context = this
}

fun fake(dir: File): Context {
    val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible = true }
    val u = f.get(null) as sun.misc.Unsafe
    val c = u.allocateInstance(FakeCtx::class.java) as FakeCtx
    val fld = FakeCtx::class.java.getDeclaredField("dir").also { it.isAccessible = true }
    fld.set(c, dir)
    return c
}

fun main() {
    var fails = 0
    fun check(name: String, ok: Boolean, extra: String = "") { if (!ok) fails++; println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isEmpty()) "" else "  [$extra]")) }
    val dir = File(System.getProperty("java.io.tmpdir"), "gb-test-" + System.nanoTime()).also { it.mkdirs() }
    val ctx = fake(dir)

    // ---- ClipStore: persistence of tricky text, dedupe, pin, trim, expire ----
    val cc = ClipStore::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
    val s1 = cc.newInstance(ctx)
    val tricky = "line1\nline2\twith tab \\ backslash \\n literal\r\nend"
    s1.addText(tricky); s1.addText("plain"); s1.addText(tricky)
    check("dedupe identical text", s1.all().count { it.data == tricky } == 1 && s1.all().size == 2)
    val s2 = cc.newInstance(ctx)
    check("round-trip tricky text via file", s2.all().any { it.data == tricky }, s2.all().map { it.data }.toString())
    val pinId = s2.all().first { it.data == "plain" }.id
    s2.togglePin(pinId)
    check("pinned sorts first", s2.all().first().pinned && s2.all().first().data == "plain")
    for (i in 0 until 90) s2.addText("item $i")
    check("history capped at 60", s2.all().size <= 60, "size=${s2.all().size}")
    check("pinned survives trim", s2.all().any { it.id == pinId })
    check("newest kept", s2.all().any { it.data == "item 89" } && s2.all().none { it.data == "item 0" })
    Thread.sleep(15); s2.expire(5)
    check("expire removes unpinned only", s2.all().size == 1 && s2.all()[0].pinned)
    s2.clearAll(); check("clearAll", s2.all().isEmpty() && cc.newInstance(ctx).all().isEmpty())
    check("blank / huge text ignored", run { s2.addText("   "); s2.addText("x".repeat(60_000)); s2.all().isEmpty() })

    // ---- Predictor: learning is persisted and reloaded ----
    val pc = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
    val p1 = pc.newInstance(ctx)
    p1.learn("hello", "zorblax"); p1.learn("hello", "zorblax"); p1.learn("hello", "zorblax")
    p1.saveAsync(); Thread.sleep(300)
    val p2 = pc.newInstance(ctx)
    Predictor::class.java.getDeclaredMethod("loadUser").also { it.isAccessible = true }.invoke(p2)
    check("learned word restored", p2.isKnown("zorblax"))
    check("learned word suggested", p2.suggest("zorb", "hello", 0, 3).contains("zorblax"), p2.suggest("zorb", "hello", 0, 3).toString())
    check("next-word from bigram", p2.suggest("", "hello", 0, 3).contains("zorblax"), p2.suggest("", "hello", 0, 3).toString())
    p2.clearAll(); check("clearAll wipes files", listOf("userdata.v2", "user_words.tsv", "bigrams.tsv").none { File(dir, it).exists() })
    p1.learn("a", "b1x"); check("digits never learned", !p1.isKnown("b1x"))

    println(if (fails == 0) "ALL STORAGE TESTS PASSED" else "$fails TEST(S) FAILED")
    dir.deleteRecursively()
    System.exit(if (fails == 0) 0 else 1)
}
