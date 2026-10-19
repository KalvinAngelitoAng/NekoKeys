package com.nekokeys.keyboard

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class ClipPanel(private val ctx: Context, private val host: Host) : LinearLayout(ctx) {

    interface Host {
        fun onPasteText(s: String)
        fun onPasteImage(uri: Uri, mime: String): Boolean
        fun onClosePanel()
    }

    private var theme = THEMES[0]
    private var accent = theme.accent
    private var tab = 0
    private val store = ClipStore.get(ctx)
    private val prefs = Prefs(ctx)
    private val ui = Handler(Looper.getMainLooper())
    private val tabs = ArrayList<TextView>()
    private val back = IconView(ctx, IconView.BACK)
    private val trash = IconView(ctx, IconView.TRASH)
    private val empty = TextView(ctx)
    private val grid = GridLayout(ctx)
    private val scroll = ScrollView(ctx)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        back.setOnClickListener { host.onClosePanel() }
        bar.addView(back, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        listOf("Clipboard", "Screenshot").forEachIndexed { i, n ->
            val tv = TextView(ctx).apply {
                text = n
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { tab = i; rebuild() }
            }
            tabs.add(tv)
            bar.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        bar.addView(View(ctx), LayoutParams(0, 1, 1f))
        trash.setOnClickListener { store.clearUnpinned(); rebuild() }
        bar.addView(trash, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        grid.columnCount = 2
        empty.apply { gravity = Gravity.CENTER; textSize = 14f; setPadding(dp(24), dp(40), dp(24), dp(40)) }
        val content = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(8))
            addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        scroll.addView(content)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private var transparent = false

    fun applyTheme(t: KbTheme, transparentBg: Boolean) { theme = t; accent = t.accent; transparent = transparentBg }

    fun release() {
        grid.removeAllViews()
        empty.text = ""
    }

    fun show() {
        store.expire(ClipStore.TTLS[prefs.clipTtl.coerceIn(0, 3)])
        rebuild()
    }

    private fun shotsAvailable() = prefs.screenshots && Screenshots.granted(ctx)

    private fun rebuild() {
        setBackgroundColor(if (transparent) Color.TRANSPARENT else theme.bg)
        back.color = theme.text
        trash.color = theme.text
        if (tab == 1 && !shotsAvailable()) tab = 0
        tabs[1].visibility = if (shotsAvailable()) View.VISIBLE else View.GONE
        trash.visibility = if (tab == 0) View.VISIBLE else View.INVISIBLE
        tabs.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == tab) accent else theme.text and 0x99FFFFFF.toInt())
            tv.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        empty.setTextColor(theme.text and 0x99FFFFFF.toInt())
        grid.removeAllViews()
        if (tab == 0) {
            val clips = store.all()
            empty.text = if (clips.isEmpty()) "Belum ada salinan.\nTeks atau gambar yang kamu salin akan muncul di sini." else ""
            empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
            clips.forEach { grid.addView(if (it.type == 0) textCell(it) else imageCell(it), cellLp()) }
        } else {
            val shots = Screenshots.recent(ctx, 12)
            empty.text = if (shots.isEmpty()) "Belum ada screenshot." else ""
            empty.visibility = if (shots.isEmpty()) View.VISIBLE else View.GONE
            shots.forEach { grid.addView(shotCell(it), cellLp()) }
        }
        scroll.scrollTo(0, 0)
    }

    private fun cellLp() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL),
        GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f)
    ).apply {
        width = 0
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun card(pinned: Boolean): FrameLayout = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            setColor(theme.key)
            cornerRadius = dp(12).toFloat()
            if (pinned) setStroke(dp(2), accent)
        }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun textCell(c: Clip): View {
        val f = card(c.pinned)
        f.minimumHeight = dp(88)
        f.addView(TextView(ctx).apply {
            text = c.data
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            textSize = 14f
            setTextColor(theme.text)
            setPadding(dp(12), dp(10), dp(12), dp(38))
        })
        f.addView(actions(c, false), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        f.setOnClickListener { host.onPasteText(c.data) }
        return f
    }

    private fun imageCell(c: Clip): View {
        val f = card(c.pinned)
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        Thumbs.loadFile(iv, store.fileOf(c), ui)
        f.addView(actions(c, true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
        f.setOnClickListener { host.onPasteImage(ClipStore.uriOf(c), ClipStore.mimeOfName(c.data)) }
        return f
    }

    private fun shotCell(s: Screenshots.Shot): View {
        val f = card(false)
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)))
        Thread {
            try {
                val bmp = ctx.contentResolver.loadThumbnail(s.uri, Size(360, 360), null)
                ui.post { if (iv.isAttachedToWindow) iv.setImageBitmap(bmp) }
            } catch (e: Exception) { }
        }.start()
        f.setOnClickListener { host.onPasteImage(s.uri, s.mime) }
        return f
    }

    private fun actions(c: Clip, onImage: Boolean): LinearLayout {
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            if (onImage) background = GradientDrawable().apply { setColor(Color.argb(150, 0, 0, 0)); cornerRadius = dp(16).toFloat() }
        }
        val normal = if (onImage) Color.WHITE else theme.text and 0xB3FFFFFF.toInt()
        row.addView(IconView(ctx, IconView.PIN, if (c.pinned) accent else normal).apply {
            filled = c.pinned
            setOnClickListener {
                if (!store.togglePin(c.id)) Toast.makeText(ctx, "Maksimal 150 item bisa disematkan", Toast.LENGTH_SHORT).show()
                rebuild()
            }
        }, LayoutParams(dp(34), dp(34)))
        row.addView(IconView(ctx, IconView.TRASH, normal).apply {
            setOnClickListener { store.remove(c.id); rebuild() }
        }, LayoutParams(dp(34), dp(34)))
        return row
    }
}

/** Decodes small thumbnails off the main thread. Results are dropped if the view has since been detached. */
object Thumbs {
    fun loadFile(iv: ImageView, f: File, ui: Handler) {
        Thread {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var ss = 1
            while (o.outWidth / ss > 600) ss *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss })
            if (bmp != null) ui.post { if (iv.isAttachedToWindow) iv.setImageBitmap(bmp) else bmp.recycle() }
        }.start()
    }
}
