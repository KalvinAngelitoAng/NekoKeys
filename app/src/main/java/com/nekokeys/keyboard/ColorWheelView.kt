package com.nekokeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Hue/saturation wheel. Brightness is a separate slider owned by the dialog. */
class ColorWheelView(context: Context) : View(context) {
    var hue = 0f
    var sat = 0f
    var bright = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onChange: (() -> Unit)? = null

    private val dens = resources.displayMetrics.density
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * dens }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    fun getColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, bright))

    fun setColor(c: Int) {
        val h = FloatArray(3)
        Color.colorToHSV(c, h)
        hue = h[0]; sat = h[1]; bright = h[2]
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) / 2f - 10f * dens
        val colors = IntArray(7) { Color.HSVToColor(floatArrayOf((it * 60f) % 360f, 1f, 1f)) }
        wheel.shader = SweepGradient(cx, cy, colors, null)
        overlay.shader = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, r, wheel)
        c.drawCircle(cx, cy, r, overlay)
        shade.color = Color.argb(((1f - bright) * 255).toInt(), 0, 0, 0)
        c.drawCircle(cx, cy, r, shade)
        val a = Math.toRadians(hue.toDouble())
        val sx = cx + cos(a).toFloat() * sat * r
        val sy = cy + sin(a).toFloat() * sat * r
        dot.color = getColor()
        c.drawCircle(sx, sy, 11f * dens, dot)
        ring.color = Color.WHITE
        c.drawCircle(sx, sy, 11f * dens, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = e.x - cx
            val dy = e.y - cy
            sat = min(1f, sqrt(dx * dx + dy * dy) / r)
            hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
            invalidate()
            onChange?.invoke()
        }
        return true
    }
}
