package com.v4rr.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Foto background keyboard (disalin ke storage internal app). */
object BgStore {
    fun file(c: Context) = File(c.filesDir, "bg.jpg")
    fun stamp(c: Context): Long = file(c).let { if (it.exists()) it.lastModified() else 0L }
    private fun bump(c: Context) = Prefs.setInt(c, "bg_rev", Prefs.int(c, "bg_rev", 0) + 1)
    fun delete(c: Context) { file(c).delete(); bump(c) }

    /** null = sukses, selain itu pesan error. */
    fun install(c: Context, uri: Uri): String? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "Gak bisa baca gambar ini."
            var s = 1
            while (bounds.outWidth / s > 1600 || bounds.outHeight / s > 1600) s *= 2
            val opt = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
                ?: return "Gak bisa baca gambar ini."
            // rata-rata kecerahan (buat nentuin warna huruf keyboard)
            val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
            var sum = 0f
            for (y in 0 until 16) for (x in 0 until 16) sum += Themes.luminance(small.getPixel(x, y))
            Prefs.setFloat(c, "bg_lum", sum / 256f)
            file(c).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bump(c)
            return null
        } catch (e: Exception) {
            return "Gagal memproses foto: ${e.javaClass.simpleName}"
        }
    }

    /** Blur murah: kecilkan lalu box-blur lalu besarkan. level 0..100 */
    fun blurred(src: Bitmap, level: Int): Bitmap {
        if (level <= 0) return src
        val div = 4 + level / 12
        val w = maxOf(8, src.width / div)
        val h = maxOf(8, src.height / div)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val r = 2
        var a = px
        repeat(2) {
            val t = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dx in -r..r) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    val p = a[y * w + xx]
                    sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF; n++
                }
                t[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            }
            val u = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dy in -r..r) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    val p = t[yy * w + x]
                    sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF; n++
                }
                u[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            }
            a = u
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(out, src.width, src.height, true)
    }
}
