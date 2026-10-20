package com.nekokeys.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Crisp vector icons drawn with Canvas, so they look identical on every device and font. */
object Icons {
    private val path = Path()
    private val rect = RectF()

    private fun stroke(p: Paint, w: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, filled: Boolean, lock: Boolean) {
        val oy = if (lock) -s * 0.15f else 0f
        path.reset()
        path.moveTo(cx, cy - s + oy)
        path.lineTo(cx + s, cy + oy)
        path.lineTo(cx + s * 0.45f, cy + oy)
        path.lineTo(cx + s * 0.45f, cy + s * 0.85f + oy)
        path.lineTo(cx - s * 0.45f, cy + s * 0.85f + oy)
        path.lineTo(cx - s * 0.45f, cy + oy)
        path.lineTo(cx - s, cy + oy)
        path.close()
        stroke(p, s * 0.2f)
        if (filled) p.style = Paint.Style.FILL_AND_STROKE
        c.drawPath(path, p)
        if (lock) c.drawLine(cx - s * 0.45f, cy + s * 1.2f + oy, cx + s * 0.45f, cy + s * 1.2f + oy, p)
    }

    fun backspace(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        path.moveTo(cx - s * 1.15f, cy)
        path.lineTo(cx - s * 0.5f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy + s * 0.8f)
        path.lineTo(cx - s * 0.5f, cy + s * 0.8f)
        path.close()
        stroke(p, s * 0.2f)
        c.drawPath(path, p)
        c.drawLine(cx - s * 0.05f, cy - s * 0.35f, cx + s * 0.65f, cy + s * 0.35f, p)
        c.drawLine(cx - s * 0.05f, cy + s * 0.35f, cx + s * 0.65f, cy - s * 0.35f, p)
    }

    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.2f)
        path.reset()
        path.moveTo(cx + s, cy - s * 0.8f)
        path.lineTo(cx + s, cy + s * 0.1f)
        path.lineTo(cx - s * 0.85f, cy + s * 0.1f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(cx - s * 0.3f, cy - s * 0.5f)
        path.lineTo(cx - s * 0.9f, cy + s * 0.1f)
        path.lineTo(cx - s * 0.3f, cy + s * 0.7f)
        c.drawPath(path, p)
    }

    fun globe(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.16f)
        c.drawCircle(cx, cy, s, p)
        rect.set(cx - s * 0.45f, cy - s, cx + s * 0.45f, cy + s)
        c.drawOval(rect, p)
        c.drawLine(cx - s, cy, cx + s, cy, p)
        c.drawLine(cx - s * 0.87f, cy - s * 0.5f, cx + s * 0.87f, cy - s * 0.5f, p)
        c.drawLine(cx - s * 0.87f, cy + s * 0.5f, cx + s * 0.87f, cy + s * 0.5f, p)
    }

    fun check(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.28f)
        path.reset()
        path.moveTo(cx - s * 0.75f, cy + s * 0.05f)
        path.lineTo(cx - s * 0.2f, cy + s * 0.6f)
        path.lineTo(cx + s * 0.8f, cy - s * 0.5f)
        c.drawPath(path, p)
    }

    fun ghost(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.moveTo(cx - s, cy)
        rect.set(cx - s, cy - s, cx + s, cy + s)
        path.arcTo(rect, 180f, 180f)
        path.lineTo(cx + s, cy + s)
        path.lineTo(cx + s * 0.66f, cy + s * 0.7f)
        path.lineTo(cx + s * 0.33f, cy + s)
        path.lineTo(cx, cy + s * 0.7f)
        path.lineTo(cx - s * 0.33f, cy + s)
        path.lineTo(cx - s * 0.66f, cy + s * 0.7f)
        path.lineTo(cx - s, cy + s)
        path.close()
        path.addCircle(cx - s * 0.36f, cy - s * 0.1f, s * 0.17f, Path.Direction.CCW)
        path.addCircle(cx + s * 0.36f, cy - s * 0.1f, s * 0.17f, Path.Direction.CCW)
        p.style = Paint.Style.FILL
        c.drawPath(path, p)
    }

    private fun head(c: Canvas, x: Float, y: Float, dir: Float, s: Float, p: Paint) {
        path.reset()
        path.moveTo(x - dir * s * 0.45f, y - s * 0.45f)
        path.lineTo(x, y)
        path.lineTo(x - dir * s * 0.45f, y + s * 0.45f)
        c.drawPath(path, p)
    }

    fun clipboard(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.18f)
        rect.set(cx - s * 0.8f, cy - s * 0.65f, cx + s * 0.8f, cy + s)
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, p)
        rect.set(cx - s * 0.4f, cy - s, cx + s * 0.4f, cy - s * 0.4f)
        c.drawRoundRect(rect, s * 0.15f, s * 0.15f, p)
        c.drawLine(cx - s * 0.4f, cy + s * 0.15f, cx + s * 0.4f, cy + s * 0.15f, p)
        c.drawLine(cx - s * 0.4f, cy + s * 0.6f, cx + s * 0.15f, cy + s * 0.6f, p)
    }

    fun trash(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.18f)
        c.drawLine(cx - s * 0.9f, cy - s * 0.55f, cx + s * 0.9f, cy - s * 0.55f, p)
        path.reset()
        path.moveTo(cx - s * 0.3f, cy - s * 0.55f)
        path.lineTo(cx - s * 0.3f, cy - s * 0.95f)
        path.lineTo(cx + s * 0.3f, cy - s * 0.95f)
        path.lineTo(cx + s * 0.3f, cy - s * 0.55f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(cx - s * 0.65f, cy - s * 0.35f)
        path.lineTo(cx - s * 0.55f, cy + s)
        path.lineTo(cx + s * 0.55f, cy + s)
        path.lineTo(cx + s * 0.65f, cy - s * 0.35f)
        c.drawPath(path, p)
        c.drawLine(cx, cy - s * 0.05f, cx, cy + s * 0.7f, p)
    }

    fun pin(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, filled: Boolean) {
        stroke(p, s * 0.2f)
        p.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawCircle(cx, cy - s * 0.35f, s * 0.55f, p)
        p.style = Paint.Style.STROKE
        c.drawLine(cx, cy + s * 0.2f, cx, cy + s, p)
    }

    fun back(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.2f)
        path.reset()
        path.moveTo(cx + s * 0.35f, cy - s * 0.8f)
        path.lineTo(cx - s * 0.45f, cy)
        path.lineTo(cx + s * 0.35f, cy + s * 0.8f)
        c.drawPath(path, p)
    }

    fun swap(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.18f)
        c.drawLine(cx - s, cy - s * 0.4f, cx + s, cy - s * 0.4f, p)
        head(c, cx + s, cy - s * 0.4f, 1f, s, p)
        c.drawLine(cx + s, cy + s * 0.4f, cx - s, cy + s * 0.4f, p)
        head(c, cx - s, cy + s * 0.4f, -1f, s, p)
    }

    fun expand(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.18f)
        c.drawLine(cx - s, cy, cx + s, cy, p)
        head(c, cx + s, cy, 1f, s, p)
        head(c, cx - s, cy, -1f, s, p)
    }

    fun oneHand(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.18f)
        rect.set(cx - s * 0.7f, cy - s, cx + s * 0.7f, cy + s)
        c.drawRoundRect(rect, s * 0.2f, s * 0.2f, p)
        p.style = Paint.Style.FILL
        rect.set(cx, cy + s * 0.15f, cx + s * 0.5f, cy + s * 0.7f)
        c.drawRoundRect(rect, s * 0.1f, s * 0.1f, p)
    }

    fun face(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.17f)
        c.drawCircle(cx, cy, s, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx - s * 0.35f, cy - s * 0.25f, s * 0.11f, p)
        c.drawCircle(cx + s * 0.35f, cy - s * 0.25f, s * 0.11f, p)
        stroke(p, s * 0.17f)
        rect.set(cx - s * 0.55f, cy - s * 0.5f, cx + s * 0.55f, cy + s * 0.6f)
        c.drawArc(rect, 25f, 130f, false, p)
    }

    fun gear(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.18f)
        c.drawCircle(cx, cy, s * 0.62f, p)
        c.drawCircle(cx, cy, s * 0.24f, p)
        p.strokeWidth = s * 0.3f
        p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * s * 0.78f, cy + sn * s * 0.78f, cx + cs * s, cy + sn * s, p)
        }
    }

    fun spaceSym(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.2f)
        path.reset()
        path.moveTo(cx - s, cy - s * 0.35f)
        path.lineTo(cx - s, cy + s * 0.35f)
        path.lineTo(cx + s, cy + s * 0.35f)
        path.lineTo(cx + s, cy - s * 0.35f)
        c.drawPath(path, p)
    }

    fun plus(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.22f)
        c.drawLine(cx - s, cy, cx + s, cy, p)
        c.drawLine(cx, cy - s, cx, cy + s, p)
    }

    fun search(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.22f)
        c.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.7f, p)
        c.drawLine(cx + s * 0.32f, cy + s * 0.32f, cx + s * 0.95f, cy + s * 0.95f, p)
    }

    fun close(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s * 0.22f)
        c.drawLine(cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy + s * 0.7f, p)
        c.drawLine(cx + s * 0.7f, cy - s * 0.7f, cx - s * 0.7f, cy + s * 0.7f, p)
    }

    // ---- emoji category icons: 0 recent, 1 smileys, 2 people, 3 animals, 4 food, 5 travel, 6 activity, 7 objects, 8 symbols, 9 flags
    fun cat(c: Canvas, idx: Int, cx: Float, cy: Float, s: Float, p: Paint) {
        when (idx) {
            0 -> {
                stroke(p, s * 0.18f)
                c.drawCircle(cx, cy, s, p)
                c.drawLine(cx, cy, cx, cy - s * 0.55f, p)
                c.drawLine(cx, cy, cx + s * 0.4f, cy + s * 0.2f, p)
            }
            1 -> face(c, cx, cy, s, p)
            2 -> {
                stroke(p, s * 0.18f)
                c.drawCircle(cx, cy - s * 0.45f, s * 0.4f, p)
                rect.set(cx - s * 0.85f, cy + s * 0.15f, cx + s * 0.85f, cy + s * 1.7f)
                c.drawArc(rect, 200f, 140f, false, p)
            }
            3 -> {
                p.style = Paint.Style.FILL
                rect.set(cx - s * 0.5f, cy + s * 0.05f, cx + s * 0.5f, cy + s * 0.95f)
                c.drawOval(rect, p)
                c.drawCircle(cx - s * 0.78f, cy - s * 0.15f, s * 0.22f, p)
                c.drawCircle(cx - s * 0.28f, cy - s * 0.62f, s * 0.22f, p)
                c.drawCircle(cx + s * 0.28f, cy - s * 0.62f, s * 0.22f, p)
                c.drawCircle(cx + s * 0.78f, cy - s * 0.15f, s * 0.22f, p)
            }
            4 -> {
                stroke(p, s * 0.18f)
                path.reset()
                path.moveTo(cx - s * 0.75f, cy - s * 0.5f)
                path.lineTo(cx - s * 0.6f, cy + s * 0.9f)
                path.lineTo(cx + s * 0.5f, cy + s * 0.9f)
                path.lineTo(cx + s * 0.65f, cy - s * 0.5f)
                path.close()
                c.drawPath(path, p)
                rect.set(cx + s * 0.4f, cy - s * 0.2f, cx + s * 1.1f, cy + s * 0.5f)
                c.drawArc(rect, -90f, 180f, false, p)
            }
            5 -> globe(c, cx, cy, s, p)
            6 -> {
                stroke(p, s * 0.16f)
                c.drawCircle(cx, cy, s, p)
                c.drawLine(cx - s, cy, cx + s, cy, p)
                c.drawLine(cx, cy - s, cx, cy + s, p)
            }
            7 -> {
                stroke(p, s * 0.18f)
                c.drawCircle(cx, cy - s * 0.25f, s * 0.65f, p)
                c.drawLine(cx - s * 0.3f, cy + s * 0.7f, cx + s * 0.3f, cy + s * 0.7f, p)
                c.drawLine(cx - s * 0.22f, cy + s, cx + s * 0.22f, cy + s, p)
            }
            8 -> {
                stroke(p, s * 0.18f)
                path.reset()
                path.moveTo(cx, cy + s * 0.9f)
                path.cubicTo(cx - s * 1.5f, cy - s * 0.1f, cx - s * 0.7f, cy - s * 1.2f, cx, cy - s * 0.35f)
                path.cubicTo(cx + s * 0.7f, cy - s * 1.2f, cx + s * 1.5f, cy - s * 0.1f, cx, cy + s * 0.9f)
                c.drawPath(path, p)
            }
            else -> {
                stroke(p, s * 0.18f)
                c.drawLine(cx - s * 0.7f, cy - s, cx - s * 0.7f, cy + s, p)
                path.reset()
                path.moveTo(cx - s * 0.7f, cy - s)
                path.lineTo(cx + s * 0.9f, cy - s * 0.5f)
                path.lineTo(cx - s * 0.7f, cy + s * 0.1f)
                c.drawPath(path, p)
            }
        }
    }

    /** Cat head with two ears (nods to Keqing's twin buns); eyes and nose are cut out. */
    fun neko(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        path.fillType = Path.FillType.WINDING
        rect.set(cx - s, cy - s * 0.55f, cx + s, cy + s * 0.95f)
        path.addOval(rect, Path.Direction.CW)
        path.moveTo(cx - s * 0.95f, cy + s * 0.05f)
        path.lineTo(cx - s * 0.78f, cy - s * 1.05f)
        path.lineTo(cx - s * 0.1f, cy - s * 0.5f)
        path.close()
        path.moveTo(cx + s * 0.95f, cy + s * 0.05f)
        path.lineTo(cx + s * 0.1f, cy - s * 0.5f)
        path.lineTo(cx + s * 0.78f, cy - s * 1.05f)
        path.close()
        path.addCircle(cx - s * 0.42f, cy + s * 0.12f, s * 0.15f, Path.Direction.CCW)
        path.addCircle(cx + s * 0.42f, cy + s * 0.12f, s * 0.15f, Path.Direction.CCW)
        path.moveTo(cx - s * 0.13f, cy + s * 0.42f)
        path.lineTo(cx, cy + s * 0.58f)
        path.lineTo(cx + s * 0.13f, cy + s * 0.42f)
        path.close()
        p.style = Paint.Style.FILL
        c.drawPath(path, p)
    }
}
