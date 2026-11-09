package com.v4rr.keyboard

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Font disimpan di storage internal app. Ada 2 slot:
 *  - KIND_EMOJI : font emoji berwarna (NotoColorEmoji dll)
 *  - KIND_TEXT  : font huruf biasa, dipakai buat tombol & kata saran
 */
object FontStore {
    const val KIND_TEXT = 0
    const val KIND_EMOJI = 1

    class Staged(val kind: Int, val name: String, val error: String?)

    private fun slot(c: Context, kind: Int) = File(c.filesDir, if (kind == KIND_EMOJI) "emoji.ttf" else "text.ttf")
    fun file(c: Context, kind: Int = KIND_EMOJI) = slot(c, kind)
    fun staged(c: Context) = File(c.cacheDir, "pick.ttf")

    fun stamp(c: Context, kind: Int): Long = slot(c, kind).let { if (it.exists()) it.lastModified() else 0L }
    fun stamp(c: Context): Long = stamp(c, KIND_EMOJI)

    fun load(c: Context, kind: Int = KIND_EMOJI): Typeface? {
        val f = slot(c, kind)
        if (!f.exists()) return null
        return try { Typeface.createFromFile(f) } catch (e: Exception) { null }
    }

    private fun bump(c: Context) = Prefs.setInt(c, "font_rev", Prefs.int(c, "font_rev", 0) + 1)

    fun delete(c: Context, kind: Int) { slot(c, kind).delete(); bump(c) }

    private fun tags(f: File): List<String> {
        val out = ArrayList<String>()
        RandomAccessFile(f, "r").use { r ->
            r.seek(4)
            val n = r.readUnsignedShort()
            for (i in 0 until n) {
                r.seek(12L + 16L * i)
                val b = ByteArray(4)
                r.readFully(b)
                out.add(String(b, Charsets.ISO_8859_1))
            }
        }
        return out
    }

    /** null = valid, selain itu pesan error. */
    fun validate(f: File): String? {
        try {
            RandomAccessFile(f, "r").use { r ->
                if (r.length() < 12) return "File terlalu kecil, bukan font."
                val magic = r.readInt()
                val ok = magic == 0x00010000 || magic == 0x4F54544F || magic == 0x74727565 || magic == 0x74797031
                if (magic == 0x74746366) return "File .ttc (kumpulan font) belum didukung. Pakai .ttf atau .otf."
                if (!ok) return "File bukan font yang valid."
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
                    return "Font KEPOTONG (${f.length() / 1048576} MB dari ${maxEnd / 1048576} MB). File-nya rusak, download ulang."
            }
        } catch (e: Exception) {
            return "File bukan font yang valid."
        }
        return null
    }

    private fun isEmojiFont(f: File): Boolean {
        return try {
            val t = tags(f)
            t.contains("CBDT") || t.contains("COLR") || t.contains("sbix") || t.contains("SVG ")
        } catch (e: Exception) { false }
    }

    /** Terima .ttf/.otf langsung atau .zip. Hasil disimpan sementara, kinds dideteksi otomatis. */
    fun stage(c: Context, uri: Uri, displayName: String): Staged {
        val tmp = staged(c)
        tmp.delete()
        try {
            val raw = c.contentResolver.openInputStream(uri) ?: return Staged(0, displayName, "Gak bisa buka file.")
            val bis = BufferedInputStream(raw, 1 shl 16)
            bis.mark(4)
            val b0 = bis.read()
            val b1 = bis.read()
            bis.reset()
            if (b0 == 'P'.code && b1 == 'K'.code) {
                val zin = ZipInputStream(bis)
                var found = false
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    val n = e.name.lowercase()
                    val isFont = !e.isDirectory && (n.endsWith(".ttf") || n.endsWith(".otf"))
                    if (isFont) {
                        val emojiName = n.contains("emoji")
                        if (!found || emojiName) {
                            tmp.outputStream().use { o -> copy(zin, o) }
                            found = true
                            if (emojiName) break
                        }
                    }
                    e = zin.nextEntry
                }
                if (!found) { tmp.delete(); bis.close(); return Staged(0, displayName, "Gak nemu file .ttf/.otf di dalam zip.") }
            } else {
                tmp.outputStream().use { o -> copy(bis, o) }
            }
            bis.close()
        } catch (e: Exception) {
            tmp.delete()
            return Staged(0, displayName, "Gagal baca file (rusak/kepotong?): ${e.javaClass.simpleName}")
        }
        val err = validate(tmp)
        if (err != null) { tmp.delete(); return Staged(0, displayName, err) }
        return Staged(if (isEmojiFont(tmp)) KIND_EMOJI else KIND_TEXT, displayName, null)
    }

    /** Pasang font hasil stage ke keyboard. Null = sukses. */
    fun commit(c: Context, kind: Int): String? {
        val src = staged(c)
        if (!src.exists()) return "File font sementara hilang, pilih ulang."
        val dst = slot(c, kind)
        val tmp = File(c.filesDir, "font.tmp")
        return try {
            src.inputStream().use { i -> tmp.outputStream().use { o -> copy(i, o) } }
            dst.delete()
            if (!tmp.renameTo(dst)) "Gagal menyimpan font." else {
                Prefs.setBool(c, if (kind == KIND_EMOJI) "font" else "tfont", true)
                bump(c)
                null
            }
        } catch (e: Exception) { "Gagal menyimpan font: ${e.javaClass.simpleName}" }
    }

    /** Tulis file .ttf hasil stage ke output (misal file yang dipilih lewat "Simpan ke perangkat"). */
    fun writeFont(c: Context, out: OutputStream) {
        staged(c).inputStream().use { copy(it, out) }
    }

    /** Bikin modul Magisk (zip) dari font hasil stage. Nanti di-flash lewat Magisk/KernelSU buat ganti font sistem. */
    fun writeModule(c: Context, kind: Int, out: OutputStream) {
        val emoji = kind == KIND_EMOJI
        val id = if (emoji) "v4rr_emoji_font" else "v4rr_text_font"
        val name = if (emoji) "V4RR Emoji Font" else "V4RR Font"
        val targets = if (emoji) "NotoColorEmoji.ttf SamsungColorEmoji.ttf"
        else "Roboto-Regular.ttf RobotoStatic-Regular.ttf RobotoFlex-Regular.ttf GoogleSans-Regular.ttf GoogleSansText-Regular.ttf"
        val prop = "id=$id\nname=$name\nversion=v1\nversionCode=1\nauthor=V4RR Keyboard\n" +
                "description=Ganti font ${if (emoji) "emoji" else "huruf"} sistem dengan font pilihanmu (dibuat oleh V4RR Keyboard)\n"
        val updateBinary = "#!/sbin/sh\n" +
                "umask 022\n" +
                "ui_print() { echo \"\$1\"; }\n" +
                "require_new_magisk() {\n" +
                "  ui_print \"*******************************\"\n" +
                "  ui_print \" Butuh Magisk v20.4+ / KernelSU \"\n" +
                "  ui_print \"*******************************\"\n" +
                "  exit 1\n" +
                "}\n" +
                "OUTFD=\$2\n" +
                "ZIPFILE=\$3\n" +
                "mount /data 2>/dev/null\n" +
                "[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk\n" +
                ". /data/adb/magisk/util_functions.sh\n" +
                "[ \$MAGISK_VER_CODE -lt 20400 ] && require_new_magisk\n" +
                "install_module\n" +
                "exit 0\n"
        val customize = "ui_print \"- $name\"\n" +
                "mkdir -p \"\$MODPATH/system/fonts\"\n" +
                "FOUND=0\n" +
                "for n in $targets; do\n" +
                "  if [ -f \"/system/fonts/\$n\" ]; then\n" +
                "    cp -f \"\$MODPATH/font.ttf\" \"\$MODPATH/system/fonts/\$n\"\n" +
                "    ui_print \"  + \$n\"\n" +
                "    FOUND=1\n" +
                "  fi\n" +
                "done\n" +
                "rm -f \"\$MODPATH/font.ttf\"\n" +
                "[ \"\$FOUND\" = 0 ] && ui_print \"! Gak nemu font target di /system/fonts, HP ini mungkin gak didukung\"\n" +
                "set_permissions() {\n" +
                "  set_perm_recursive \$MODPATH 0 0 0755 0644\n" +
                "}\n"
        val z = ZipOutputStream(out)
        fun put(n: String, data: ByteArray) {
            z.putNextEntry(ZipEntry(n)); z.write(data); z.closeEntry()
        }
        put("META-INF/com/google/android/update-binary", updateBinary.toByteArray())
        put("META-INF/com/google/android/updater-script", "#MAGISK\n".toByteArray())
        put("module.prop", prop.toByteArray())
        put("customize.sh", customize.toByteArray())
        z.putNextEntry(ZipEntry("font.ttf"))
        staged(c).inputStream().use { copy(it, z) }
        z.closeEntry()
        z.finish()
        z.flush()
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
        }
    }
}
