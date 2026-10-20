package com.nekokeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max

/**
 * Lays a flat emoji list out as "lines": a title line (label >= 0) or a row of emoji (label = -1).
 * Every section starts on its own row, under its own title when it has one. Pure Kotlin on purpose,
 * so the layout can be tested on the JVM without Android.
 */
class EmojiLines(private val cell: Float, private val titleH: Float) {
    var first = IntArray(0); private set   // first item of a row (for a title: first item of its section)
    var len = IntArray(0); private set     // emoji in the row (0 for a title); the last row of a section can be short
    var label = IntArray(0); private set   // index of the section for a title line, otherwise -1
    var top = FloatArray(0); private set
    var totalH = 0f; private set
    val count get() = first.size

    fun build(itemCount: Int, cols: Int, sections: IntArray, titled: (Int) -> Boolean) {
        val f = ArrayList<Int>()
        val l = ArrayList<Int>()
        val b = ArrayList<Int>()
        for (k in sections.indices) {
            val s = sections[k].coerceIn(0, itemCount)
            val e = (if (k + 1 < sections.size) sections[k + 1] else itemCount).coerceIn(s, itemCount)
            if (e <= s) continue
            if (titled(k)) { f.add(s); l.add(0); b.add(k) }
            var i = s
            while (i < e) { f.add(i); l.add(minOf(cols, e - i)); b.add(-1); i += cols }
        }
        first = f.toIntArray(); len = l.toIntArray(); label = b.toIntArray()
        top = FloatArray(first.size)
        var y = 0f
        for (i in first.indices) { top[i] = y; y += if (label[i] >= 0) titleH else cell }
        totalH = y
    }

    /** The line that contains vertical position [y]. */
    fun lineAt(y: Float): Int {
        if (count == 0) return 0
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (top[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The line to scroll to for item [index]: its row, or the title above it when it opens a section. */
    fun lineOfItem(index: Int): Int {
        var r = 0
        for (k in 0 until count) {
            if (label[k] >= 0) continue
            if (first[k] <= index) r = k else break
        }
        return if (r > 0 && label[r - 1] >= 0 && first[r] == index) r - 1 else r
    }
}

/**
 * Draws only the visible emoji cells (about 50 drawText calls per frame) and does its own
 * touch scrolling + fling, so a 400-emoji page costs no views and almost no memory.
 */
class EmojiGridView(context: Context) : View(context) {
    var items: List<String> = emptyList()
        private set
    var onScrolled: ((Int) -> Unit)? = null   // index of the first emoji in the top visible row
    private var lastFirst = -1

    /**
     * Item indices where each section (Recents, Smileys, ...) begins. Every section starts on a fresh row and,
     * when [sectionLabels] is given, with a small title line above it.
     */
    private var sections: IntArray = intArrayOf(0)
    private var labels: Array<String>? = null

    private var layoutCols = -1

    fun setItems(
        list: List<String>, keepOffset: Boolean,
        sectionStarts: IntArray = intArrayOf(0), sectionLabels: Array<String>? = null
    ) {
        items = list
        val ok = sectionStarts.isNotEmpty() && sectionStarts[0] == 0
        sections = if (ok) sectionStarts else intArrayOf(0)
        labels = if (ok) sectionLabels else null
        layoutCols = -1                  // force the layout to be rebuilt
        scroller.forceFinished(true)
        offset = if (keepOffset) offset.coerceIn(0f, maxScroll()) else 0f
        lastFirst = -1
        notifyScrolled()
        invalidate()
    }

    /** Smoothly scrolls so the row containing item [index] is at the top. */
    fun scrollToIndex(index: Int) {
        lineCount()
        val target = lines.top.getOrElse(lines.lineOfItem(index)) { 0f }.coerceIn(0f, maxScroll())
        scroller.forceFinished(true)
        scroller.startScroll(0, offset.toInt(), 0, (target - offset).toInt(), 260)
        postInvalidateOnAnimation()
    }

    private fun notifyScrolled() {
        val n = lineCount()
        val first = if (n == 0) 0 else lines.first[lines.lineAt(offset)]
        if (first != lastFirst) { lastFirst = first; onScrolled?.invoke(first) }
    }
    var textColor = Color.WHITE
        set(v) { field = v; invalidate() }
    var onPick: ((Int) -> Unit)? = null
    var onLong: ((Int) -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null

    private val dens = resources.displayMetrics.density
    private val cell = 46f * dens
    private val titleH = 32f * dens
    private val titlePad = 12f * dens
    private val lines = EmojiLines(cell, titleH)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 26f * dens }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; textSize = 14f * dens }
    private val fm = Paint.FontMetrics()
    private val scroller = OverScroller(context)
    private var vt: VelocityTracker? = null
    private var offset = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var longFired = false
    private val ui = Handler(Looper.getMainLooper())
    private var longRun: Runnable? = null
    private val cfg = ViewConfiguration.get(context)

    private fun cols() = max(1, (width / cell).toInt())
    private fun maxScroll(): Float { lineCount(); return max(0f, lines.totalH - height) }

    /** Rebuilds the line layout when the list or the column count changed. */
    private fun lineCount(): Int {
        val c = cols()
        if (c != layoutCols) {
            layoutCols = c
            lines.build(items.size, c, sections) { labels?.getOrNull(it) != null }
        }
        return lines.count
    }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        val cols = cols()
        val cw = width / cols.toFloat()
        paint.color = textColor
        paint.getFontMetrics(fm)
        val base = -(fm.ascent + fm.descent) / 2f
        titlePaint.color = (textColor and 0x00FFFFFF) or 0x99000000.toInt()
        val tfm = titlePaint.fontMetrics
        val tbase = -(tfm.ascent + tfm.descent) / 2f
        val n = lineCount()
        var li = lines.lineAt(offset)
        while (li < n && lines.top[li] < offset + height) {
            val top = lines.top[li] - offset
            if (lines.label[li] >= 0) {
                val text = labels?.getOrNull(lines.label[li])
                if (text != null) c.drawText(text, titlePad, top + titleH * 0.62f + tbase, titlePaint)
            } else {
                for (col in 0 until lines.len[li]) {
                    c.drawText(items[lines.first[li] + col], col * cw + cw / 2f, top + cell / 2f + base, paint)
                }
            }
            li++
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            notifyScrolled()
            postInvalidateOnAnimation()
        }
    }

    private fun hit(x: Float, y: Float): Int? {
        val cols = cols()
        val col = (x / (width / cols.toFloat())).toInt().coerceIn(0, cols - 1)
        val n = lineCount()
        if (n == 0 || y + offset < 0f || y + offset >= lines.totalH) return null
        val li = lines.lineAt(y + offset)
        if (lines.label[li] >= 0 || col >= lines.len[li]) return null
        return lines.first[li] + col
    }

    private fun cancelLong() { longRun?.let { ui.removeCallbacks(it) }; longRun = null }
    private fun recycle() { vt?.recycle(); vt = null }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y
                moved = false; longFired = false
                onTouchStart?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                val r = Runnable { longRun = null; longFired = true; hit(downX, downY)?.let { onLong?.invoke(it) } }
                longRun = r
                ui.postDelayed(r, 420L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(e.y - downY) > cfg.scaledTouchSlop) { moved = true; cancelLong() }
                if (moved) {
                    offset = (offset - (e.y - lastY)).coerceIn(0f, maxScroll())
                    lastY = e.y
                    notifyScrolled()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLong()
                if (!moved && !longFired) {
                    hit(e.x, e.y)?.let { onPick?.invoke(it) }
                } else if (moved) {
                    vt?.computeCurrentVelocity(1000, cfg.scaledMaximumFlingVelocity.toFloat())
                    val v = -(vt?.yVelocity ?: 0f)
                    if (abs(v) > cfg.scaledMinimumFlingVelocity) {
                        scroller.fling(0, offset.toInt(), 0, v.toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                }
                recycle()
            }
            MotionEvent.ACTION_CANCEL -> { cancelLong(); recycle() }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        cancelLong()
        scroller.abortAnimation()
        recycle()
        super.onDetachedFromWindow()
    }
}
