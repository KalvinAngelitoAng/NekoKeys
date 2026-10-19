package com.nekokeys.keyboard

enum class Kind { CHAR, SHIFT, DELETE, SPACE, ENTER, MODE, EMOJI, SPACER }

object Page { const val LETTERS = 0; const val SYM1 = 1; const val SYM2 = 2; const val NUMPAD = 3 }

class Key(
    val label: String,
    val kind: Kind = Kind.CHAR,
    val weight: Float = 1f,
    val alts: List<String> = emptyList(),
    val hint: String? = null,
    val target: Int = 0
) {
    val upper: String = label.uppercase()
    val letter: Boolean = label.length == 1 && label[0] in 'a'..'z'
}

/** Gboard-style pages. Every row sums to a weight of 10. */
object Layouts {
    private val ALTS = mapOf(
        "a" to listOf("à", "á", "â", "ä", "ã", "å", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "c" to listOf("ç"), "n" to listOf("ñ"), "s" to listOf("ß")
    )
    private val SYM = mapOf(
        "(" to listOf("[", "{", "<"), ")" to listOf("]", "}", ">"),
        "-" to listOf("–", "—", "_", "/"), "\$" to listOf("€", "£", "¥", "₹", "₩", "Rp"),
        "\"" to listOf("“", "”", "«", "»"), "'" to listOf("‘", "’"),
        "*" to listOf("†", "‡", "•"), "=" to listOf("≠", "≈", "±"),
        "!" to listOf("¡"), "?" to listOf("¿"), "." to listOf("…", ",", "?", "!", "'", "\"", ":", ";", "-"),
        "," to listOf("…", ".", "?", "!", "'", "\"", ":", ";", "-")
    )

    private fun row(s: String, hints: String? = null) = s.mapIndexed { i, c ->
        val k = c.toString()
        val h = hints?.get(i)?.toString()
        Key(k, alts = (if (h != null) listOf(h) else emptyList()) + (ALTS[k] ?: emptyList()), hint = h)
    }

    private fun sym(s: String) = s.map { Key(it.toString(), alts = SYM[it.toString()] ?: emptyList()) }
    private fun spacer(w: Float) = Key("", Kind.SPACER, w)
    private fun del(w: Float) = Key("", Kind.DELETE, w)
    private fun enter() = Key("", Kind.ENTER, 1.5f)

    fun letters(numberRow: Boolean): List<List<Key>> {
        val rows = ArrayList<List<Key>>()
        if (numberRow) rows.add("1234567890".map { Key(it.toString()) })
        rows.add(row("qwertyuiop", if (numberRow) null else "1234567890"))
        rows.add(listOf(spacer(0.5f)) + row("asdfghjkl", "@#\$_&-+()") + spacer(0.5f))
        rows.add(listOf(Key("", Kind.SHIFT, 1.5f)) + row("zxcvbnm", "*\"':;!?") + del(1.5f))
        rows.add(
            listOf(
                Key("?123", Kind.MODE, 1.5f, target = Page.SYM1),
                Key(",", alts = SYM[","]!!), Key("", Kind.EMOJI),
                Key(" ", Kind.SPACE, 4f), Key(".", alts = SYM["."]!!), enter()
            )
        )
        return rows
    }

    fun symbols1(): List<List<Key>> = listOf(
        "1234567890".map { Key(it.toString()) },
        sym("@#\$_&-+()/"),
        listOf(Key("=\\<", Kind.MODE, 1.5f, target = Page.SYM2)) + sym("*\"':;!?") + del(1.5f),
        listOf(
            Key("ABC", Kind.MODE, 1.5f, target = Page.LETTERS), Key(","),
            Key("1234", Kind.MODE, 1f, target = Page.NUMPAD), Key(" ", Kind.SPACE, 4f), Key("."), enter()
        )
    )

    fun symbols2(): List<List<Key>> = listOf(
        "~`|•√π÷×§Δ".map { Key(it.toString()) },
        sym("€¥\$¢^°={}\\"),
        listOf(Key("?123", Kind.MODE, 1.5f, target = Page.SYM1)) + sym("%©®™✓[]") + del(1.5f),
        listOf(
            Key("ABC", Kind.MODE, 1.5f, target = Page.LETTERS), Key("<"),
            Key("1234", Kind.MODE, 1f, target = Page.NUMPAD), Key(" ", Kind.SPACE, 4f), Key(">"), enter()
        )
    )

    fun numpad(): List<List<Key>> {
        val w = 7f / 3f
        fun d(s: String) = Key(s, weight = w)
        return listOf(
            listOf(Key("+", weight = 1.5f), d("1"), d("2"), d("3"), Key("%", weight = 1.5f)),
            listOf(Key("-", weight = 1.5f, alts = listOf("/", "–")), d("4"), d("5"), d("6"), Key(" ", Kind.SPACE, 1.5f)),
            listOf(Key("*", weight = 1.5f), d("7"), d("8"), d("9"), del(1.5f)),
            listOf(
                Key("ABC", Kind.MODE, 1.5f, target = Page.LETTERS), Key(","),
                Key("!?#", Kind.MODE, 1.3f, target = Page.SYM1), Key("0", weight = 2.7f),
                Key("="), Key("."), enter()
            )
        )
    }
}
