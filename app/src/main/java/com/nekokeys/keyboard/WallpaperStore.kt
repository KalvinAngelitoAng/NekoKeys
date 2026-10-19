package com.nekokeys.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import kotlin.math.max

/** Centre-crops the bitmap to whatever size it is drawn at, then dims it so keys stay readable. */
class WallpaperDrawable(private var bmp: Bitmap?, dimPercent: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val src = Rect()

    var dim = dimPercent
        set(v) { field = v; dimPaint.color = Color.argb(v.coerceIn(0, 90) * 255 / 100, 0, 0, 0); invalidateSelf() }

    init { dim = dimPercent }

    override fun draw(c: Canvas) {
        val b = bmp ?: return
        val r = bounds
        if (r.isEmpty || b.isRecycled) return
        val scale = max(r.width() / b.width.toFloat(), r.height() / b.height.toFloat())
        val sw = (r.width() / scale).toInt().coerceAtMost(b.width)
        val sh = (r.height() / scale).toInt().coerceAtMost(b.height)
        val l = (b.width - sw) / 2
        val t = (b.height - sh) / 2
        src.set(l, t, l + sw, t + sh)
        c.drawBitmap(b, src, r, paint)
        c.drawRect(r, dimPaint)
    }

    /** Frees the pixels right away instead of waiting for the GC. Never draw this drawable afterwards. */
    fun release() {
        val b = bmp
        bmp = null
        if (b != null && !b.isRecycled) b.recycle()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

object WallpaperStore {
    private const val MAX_SIDE = 1600

    fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")
    fun has(ctx: Context) = file(ctx).exists()
    fun stamp(ctx: Context) = file(ctx).lastModified()
    fun clear(ctx: Context) { file(ctx).delete() }

    fun load(ctx: Context, dim: Int): WallpaperDrawable? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var ss = 1
            while (bounds.outWidth / ss > 1300) ss *= 2
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = ss }
            val bmp = BitmapFactory.decodeFile(f.path, o) ?: return null
            WallpaperDrawable(bmp, dim)
        } catch (e: Throwable) { null }
    }

    /** Decodes a picked image at reduced size and stores it as a private JPEG. Call off the main thread. */
    fun saveFrom(ctx: Context, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return false
            var ss = 1
            while (max(bounds.outWidth, bounds.outHeight) / ss > MAX_SIDE) ss *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss })
            } ?: return false
            file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            true
        } catch (e: Throwable) { false }
    }
}
