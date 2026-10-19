package com.nekokeys.keyboard

import android.content.Context
import android.graphics.Paint

/**
 * Emoji list (assets/emoji.tsv: every standard emoji up to Emoji 17.0). Emoji this phone's font cannot
 * draw are filtered out, so newer ones simply appear once the phone's emoji font supports them.
 */
object EmojiData {
    @Volatile var cats: List<List<String>>? = null
    val toneable = HashSet<String>()                 // emoji that accept a skin tone, including ZWJ sequences
    private val bases = HashSet<Int>()               // code points that take a skin-tone modifier
    private val TONES = arrayOf("", "\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")

    fun ensureLoaded(ctx: Context) {
        if (cats != null) return
        synchronized(this) {
            if (cats != null) return
            val paint = Paint()
            ctx.applicationContext.assets.open("emoji.tsv").bufferedReader().useLines { seq ->
                load(seq) { paint.hasGlyph(it) }
            }
        }
    }

    /** Pure parsing step (also used by the JVM tests). Lines: group TAB emoji TAB toneFlag. */
    fun load(lines: Sequence<String>, canDraw: (String) -> Boolean) {
        val lists = List(9) { ArrayList<String>() }
        val tone = ArrayList<String>()
        for (l in lines) {
            val p = l.split('\t')
            if (p.size < 3) continue
            val e = p[1]
            if (!canDraw(e)) continue
            lists[p[0].toInt()].add(e)
            if (p[2] == "1") tone.add(e)
        }
        // a single emoji that takes a tone (👋, ☝️, 👩 ...) tells us which code points are modifier bases
        for (e in tone) if (e.codePointCount(0, e.length) <= 2 && e.indexOf('\u200D') < 0) bases.add(e.codePointAt(0))
        toneable.addAll(tone)
        cats = lists
    }

    /**
     * Applies a skin tone (1..5). Works for sequences too: 👩‍⚕️ -> 👩🏽‍⚕️, 👩‍❤️‍👨 -> 👩🏽‍❤️‍👨🏽, 🏃‍♀️ -> 🏃🏽‍♀️.
     * The modifier goes after each person; the handshake inside "people holding hands" stays untoned.
     */
    fun withTone(e: String, tone: Int): String {
        if (tone !in 1..5 || e !in toneable) return e
        val sb = StringBuilder(e.length + 8)
        var i = 0
        while (i < e.length) {
            val cp = e.codePointAt(i)
            sb.appendCodePoint(cp)
            val isBase = cp in bases && !(cp == 0x1F91D && i > 0)
            i += Character.charCount(cp)
            if (isBase) {
                sb.append(TONES[tone])
                if (i < e.length && e[i] == '\uFE0F') i++ // the modifier replaces the variation selector
            }
        }
        return sb.toString()
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("emoji", Context.MODE_PRIVATE)

    fun recents(ctx: Context): List<String> =
        (sp(ctx).getString("recent", "") ?: "").split(' ').filter { it.isNotEmpty() }

    fun addRecent(ctx: Context, e: String) {
        val l = recents(ctx).toMutableList()
        l.remove(e)
        l.add(0, e)
        sp(ctx).edit().putString("recent", l.take(32).joinToString(" ")).apply()
    }

    fun clearRecents(ctx: Context) { sp(ctx).edit().remove("recent").apply() }
}
