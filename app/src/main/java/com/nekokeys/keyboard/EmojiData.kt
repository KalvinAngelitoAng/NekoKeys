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

    // ---------- search (names + keywords, English and Indonesian) ----------

    /** One searchable emoji. nameWords come from the official names, keyWords from the CLDR keywords. */
    private class Named(val emoji: String, val nameWords: List<String>, val keyWords: List<String>) {
        /** Lower = better match; -1 = no match. */
        fun score(t: String): Int {
            if (t in nameWords) return 0
            if (t in keyWords) return 2
            if (nameWords.any { it.startsWith(t) }) return 3
            if (keyWords.any { it.startsWith(t) }) return 5
            if (t.length >= 3 && nameWords.any { it.contains(t) }) return 8
            return -1
        }
    }

    @Volatile private var named: List<Named>? = null

    /** Loads assets/emoji_names.tsv (emoji TAB names TAB keywords). Safe to call from a background thread. */
    fun ensureNamesLoaded(ctx: Context) {
        if (named != null) return
        ensureLoaded(ctx)
        val lines = try {
            ctx.applicationContext.assets.open("emoji_names.tsv").bufferedReader().use { it.readLines() }
        } catch (e: Exception) { emptyList() }
        loadNames(lines.asSequence())
    }

    /** Pure parsing step (also used by the JVM tests). Only emoji this phone can draw are searchable. */
    fun loadNames(lines: Sequence<String>) {
        val drawable = HashSet<String>()
        cats?.forEach { drawable.addAll(it) }
        val out = ArrayList<Named>(2000)
        fun words(t: String) = t.split(' ', ':', '|').filter { it.isNotEmpty() }
        for (l in lines) {
            val p = l.split('\t')
            if (p.size < 3 || (drawable.isNotEmpty() && p[0] !in drawable)) continue
            out.add(Named(p[0], words(p[1]), words(p[2])))
        }
        named = out
    }

    fun namesReady() = named != null

    // ---------- emoji suggestion for a typed word ----------

    @Volatile private var wordEmoji: Map<String, String>? = null

    /** Loads assets/emoji_words.tsv (word TAB emoji). Safe to call from a background thread; cheap once loaded. */
    fun ensureWordsLoaded(ctx: Context) {
        if (wordEmoji != null) return
        ensureLoaded(ctx)
        val lines = try {
            ctx.applicationContext.assets.open("emoji_words.tsv").bufferedReader().use { it.readLines() }
        } catch (e: Exception) { emptyList() }
        loadWords(lines.asSequence())
    }

    /** Pure parsing step (also used by the JVM tests). Only emoji this phone can draw are offered. */
    fun loadWords(lines: Sequence<String>) {
        val drawable = HashSet<String>()
        cats?.forEach { drawable.addAll(it) }
        val m = HashMap<String, String>(1200)
        for (l in lines) {
            val p = l.split('\t')
            if (p.size == 2 && (drawable.isEmpty() || p[1] in drawable)) m[p[0]] = p[1]
        }
        wordEmoji = m
    }

    fun wordsReady() = wordEmoji != null

    /** The emoji to offer after the (lowercase) [word] was typed, or null. */
    fun emojiFor(word: String): String? = wordEmoji?.get(word)

    /** Frees the search index; the panel calls this when it closes. */
    fun releaseNames() { named = null }

    /** Emoji whose name or keywords match every word of [query] (prefix match), best matches first. */
    fun search(query: String, limit: Int = 150): List<String> {
        val list = named ?: return emptyList()
        val toks = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val hits = ArrayList<IntArray>()          // [score, index in list]
        for (i in list.indices) {
            var total = 0
            var ok = true
            for (t in toks) {
                val sc = list[i].score(t)
                if (sc < 0) { ok = false; break }
                total += sc
            }
            if (ok) hits.add(intArrayOf(total, i))
        }
        hits.sortWith(compareBy({ it[0] }, { it[1] }))
        return hits.take(limit).map { list[it[1]].emoji }
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
