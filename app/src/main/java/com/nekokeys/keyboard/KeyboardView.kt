package com.nekokeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One custom View draws every key. No view-per-key, no allocation in onDraw / touch hot paths,
 * dirty-rect invalidation for key presses, multi-touch aware, glide typing with a fading trail.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onChar(s: String)
        fun onDelete()
        fun onEnter()
        fun onCursorMove(delta: Int)
        fun onEmoji()
        fun onLanguage()
        fun onSwitchKeyboard()
        fun onShiftTapped()
        fun onDeleteSwipe(words: Int)
        fun onDeleteSwipeEnd()
        fun onGlide(xs: FloatArray, ys: FloatArray, n: Int)
    }

    private class Cell(val key: Key, val hit: RectF, val draw: RectF)
    private class Ptr(val id: Int, val startCell: Cell, val x0: Float, val y0: Float) {
        var cell = startCell
        var lastX = x0
        var spaceAcc = 0f
        var consumed = false
        var swiping = false
        var words = 0
        var gliding = false
        var lp: Runnable? = null
        var rep: Runnable? = null
    }

    var listener: Listener? = null
    var shift = 0 // 0 off, 1 once, 2 caps lock
        set(v) { field = v; invalidate() }
    var page = Page.LETTERS
        set(v) { field = v; rebuild() }
    var langLabel = "EN • ID"
        set(v) { field = v; invalidate() }
    var transparentBg = false
        set(v) { field = v; invalidate() }

    private var theme = THEMES[0]
    private var keyDp = 54
    private var cornerDp = 10
    private var haptics = true
    private var popups = true
    private var numberRow = false
    private var borderless = true
    private var glideOn = true
    private var rows: List<List<Key>> = Layouts.letters(false)

    private val dens = resources.displayMetrics.density
    private val cells = ArrayList<Cell>()
    private val ptrs = SparseArray<Ptr>()
    private val ui = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fm = Paint.FontMetrics()
    private val rect = RectF()
    private var lastShiftTap = 0L

    private var popupItems: List<String>? = null
    private var popupPtr = -1
    private var popupSel = 0
    private var popupLeft = 0f
    private var popupItemW = 0f

    // glide typing
    private val maxG = 400
    private val gx = FloatArray(maxG)
    private val gy = FloatArray(maxG)
    private var gn = 0
    private var trailEnd = 0L
    private val trailPath = Path()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init { isHapticFeedbackEnabled = true }

    private fun dp(v: Float) = v * dens
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun apply(p: Prefs) {
        theme = themeOf(p)
        keyDp = p.keyHeight
        cornerDp = p.corner
        haptics = p.haptics
        popups = p.popups
        numberRow = p.numberRow
        borderless = p.borderless
        glideOn = p.glide
        rebuild()
    }

    private fun rebuild() {
        rows = when (page) {
            Page.SYM1 -> Layouts.symbols1()
            Page.SYM2 -> Layouts.symbols2()
            Page.NUMPAD -> Layouts.numpad()
            else -> Layouts.letters(numberRow)
        }
        layoutKeys()
        requestLayout()
        invalidate()
    }

    fun desiredHeight(): Int {
        val maxRows = if (numberRow) 5 else 4
        return (maxRows * dp(keyDp.toFloat()) + dp(6f) * 2).toInt()
    }

    fun unitWidth() = width / 10f

    /** Pixel centre of every letter key a..z (NaN when the current page has no such key). */
    fun fillLetterCenters(xs: FloatArray, ys: FloatArray) {
        java.util.Arrays.fill(xs, Float.NaN)
        java.util.Arrays.fill(ys, Float.NaN)
        for (cell in cells) if (cell.key.letter) {
            val i = cell.key.label[0] - 'a'
            xs[i] = cell.hit.centerX()
            ys[i] = cell.hit.centerY()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), desiredHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutKeys() }

    private fun layoutKeys() {
        cells.clear()
        if (width <= 0) return
        val unit = width / 10f
        val maxRows = if (numberRow) 5 else 4
        val cellH = maxRows * dp(keyDp.toFloat()) / rows.size
        rows.forEachIndexed { r, row ->
            var x = 0f
            val top = dp(6f) + r * cellH
            for (k in row) {
                val w = unit * k.weight
                val hit = RectF(x, top, x + w, top + cellH)
                val d = RectF(hit)
                d.inset(dp(2.5f), dp(3f))
                cells.add(Cell(k, hit, d))
                x += w
            }
        }
    }

    // ---------- drawing ----------

    private fun onColor(bg: Int): Int {
        val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
        return if (lum > 0.6) Color.BLACK else Color.WHITE
    }

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        tp.textSize = sp(size)
        tp.color = color
        tp.getFontMetrics(fm)
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    private fun isDown(cell: Cell): Boolean {
        for (i in 0 until ptrs.size()) {
            val p = ptrs.valueAt(i)
            if (p.cell === cell && !p.gliding) return true
        }
        return false
    }

    private fun isGliding(): Boolean {
        for (i in 0 until ptrs.size()) if (ptrs.valueAt(i).gliding) return true
        return false
    }

    private fun display(k: Key): String =
        if (page == Page.LETTERS && shift != 0 && k.letter) k.upper else k.label

    override fun onDraw(c: Canvas) {
        if (!transparentBg) c.drawColor(theme.bg)
        val r = dp(cornerDp.toFloat())
        for (cell in cells) {
            val k = cell.key
            if (k.kind == Kind.SPACER) continue
            val down = isDown(cell)
            val pill = k.kind == Kind.MODE || k.kind == Kind.SPACE || k.kind == Kind.ENTER
            val shiftOn = k.kind == Kind.SHIFT && shift != 0
            var fill = 0
            when {
                down -> fill = theme.pressed
                k.kind == Kind.ENTER || (shiftOn && !borderless) -> fill = theme.accent
                pill -> fill = theme.pill
                !borderless -> fill = if (k.kind == Kind.CHAR) theme.key else theme.keyAlt
            }
            if (fill != 0) {
                paint.color = fill
                val rr = if (pill || (borderless && down)) cell.draw.height() / 2f else r
                c.drawRoundRect(cell.draw, rr, rr, paint)
            }
            val cx = cell.draw.centerX()
            val cy = cell.draw.centerY()
            val isz = dp(9f)
            when (k.kind) {
                Kind.CHAR -> {
                    val digit = k.label[0].isDigit()
                    val size = when {
                        page == Page.NUMPAD && digit -> 28f
                        page == Page.NUMPAD -> 20f
                        page == Page.LETTERS && digit -> 18f
                        else -> 22f
                    }
                    text(c, display(k), cx, cy, size, if (page == Page.LETTERS && digit) theme.dim else theme.text)
                    val h = k.hint
                    if (h != null && page == Page.LETTERS) {
                        text(c, h, cell.draw.right - dp(7f), cell.draw.top + dp(12f), 11f, theme.dim)
                    }
                }
                Kind.SHIFT -> {
                    ip.color = if (shiftOn && !borderless) onColor(theme.accent) else theme.text
                    Icons.shift(c, cx, cy, isz, ip, shiftOn, shift == 2)
                }
                Kind.DELETE -> { ip.color = theme.text; Icons.backspace(c, cx, cy, isz, ip) }
                Kind.EMOJI -> { ip.color = theme.text; Icons.face(c, cx, cy, isz * 1.05f, ip) }
                Kind.ENTER -> { ip.color = onColor(theme.accent); Icons.enter(c, cx, cy, isz, ip) }
                Kind.MODE -> {
                    if (k.label == "1234") {
                        text(c, "12", cx, cy - dp(7f), 13f, theme.text)
                        text(c, "34", cx, cy + dp(7f), 13f, theme.text)
                    } else text(c, k.label, cx, cy, 15f, theme.text)
                }
                Kind.SPACE -> {
                    if (k.weight < 2f) { ip.color = theme.text; Icons.spaceSym(c, cx, cy, dp(7f), ip) }
                    else text(c, langLabel, cx, cy, 13f, theme.dim)
                }
                else -> {}
            }
        }
        val items = popupItems
        if (items != null) {
            drawAltPopup(c, items, r)
        } else if (popups) {
            for (i in 0 until ptrs.size()) {
                val p = ptrs.valueAt(i)
                if (!p.gliding && p.cell.key.kind == Kind.CHAR) drawBubble(c, p.cell, display(p.cell.key), r)
            }
        }
        drawTrail(c)
    }

    private fun drawTrail(c: Canvas) {
        if (gn < 2) return
        val active = isGliding()
        val alpha = if (active) 210 else {
            val el = SystemClock.uptimeMillis() - trailEnd
            if (el >= 200) { gn = 0; 0 } else (210 * (1f - el / 200f)).toInt()
        }
        if (alpha <= 0) return
        trailPath.reset()
        trailPath.moveTo(gx[0], gy[0])
        for (i in 1 until gn) trailPath.lineTo(gx[i], gy[i])
        trailPaint.strokeWidth = dp(5f)
        trailPaint.color = (theme.accent and 0x00FFFFFF) or (alpha shl 24)
        c.drawPath(trailPath, trailPaint)
        if (!active) postInvalidateOnAnimation()
    }

    private fun drawBubble(c: Canvas, cell: Cell, label: String, r: Float) {
        val bw = cell.draw.width() * 1.3f
        val bh = cell.draw.height() * 1.15f
        val left = (cell.draw.centerX() - bw / 2).coerceIn(0f, max(0f, width - bw))
        val top = max(0f, cell.draw.top - bh - dp(6f))
        rect.set(left, top, left + bw, top + bh)
        paint.color = theme.pressed
        c.drawRoundRect(rect, r, r, paint)
        text(c, label, rect.centerX(), rect.centerY(), 30f, theme.text)
    }

    private fun drawAltPopup(c: Canvas, items: List<String>, r: Float) {
        val cell = ptrs.get(popupPtr)?.cell ?: return
        val h = cell.draw.height() * 1.05f
        val top = max(dp(4f), cell.draw.top - h - dp(6f))
        rect.set(popupLeft - dp(4f), top - dp(4f), popupLeft + items.size * popupItemW + dp(4f), top + h + dp(4f))
        paint.color = theme.pressed
        c.drawRoundRect(rect, r, r, paint)
        items.forEachIndexed { i, s ->
            rect.set(popupLeft + i * popupItemW + dp(2f), top, popupLeft + (i + 1) * popupItemW - dp(2f), top + h)
            if (i == popupSel) {
                paint.color = theme.accent
                c.drawRoundRect(rect, r, r, paint)
            }
            text(c, s, rect.centerX(), rect.centerY(), 22f, if (i == popupSel) onColor(theme.accent) else theme.text)
        }
    }

    private fun invalidateKey(cell: Cell) {
        val h = cell.hit.height()
        invalidate(
            (cell.hit.left - h * 0.4f).toInt(), (cell.hit.top - h * 1.5f).toInt(),
            (cell.hit.right + h * 0.4f).toInt(), (cell.hit.bottom + 2f).toInt()
        )
    }

    // ---------- touch ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun cellAt(x: Float, y: Float): Cell? {
        if (cells.isEmpty()) return null
        val yy = y.coerceIn(cells.first().hit.top, cells.last().hit.bottom - 1f)
        val xx = x.coerceIn(0f, width - 1f)
        for (cell in cells) if (cell.key.kind != Kind.SPACER && cell.hit.contains(xx, yy)) return cell
        return null
    }

    private fun haptic() { if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private fun down(id: Int, x: Float, y: Float) {
        val cell = cellAt(x, y) ?: return
        val p = Ptr(id, cell, x, y)
        ptrs.put(id, p)
        haptic()
        if (cell.key.kind == Kind.DELETE) {
            listener?.onDelete()
            var interval = 90L
            val r = object : Runnable {
                override fun run() {
                    listener?.onDelete()
                    interval = max(35L, interval - 5)
                    ui.postDelayed(this, interval)
                }
            }
            p.rep = r
            ui.postDelayed(r, 400L)
        } else {
            scheduleLong(p)
        }
        invalidateKey(cell)
    }

    private fun scheduleLong(p: Ptr) {
        val k = p.cell.key
        if (k.kind == Kind.SPACE || k.kind == Kind.EMOJI || (k.kind == Kind.CHAR && k.alts.isNotEmpty())) {
            val r = Runnable { p.lp = null; onLong(p) }
            p.lp = r
            ui.postDelayed(r, 380L)
        }
    }

    private fun onLong(p: Ptr) {
        val k = p.cell.key
        when (k.kind) {
            Kind.SPACE -> { p.consumed = true; haptic(); listener?.onLanguage(); return }
            Kind.EMOJI -> { p.consumed = true; haptic(); listener?.onSwitchKeyboard(); return }
            else -> {}
        }
        p.consumed = true
        val upper = shift != 0 && page == Page.LETTERS
        val items = k.alts.map { if (upper) it.uppercase() else it }
        popupItems = items
        popupPtr = p.id
        popupSel = 0
        popupItemW = max(p.cell.draw.width(), dp(40f))
        popupLeft = (p.cell.draw.centerX() - items.size * popupItemW / 2)
            .coerceIn(0f, max(0f, width - items.size * popupItemW))
        haptic()
        invalidate()
    }

    private fun addGlidePoint(x: Float, y: Float) {
        if (gn >= maxG) return
        if (gn > 0) {
            val dx = x - gx[gn - 1]
            val dy = y - gy[gn - 1]
            if (dx * dx + dy * dy < dp(3f) * dp(3f)) return
        }
        gx[gn] = x
        gy[gn] = y
        gn++
    }

    private fun startGlide(p: Ptr, x: Float, y: Float) {
        cancelCallbacks(p)
        p.gliding = true
        p.consumed = true
        gn = 0
        addGlidePoint(p.x0, p.y0)
        addGlidePoint(x, y)
        invalidate()
    }

    private fun finishGlide() {
        trailEnd = SystemClock.uptimeMillis()
        val n = gn
        if (n >= 4) listener?.onGlide(gx, gy, n)
        if (shift == 1) shift = 0
        postInvalidateOnAnimation()
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        val items = popupItems
        if (popupPtr == id && items != null) {
            val s = ((x - popupLeft) / popupItemW).toInt().coerceIn(0, items.size - 1)
            if (s != popupSel) { popupSel = s; invalidate() }
            return
        }
        if (p.gliding) { addGlidePoint(x, y); invalidate(); return }
        val k = p.cell.key
        when (k.kind) {
            Kind.SPACE -> { // swipe on spacebar = move cursor
                p.spaceAcc += x - p.lastX
                p.lastX = x
                val step = dp(14f)
                while (abs(p.spaceAcc) >= step) {
                    p.lp?.let { ui.removeCallbacks(it) }
                    p.lp = null
                    val d = if (p.spaceAcc > 0) 1 else -1
                    p.spaceAcc -= d * step
                    p.consumed = true
                    listener?.onCursorMove(d)
                }
            }
            Kind.DELETE -> { // swipe left on backspace = delete word by word
                val dx = p.x0 - x
                val w = if (dx < dp(18f)) 0 else ((dx - dp(18f)) / dp(40f)).toInt() + 1
                if (w > 0 && !p.swiping) {
                    p.swiping = true
                    p.rep?.let { ui.removeCallbacks(it) }
                    p.rep = null
                }
                if (p.swiping && w != p.words) {
                    p.words = w
                    haptic()
                    listener?.onDeleteSwipe(w)
                }
            }
            Kind.CHAR -> {
                if (glideOn && page == Page.LETTERS && p.startCell.key.letter) {
                    val dx = x - p.x0
                    val dy = y - p.y0
                    val th = dp(24f)
                    if (dx * dx + dy * dy > th * th) { startGlide(p, x, y); return }
                }
                val c = cellAt(x, y)
                if (c != null && c !== p.cell && c.key.kind == Kind.CHAR) {
                    cancelCallbacks(p)
                    invalidateKey(p.cell)
                    p.cell = c
                    p.consumed = false
                    scheduleLong(p)
                    invalidateKey(c)
                }
            }
            else -> {}
        }
    }

    private fun emit(s: String) {
        if (shift == 1) shift = 0
        listener?.onChar(s)
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        ptrs.remove(id)
        cancelCallbacks(p)
        if (p.gliding) {
            addGlidePoint(x, y)
            finishGlide()
            invalidate()
            return
        }
        val items = popupItems
        if (popupPtr == id && items != null) {
            val s = items[popupSel]
            popupItems = null
            popupPtr = -1
            emit(s)
            invalidate()
            return
        }
        if (p.swiping) {
            listener?.onDeleteSwipeEnd()
            invalidateKey(p.cell)
            return
        }
        if (!p.consumed) {
            val k = p.cell.key
            when (k.kind) {
                Kind.CHAR -> emit(display(k))
                Kind.SPACE -> emit(" ")
                Kind.ENTER -> listener?.onEnter()
                Kind.EMOJI -> listener?.onEmoji()
                Kind.SHIFT -> {
                    listener?.onShiftTapped()
                    val now = SystemClock.uptimeMillis()
                    shift = when {
                        shift == 2 -> 0
                        shift == 1 && now - lastShiftTap < 320 -> 2
                        shift == 1 -> 0
                        else -> 1
                    }
                    lastShiftTap = now
                }
                Kind.MODE -> { shift = 0; page = k.target }
                else -> {}
            }
        }
        invalidateKey(p.cell)
    }

    private fun cancelCallbacks(p: Ptr) {
        p.lp?.let { ui.removeCallbacks(it) }
        p.rep?.let { ui.removeCallbacks(it) }
        p.lp = null
        p.rep = null
    }

    private fun cancelAll() {
        for (i in 0 until ptrs.size()) cancelCallbacks(ptrs.valueAt(i))
        ptrs.clear()
        popupItems = null
        popupPtr = -1
        gn = 0
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }
}
