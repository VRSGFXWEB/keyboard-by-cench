package com.v4rr.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import android.widget.TextView
import android.view.inputmethod.InputMethodManager

class MainActivity : Activity() {

    private lateinit var pal: Palette
    private var statusEnable: TextView? = null
    private var statusDefault: TextView? = null
    private var statusFont: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Themes.palette(this)
        window.statusBarColor = pal.page
        window.navigationBarColor = pal.page
        if (!pal.dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val def = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        statusEnable?.text = if (enabled) "✓ Sudah aktif" else "✗ Belum aktif"
        statusEnable?.setTextColor(if (enabled) 0xFF30D158.toInt() else 0xFFFF453A.toInt())
        statusDefault?.text = if (def) "✓ Jadi keyboard default" else "✗ Belum jadi default"
        statusDefault?.setTextColor(if (def) 0xFF30D158.toInt() else 0xFFFF453A.toInt())
        val ff = FontStore.file(this)
        statusFont?.text = if (ff.exists()) "✓ Font emoji terpasang (${ff.length() / 1048576} MB)" else "✗ Belum ada font emoji (pakai bawaan HP)"
        statusFont?.setTextColor(if (ff.exists()) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Toast.makeText(this, "Memproses font…", Toast.LENGTH_SHORT).show()
            Thread {
                val err = FontStore.install(this, uri)
                runOnUiThread {
                    Toast.makeText(this, err ?: "Font emoji terpasang ✓", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }.start()
        }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextColor(color)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        return tv
    }

    private fun btn(label: String, filled: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(if (filled) 0xFFFFFFFF.toInt() else pal.text)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (filled) pal.accent else pal.sel)
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(16))
        c.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(pal.card) }
        c.addView(text(title, 13f, pal.hint, true).apply { setPadding(0, 0, 0, dp(10)) })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(14))
        parent.addView(c, lp)
        return c
    }

    private fun buildUi(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(pal.page)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(24))
        sv.addView(col)

        col.addView(text("V4RR Keyboard", 30f, pal.text, true))
        col.addView(text("Keyboard bergaya iOS + font emoji iOS", 14f, pal.hint).apply {
            setPadding(0, dp(2), 0, dp(18))
        })

        // --- aktivasi
        val a = card(col, "AKTIVASI")
        statusEnable = text("", 14f, pal.text, true)
        a.addView(text("1. Aktifkan keyboard di pengaturan", 15f, pal.text))
        a.addView(statusEnable)
        a.addView(btn("Buka pengaturan keyboard") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, mp())
        statusDefault = text("", 14f, pal.text, true)
        a.addView(text("2. Jadikan keyboard default", 15f, pal.text).apply { setPadding(0, dp(14), 0, 0) })
        a.addView(statusDefault)
        a.addView(btn("Pilih V4RR Keyboard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, mp())

        // --- tema
        val t = card(col, "TEMA")
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        val cur = Prefs.theme(this)
        listOf("Sistem", "Terang", "Gelap").forEachIndexed { i, n ->
            val b = btn(n, i == cur) { Prefs.setTheme(this, i); recreate() }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            seg.addView(b, lp)
        }
        t.addView(seg)

        // --- font emoji
        val f = card(col, "FONT EMOJI iOS")
        statusFont = text("", 14f, pal.text, true)
        f.addView(text("Pilih file NotoColorEmoji.ttf atau zip module Magisk (v26_4_xxx.zip) dari HP lu.", 13f, pal.hint))
        f.addView(statusFont, mp())
        f.addView(btn("Pilih file font / zip") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7
            )
        }, mp())
        f.addView(btn("Hapus font", false) { FontStore.delete(this); refreshStatus() }, mp())
        f.addView(sw("Pakai font emoji iOS di keyboard", "font", true))

        // --- ukuran
        val z = card(col, "UKURAN KEYBOARD")
        z.addView(text("Buka keyboard, lalu TAHAN tombol spasi (tulisan V4RR). Geser garis atas = tinggi, garis kiri/kanan = lebar, tombol panah = geser posisi.", 13f, pal.hint))
        z.addView(btn("Reset ukuran", false) {
            Prefs.setFloat(this, "kb_h", 1f); Prefs.setFloat(this, "kb_w", 1f); Prefs.setFloat(this, "kb_x", 0f)
            Toast.makeText(this, "Ukuran direset", Toast.LENGTH_SHORT).show()
        }, mp())

        // --- pengaturan
        val s = card(col, "PENGATURAN")
        s.addView(sw("Getar saat mengetik", "haptic", true))
        s.addView(sw("Suara tombol", "sound", false))
        s.addView(sw("Huruf besar otomatis", "autocap", true))
        s.addView(sw("Kata saran (tap untuk ganti, gak pernah otomatis)", "suggest", true))
        s.addView(sw("Spasi 2x jadi titik", "dotspace", false))
        s.addView(sw("Background blur (Android 12+)", "blur", true))

        // --- tes
        val x = card(col, "COBA KEYBOARD")
        val et = EditText(this)
        et.hint = "Ketik di sini…"
        et.setTextColor(pal.text)
        et.setHintTextColor(pal.hint)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.setPadding(dp(14), dp(12), dp(14), dp(12))
        et.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(pal.sel) }
        x.addView(et)

        col.addView(text("V4RR Keyboard v1.0", 12f, pal.hint).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        return sv
    }

    private fun mp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun sw(label: String, key: String, def: Boolean): Switch {
        val s = Switch(this)
        s.text = label
        s.setTextColor(pal.text)
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        s.setPadding(0, dp(8), 0, dp(8))
        s.isChecked = Prefs.bool(this, key, def)
        s.setOnCheckedChangeListener { _, v -> Prefs.setBool(this, key, v) }
        return s
    }
}
