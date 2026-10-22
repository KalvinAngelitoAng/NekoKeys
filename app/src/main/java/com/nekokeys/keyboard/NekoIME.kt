package com.nekokeys.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.util.Size
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NekoIME : InputMethodService(), KeyboardView.Listener, ClipPanel.Host, EmojiPanel.Host {

    private class Correction(val original: String, val fixed: String, val sep: String)
    /** [literalIdx]: slot that shows the typed word in quotes (what space will keep), or -1. [emoji]: emoji offered in the last slot. */
    private class Sugg(val vals: Array<String?>, val toolbar: Boolean, val secure: Boolean, val literalIdx: Int, val emoji: String? = null)
    private var emojiSlot = -1
    private fun acTau() = Tune.levelTau[prefs.acLevel.coerceIn(0, Tune.levelTau.size - 1)]

    private val langNames = arrayOf("English", "Indonesia", "EN • ID")

    private lateinit var prefs: Prefs
    private lateinit var predictor: Predictor
    private lateinit var theme: KbTheme
    private lateinit var root: LinearLayout
    private lateinit var column: LinearLayout
    private lateinit var side: LinearLayout
    private lateinit var bar: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var suggRow: LinearLayout
    private lateinit var kb: KeyboardView
    private lateinit var clipPanel: ClipPanel
    private lateinit var emojiPanel: EmojiPanel
    private lateinit var tGear: IconView
    private lateinit var tClip: IconView
    private lateinit var tOneHand: IconView
    private lateinit var tGhost: IconView
    private lateinit var ghostMini: IconView
    private lateinit var sideSwap: IconView
    private lateinit var sideExpand: IconView
    private lateinit var cm: ClipboardManager
    private val slots = ArrayList<TextView>()
    private val dividers = ArrayList<View>()

    // clipboard pill: the newest copied text / image / screenshot, shown in the bar until used or expired
    private class PillData(val key: String, val time: Long, val text: String?, val uri: Uri?, val mime: String?,
                           val file: File?, val label: String)
    private lateinit var pillRow: FrameLayout
    private lateinit var pill: LinearLayout
    private lateinit var pillIcon: IconView
    private lateinit var pillThumb: ImageView
    private lateinit var pillText: TextView
    private lateinit var pillClose: IconView
    private var pillData: PillData? = null
    private var copiedImage: PillData? = null      // newest image copied to the system clipboard
    private val consumed = HashSet<String>()       // pills already pasted or dismissed
    private var pillSeq = 0
    private var barToolbarMode = true              // true while the bar shows the toolbar (field empty)
    private var shotWatching = false
    private val pillRun = Runnable { refreshPill() }
    private val pillExpireRun = Runnable { setPill(null) }
    private val shotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { ui.removeCallbacks(pillRun); ui.postDelayed(pillRun, 700) }
    }

    // emoji search: while true, typed keys go to the emoji search box instead of the text field
    private var searching = false
    private var searchPrevPage = Page.LETTERS
    private val vals = arrayOfNulls<String>(3)
    private val ui = Handler(Looper.getMainLooper())
    private var exec: ExecutorService? = null
    private var destroyed = false
    private var wp: WallpaperDrawable? = null
    private var wpStamp = 0L

    private val word = StringBuilder()
    private var prevWord = ""
    private var lang = 2
    private var secure = false      // password-like field: no suggestions, no learning
    private var plain = false       // email / url / no-suggestion field: no autocorrect
    private var noLearn = false     // app asked for no personalised learning
    private var capField = false
    private var autoShifted = false
    private var lastCorrection: Correction? = null
    private var spaceFromSuggestion = false

    // glide typing state: after a glide the bar shows alternatives that replace the inserted word
    private var glideActive = false
    private var glideText = ""

    private var selStart = 0
    private var selEnd = 0
    private var swipeAnchor = -1
    private var swipeText: CharSequence = ""
    private var swipeLen = 0
    @Volatile private var seq = 0

    private val syncRun = Runnable { syncFromEditor() }
    private val suggRun = Runnable { requestSuggestions() }
    private var lastClipChange = 0L   // fallback "when was it copied" for phones that report no clip timestamp
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        lastClipChange = System.currentTimeMillis()
        captureClip()
        refreshPill()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        predictor = Predictor.get(this)
        cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener(clipListener)
        exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "nekokeys-predict").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }

    override fun onEvaluateFullscreenMode() = false

    // ---------- view construction ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        kb = KeyboardView(this).apply { listener = this@NekoIME }
        bar = buildBar()
        clipPanel = ClipPanel(this, this)
        clipPanel.visibility = View.GONE
        emojiPanel = EmojiPanel(this, this)
        emojiPanel.visibility = View.GONE
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            // the emoji panel is above the keyboard: during emoji search both are visible (results over keys)
            addView(emojiPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(kb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(clipPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        side = buildSide()
        root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        applyAll()
        return root
    }

    private fun buildBar(): FrameLayout {
        slots.clear()
        dividers.clear()
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun tb(kind: Int, action: () -> Unit): IconView = IconView(this, kind).apply {
            setOnClickListener { action() }
        }.also { toolbar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
        tGear = tb(IconView.GEAR) { openSettings() }
        tClip = tb(IconView.CLIP) { openClip() }
        tOneHand = tb(IconView.ONEHAND) { prefs.oneHand = true; layoutOneHand(); updateBarIcons() }
        tGhost = tb(IconView.GHOST) { prefs.incognito = !prefs.incognito; refreshGhost() }

        suggRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ghostMini = IconView(this, IconView.GHOST).apply { setOnClickListener { prefs.incognito = false; refreshGhost() } }
        suggRow.addView(ghostMini, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT))
        for (i in 0..2) {
            val tv = TextView(this).apply {
                textSize = 17f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { pick(i) }
            }
            slots.add(tv)
            suggRow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            if (i < 2) {
                val d = View(this)
                dividers.add(d)
                suggRow.addView(d, LinearLayout.LayoutParams(dp(1), dp(18)))
            }
        }
        suggRow.visibility = View.GONE

        pillIcon = IconView(this, IconView.CLIP).apply { isClickable = false }
        pillThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            visibility = View.GONE
        }
        pillText = TextView(this).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        pillClose = IconView(this, IconView.CLOSE).apply { setOnClickListener { dismissPill() } }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onPillTap() }
        }
        pill.addView(pillIcon, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        pill.addView(pillThumb, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(8); rightMargin = dp(8) })
        pill.addView(pillText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pill.addView(pillClose, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        pillRow = FrameLayout(this).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
            addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return FrameLayout(this).apply {
            addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(suggRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(pillRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildSide(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        sideSwap = IconView(this, IconView.SWAP).apply {
            setOnClickListener { prefs.oneHandRight = !prefs.oneHandRight; layoutOneHand() }
        }
        sideExpand = IconView(this, IconView.EXPAND).apply {
            setOnClickListener { prefs.oneHand = false; layoutOneHand(); updateBarIcons() }
        }
        col.addView(sideSwap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        col.addView(sideExpand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        return col
    }

    /** One-handed mode: the keyboard column shrinks to one side, a slim control strip fills the rest. */
    private fun layoutOneHand() {
        root.removeAllViews()
        if (!prefs.oneHand) {
            root.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        val screenW = resources.displayMetrics.widthPixels
        val colW = (screenW * prefs.oneHandWidth.coerceIn(60, 95) / 100f).toInt()
        val colLp = LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT)
        val sideLp = LinearLayout.LayoutParams(screenW - colW, ViewGroup.LayoutParams.MATCH_PARENT)
        if (prefs.oneHandRight) { root.addView(side, sideLp); root.addView(column, colLp) }
        else { root.addView(column, colLp); root.addView(side, sideLp) }
    }

    private fun updateBarIcons() {
        tOneHand.visibility = if (prefs.oneHand) View.GONE else View.VISIBLE
        tClip.visibility = if (prefs.clipboard) View.VISIBLE else View.GONE
    }

    private fun refreshGhost() {
        val on = prefs.incognito
        tGhost.color = if (on) theme.accent else theme.text and 0xCCFFFFFF.toInt()
        tGhost.alpha = if (on) 1f else 0.7f
        ghostMini.color = theme.accent
        ghostMini.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun applyAll() {
        val t = themeOf(prefs)
        theme = t
        kb.apply(prefs)
        kb.langLabel = langNames[lang]
        slots.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == 1) t.accent else t.text)
            tv.setTypeface(null, if (i == 1) Typeface.BOLD else Typeface.NORMAL)
        }
        dividers.forEach { it.setBackgroundColor(t.text and 0x33FFFFFF) }
        pill.background = GradientDrawable().apply { setColor(t.pill); cornerRadius = dp(18).toFloat() }
        pillThumb.background = GradientDrawable().apply { setColor(t.key); cornerRadius = dp(6).toFloat() }
        pillText.setTextColor(t.text)
        pillIcon.color = t.accent
        pillClose.color = t.dim
        for (v in listOf(tGear, tClip, tOneHand, sideSwap, sideExpand)) v.color = t.text and 0xCCFFFFFF.toInt()
        applyBackground(t)
        layoutOneHand()
        updateBarIcons()
        refreshGhost()
    }

    /** The wallpaper bitmap is kept only while the keyboard is on screen. */
    private fun applyBackground(t: KbTheme) {
        if (prefs.wallpaper && WallpaperStore.has(this)) {
            val stamp = WallpaperStore.stamp(this)
            if (wp == null || wpStamp != stamp) {
                wp?.let { root.background = ColorDrawable(t.bg); it.release() }
                wp = WallpaperStore.load(this, prefs.wallpaperDim)
                wpStamp = stamp
            }
            wp?.dim = prefs.wallpaperDim
        } else if (wp != null) {
            root.background = ColorDrawable(t.bg)
            wp?.release()
            wp = null
        }
        root.background = wp ?: ColorDrawable(t.bg)
        kb.transparentBg = wp != null
        clipPanel.applyTheme(t, wp != null)
        emojiPanel.applyTheme(t, wp != null)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (::root.isInitialized) {
            root.background = ColorDrawable(theme.bg)
            wp?.release()
            wp = null
            kb.transparentBg = false
        }
        stopShotWatch()
        closePanels()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (::root.isInitialized) applyAll()
    }

    // ---------- input session ----------

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val ei = info ?: return
        val type = ei.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val v = type and InputType.TYPE_MASK_VARIATION
        val isText = cls == InputType.TYPE_CLASS_TEXT
        secure = (isText && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        plain = isText && (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                v == InputType.TYPE_TEXT_VARIATION_URI ||
                (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0)
        noLearn = (ei.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        capField = isText && !secure && !plain
        if (!restarting) lang = prefs.language.coerceIn(0, 2)
        closePanels()
        applyAll()
        kb.page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) Page.NUMPAD else Page.LETTERS
        kb.shift = 0
        autoShifted = false
        lastCorrection = null
        spaceFromSuggestion = false
        glideActive = false
        syncFromEditor()
        startShotWatch()
        refreshPill()
        if (prefs.emojiSuggest && !EmojiData.wordsReady()) Thread { EmojiData.ensureWordsLoaded(this) }.start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopShotWatch()
        closePanels()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        predictor.saveAsync()
        word.setLength(0)
        prevWord = ""
    }

    override fun onDestroy() {
        destroyed = true
        stopShotWatch()
        ui.removeCallbacksAndMessages(null)
        exec?.shutdownNow()
        exec = null
        cm.removePrimaryClipChangedListener(clipListener)
        wp?.release()
        wp = null
        predictor.saveBlocking()
        super.onDestroy()
    }

    override fun onUpdateSelection(os: Int, oe: Int, ns: Int, ne: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce)
        selStart = ns
        selEnd = ne
        if (swipeAnchor < 0) scheduleSync()
    }

    private fun scheduleSync() { ui.removeCallbacks(syncRun); ui.postDelayed(syncRun, 40) }

    // ---------- state helpers ----------

    private fun canLearn() = prefs.learning && !prefs.incognito && !secure && !noLearn
    private fun autoCorrectOn() = prefs.autoCorrect && !secure && !plain
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'

    private fun syncFromEditor() {
        val ic = currentInputConnection ?: return
        parse(ic.getTextBeforeCursor(64, 0) ?: "")
        if (capField && kb.shift != 2) {
            val caps = ic.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
            if (caps && kb.shift == 0) { kb.shift = 1; autoShifted = true }
            else if (!caps && kb.shift == 1 && autoShifted) { kb.shift = 0; autoShifted = false }
        }
        if (!glideActive) updateSuggestions()
    }

    private fun parse(t: CharSequence) {
        var i = t.length
        while (i > 0 && isWordChar(t[i - 1])) i--
        val w = t.subSequence(i, t.length).toString().replace('’', '\'').trimStart('\'')
        word.setLength(0)
        word.append(w)
        var j = i
        var boundary = false
        var steps = 0
        while (j > 0 && !isWordChar(t[j - 1]) && steps < 6) {
            if (t[j - 1] == '.' || t[j - 1] == '!' || t[j - 1] == '?' || t[j - 1] == '\n') boundary = true
            j--
            steps++
        }
        var k = j
        while (k > 0 && isWordChar(t[k - 1])) k--
        prevWord = if (boundary || k == j) "" else t.subSequence(k, j).toString().lowercase()
    }

    // ---------- KeyboardView.Listener ----------

    override fun onShiftTapped() { autoShifted = false }

    /** Swipe left on backspace: live-select N words before the cursor; released = deleted. */
    override fun onDeleteSwipe(words: Int) {
        if (searching) return
        val ic = currentInputConnection ?: return
        if (swipeAnchor < 0) {
            if (selStart != selEnd) return
            swipeAnchor = selStart
            swipeText = ic.getTextBeforeCursor(600, 0) ?: ""
            lastCorrection = null
            spaceFromSuggestion = false
            glideActive = false
        }
        swipeLen = wordsLength(swipeText, words)
        if (swipeLen > 0) ic.setSelection(swipeAnchor - swipeLen, swipeAnchor)
        else ic.setSelection(swipeAnchor, swipeAnchor)
    }

    override fun onDeleteSwipeEnd() {
        if (searching) return
        val ic = currentInputConnection
        if (ic != null && swipeAnchor >= 0) {
            if (swipeLen > 0) ic.commitText("", 1) else ic.setSelection(swipeAnchor, swipeAnchor)
        }
        swipeAnchor = -1
        swipeLen = 0
        word.setLength(0)
        lastCorrection = null
        scheduleSync()
    }

    private fun wordsLength(t: CharSequence, n: Int): Int {
        var i = t.length
        repeat(n) {
            while (i > 0 && !isWordChar(t[i - 1])) i--
            while (i > 0 && isWordChar(t[i - 1])) i--
        }
        return t.length - i
    }

    override fun onChar(s: String) {
        if (searching) emojiPanel.searchChar(s) else typeText(s)
    }

    /** Types [s] into the text field (the normal path; emoji from the panel also come through here). */
    private fun typeText(s: String) {
        val ic = currentInputConnection ?: return
        if (pillData != null) dismissPill()   // typing means the copied text is not wanted
        glideActive = false
        if (s.length == 1 && isWordChar(s[0]) && !(s[0] == '\'' && word.isEmpty())) {
            ic.commitText(s, 1)
            word.append(s)
            lastCorrection = null
            spaceFromSuggestion = false
            updateSuggestions()
            return
        }
        handleSeparator(ic, s)
    }

    private fun handleSeparator(ic: InputConnection, s: String) {
        if (spaceFromSuggestion && s.length == 1 && s[0] in ".,!?;:") ic.deleteSurroundingText(1, 0)
        spaceFromSuggestion = false
        if (s == " " && word.isEmpty() && prefs.doubleSpace) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastCorrection = null
                scheduleSync()
                return
            }
        }
        lastCorrection = null
        if (word.isNotEmpty()) finishWord(ic, s)
        ic.commitText(s, 1)
        if (s != " ") prevWord = if (".!?".contains(s)) "" else prevWord
        updateSuggestions()
        scheduleSync()
    }

    private fun finishWord(ic: InputConnection, sep: String) {
        val w = word.toString()
        var final = w
        if (autoCorrectOn() && !w.endsWith("'")) {
            val fix = predictor.correct(w, prevWord, lang, acTau())
            if (fix != null && fix != w) {
                ic.deleteSurroundingText(w.length, 0)
                ic.commitText(fix, 1)
                final = fix
                lastCorrection = Correction(w, fix, sep)
            }
        }
        if (!secure) predictor.observe(final)
        if (canLearn()) predictor.learn(prevWord, final)
        prevWord = final.lowercase()
        word.setLength(0)
    }

    override fun onDelete() {
        if (searching) { emojiPanel.searchBackspace(); return }
        val ic = currentInputConnection ?: return
        if (glideActive) {
            glideActive = false
            if (glideIntact(ic)) { // backspace right after a glided word removes the whole word
                ic.deleteSurroundingText(glideText.length, 0)
                word.setLength(0)
                spaceFromSuggestion = false
                scheduleSync()
                return
            }
        }
        val lc = lastCorrection
        if (lc != null) { // backspace right after an autocorrect = undo it
            lastCorrection = null
            ic.deleteSurroundingText(lc.fixed.length + lc.sep.length, 0)
            ic.commitText(lc.original, 1)
            predictor.accept(lc.original)
            word.setLength(0)
            word.append(lc.original)
            updateSuggestions()
            scheduleSync()
            return
        }
        spaceFromSuggestion = false
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (word.isNotEmpty()) word.setLength(word.length - 1)
        scheduleSync()
    }

    override fun onEnter() {
        if (searching) { emojiPanel.closeSearch(); return }
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        glideActive = false
        if (word.isNotEmpty()) finishWord(ic, "\n")
        lastCorrection = null
        spaceFromSuggestion = false
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = ei == null || (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
                action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (noAction) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) else ic.performEditorAction(action)
        prevWord = ""
        scheduleSync()
    }

    override fun onCursorMove(delta: Int) {
        if (searching) return
        sendDownUpKeyEvents(if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        lastCorrection = null
        glideActive = false
        word.setLength(0)
        scheduleSync()
    }

    override fun onEmoji() {
        if (searching) { emojiPanel.closeSearch(); return }
        glideActive = false
        openEmoji()
    }

    /** Long-press on space cycles EN • ID -> English -> Indonesia (suggestions and glide follow). */
    override fun onLanguage() {
        lang = (lang + 1) % 3
        prefs.language = lang
        kb.langLabel = langNames[lang]
        updateSuggestions()
    }

    override fun onSwitchKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // ---------- glide typing ----------

    private fun glideIntact(ic: InputConnection): Boolean {
        if (glideText.isEmpty()) return false
        val t = ic.getTextBeforeCursor(glideText.length, 0) ?: return false
        return t.toString() == glideText
    }

    override fun onGlide(xs: FloatArray, ys: FloatArray, n: Int) {
        if (searching) return
        val gxs = xs.copyOf(n)
        val gys = ys.copyOf(n)
        val cx = FloatArray(26)
        val cy = FloatArray(26)
        kb.fillLetterCenters(cx, cy)
        val kw = kb.unitWidth()
        val prev = prevWord
        val l = lang
        val cap = kb.shift != 0
        exec?.execute {
            val res = predictor.glide(gxs, gys, n, cx, cy, kw, l, prev)
            ui.post { if (!destroyed) applyGlide(res, cap) }
        }
    }

    private fun applyGlide(res: List<String>, cap: Boolean) {
        if (res.isEmpty()) return
        val ic = currentInputConnection ?: return
        val alts = res.map { Predictor.properCase(it, "", cap) }
        val best = alts[0]
        if (word.isNotEmpty()) {
            finishWord(ic, " ")
            ic.commitText(" ", 1)
        } else {
            val b = ic.getTextBeforeCursor(1, 0)
            if (b != null && b.isNotEmpty() && (b[0].isLetterOrDigit() || b[0] in ".,!?;:")) ic.commitText(" ", 1)
        }
        ic.commitText("$best ", 1)
        if (!secure) predictor.observe(best)
        if (canLearn()) predictor.learn(prevWord, best)
        prevWord = best.lowercase()
        word.setLength(0)
        lastCorrection = null
        spaceFromSuggestion = true
        glideActive = true
        glideText = "$best "
        ui.removeCallbacks(suggRun)
        seq++
        vals[1] = alts[0]
        vals[0] = alts.getOrNull(1)
        vals[2] = alts.getOrNull(2)
        for (i in 0..2) slots[i].text = vals[i] ?: ""
        toolbar.visibility = View.GONE
        suggRow.visibility = View.VISIBLE
        scheduleSync()
    }

    private fun replaceGlide(ic: InputConnection, s: String) {
        if (!glideIntact(ic)) { glideActive = false; updateSuggestions(); return }
        ic.deleteSurroundingText(glideText.length, 0)
        ic.commitText("$s ", 1)
        glideText = "$s "
        if (canLearn()) { predictor.accept(s); predictor.learn(prevWord, s) }
        prevWord = s.lowercase()
    }

    // ---------- clipboard ----------

    private fun captureClip() {
        if (!prefs.clipboard) return
        val clip = try { cm.primaryClip } catch (e: Exception) { null } ?: return
        if (clip.itemCount == 0) return
        val desc = clip.description
        // password managers flag their clips as sensitive: never store those
        if (desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
        val item = clip.getItemAt(0)
        val store = ClipStore.get(this)
        val uri = item.uri
        if (uri != null && desc != null && desc.mimeTypeCount > 0 && desc.getMimeType(0).startsWith("image/")) {
            val mime = desc.getMimeType(0)
            Thread { store.addImage(uri, mime); ui.post { noteCopiedImage() } }.start()
        } else {
            val t = item.text?.toString() ?: return
            store.addText(t)
        }
        store.expire(ClipStore.TTLS[prefs.clipTtl.coerceIn(0, 3)])
    }

    // ---------- panels ----------

    private fun openClip() { showPanel(clipPanel) { clipPanel.show() } }
    private fun openEmoji() { showPanel(emojiPanel) { emojiPanel.show() } }

    private fun showPanel(p: View, show: () -> Unit) {
        p.layoutParams.height = dp(46) + kb.desiredHeight()
        bar.visibility = View.GONE
        kb.visibility = View.GONE
        clipPanel.visibility = View.GONE
        emojiPanel.visibility = View.GONE
        p.visibility = View.VISIBLE
        show()
        column.requestLayout()
    }

    private fun closePanels() {
        if (!::clipPanel.isInitialized) return
        if (searching) { searching = false; kb.page = searchPrevPage }
        clipPanel.visibility = View.GONE
        emojiPanel.visibility = View.GONE
        bar.visibility = View.VISIBLE
        kb.visibility = View.VISIBLE
        clipPanel.release()
        emojiPanel.release()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun afterPaste() {
        word.setLength(0)
        lastCorrection = null
        spaceFromSuggestion = false
        glideActive = false
        closePanels()
        scheduleSync()
    }

    override fun onClosePanel() = closePanels()

    override fun onPanelDelete() = onDelete()

    override fun onEmojiText(s: String) = typeText(s)

    /** Emoji search opened or closed: show the keyboard under a short results panel, or go back to the full panel. */
    override fun onEmojiSearch(active: Boolean) {
        searching = active
        if (active) {
            searchPrevPage = kb.page
            kb.page = Page.LETTERS
            emojiPanel.layoutParams.height = dp(46) + dp(46) * 2    // query box + two rows of results
            kb.visibility = View.VISIBLE
        } else {
            kb.page = searchPrevPage
            emojiPanel.layoutParams.height = dp(46) + kb.desiredHeight()
            kb.visibility = View.GONE
        }
        column.requestLayout()
    }

    override fun onPickMedia() {
        startActivity(Intent(this, PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onPasteText(s: String) {
        currentInputConnection?.commitText(s, 1)
        afterPaste()
    }

    override fun onPasteImage(uri: Uri, mime: String): Boolean {
        val ic = currentInputConnection ?: return false
        val info = InputContentInfo(uri, ClipDescription("image", arrayOf(mime)))
        val ok = ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        if (ok) afterPaste()
        else Toast.makeText(this, "Aplikasi ini tidak menerima gambar", Toast.LENGTH_SHORT).show()
        return ok
    }

    // ---------- clipboard pill ----------

    private val PILL_TTL_MS = 120_000L   // a copy or screenshot stays offered for two minutes
    private val indo get() = resources.configuration.locales[0].language == "id"

    /** Newest candidates that need no I/O: text on the system clipboard, or an image copied a moment ago. */
    private fun syncPill(now: Long): PillData? {
        if (!prefs.clipboard) return null
        var best: PillData? = null
        val clip = try { cm.primaryClip } catch (e: Exception) { null }
        if (clip != null && clip.itemCount > 0) {
            val desc = clip.description
            val sensitive = desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            val ts = (desc?.timestamp ?: 0L).takeIf { it > 0L } ?: lastClipChange
            val item = clip.getItemAt(0)
            val isImage = item.uri != null && desc != null && desc.mimeTypeCount > 0 && desc.getMimeType(0).startsWith("image/")
            val t = if (!sensitive && !isImage) item.text?.toString() else null
            if (t != null && t.isNotBlank() && t.length <= ClipStore.MAX_TEXT && ts > 0 && now - ts <= PILL_TTL_MS)
                best = PillData("t$ts", ts, t, null, null, null, "")
        }
        val img = copiedImage
        if (img != null && now - img.time <= PILL_TTL_MS && (best == null || img.time > best.time)) best = img
        return best
    }

    /** Looks for the newest thing worth offering and shows it. The screenshot lookup runs off the main thread. */
    private fun refreshPill() {
        if (!::bar.isInitialized || destroyed) return
        ui.removeCallbacks(pillRun)
        val sync = syncPill(System.currentTimeMillis())
        val watch = prefs.screenshots && Screenshots.granted(this)
        val mine = ++pillSeq
        if (!watch) { setPill(sync); return }
        Thread {                                    // its own thread: never queue behind (or in front of) suggestions
            val shot = Screenshots.newest(this)
            ui.post {
                if (destroyed || mine != pillSeq) return@post
                val now = System.currentTimeMillis()
                val s = if (shot != null && now - shot.addedMs <= PILL_TTL_MS)
                    PillData("s${shot.id}", shot.addedMs, null, shot.shot.uri, shot.shot.mime, null, "Screenshot")
                else null
                setPill(if (s != null && (sync == null || s.time > sync.time)) s else sync)
            }
        }.start()
    }

    private fun noteCopiedImage() {
        if (destroyed) return
        val store = ClipStore.get(this)
        val c = store.all().filter { it.type == 1 && !it.pinned }.maxByOrNull { it.time } ?: return
        if (System.currentTimeMillis() - c.time > 15_000L) return
        copiedImage = PillData("i${c.id}", c.time, null, ClipStore.uriOf(c), ClipStore.mimeOfName(c.data),
            store.fileOf(c), if (indo) "Gambar" else "Image")
        refreshPill()
    }

    private fun setPill(d0: PillData?) {
        val d = if (d0 != null && d0.key in consumed) null else d0
        if (d?.key == pillData?.key) { applyPill(); return }
        pillData = d
        ui.removeCallbacks(pillExpireRun)
        pillThumb.setImageDrawable(null)
        if (d != null) {
            if (d.text != null) {
                pillIcon.visibility = View.VISIBLE
                pillThumb.visibility = View.GONE
                pillText.text = d.text.replace('\n', ' ').trim().take(200)
            } else {
                pillIcon.visibility = View.GONE
                pillThumb.visibility = View.VISIBLE
                pillText.text = d.label
                loadPillThumb(d)
            }
            ui.postDelayed(pillExpireRun, (d.time + PILL_TTL_MS - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        applyPill()
    }

    /** The pill takes the toolbar's place while the field is empty; typing or a secure field brings the bar back. */
    private fun applyPill() {
        val show = pillData != null && barToolbarMode
        val pv = if (show) View.VISIBLE else View.GONE
        val tv = if (barToolbarMode && !show) View.VISIBLE else View.GONE
        if (pillRow.visibility != pv) pillRow.visibility = pv      // touching visibility forces a layout pass
        if (toolbar.visibility != tv) toolbar.visibility = tv
    }

    private fun dismissPill() {
        pillData?.let { consumed.add(it.key) }
        if (consumed.size > 40) consumed.clear()
        setPill(null)
    }

    private fun onPillTap() {
        val d = pillData ?: return
        if (d.text != null) {
            dismissPill()
            onPasteText(d.text)
        } else if (d.uri != null && onPasteImage(d.uri, d.mime ?: "image/png")) {
            dismissPill()
        }
    }

    private fun loadPillThumb(d: PillData) {
        Thread {
            val bmp: Bitmap? = try {
                if (d.file != null) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(d.file.path, o)
                    var ss = 1
                    while (o.outWidth / ss > 256) ss *= 2
                    BitmapFactory.decodeFile(d.file.path, BitmapFactory.Options().apply { inSampleSize = ss })
                } else contentResolver.loadThumbnail(d.uri!!, Size(128, 128), null)
            } catch (e: Exception) { null }
            if (bmp != null) ui.post { if (!destroyed && pillData?.key == d.key) pillThumb.setImageBitmap(bmp) }
        }.start()
    }

    /** While the keyboard is on screen (and screenshots are enabled) a new screenshot refreshes the pill. */
    private fun startShotWatch() {
        if (shotWatching || !prefs.screenshots || !Screenshots.granted(this)) return
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, shotObserver)
            shotWatching = true
        } catch (e: Exception) { }
    }

    private fun stopShotWatch() {
        if (!shotWatching) return
        try { contentResolver.unregisterContentObserver(shotObserver) } catch (e: Exception) { }
        shotWatching = false
    }

    // ---------- suggestion bar (computed off the main thread, newest request wins) ----------

    private fun updateSuggestions() { ui.removeCallbacks(suggRun); ui.postDelayed(suggRun, 15) }

    private fun requestSuggestions() {
        if (!::bar.isInitialized || destroyed) return
        val w = word.toString()
        val prev = prevWord
        val l = lang
        val ac = autoCorrectOn()
        val cap = kb.shift != 0
        val sec = secure
        val on = prefs.suggestions
        val tau = acTau()
        val emojiOn = prefs.emojiSuggest && !glideActive
        val mine = ++seq
        exec?.execute {
            if (mine != seq) return@execute          // a newer key press already replaced this request: do not compute it
            val r = computeSugg(w, prev, l, ac, cap, sec, on, tau, emojiOn)
            ui.post { if (!destroyed && mine == seq) showSugg(r) }
        }
    }

    private fun computeSugg(w: String, prev: String, l: Int, ac: Boolean, cap: Boolean, sec: Boolean, on: Boolean,
                            tau: Double, emojiOn: Boolean): Sugg {
        if (sec) return Sugg(arrayOfNulls(3), false, true, -1)
        if (!on || (w.isEmpty() && prev.isEmpty())) return Sugg(arrayOfNulls(3), true, false, -1)
        val unknown = w.isNotEmpty() && !predictor.isKnown(w, l)
        val list = ArrayList<String>()
        var applies = false   // will space replace the typed word by the first entry?
        if (unknown && ac) {
            val fix = predictor.correction(w, prev, l)
            if (fix != null) {
                list.add(fix.word)
                for (o in fix.others) if (o !in list) list.add(o)
                applies = fix.confidence >= tau
            }
        }
        for (s in predictor.suggest(w, prev, l, 3)) {
            val c = Predictor.properCase(s, w, cap)
            if (c !in list && c != w) list.add(c)
        }
        val arr = arrayOfNulls<String>(3)
        if (unknown) {
            // the middle slot always shows what space will do: the correction when we are sure, else the word as typed
            if (applies) { arr[0] = w; arr[1] = list.getOrNull(0); arr[2] = list.getOrNull(1); return Sugg(arr, false, false, 0) }
            arr[0] = list.getOrNull(0); arr[1] = w; arr[2] = list.getOrNull(1)
            return Sugg(arr, false, false, 1)
        }
        arr[1] = list.getOrNull(0); arr[0] = list.getOrNull(1); arr[2] = list.getOrNull(2)
        var emoji: String? = null
        if (emojiOn && w.length >= 2 && EmojiData.wordsReady()) {     // loaded in the background, see onStartInputView
            emoji = EmojiData.emojiFor(w.lowercase())
            if (emoji != null) arr[2] = emoji
        }
        return Sugg(arr, false, false, -1, emoji)
    }

    private fun showSugg(r: Sugg) {
        for (i in 0..2) {
            vals[i] = r.vals[i]
            slots[i].text = if (i == r.literalIdx) "\"${r.vals[i]}\"" else (r.vals[i] ?: "")
        }
        emojiSlot = if (r.emoji != null) 2 else -1
        if (r.secure) slots[1].text = "Kolom aman"
        val showTool = r.toolbar
        suggRow.visibility = if (showTool) View.GONE else View.VISIBLE
        barToolbarMode = showTool
        applyPill()                                   // also decides the toolbar's visibility
    }

    /** The emoji slot was tapped: keep the typed word and add the emoji after it. */
    private fun pickEmoji(ic: InputConnection, e: String) {
        if (word.isNotEmpty()) finishWord(ic, " ")      // learns the word; it is a known word, so nothing is corrected
        ic.commitText(" $e ", 1)
        EmojiData.addRecent(this, e)
        lastCorrection = null
        spaceFromSuggestion = true
        updateSuggestions()
        scheduleSync()
    }

    private fun pick(i: Int) {
        val s = vals[i] ?: return
        val ic = currentInputConnection ?: return
        if (i == emojiSlot && !glideActive) { pickEmoji(ic, s); return }
        if (glideActive) { replaceGlide(ic, s); return }
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$s ", 1)
        if (!secure) predictor.observe(s)
        if (canLearn()) { predictor.accept(s); predictor.learn(prevWord, s) }
        prevWord = s.lowercase()
        word.setLength(0)
        lastCorrection = null
        spaceFromSuggestion = true
        updateSuggestions()
        scheduleSync()
    }
}
