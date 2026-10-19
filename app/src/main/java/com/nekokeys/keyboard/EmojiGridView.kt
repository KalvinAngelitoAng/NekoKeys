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
 * Draws only the visible emoji cells (about 50 drawText calls per frame) and does its own
 * touch scrolling + fling, so a 400-emoji page costs no views and almost no memory.
 */
class EmojiGridView(context: Context) : View(context) {
    var items: List<String> = emptyList()
        private set
    var onScrolled: ((Int) -> Unit)? = null   // index of the first emoji in the top visible row
    private var lastFirst = -1

    /** Item indices where each section (Recents, Smileys, ...) begins. Every section starts on a fresh row. */
    private var sections: IntArray = intArrayOf(0)
    private var rowFirst = IntArray(0)   // index of the first item in each row
    private var rowLen = IntArray(0)   // items in each row (the last row of a section can be short)
    private var layoutCols = -1

    fun setItems(list: List<String>, keepOffset: Boolean, sectionStarts: IntArray = intArrayOf(0)) {
        items = list
        sections = sectionStarts
        layoutCols = -1                  // force the row layout to be rebuilt
        scroller.forceFinished(true)
        offset = if (keepOffset) offset.coerceIn(0f, maxScroll()) else 0f
        lastFirst = -1
        notifyScrolled()
        invalidate()
    }

    /** Smoothly scrolls so the row containing item [index] is at the top. */
    fun scrollToIndex(index: Int) {
        val target = (rowOf(index) * cell).coerceIn(0f, maxScroll())
        scroller.forceFinished(true)
        scroller.startScroll(0, offset.toInt(), 0, (target - offset).toInt(), 260)
        postInvalidateOnAnimation()
    }

    private fun notifyScrolled() {
        val n = rowTotal()
        val first = if (n == 0) 0 else rowFirst[(offset / cell).toInt().coerceIn(0, n - 1)]
        if (first != lastFirst) { lastFirst = first; onScrolled?.invoke(first) }
    }
    var textColor = Color.WHITE
        set(v) { field = v; invalidate() }
    var onPick: ((Int) -> Unit)? = null
    var onLong: ((Int) -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null

    private val dens = resources.displayMetrics.density
    private val cell = 46f * dens
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 26f * dens }
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
    private fun maxScroll(): Float = max(0f, rowTotal() * cell - height)

    /** Lays the flat list out in rows, starting a new row at every section boundary. */
    private fun rowTotal(): Int {
        val c = cols()
        if (c != layoutCols) {
            layoutCols = c
            val bounds = (listOf(0) + sections.toList() + items.size)
                .filter { it in 0..items.size }.distinct().sorted()
            val rf = ArrayList<Int>()
            val rc = ArrayList<Int>()
            for (k in 0 until bounds.size - 1) {
                var i = bounds[k]
                val end = bounds[k + 1]
                while (i < end) { rf.add(i); rc.add(minOf(c, end - i)); i += c }
            }
            rowFirst = rf.toIntArray()
            rowLen = rc.toIntArray()
        }
        return rowFirst.size
    }

    /** Row that contains item [index]. */
    private fun rowOf(index: Int): Int {
        val n = rowTotal()
        var r = 0
        for (k in 0 until n) if (rowFirst[k] <= index) r = k else break
        return r
    }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        val cols = cols()
        val cw = width / cols.toFloat()
        paint.color = textColor
        paint.getFontMetrics(fm)
        val base = -(fm.ascent + fm.descent) / 2f
        val rows = rowTotal()
        val first = (offset / cell).toInt().coerceAtLeast(0)
        val last = minOf(((offset + height) / cell).toInt(), rows - 1)
        for (row in first..last) for (col in 0 until rowLen[row]) {
            val i = rowFirst[row] + col
            c.drawText(items[i], col * cw + cw / 2f, row * cell - offset + cell / 2f + base, paint)
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
        val row = ((y + offset) / cell).toInt()
        if (row !in 0 until rowTotal() || col >= rowLen[row]) return null
        return rowFirst[row] + col
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