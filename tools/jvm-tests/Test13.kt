/** Emoji grid layout: every category starts on its own row under its own title (the "merged recents tab" bug). */
import com.nekokeys.keyboard.EmojiLines

fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) System.exit(1) }

fun main() {
    val cell = 46f; val titleH = 32f; val cols = 8
    // recents(5) + smileys(150) + people(80) + animals(60)
    val starts = intArrayOf(0, 5, 155, 235)
    val total = 295
    val L = EmojiLines(cell, titleH)
    L.build(total, cols, starts) { true }

    // layout: title, recents row | title, 19 rows smileys | title, 10 rows | title, 8 rows
    check("line 0 is the 'Recent' title (no emoji)", L.label[0] == 0 && L.len[0] == 0)
    check("line 1 = recents row with exactly 5 emoji", L.label[1] == -1 && L.first[1] == 0 && L.len[1] == 5)
    check("line 2 = SMILEYS title, starts at item 5", L.label[2] == 1 && L.first[2] == 5)
    check("line 3 = first smileys row starts at item 5 (not mixed with recents)", L.first[3] == 5 && L.len[3] == 8)
    check("tops accumulate: title 32 + row 46", L.top[1] == 32f && L.top[2] == 78f && L.top[3] == 110f)

    // the original bug: clicking the Smileys tab then highlighting
    fun catOf(first: Int): Int { var c = 0; for (k in starts.indices) if (starts[k] <= first) c = k; return c }
    for (k in 1..3) {
        val line = L.lineOfItem(starts[k])
        check("tab $k scrolls to its title line", L.label[line] == k)
        val shownFirst = L.first[L.lineAt(L.top[line])]       // what onScrolled would report
        check("tab $k stays highlighted after scrolling (was the bug)", catOf(shownFirst) == k)
    }
    // tab 0 (recents) -> the very top
    check("recents tab -> line 0", L.lineOfItem(0) == 0 && L.top[0] == 0f)

    // an ordinary item mid-section scrolls to its own row, not the title
    val mid = L.lineOfItem(5 + 20)
    check("mid-section item -> its row", L.label[mid] == -1 && L.first[mid] <= 25 && 25 < L.first[mid] + L.len[mid])

    // hit testing equivalent: lineAt on positions
    check("y inside title -> title line", L.label[L.lineAt(10f)] == 0)
    check("y inside first row", L.lineAt(40f) == 1)
    check("y at exact boundary goes to next line", L.lineAt(78f) == 2)
    check("totalH = titles + rows", L.totalH == 4 * titleH + (1 + 19 + 10 + 8) * cell)

    // last row of each section is short, never mixed
    var sum = 0; for (i in 0 until L.count) sum += L.len[i]
    check("every item is drawn exactly once ($sum)", sum == total)

    // recents empty: only 3 sections, no 'Recent' title
    val L2 = EmojiLines(cell, titleH)
    L2.build(290, cols, intArrayOf(0, 150, 230)) { true }
    check("no recents: first line is the Smileys title", L2.label[0] == 0 && L2.first[0] == 0)
    // recents exactly 8 (full row) -> no empty line
    val L3 = EmojiLines(cell, titleH)
    L3.build(8 + 16, cols, intArrayOf(0, 8)) { true }
    check("recents=8: one row, then title, then 2 rows", L3.len[1] == 8 && L3.label[2] == 1 && L3.count == 1 + 1 + 1 + 2)

    // no titles (search results): plain rows, section 0 only
    val L4 = EmojiLines(cell, titleH)
    L4.build(20, cols, intArrayOf(0)) { false }
    check("search: 3 plain rows 8/8/4", L4.count == 3 && L4.len[0] == 8 && L4.len[2] == 4 && L4.label.all { it == -1 })
    // empty list
    val L5 = EmojiLines(cell, titleH); L5.build(0, cols, intArrayOf(0)) { false }
    check("empty list is safe", L5.count == 0 && L5.lineAt(100f) == 0 && L5.lineOfItem(3) == 0 && L5.totalH == 0f)
    // cols = 1 (width not measured yet) must not crash
    val L6 = EmojiLines(cell, titleH); L6.build(total, 1, starts) { true }
    check("cols=1 is safe", L6.count > 0)
    // stale starts bigger than item count (emoji not loaded yet) are ignored
    val L7 = EmojiLines(cell, titleH); L7.build(5, cols, intArrayOf(0, 5, 5, 5)) { true }
    check("empty/stale sections skipped", L7.count == 2 && L7.label[0] == 0 && L7.len[1] == 5)
    println("ALL LAYOUT TESTS PASSED")
}
