package com.v4rr.keyboard

import android.content.Context
import android.content.res.Configuration

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("v4rr", Context.MODE_PRIVATE)

    /** 0 = ikut sistem, 1 = terang, 2 = gelap */
    fun theme(c: Context) = sp(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) = sp(c).edit().putInt("theme", v).apply()

    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun float(c: Context, k: String, d: Float) = sp(c).getFloat(k, d)
    fun setFloat(c: Context, k: String, v: Float) = sp(c).edit().putFloat(k, v).apply()

    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()

    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    fun remove(c: Context, vararg keys: String) {
        val e = sp(c).edit()
        for (k in keys) e.remove(k)
        e.apply()
    }

    fun registerListener(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).unregisterOnSharedPreferenceChangeListener(l)

    /** Gabungan semua pengaturan (kecuali emoji terakhir) buat deteksi perlu rebuild keyboard atau nggak. */
    fun sigString(c: Context): String {
        val m = sp(c).all
        val ks = m.keys.filter { it != "recent" }.sorted()
        val sb = StringBuilder()
        for (k in ks) sb.append(k).append('=').append(m[k]).append('|')
        return sb.toString()
    }

    fun recents(c: Context): List<String> =
        (sp(c).getString("recent", "") ?: "").split(" ").filter { it.isNotEmpty() }

    fun addRecent(c: Context, e: String) {
        val l = recents(c).toMutableList()
        l.remove(e)
        l.add(0, e)
        sp(c).edit().putString("recent", l.take(32).joinToString(" ")).apply()
    }

    // ---- background keyboard: 0 bawaan, 1 warna, 2 foto
    fun bgMode(c: Context) = int(c, "bg_mode", 0)
}

class Palette(
    val dark: Boolean,
    val page: Int, val card: Int,
    val bg: Int, val key: Int, val special: Int,
    val text: Int, val hint: Int, val panel: Int, val sel: Int, val accent: Int
)

object Themes {
    fun isDark(c: Context): Boolean = when (Prefs.theme(c)) {
        1 -> false
        2 -> true
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16 and 0xFF) * (1 - t) + (b shr 16 and 0xFF) * t).toInt()
        val g = ((a shr 8 and 0xFF) * (1 - t) + (b shr 8 and 0xFF) * t).toInt()
        val bl = ((a and 0xFF) * (1 - t) + (b and 0xFF) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun luminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Palet bawaan (dipakai juga oleh halaman aplikasi). */
    fun palette(c: Context): Palette =
        if (isDark(c)) Palette(
            true, 0xFF000000.toInt(), 0xFF1C1C1E.toInt(),
            0xFF0C0C0E.toInt(), 0xFF4D4D50.toInt(), 0xFF333335.toInt(),
            0xFFFFFFFF.toInt(), 0xFF8E8E93.toInt(), 0xFF121214.toInt(), 0xFF3A3A3C.toInt(), 0xFF0A84FF.toInt()
        ) else Palette(
            false, 0xFFF2F2F7.toInt(), 0xFFFFFFFF.toInt(),
            0xFFD2D5DB.toInt(), 0xFFFFFFFF.toInt(), 0xFFAEB4BE.toInt(),
            0xFF000000.toInt(), 0xFF9EA2A8.toInt(), 0xFFE8EAED.toInt(), 0xFFCFD2D8.toInt(), 0xFF007AFF.toInt()
        )

    private fun derive(base: Palette, bg: Int, dark: Boolean): Palette {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        return if (dark) Palette(
            true, base.page, base.card, bg,
            mix(bg, white, 0.24f), mix(bg, white, 0.12f),
            white, mix(bg, white, 0.6f), base.panel, mix(bg, white, 0.18f), 0xFF0A84FF.toInt()
        ) else Palette(
            false, base.page, base.card, bg,
            white, mix(bg, black, 0.16f),
            black, 0xFF8A8F96.toInt(), base.panel, mix(bg, black, 0.1f), 0xFF007AFF.toInt()
        )
    }

    /** Palet khusus keyboard: ikut warna/foto background yang dipilih. */
    fun keyboardPalette(c: Context): Palette {
        val base = palette(c)
        return when (Prefs.bgMode(c)) {
            1 -> {
                val col = Prefs.int(c, "bg_color", 0xFF1C1C1E.toInt()) or (0xFF shl 24)
                derive(base, col, luminance(col) < 0.55f)
            }
            2 -> {
                val dim = Prefs.int(c, "bg_dim", 25) / 100f
                val lum = Prefs.float(c, "bg_lum", 0.4f) * (1f - dim)
                val dark = lum < 0.5f
                derive(base, if (dark) 0xFF1C1C1E.toInt() else 0xFFD2D5DB.toInt(), dark)
            }
            else -> base
        }
    }
}
