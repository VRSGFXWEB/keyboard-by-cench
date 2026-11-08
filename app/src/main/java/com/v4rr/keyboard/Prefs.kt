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

    fun recents(c: Context): List<String> =
        (sp(c).getString("recent", "") ?: "").split(" ").filter { it.isNotEmpty() }

    fun addRecent(c: Context, e: String) {
        val l = recents(c).toMutableList()
        l.remove(e)
        l.add(0, e)
        sp(c).edit().putString("recent", l.take(32).joinToString(" ")).apply()
    }
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
}
