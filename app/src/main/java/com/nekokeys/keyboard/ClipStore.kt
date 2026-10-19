package com.nekokeys.keyboard

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** type 0 = text, 1 = image (data = file name inside filesDir/clips). */
class Clip(val id: Long, val type: Int, val data: String, var pinned: Boolean, var time: Long)

/** Clipboard history. Lives only in app-private storage; never leaves the device. */
class ClipStore private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var inst: ClipStore? = null
        fun get(c: Context): ClipStore = inst ?: synchronized(this) {
            inst ?: ClipStore(c.applicationContext).also { inst = it }
        }

        val TTLS = longArrayOf(3_600_000L, 86_400_000L, 604_800_000L, 0L) // 1h, 1d, 7d, never
        const val AUTHORITY = "com.nekokeys.keyboard.clips"
        private const val MAX_ITEMS = 60
        private const val MAX_IMAGES = 20
        private const val MAX_PINNED = 150
        private const val MIN_UNPINNED = 20
        private const val MAX_IMAGE_BYTES = 150L * 1024 * 1024  // all stored images together
        const val MAX_TEXT = 20_000

        fun mimeOfName(n: String) = when (n.substringAfterLast('.', "")) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/*"
        }

        fun uriOf(c: Clip): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(c.data).build()
    }

    private val items = ArrayList<Clip>()
    private val dir = File(ctx.filesDir, "clips")
    private val index = File(ctx.filesDir, "clips.tsv")

    init {
        dir.mkdirs()
        load()
    }

    private fun esc(s: String) = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }

    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                i++
                append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> s[i] })
            } else append(ch)
            i++
        }
    }

    private fun load() {
        try {
            if (index.exists()) index.forEachLine { l ->
                val p = l.split('\t', limit = 5)
                if (p.size == 5) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    items.add(Clip(id, p[1].toIntOrNull() ?: 0, unesc(p[4]), p[2] == "1", p[3].toLongOrNull() ?: 0L))
                }
            }
        } catch (e: Exception) { }
    }

    /** Temp file + rename, so a crash can never leave a half-written index. */
    private fun save() {
        try {
            val tmp = File(ctx.filesDir, "clips.tsv.tmp")
            val text = items.joinToString("\n") {
                "${it.id}\t${it.type}\t${if (it.pinned) 1 else 0}\t${it.time}\t${esc(it.data)}"
            }
            FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
        } catch (e: Exception) { }
    }

    private fun newId(): Long {
        var id = System.currentTimeMillis()
        while (items.any { it.id == id }) id++
        return id
    }

    private fun discard(c: Clip) {
        items.remove(c)
        if (c.type == 1) File(dir, c.data).delete()
    }

    private fun trim() {
        val un = items.filter { !it.pinned }.sortedByDescending { it.time }
        // always leave room for recent copies, even when many items are pinned
        val keep = (MAX_ITEMS - items.count { it.pinned }).coerceAtLeast(MIN_UNPINNED)
        un.drop(keep).forEach { discard(it) }
        un.filter { it.type == 1 }.drop(MAX_IMAGES).forEach { discard(it) }
    }

    @Synchronized fun all(): List<Clip> =
        items.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })

    @Synchronized fun addText(t: String) {
        if (t.isBlank() || t.length > MAX_TEXT) return
        val now = System.currentTimeMillis()
        val ex = items.firstOrNull { it.type == 0 && it.data == t }
        if (ex != null) ex.time = now else items.add(Clip(newId(), 0, t, false, now))
        trim()
        save()
    }

    /** Copies the image into private storage right away, because clip URIs are only temporarily readable. */
    fun addImage(uri: Uri, mime: String, pinned: Boolean = false) {
        if (pinned && synchronized(this) { items.count { it.type == 1 && it.pinned } } >= 40) return
        val ext = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "img" }
        val id = synchronized(this) { newId() }
        val f = File(dir, "$id.$ext")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return
            input.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 10_000_000) throw IOException("too big")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            f.delete()
            return
        }
        addImageFile(f, pinned)
    }

    /** Registers an image file that already sits in the clips folder, enforcing the storage limits. */
    fun addImageFile(f: File, pinned: Boolean = false): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        if (items.any { it.type == 1 && now - it.time < 3000 && File(dir, it.data).length() == f.length() }) {
            f.delete()
            return false
        }
        if (pinned && items.count { it.type == 1 && it.pinned } >= 40) { f.delete(); return false }
        val added = Clip(f.nameWithoutExtension.toLongOrNull() ?: newId(), 1, f.name, pinned, now)
        items.add(added)
        // all images together are capped: oldest unpinned ones go first
        var total = items.filter { it.type == 1 }.sumOf { File(dir, it.data).length() }
        for (c in items.filter { it.type == 1 && !it.pinned && it !== added }.sortedBy { it.time }) {
            if (total <= MAX_IMAGE_BYTES) break
            total -= File(dir, c.data).length()
            discard(c)
        }
        if (total > MAX_IMAGE_BYTES) { discard(added); return false }
        trim()
        save()
        true
    }

    /** Returns false when the pin limit is reached. */
    @Synchronized fun togglePin(id: Long): Boolean {
        val c = items.firstOrNull { it.id == id } ?: return true
        if (!c.pinned && items.count { it.pinned } >= MAX_PINNED) return false
        c.pinned = !c.pinned
        save()
        return true
    }
    @Synchronized fun remove(id: Long) { items.firstOrNull { it.id == id }?.let { discard(it); save() } }
    @Synchronized fun clearUnpinned() { items.filter { !it.pinned }.forEach { discard(it) }; save() }
    @Synchronized fun clearAll() { items.toList().forEach { discard(it) }; save() }

    @Synchronized fun expire(ttl: Long) {
        if (ttl <= 0) return
        val now = System.currentTimeMillis()
        val old = items.filter { !it.pinned && now - it.time > ttl }
        if (old.isNotEmpty()) { old.forEach { discard(it) }; save() }
    }

    fun fileOf(c: Clip) = File(dir, c.data)
}

/** Optional: lists the newest screenshots straight from MediaStore. Nothing is copied or indexed. */
object Screenshots {
    class Shot(val uri: Uri, val mime: String)

    fun permission() =
        if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"

    fun granted(ctx: Context) = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun recent(ctx: Context, n: Int): List<Shot> {
        if (!granted(ctx)) return emptyList()
        val out = ArrayList<Shot>()
        try {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%Screenshots%"))
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_ADDED} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, n)
            }
            val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE)
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out.add(Shot(uri, c.getString(1) ?: "image/png"))
                }
            }
        } catch (e: Exception) { }
        return out
    }
}
