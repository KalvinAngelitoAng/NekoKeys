package com.nekokeys.keyboard

import android.graphics.Color

fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
)

/** A theme is just three colors; every other surface is derived, so any custom color combo looks coherent. */
class KbTheme(val name: String, val bg: Int, val text: Int, val accent: Int) {
    constructor(name: String, bg: String, text: String, accent: String) :
        this(name, Color.parseColor(bg), Color.parseColor(text), Color.parseColor(accent))

    val key = mix(bg, text, 0.10f)
    val keyAlt = mix(bg, text, 0.05f)
    val pressed = mix(bg, text, 0.22f)
    val pill = mix(bg, text, 0.17f)
    val dim = (text and 0x00FFFFFF) or 0x99000000.toInt()
}

/**
 * Default look = Keqing (Genshin Impact), read from her character art, not an official swatch:
 *  - near-black violet like her dress, lavender-white like her lace and ribbon
 *  - electro-violet accent like her hair and the Electro vision
 * Every other surface (keys, pills, pressed state) is derived from these three colors.
 */
val THEMES = listOf(
    KbTheme("Keqing", "#181321", "#EFE8FF", "#B691FF"),
    KbTheme("Lilac", "#F4EEFF", "#2A1D45", "#8E5CF0"),
    KbTheme("Electro", "#0C0814", "#F5EAFF", "#D376FD"),
    KbTheme("Classic", "#202124", "#E8EAED", "#A8C7FA"),
    KbTheme("AMOLED", "#000000", "#FFFFFF", "#00E5A8"),
    KbTheme("Sakura", "#FFF1F4", "#3B2630", "#E91E63"),
    KbTheme("Nord", "#2E3440", "#ECEFF4", "#88C0D0")
)

val DEFAULT_ACCENT = Color.parseColor("#B691FF")

val ACCENTS = listOf("#B691FF", "#D376FD", "#F2A5D0", "#8E5CF0", "#A8C7FA", "#00E5A8", "#FFB300", "#FF5252")
    .map { Color.parseColor(it) }

/** prefs.theme == THEMES.size means "custom colors". An accent override applies to any theme. */
fun themeOf(p: Prefs): KbTheme {
    val base = if (p.theme in THEMES.indices) THEMES[p.theme] else KbTheme("Custom", p.customBg, p.customText, DEFAULT_ACCENT)
    return if (p.accent != 0) KbTheme(base.name, base.bg, base.text, p.accent) else base
}
