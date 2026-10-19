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
import kotlin.math.ceil
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

    fun setItems(list: List<String>, keepOffset: Boolean) {
        items = list
        scroller.forceFinished(true)
        offset = if (keepOffset) offset.coerceIn(0f, maxScroll()) else 0f
        lastFirst = -1
        notifyScrolled()
        invalidate()
    }

    /** Smoothly scrolls so the row containing item [index] is at the top. */
    fun scrollToIndex(index: Int) {
        val target = ((index / cols()) * cell).coerceIn(0f, maxScroll())
        scroller.forceFinished(true)
        scroller.startScroll(0, offset.toInt(), 0, (target - offset).toInt(), 260)
        postInvalidateOnAnimation()
    }

    private fun notifyScrolled() {
        val first = (offset / cell).toInt() * cols()
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
    private fun maxScroll(): Float {
        val rows = ceil(items.size / cols().toFloat())
        return max(0f, rows * cell - height)
    }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        val cols = cols()
        val cw = width / cols.toFloat()
        paint.color = textColor
        paint.getFontMetrics(fm)
        val base = -(fm.ascent + fm.descent) / 2f
        val first = (offset / cell).toInt().coerceAtLeast(0)
        val last = ((offset + height) / cell).toInt()
        for (row in first..last) for (col in 0 until cols) {
            val i = row * cols + col
            if (i >= items.size) return
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
        val i = row * cols + col
        return if (i in items.indices) i else null
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
