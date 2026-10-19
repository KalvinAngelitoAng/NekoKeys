package com.nekokeys.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Invisible activity: opens the system picker, copies the chosen images into the private GIF/sticker collection. */
class PickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val i = Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, 21) } catch (e: Exception) { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) { finish(); return }
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        val app = applicationContext
        Thread {
            val store = ClipStore.get(app)
            var n = 0
            for (u in uris) {
                val mime = contentResolver.getType(u) ?: "image/jpeg"
                if (mime.startsWith("image/")) { store.addImage(u, mime, true); n++ }
            }
            runOnUiThread {
                Toast.makeText(this, "$n gambar ditambahkan ke koleksi", Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }
}
