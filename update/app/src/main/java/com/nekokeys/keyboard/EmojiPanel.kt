package com.nekokeys.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Emoji | Kaomoji | GIF & sticker collection. Replaces the keyboard area while open. */
class EmojiPanel(private val ctx: Context, private val host: Host) : LinearLayout(ctx) {

    interface Host {
        fun onEmojiText(s: String)
        fun onPanelDelete()
        fun onPasteImage(uri: Uri, mime: String): Boolean
        fun onPickMedia()
        fun onClosePanel()
        /** Search opened (true) or closed (false). While open, the host sends typed keys to [searchChar]. */
        fun onEmojiSearch(active: Boolean)
    }

    private val KAOMOJI = listOf(
        "(^_^)", "¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻", "(｡♥‿♥｡)", "(づ｡◕‿‿◕｡)づ", "ಠ_ಠ", "(ノಠ益ಠ)ノ", "(☞ﾟヮﾟ)☞",
        "(•‿•)", "(T_T)", "(≧▽≦)", "(*^▽^*)", "(¬_¬)", "(⌐■_■)", "ʕ•ᴥ•ʔ", "(=^･ω･^=)", "♪(´▽｀)",
        "(ง •̀_•́)ง", "(｡•́︿•̀｡)", "(´• ω •`)", "orz", "<(‾︶‾)>", "(☆▽☆)", "(*´▽`*)", "\\(^o^)/",
        "( ˘▽˘)っ♨", "(`･ω･´)", "(^_−)−☆", "(ᵔᴥᵔ)", "(つ✧ω✧)つ"
    )

    private val indo = ctx.resources.configuration.locales[0].language == "id"
    // titles above each section: index 0 = recents, 1..9 = the nine Unicode groups (same order as catStart)
    private val CAT_LABELS = if (indo)
        arrayOf("Emoji terbaru", "Senyum dan emosi", "Orang", "Hewan dan alam", "Makanan dan minuman",
            "Perjalanan dan tempat", "Aktivitas", "Objek", "Simbol", "Bendera")
    else
        arrayOf("Recent emoji", "Smileys and emotions", "People", "Animals and nature", "Food and drink",
            "Travel and places", "Activities", "Objects", "Symbols", "Flags")

    private var theme = THEMES[0]
    private var transparent = false
    private var mode = 0 // 0 emoji, 1 kaomoji, 2 collection
    private var curCat = 1                 // category the list is currently scrolled to
    private val catStart = IntArray(10)   // index in the full list where each category begins
    private var resetScroll = true
    private val prefs = Prefs(ctx)
    private val ui = Handler(Looper.getMainLooper())
    private val grid = EmojiGridView(ctx)
    private val catBar = LinearLayout(ctx)
    private val catIcons = ArrayList<IconView>()
    private val kaoScroll = ScrollView(ctx)
    private val kaoGrid = GridLayout(ctx)
    private val colScroll = ScrollView(ctx)
    private val colGrid = GridLayout(ctx)
    private val toneBar = LinearLayout(ctx)
    private val bottom = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }

    // search: a query box on top, results in the same grid; typing comes from the keyboard shown below the panel
    private var searching = false
    private val query = StringBuilder()
    private val searchBar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private val searchBack = IconView(ctx, IconView.BACK)
    private val searchBox = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val searchGlass = IconView(ctx, IconView.SEARCH)
    private val queryView = TextView(ctx)
    private val searchClear = IconView(ctx, IconView.CLOSE)
    private val searchEmpty = TextView(ctx)
    private val searchOpen = IconView(ctx, IconView.SEARCH)
    private val abc = TextView(ctx)
    private val tabEmoji = IconView(ctx, IconView.FACE)
    private val tabKao = TextView(ctx)
    private val tabGif = TextView(ctx)
    private val del = IconView(ctx, IconView.BACKSPACE)
    private var shownBases: List<String> = emptyList()
    private var delRun: Runnable? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL

        searchBack.setOnClickListener { exitSearch() }
        searchBar.addView(searchBack, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        searchGlass.isClickable = false
        searchBox.addView(searchGlass, LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
        queryView.apply { textSize = 16f; maxLines = 1; gravity = Gravity.CENTER_VERTICAL }
        searchBox.addView(queryView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        searchClear.setOnClickListener { query.setLength(0); buildSearch() }
        searchBox.addView(searchClear, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        searchBar.addView(searchBox, LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(8) })
        addView(searchBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        searchOpen.setOnClickListener { startSearch() }
        catBar.addView(searchOpen, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        for (i in 0..9) {
            val v = IconView(ctx, IconView.CAT + i)
            v.setOnClickListener { curCat = i; paintCats(); grid.scrollToIndex(catStart[i]) }
            catIcons.add(v)
            catBar.addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(catBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        val content = FrameLayout(ctx)
        content.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        searchEmpty.apply { gravity = Gravity.CENTER; textSize = 14f; visibility = View.GONE }
        content.addView(searchEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        kaoGrid.columnCount = 2
        kaoScroll.addView(kaoGrid)
        content.addView(kaoScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        colGrid.columnCount = 3
        colScroll.addView(colGrid)
        content.addView(colScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        toneBar.orientation = HORIZONTAL
        toneBar.visibility = View.GONE
        content.addView(toneBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        abc.apply { text = "ABC"; textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setOnClickListener { host.onClosePanel() } }
        bottom.addView(abc, LayoutParams(dp(64), dp(34)).apply { leftMargin = dp(8); rightMargin = dp(8) })
        tabEmoji.setOnClickListener { mode = 0; refresh() }
        bottom.addView(tabEmoji, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        listOf(tabKao to "^_^", tabGif to "GIF").forEachIndexed { i, (tv, label) ->
            tv.apply { text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setOnClickListener { mode = i + 1; refresh() } }
            bottom.addView(tv, LayoutParams(dp(56), LayoutParams.MATCH_PARENT))
        }
        bottom.addView(View(ctx), LayoutParams(0, 1, 1f))
        wireDelete()
        bottom.addView(del, LayoutParams(dp(56), LayoutParams.MATCH_PARENT))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        grid.onScrolled = { first ->
            var c = 0
            if (EmojiData.cats != null) for (k in 0..9) if (catStart[k] <= first) c = k
            if (c != curCat) { curCat = c; paintCats() }
        }
        grid.onTouchStart = { toneBar.visibility = View.GONE }
        grid.onPick = { i -> insert(grid.items[i]) }
        grid.onLong = { i -> val b = shownBases.getOrNull(i); if (b != null && b in EmojiData.toneable) showTones(b) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireDelete() {
        del.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    host.onPanelDelete()
                    var interval = 90L
                    val r = object : Runnable {
                        override fun run() {
                            host.onPanelDelete()
                            interval = maxOf(35L, interval - 5)
                            ui.postDelayed(this, interval)
                        }
                    }
                    delRun = r
                    ui.postDelayed(r, 400L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDelete()
            }
            true
        }
    }

    private fun stopDelete() { delRun?.let { ui.removeCallbacks(it) }; delRun = null }

    private fun insert(e: String) {
        host.onEmojiText(e)
        EmojiData.addRecent(ctx, e)
    }

    private fun showTones(b: String) {
        toneBar.removeAllViews()
        toneBar.background = GradientDrawable().apply { setColor(theme.pill); cornerRadius = dp(27).toFloat() }
        toneBar.setPadding(dp(8), 0, dp(8), 0)
        for (t in 0..5) {
            val e = EmojiData.withTone(b, t)
            toneBar.addView(TextView(ctx).apply {
                text = e
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(theme.text)
                setOnClickListener { prefs.skinTone = t; insert(e); toneBar.visibility = View.GONE; refresh() }
            }, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        }
        toneBar.visibility = View.VISIBLE
    }

    fun applyTheme(t: KbTheme, transparentBg: Boolean) { theme = t; transparent = transparentBg }

    fun show() {
        if (EmojiData.cats == null) {
            Thread {
                EmojiData.ensureLoaded(ctx)
                ui.post { if (isAttachedToWindow) refresh() }
            }.start()
        }
        searching = false
        query.setLength(0)
        resetScroll = true
        refresh()
    }

    /** Drops thumbnails and the emoji list so a closed panel holds almost no memory. */
    fun release() {
        stopDelete()
        searching = false
        query.setLength(0)
        EmojiData.releaseNames()
        grid.setItems(emptyList(), false)
        kaoGrid.removeAllViews()
        colGrid.removeAllViews()
        toneBar.visibility = View.GONE
    }

    private fun refresh() {
        setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        searchBar.visibility = if (searching) View.VISIBLE else View.GONE
        bottom.visibility = if (searching) View.GONE else View.VISIBLE
        catBar.visibility = if (mode == 0 && !searching) View.VISIBLE else View.GONE
        grid.visibility = if (mode == 0) View.VISIBLE else View.GONE
        kaoScroll.visibility = if (mode == 1) View.VISIBLE else View.GONE
        colScroll.visibility = if (mode == 2) View.VISIBLE else View.GONE
        paintCats()
        abc.setTextColor(theme.text)
        abc.background = GradientDrawable().apply { setColor(theme.pill); cornerRadius = dp(17).toFloat() }
        tabEmoji.color = if (mode == 0) theme.accent else theme.dim
        tabKao.setTextColor(if (mode == 1) theme.accent else theme.dim)
        tabGif.setTextColor(if (mode == 2) theme.accent else theme.dim)
        del.color = theme.text
        searchBack.color = theme.text
        searchGlass.color = theme.dim
        searchClear.color = theme.dim
        searchOpen.color = theme.dim
        searchBox.background = GradientDrawable().apply { setColor(theme.pill); cornerRadius = dp(18).toFloat() }
        searchEmpty.setTextColor(theme.dim)
        when (mode) {
            0 -> if (searching) buildSearch() else buildEmoji()
            1 -> buildKaomoji()
            else -> buildCollection()
        }
    }

    private fun paintCats() {
        catIcons.forEachIndexed { i, v -> v.color = if (i == curCat) theme.accent else theme.dim }
    }

    // ---------- search ----------

    private fun startSearch() {
        searching = true
        query.setLength(0)
        host.onEmojiSearch(true)
        refresh()
        Thread {
            EmojiData.ensureNamesLoaded(ctx)
            ui.post { if (searching && isAttachedToWindow) buildSearch() }
        }.start()
    }

    private fun exitSearch() {
        if (!searching) return
        searching = false
        query.setLength(0)
        host.onEmojiSearch(false)
        resetScroll = true
        refresh()
    }

    /** Called by the host for every key typed while search is open. */
    fun searchChar(s: String) {
        if (!searching) return
        val c = s.lowercase()
        if (c == " " && (query.isEmpty() || query.last() == ' ')) return
        if (query.length >= 30) return
        query.append(c)
        buildSearch()
    }

    fun searchBackspace() {
        if (!searching || query.isEmpty()) return
        val n = if (query.length >= 2 && Character.isLowSurrogate(query.last())) 2 else 1
        query.setLength(query.length - n)
        buildSearch()
    }

    /** Leaves search (used by Enter and by the keyboard's emoji key). */
    fun closeSearch() = exitSearch()

    private fun buildSearch() {
        val q = query.toString().trim()
        val found = if (q.isEmpty()) EmojiData.recents(ctx) else EmojiData.search(q)
        val tone = prefs.skinTone
        shownBases = found
        queryView.setTextColor(if (query.isEmpty()) theme.dim else theme.text)
        queryView.text = if (query.isEmpty()) (if (indo) "Cari emoji" else "Search emoji") else "$query|"
        searchEmpty.text = if (indo) "Tidak ada hasil" else "No results"
        searchEmpty.visibility = if (q.isNotEmpty() && found.isEmpty() && EmojiData.namesReady()) View.VISIBLE else View.GONE
        grid.textColor = theme.text
        grid.setItems(found.map { EmojiData.withTone(it, tone) }, false)
    }

    /** Recents first, then every emoji in Unicode order, all in one list you can scroll through. */
    private fun buildEmoji() {
        val recents = EmojiData.recents(ctx)
        val tone = prefs.skinTone
        val all = ArrayList<String>(2100)
        val bases = ArrayList<String>(2100)
        val starts = ArrayList<Int>(10)       // sections that actually exist, with their titles
        val titles = ArrayList<String>(10)
        all.addAll(recents)
        bases.addAll(recents)
        catStart[0] = 0
        if (recents.isNotEmpty()) { starts.add(0); titles.add(CAT_LABELS[0]) }
        EmojiData.cats?.forEachIndexed { gi, list ->
            catStart[gi + 1] = all.size
            starts.add(all.size); titles.add(CAT_LABELS[gi + 1])
            for (e in list) { bases.add(e); all.add(EmojiData.withTone(e, tone)) }
        }
        shownBases = bases
        searchEmpty.visibility = View.GONE
        grid.textColor = theme.text
        // every section starts on its own row, under its own title
        grid.setItems(all, !resetScroll, starts.toIntArray(), titles.toTypedArray())
        resetScroll = false
    }

    private fun card(): FrameLayout = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { setColor(theme.key); cornerRadius = dp(12).toFloat() }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun lp() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL),
        GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f)
    ).apply { width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT; setMargins(dp(4), dp(4), dp(4), dp(4)) }

    private fun buildKaomoji() {
        kaoGrid.removeAllViews()
        for (k in KAOMOJI) {
            val f = card()
            f.addView(TextView(ctx).apply {
                text = k
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(theme.text)
                setPadding(dp(8), dp(14), dp(8), dp(14))
            })
            f.setOnClickListener { host.onEmojiText(k) }
            kaoGrid.addView(f, lp())
        }
    }

    private fun buildCollection() {
        colGrid.removeAllViews()
        val plus = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), theme.dim, dp(6).toFloat(), dp(5).toFloat())
            }
            minimumHeight = dp(96)
            addView(IconView(ctx, IconView.PLUS, theme.dim), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            setOnClickListener { host.onPickMedia() }
        }
        colGrid.addView(plus, lp())
        val store = ClipStore.get(ctx)
        for (c in store.all().filter { it.type == 1 && it.pinned }) {
            val f = card()
            f.minimumHeight = dp(96)
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
            Thumbs.loadFile(iv, store.fileOf(c), ui)
            val trash = LinearLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(Color.argb(150, 0, 0, 0)); cornerRadius = dp(14).toFloat() }
                addView(IconView(ctx, IconView.TRASH, Color.WHITE).apply {
                    setOnClickListener { store.remove(c.id); buildCollection() }
                }, LayoutParams(dp(30), dp(30)))
            }
            f.addView(trash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(3), dp(3)) })
            f.setOnClickListener { host.onPasteImage(ClipStore.uriOf(c), ClipStore.mimeOfName(c.data)) }
            colGrid.addView(f, lp())
        }
    }

    override fun onDetachedFromWindow() {
        stopDelete()
        super.onDetachedFromWindow()
    }
}
