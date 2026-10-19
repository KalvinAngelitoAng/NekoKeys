package com.nekokeys.keyboard

import android.content.Context

/** All settings live in app-private SharedPreferences. Nothing leaves the device. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nekokeys", Context.MODE_PRIVATE)

    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var theme: Int
        get() = i("theme", 0)
        set(v) = put("theme", v)
    var accent: Int // 0 = use theme accent
        get() = i("accent", 0)
        set(v) = put("accent", v)
    var keyHeight: Int
        get() = i("keyHeight", 54)
        set(v) = put("keyHeight", v)
    var corner: Int
        get() = i("corner", 10)
        set(v) = put("corner", v)
    var language: Int // 0 = English, 1 = Indonesia, 2 = both
        get() = i("language", 2)
        set(v) = put("language", v)
    var haptics: Boolean
        get() = b("haptics", true)
        set(v) = put("haptics", v)
    var numberRow: Boolean
        get() = b("numberRow", true)
        set(v) = put("numberRow", v)
    var popups: Boolean
        get() = b("popups", true)
        set(v) = put("popups", v)
    var suggestions: Boolean
        get() = b("suggestions", true)
        set(v) = put("suggestions", v)
    var autoCorrect: Boolean
        get() = b("autoCorrect", true)
        set(v) = put("autoCorrect", v)
    var learning: Boolean
        get() = b("learning", true)
        set(v) = put("learning", v)
    var incognito: Boolean
        get() = b("incognito", false)
        set(v) = put("incognito", v)
    var doubleSpace: Boolean
        get() = b("doubleSpace", true)
        set(v) = put("doubleSpace", v)

    var oneHand: Boolean
        get() = b("oneHand", false)
        set(v) = put("oneHand", v)
    var oneHandRight: Boolean
        get() = b("oneHandRight", true)
        set(v) = put("oneHandRight", v)
    var oneHandWidth: Int // percent of screen width
        get() = i("oneHandWidth", 82)
        set(v) = put("oneHandWidth", v)
    var clipboard: Boolean
        get() = b("clipboard", true)
        set(v) = put("clipboard", v)
    var clipTtl: Int // index into ClipStore.TTLS
        get() = i("clipTtl", 1)
        set(v) = put("clipTtl", v)
    var screenshots: Boolean
        get() = b("screenshots", false)
        set(v) = put("screenshots", v)

    var customBg: Int
        get() = i("customBg", 0xFF181321.toInt())
        set(v) = put("customBg", v)
    var customText: Int
        get() = i("customText", 0xFFEFE8FF.toInt())
        set(v) = put("customText", v)
    var wallpaper: Boolean
        get() = b("wallpaper", false)
        set(v) = put("wallpaper", v)
    var wallpaperDim: Int
        get() = i("wallpaperDim", 45)
        set(v) = put("wallpaperDim", v)
    var borderless: Boolean
        get() = b("borderless", true)
        set(v) = put("borderless", v)
    var glide: Boolean
        get() = b("glide", true)
        set(v) = put("glide", v)
    var skinTone: Int
        get() = i("skinTone", 0)
        set(v) = put("skinTone", v)
}
