package com.nekokeys.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

class IconView(context: Context, kind: Int = GHOST, color: Int = Color.WHITE) : View(context) {
    companion object {
        const val GHOST = 0; const val CHECK = 1; const val CLIP = 2; const val TRASH = 3
        const val PIN = 4; const val BACK = 5; const val ONEHAND = 6; const val SWAP = 7; const val EXPAND = 8
        const val FACE = 9; const val GEAR = 10; const val PLUS = 11; const val BACKSPACE = 12; const val NEKO = 13
        const val CAT = 100 // CAT + n = emoji category n
    }

    var kind = kind
        set(v) { field = v; invalidate() }
    var color = color
        set(v) { field = v; invalidate() }
    var filled = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        p.color = color
        val s = min(width, height) * 0.2f
        val cx = width / 2f
        val cy = height / 2f
        when (kind) {
            GHOST -> Icons.ghost(c, cx, cy, s, p)
            CHECK -> Icons.check(c, cx, cy, s * 0.8f, p)
            CLIP -> Icons.clipboard(c, cx, cy, s, p)
            TRASH -> Icons.trash(c, cx, cy, s, p)
            PIN -> Icons.pin(c, cx, cy, s, p, filled)
            BACK -> Icons.back(c, cx, cy, s, p)
            ONEHAND -> Icons.oneHand(c, cx, cy, s, p)
            SWAP -> Icons.swap(c, cx, cy, s, p)
            EXPAND -> Icons.expand(c, cx, cy, s, p)
            FACE -> Icons.face(c, cx, cy, s, p)
            GEAR -> Icons.gear(c, cx, cy, s, p)
            PLUS -> Icons.plus(c, cx, cy, s, p)
            BACKSPACE -> Icons.backspace(c, cx, cy, s, p)
            NEKO -> Icons.neko(c, cx, cy, s * 1.1f, p)
            else -> if (kind >= CAT) Icons.cat(c, kind - CAT, cx, cy, s, p)
        }
    }
}
