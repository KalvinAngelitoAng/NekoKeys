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
    }

    private val KAOMOJI = listOf(
        "(^_^)", "¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻", "(｡♥‿♥｡)", "(づ｡◕‿‿◕｡)づ", "ಠ_ಠ", "(ノಠ益ಠ)ノ", "(☞ﾟヮﾟ)☞",
        "(•‿•)", "(T_T)", "(≧▽≦)", "(*^▽^*)", "(¬_¬)", "(⌐■_■)", "ʕ•ᴥ•ʔ", "(=^･ω･^=)", "♪(´▽｀)",
        "(ง •̀_•́)ง", "(｡•́︿•̀｡)", "(´• ω •`)", "orz", "<(‾︶‾)>", "(☆▽☆)", "(*´▽`*)", "\\(^o^)/",
        "( ˘▽˘)っ♨", "(`･ω･´)", "(^_−)−☆", "(ᵔᴥᵔ)", "(つ✧ω✧)つ"
    )

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

        for (i in 0..9) {
            val v = IconView(ctx, IconView.CAT + i)
            v.setOnClickListener { curCat = i; paintCats(); grid.scrollToIndex(catStart[i]) }
            catIcons.add(v)
            catBar.addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(catBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        val content = FrameLayout(ctx)
        content.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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

        val bottom = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
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
            for (k in 0..9) if (catStart[k] <= first) c = k
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
        resetScroll = true
        refresh()
    }

    /** Drops thumbnails and the emoji list so a closed panel holds almost no memory. */
    fun release() {
        stopDelete()
        grid.setItems(emptyList(), false)
        kaoGrid.removeAllViews()
        colGrid.removeAllViews()
        toneBar.visibility = View.GONE
    }

    private fun refresh() {
        setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        catBar.visibility = if (mode == 0) View.VISIBLE else View.GONE
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
        when (mode) {
            0 -> buildEmoji()
            1 -> buildKaomoji()
            else -> buildCollection()
        }
    }

    private fun paintCats() {
        catIcons.forEachIndexed { i, v -> v.color = if (i == curCat) theme.accent else theme.dim }
    }

    /** Recents first, then every emoji in Unicode order, all in one list you can scroll through. */
    private fun buildEmoji() {
        val recents = EmojiData.recents(ctx)
        val tone = prefs.skinTone
        val all = ArrayList<String>(2100)
        val bases = ArrayList<String>(2100)
        all.addAll(recents)
        bases.addAll(recents)
        catStart[0] = 0
        EmojiData.cats?.forEachIndexed { gi, list ->
            catStart[gi + 1] = all.size
            for (e in list) { bases.add(e); all.add(EmojiData.withTone(e, tone)) }
        }
        shownBases = bases
        grid.textColor = theme.text
        grid.setItems(all, !resetScroll)
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
