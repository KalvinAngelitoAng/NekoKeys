package com.nekokeys.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private val BG = Color.parseColor("#120E1A")
    private val CARD = Color.parseColor("#1B1527")
    private val LINE = Color.parseColor("#2B2240")
    private val TEXT = Color.parseColor("#EFE8FF")
    private val SUB = Color.parseColor("#9A8FB5")
    private val ACC = Color.parseColor("#B691FF")
    private val ON_ACC = Color.parseColor("#1A1028")

    private lateinit var prefs: Prefs
    private lateinit var preview: KeyboardView
    private lateinit var root: LinearLayout
    private lateinit var card: LinearLayout
    private val chips = ArrayList<View>()
    private val swatches = ArrayList<() -> Unit>()
    private var wpPrev: WallpaperDrawable? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(BG)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(36))
        }
        scroll.addView(root)
        setContentView(scroll)

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(IconView(this, IconView.NEKO, ACC), LinearLayout.LayoutParams(dp(64), dp(64)))
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(label("NekoKeys", 26f, TEXT, true))
        ht.addView(label("Keyboard privat bergaya Keqing. Tanpa internet.", 14f, SUB, false))
        head.addView(ht)
        root.addView(head)

        section("Setup")
        newCard()
        button("Aktifkan NekoKeys", true) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("Pilih sebagai keyboard", false) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        section("Coba langsung")
        newCard()
        card.addView(EditText(this).apply {
            hint = "Ketik di sini…"
            setTextColor(TEXT)
            setHintTextColor(SUB)
            background = rounded(BG, 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
        preview = KeyboardView(this)
        preview.apply(prefs)
        card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })

        section("Tema")
        newCard()
        val themeGrid = GridLayout(this).apply { columnCount = 3 }
        for (i in 0..THEMES.size) {
            val chip = ThemeChip(i)
            chips.add(chip)
            themeGrid.addView(chip, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; height = dp(78); setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        card.addView(themeGrid)
        card.addView(label("Warna aksen", 14f, SUB, false).apply { setPadding(0, dp(16), 0, dp(8)) })
        val accRow = LinearLayout(this)
        ACCENTS.forEach { c ->
            val dot = AccentDot(c)
            chips.add(dot)
            accRow.addView(dot, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) })
        }
        card.addView(accRow)
        card.addView(label("Pakai warna bawaan tema", 13f, ACC, false).apply {
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { prefs.accent = 0; refresh() }
        })

        section("Warna bebas")
        newCard()
        card.addView(label("Pilih warna apa saja dengan color wheel atau kode hex.", 13f, SUB, false)
            .apply { setPadding(0, 0, 0, dp(4)) })
        colorRow("Latar keyboard", { themeOf(prefs).bg }) { c -> ensureCustom(); prefs.customBg = c }
        colorRow("Warna huruf", { themeOf(prefs).text }) { c -> ensureCustom(); prefs.customText = c }
        colorRow("Warna aksen (tombol enter, saran)", { themeOf(prefs).accent }) { c -> prefs.accent = c }

        section("Wallpaper")
        newCard()
        button("Pilih foto dari galeri", true) {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 31
            )
        }
        switchRow("Pakai wallpaper", "Foto jadi latar keyboard", { prefs.wallpaper && WallpaperStore.has(this) }) { v ->
            if (v && !WallpaperStore.has(this)) Toast.makeText(this, "Pilih foto dulu", Toast.LENGTH_SHORT).show()
            else prefs.wallpaper = v
        }
        slider("Redupkan wallpaper (%)", 0, 85, { prefs.wallpaperDim }) { prefs.wallpaperDim = it }
        button("Hapus wallpaper", false) {
            prefs.wallpaper = false
            WallpaperStore.clear(this)
            wpPrev?.release()
            wpPrev = null
            refresh()
        }

        section("Bentuk")
        newCard()
        slider("Tinggi tombol", 40, 70, { prefs.keyHeight }) { prefs.keyHeight = it }
        slider("Kelengkungan tombol", 0, 20, { prefs.corner }) { prefs.corner = it }
        switchRow("Border tombol", "Mati = huruf polos seperti Gboard", { !prefs.borderless }) { prefs.borderless = !it }
        switchRow("Baris angka", "Angka di atas huruf", { prefs.numberRow }) { prefs.numberRow = it }
        switchRow("Getaran", "Umpan balik tiap ketukan", { prefs.haptics }) { prefs.haptics = it }
        switchRow("Pratinjau tombol", "Huruf membesar saat ditekan", { prefs.popups }) { prefs.popups = it }

        section("Mengetik")
        newCard()
        segments("Bahasa", listOf("English", "Indonesia", "EN • ID"), { prefs.language }) { prefs.language = it }
        switchRow("Glide typing", "Geser jari melewati huruf untuk mengetik satu kata", { prefs.glide }) { prefs.glide = it }
        switchRow("Saran kata", "Tampil di atas keyboard", { prefs.suggestions }) { prefs.suggestions = it }
        switchRow("Koreksi otomatis", "Backspace langsung membatalkan koreksi", { prefs.autoCorrect }) { prefs.autoCorrect = it }
        segments("Seberapa berani koreksi otomatis", listOf("Hati-hati", "Seimbang", "Berani"), { prefs.acLevel }) { prefs.acLevel = it }
        card.addView(label("Hati-hati hanya mengganti kata kalau sangat yakin. Berani lebih sering mengganti, tapi lebih sering salah. Kalau ragu, kata aslimu tetap dan pilihannya muncul di bar saran.", 12f, SUB, false)
            .apply { setPadding(0, dp(4), 0, dp(4)) })
        switchRow("Saran emoji", "Emoji muncul di bar saran setelah kata seperti \"love\" atau \"kucing\"", { prefs.emojiSuggest }) { prefs.emojiSuggest = it }
        switchRow("Spasi dua kali jadi titik", null, { prefs.doubleSpace }) { prefs.doubleSpace = it }
        card.addView(label("Tip: tahan spasi untuk ganti bahasa, geser di spasi untuk memindah kursor, geser tombol hapus ke kiri untuk menghapus beberapa kata.", 13f, SUB, false)
            .apply { setPadding(0, dp(12), 0, 0) })

        section("Mode satu tangan")
        newCard()
        switchRow("Aktifkan", "Keyboard mengecil ke satu sisi. Bisa juga dari ikon di bar atas.", { prefs.oneHand }) { prefs.oneHand = it }
        switchRow("Posisi di kanan", null, { prefs.oneHandRight }) { prefs.oneHandRight = it }
        slider("Lebar keyboard (%)", 70, 92, { prefs.oneHandWidth }) { prefs.oneHandWidth = it }

        section("Clipboard, emoji dan GIF")
        newCard()
        switchRow("Riwayat clipboard", "Simpan banyak teks dan gambar yang kamu salin", { prefs.clipboard }) { prefs.clipboard = it }
        segments("Hapus otomatis setelah", listOf("1 jam", "1 hari", "1 minggu", "Tidak"), { prefs.clipTtl }) { prefs.clipTtl = it }
        card.addView(label("Item yang disematkan tidak pernah terhapus otomatis.", 12f, SUB, false)
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        switchRow("Screenshot terbaru", "Butuh izin akses foto. Hanya membaca folder Screenshots, tanpa menyalin.",
            { prefs.screenshots && Screenshots.granted(this) }) { v ->
            if (v && !Screenshots.granted(this)) { requestPermissions(arrayOf(Screenshots.permission()), 7) }
            else { prefs.screenshots = v }
        }
        button("Tambah GIF atau stiker ke koleksi", true) {
            startActivity(Intent(this, PickerActivity::class.java))
        }
        button("Hapus emoji terbaru", false) {
            EmojiData.clearRecents(this)
            Toast.makeText(this, "Emoji terbaru dihapus", Toast.LENGTH_SHORT).show()
        }
        button("Hapus semua riwayat clipboard", false) {
            ClipStore.get(this).clearAll()
            Toast.makeText(this, "Riwayat clipboard dihapus", Toast.LENGTH_SHORT).show()
        }

        section("Privasi")
        newCard()
        listOf(
            "Tanpa izin internet. Android memblokir semua data keluar.",
            "Tanpa library pihak ketiga, analytics, atau backup cloud.",
            "Kolom password tidak pernah dipelajari.",
            "Riwayat clipboard hanya di HP ini. Salinan bertanda sensitif dilewati.",
            "GIF hanya dari koleksimu sendiri, tidak ada pencarian online."
        ).forEach { card.addView(checkRow(it)) }
        switchRow("Belajar dari ketikan saya", "Kata baru dan pasangan kata, hanya di HP ini", { prefs.learning }) { prefs.learning = it }
        switchRow("Mode incognito", "Berhenti belajar sementara", { prefs.incognito }) { prefs.incognito = it }
        val statsLabel = label("", 12f, SUB, false).apply { setPadding(0, dp(10), 0, 0) }
        val showStats = {
            val st = Predictor.get(this).stats()
            val kb = st.bytes / 1024.0
            statsLabel.text = String.format(
                "Data belajar saat ini: %d kata baru, %d pasangan kata (%s). Batas maksimal 5.000 kata dan 40.000 pasangan. " +
                "Yang jarang dipakai memudar sendiri, jadi ukurannya tidak akan terus membengkak.",
                st.words, st.pairs, if (kb >= 1024) String.format("%.1f MB", kb / 1024) else String.format("%.0f KB", kb)
            )
        }
        swatches.add(showStats)
        button("Hapus semua data belajar", false) {
            Predictor.get(this).clearAll()
            Toast.makeText(this, "Data belajar dihapus", Toast.LENGTH_SHORT).show()
            showStats()
        }
        card.addView(statsLabel)

        section("Tentang kamus")
        newCard()
        card.addView(label(
            "Kamus Inggris (60 ribu kata) dan Indonesia (50 ribu kata, termasuk bahasa chat seperti gue, nggak, banget) " +
            "disusun dari frekuensi kata subtitle OpenSubtitles (proyek FrequencyWords oleh Hermit Dave, lisensi CC BY-SA 4.0), " +
            "lalu disaring dengan kamus ejaan Hunspell dan daftar KBBI supaya typo dan nama tidak ikut. " +
            "Semua kata dibaca dari dalam aplikasi, tidak ada yang diunduh.", 12f, SUB, false))
        refresh()
    }

    override fun onPause() {
        Predictor.get(this).saveAsync()
        super.onPause()
    }

    override fun onDestroy() {
        wpPrev?.let { preview.background = null; it.release() }
        wpPrev = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 31 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                val ok = WallpaperStore.saveFrom(applicationContext, uri)
                runOnUiThread {
                    if (ok) {
                        prefs.wallpaper = true
                        wpPrev?.let { preview.background = null; it.release() }
                        wpPrev = null
                        refresh()
                        recreate()
                    } else Toast.makeText(this, "Gagal membaca gambar", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7) {
            val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
            prefs.screenshots = ok
            if (!ok) Toast.makeText(this, "Izin ditolak", Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    private fun refresh() {
        preview.apply(prefs)
        preview.langLabel = arrayOf("English", "Indonesia", "EN • ID")[prefs.language.coerceIn(0, 2)]
        if (prefs.wallpaper && WallpaperStore.has(this)) {
            if (wpPrev == null) wpPrev = WallpaperStore.load(this, prefs.wallpaperDim)
            wpPrev?.dim = prefs.wallpaperDim
            preview.background = wpPrev
            preview.transparentBg = wpPrev != null
        } else {
            preview.background = null
            preview.transparentBg = false
            wpPrev?.release()
            wpPrev = null
        }
        chips.forEach { it.invalidate() }
        swatches.forEach { it() }
    }

    /** Editing a custom color switches to the "Kustom" theme, starting from whatever theme is active now. */
    private fun ensureCustom() {
        if (prefs.theme != THEMES.size) {
            val t = themeOf(prefs)
            prefs.customBg = t.bg
            prefs.customText = t.text
            prefs.theme = THEMES.size
        }
    }

    // ---------- color picker ----------

    private fun colorRow(title: String, current: () -> Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        row.addView(label(title, 15f, TEXT, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val hex = label("", 13f, SUB, false).apply { setPadding(0, 0, dp(10), 0) }
        val dot = View(this)
        row.addView(hex)
        row.addView(dot, LinearLayout.LayoutParams(dp(30), dp(30)))
        val update = {
            val c = current()
            hex.text = String.format("#%06X", c and 0xFFFFFF)
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), SUB) }
        }
        swatches.add(update)
        update()
        row.setOnClickListener { colorDialog(title, current()) { c -> onPick(c); refresh() } }
        card.addView(row)
    }

    private fun colorDialog(title: String, initial: Int, onPick: (Int) -> Unit) {
        val wheel = ColorWheelView(this).apply { setColor(initial) }
        val bright = SeekBar(this).apply { max = 100; progress = (wheel.bright * 100).toInt() }
        val hex = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setSingleLine()
        }
        val swatch = View(this)
        var updating = false
        fun syncUi() {
            val c = wheel.getColor()
            updating = true
            hex.setText(String.format("#%06X", c and 0xFFFFFF))
            updating = false
            swatch.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), SUB) }
        }
        wheel.onChange = { bright.progress = (wheel.bright * 100).toInt(); syncUi() }
        bright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) { wheel.bright = v / 100f; syncUi() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (updating) return
                val t = e.toString().trim().removePrefix("#")
                if (t.length == 6) try {
                    wheel.setColor(Color.parseColor("#$t"))
                    bright.progress = (wheel.bright * 100).toInt()
                    swatch.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(wheel.getColor()); setStroke(dp(1), SUB) }
                } catch (ex: IllegalArgumentException) { }
            }
        })
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(swatch, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })
        bottom.addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
            addView(label("Terang", 13f, SUB, false).apply { setPadding(0, dp(8), 0, 0) })
            addView(bright)
            addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        syncUi()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Pilih") { _, _ -> onPick(wheel.getColor()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- small UI kit ----------

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(r)
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun section(t: String) {
        root.addView(label(t.uppercase(), 12f, ACC, true).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), dp(26), 0, dp(8))
        })
    }

    private fun newCard() {
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 18f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(card)
    }

    private fun button(t: String, primary: Boolean, onClick: () -> Unit) {
        card.addView(Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) ON_ACC else TEXT)
            background = rounded(if (primary) ACC else LINE, 12f)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
    }

    private fun switchRow(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, TEXT, false))
        if (sub != null) texts.addView(label(sub, 12f, SUB, false))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = get()
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACC, LINE)
            )
            setOnCheckedChangeListener { _, v -> set(v); refresh() }
        })
        card.addView(row)
    }

    private fun slider(title: String, lo: Int, hi: Int, get: () -> Int, set: (Int) -> Unit) {
        val head = LinearLayout(this).apply { setPadding(0, dp(6), 0, 0) }
        head.addView(label(title, 15f, TEXT, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = label("${get()}", 14f, SUB, false)
        head.addView(value)
        card.addView(head)
        card.addView(SeekBar(this).apply {
            max = hi - lo
            progress = get() - lo
            progressTintList = ColorStateList.valueOf(ACC)
            thumbTintList = ColorStateList.valueOf(ACC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    set(v + lo)
                    value.text = "${v + lo}"
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun segments(title: String, opts: List<String>, get: () -> Int, set: (Int) -> Unit) {
        card.addView(label(title, 14f, SUB, false).apply { setPadding(0, dp(12), 0, dp(8)) })
        val row = LinearLayout(this)
        val btns = ArrayList<Button>()
        fun paint() {
            btns.forEachIndexed { i, b ->
                b.background = rounded(if (i == get()) ACC else LINE, 12f)
                b.setTextColor(if (i == get()) ON_ACC else TEXT)
            }
        }
        opts.forEachIndexed { i, n ->
            val b = Button(this).apply {
                text = n
                isAllCaps = false
                textSize = 13f
                setOnClickListener { set(i); paint() }
            }
            btns.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = if (i < opts.size - 1) dp(6) else 0 })
        }
        paint()
        card.addView(row)
    }

    private fun checkRow(t: String): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(IconView(this, IconView.CHECK, ACC), LinearLayout.LayoutParams(dp(36), dp(36)))
        row.addView(label(t, 14f, TEXT, false))
        return row
    }

    private inner class ThemeChip(val idx: Int) : View(this@SettingsActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        init { setOnClickListener { prefs.theme = idx; refresh() } }

        private fun themeFor(): KbTheme =
            if (idx < THEMES.size) THEMES[idx] else KbTheme("Kustom", prefs.customBg, prefs.customText, themeOf(prefs).accent)

        override fun onDraw(c: Canvas) {
            val t = themeFor()
            val rad = dpf(14f)
            r.set(dpf(1f), dpf(1f), width - dpf(1f), height - dpf(1f))
            p.style = Paint.Style.FILL
            p.color = t.bg
            c.drawRoundRect(r, rad, rad, p)
            p.color = t.pill
            val kw = (width - dpf(28f)) / 3f
            for (i in 0..2) {
                r.set(dpf(8f) + i * (kw + dpf(6f)), dpf(10f), dpf(8f) + i * (kw + dpf(6f)) + kw, dpf(26f))
                c.drawRoundRect(r, dpf(8f), dpf(8f), p)
            }
            p.color = t.accent
            r.set(width - dpf(8f) - kw, dpf(31f), width - dpf(8f), dpf(45f))
            c.drawRoundRect(r, dpf(7f), dpf(7f), p)
            p.color = t.text
            p.textSize = dpf(11f)
            p.textAlign = Paint.Align.CENTER
            c.drawText(t.name, width / 2f, height - dpf(9f), p)
            p.style = Paint.Style.STROKE
            val sel = prefs.theme == idx
            p.strokeWidth = dpf(if (sel) 2.5f else 1f)
            p.color = if (sel) ACC else LINE
            r.set(dpf(1f), dpf(1f), width - dpf(1f), height - dpf(1f))
            c.drawRoundRect(r, rad, rad, p)
        }
    }

    private inner class AccentDot(val color: Int) : View(this@SettingsActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { setOnClickListener { prefs.accent = color; refresh() } }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            p.style = Paint.Style.FILL
            p.color = color
            c.drawCircle(cx, cy, width / 2f - dpf(5f), p)
            if (prefs.accent == color) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dpf(2f)
                p.color = TEXT
                c.drawCircle(cx, cy, width / 2f - dpf(1.5f), p)
            }
        }
    }
}
