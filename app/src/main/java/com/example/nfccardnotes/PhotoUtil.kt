package com.example.nfccardnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** يخزّن صور البطاقات داخل تخزين التطبيق الخاص (خاص بالتطبيق، لا يراه غيره). */
object PhotoUtil {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "photos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** ينسخ صورة مختارة إلى تخزين التطبيق ويعيد اسم الملف. */
    fun savePhoto(context: Context, key: String, slot: Int, source: Uri): String? {
        return try {
            val name = "${key}_$slot.jpg"
            val out = File(dir(context), name)
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            name
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context, name: String) {
        if (name.isEmpty()) return
        try { File(dir(context), name).delete() } catch (_: Exception) { }
    }

    /** يحمّل الصورة بحجم مخفّض لتفادي استهلاك الذاكرة. */
    fun load(context: Context, name: String): Bitmap? {
        if (name.isEmpty()) return null
        val f = File(dir(context), name)
        if (!f.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            val target = 1000
            while (opts.outWidth / sample > target || opts.outHeight / sample > target) sample *= 2
            val real = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(f.absolutePath, real)
        } catch (e: Exception) {
            null
        }
    }
}
