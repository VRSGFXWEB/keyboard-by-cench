package com.v4rr.keyboard

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/** Font emoji disimpan di storage internal app (dipilih user lewat file picker). */
object FontStore {
    fun file(c: Context) = File(c.filesDir, "emoji.ttf")
    fun stamp(c: Context): Long = file(c).let { if (it.exists()) it.lastModified() else 0L }

    fun load(c: Context): Typeface? {
        val f = file(c)
        if (!f.exists()) return null
        return try { Typeface.createFromFile(f) } catch (e: Exception) { null }
    }

    fun delete(c: Context) { file(c).delete() }

    /** null = valid, selain itu pesan error. Cek semua tabel font muat di dalam file (deteksi file kepotong). */
    fun validate(f: File): String? {
        try {
            RandomAccessFile(f, "r").use { r ->
                if (r.length() < 12) return "File terlalu kecil, bukan font."
                r.seek(4)
                val n = r.readUnsignedShort()
                var maxEnd = 0L
                for (i in 0 until n) {
                    r.seek(12L + 16L * i + 8)
                    val off = r.readInt().toLong() and 0xFFFFFFFFL
                    val len = r.readInt().toLong() and 0xFFFFFFFFL
                    if (off + len > maxEnd) maxEnd = off + len
                }
                if (f.length() + 3 < maxEnd)
                    return "Font KEPOTONG (${f.length() / 1048576} MB dari ${maxEnd / 1048576} MB). Zip/file-nya rusak, download ulang."
            }
        } catch (e: Exception) {
            return "File bukan font yang valid."
        }
        return null
    }

    /** Terima .ttf langsung atau .zip (module Magisk). Return pesan error atau null kalau sukses. */
    fun install(c: Context, uri: Uri): String? {
        val tmp = File(c.filesDir, "emoji.tmp")
        try {
            val raw = c.contentResolver.openInputStream(uri) ?: return "Gak bisa buka file."
            val bis = BufferedInputStream(raw, 1 shl 16)
            bis.mark(4)
            val b0 = bis.read(); val b1 = bis.read()
            bis.reset()
            val isZip = b0 == 'P'.code && b1 == 'K'.code
            if (isZip) {
                val zin = ZipInputStream(bis)
                var chosen = false
                var e = zin.nextEntry
                while (e != null) {
                    val n = e.name.lowercase()
                    if (!e.isDirectory && n.endsWith(".ttf") && n.contains("emoji")) {
                        tmp.outputStream().use { o -> copy(zin, o) }
                        chosen = true
                        break
                    }
                    e = zin.nextEntry
                }
                if (!chosen) { tmp.delete(); return "Gak nemu NotoColorEmoji.ttf di dalam zip." }
            } else {
                tmp.outputStream().use { o -> copy(bis, o) }
            }
            bis.close()
        } catch (e: Exception) {
            tmp.delete()
            return "Gagal baca file (zip rusak/kepotong?): ${e.javaClass.simpleName}"
        }
        val err = validate(tmp)
        if (err != null) { tmp.delete(); return err }
        val dst = file(c)
        dst.delete()
        if (!tmp.renameTo(dst)) return "Gagal menyimpan font."
        return null
    }

    private fun copy(i: InputStream, o: java.io.OutputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
        }
    }
}
