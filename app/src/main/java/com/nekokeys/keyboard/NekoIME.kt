package com.nekokeys.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NekoIME : InputMethodService(), KeyboardView.Listener, ClipPanel.Host, EmojiPanel.Host {

    private class Correction(val original: String, val fixed: String, val sep: String)
    private class Sugg(val vals: Array<String?>, val toolbar: Boolean, val secure: Boolean, val literal: Boolean)

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
    private var seq = 0

    private val syncRun = Runnable { syncFromEditor() }
    private val suggRun = Runnable { requestSuggestions() }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

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
            addView(kb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(clipPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(emojiPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        return FrameLayout(this).apply {
            addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(suggRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
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
        val ic = currentInputConnection ?: return
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
            val fix = predictor.correct(w, prevWord, lang)
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
        sendDownUpKeyEvents(if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        lastCorrection = null
        glideActive = false
        word.setLength(0)
        scheduleSync()
    }

    override fun onEmoji() { glideActive = false; openEmoji() }

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
            Thread { store.addImage(uri, mime) }.start()
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

    override fun onEmojiText(s: String) = onChar(s)

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
        val mine = ++seq
        exec?.execute {
            val r = computeSugg(w, prev, l, ac, cap, sec, on)
            ui.post { if (!destroyed && mine == seq) showSugg(r) }
        }
    }

    private fun computeSugg(w: String, prev: String, l: Int, ac: Boolean, cap: Boolean, sec: Boolean, on: Boolean): Sugg {
        if (sec) return Sugg(arrayOfNulls(3), false, true, false)
        if (!on || (w.isEmpty() && prev.isEmpty())) return Sugg(arrayOfNulls(3), true, false, false)
        val unknown = w.isNotEmpty() && !predictor.isKnown(w, l)
        val list = ArrayList<String>()
        if (unknown && ac) predictor.correct(w, prev, l)?.let { list.add(it) }
        for (s in predictor.suggest(w, prev, l, 3)) {
            val c = Predictor.properCase(s, w, cap)
            if (c !in list && c != w) list.add(c)
        }
        val arr = arrayOfNulls<String>(3)
        if (unknown) { arr[0] = w; arr[1] = list.getOrNull(0); arr[2] = list.getOrNull(1) }
        else { arr[1] = list.getOrNull(0); arr[0] = list.getOrNull(1); arr[2] = list.getOrNull(2) }
        return Sugg(arr, false, false, unknown)
    }

    private fun showSugg(r: Sugg) {
        for (i in 0..2) {
            vals[i] = r.vals[i]
            slots[i].text = if (i == 0 && r.literal) "\"${r.vals[0]}\"" else (r.vals[i] ?: "")
        }
        if (r.secure) slots[1].text = "Kolom aman"
        val showTool = r.toolbar
        toolbar.visibility = if (showTool) View.VISIBLE else View.GONE
        suggRow.visibility = if (showTool) View.GONE else View.VISIBLE
    }

    private fun pick(i: Int) {
        val s = vals[i] ?: return
        val ic = currentInputConnection ?: return
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
