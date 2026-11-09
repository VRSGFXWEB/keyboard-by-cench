package com.v4rr.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var pal: Palette
    private var statusEnable: TextView? = null
    private var statusDefault: TextView? = null
    private var statusFont: TextView? = null
    private var statusTFont: TextView? = null
    private var sampleText: TextView? = null
    private var sampleEmoji: TextView? = null
    private var bgBox: LinearLayout? = null
    private var staged: FontStore.Staged? = null
    private var exportKind = 0

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

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

    // ------------------------------------------------------------ status
    private fun refreshStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val def = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        statusEnable?.text = if (enabled) "✓ Sudah aktif" else "✗ Belum aktif"
        statusEnable?.setTextColor(if (enabled) 0xFF30D158.toInt() else 0xFFFF453A.toInt())
        statusDefault?.text = if (def) "✓ Jadi keyboard default" else "✗ Belum jadi default"
        statusDefault?.setTextColor(if (def) 0xFF30D158.toInt() else 0xFFFF453A.toInt())

        val fe = FontStore.file(this, FontStore.KIND_EMOJI)
        statusFont?.text = if (fe.exists()) "✓ Font emoji terpasang (${fe.length() / 1048576} MB)" else "✗ Font emoji: pakai bawaan HP"
        statusFont?.setTextColor(if (fe.exists()) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt())
        val ft = FontStore.file(this, FontStore.KIND_TEXT)
        statusTFont?.text = if (ft.exists()) "✓ Font huruf terpasang (${ft.length() / 1024} KB)" else "✗ Font huruf: pakai bawaan HP"
        statusTFont?.setTextColor(if (ft.exists()) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt())

        sampleText?.typeface = FontStore.load(this, FontStore.KIND_TEXT) ?: Typeface.DEFAULT
        sampleEmoji?.typeface = FontStore.load(this, FontStore.KIND_EMOJI) ?: Typeface.DEFAULT
    }

    // ------------------------------------------------------------ hasil pilih file
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            7 -> {
                Toast.makeText(this, "Memproses font…", Toast.LENGTH_SHORT).show()
                val name = displayName(uri)
                Thread {
                    val st = FontStore.stage(this, uri, name)
                    runOnUiThread {
                        if (st.error != null) Toast.makeText(this, st.error, Toast.LENGTH_LONG).show()
                        else { staged = st; showFontDialog(st) }
                    }
                }.start()
            }
            8 -> writeOut(uri, "Modul Magisk tersimpan ✓\nBuka Magisk → Modul → Pasang dari penyimpanan → pilih zip-nya → reboot.") {
                FontStore.writeModule(this, exportKind, it)
            }
            9 -> writeOut(uri, "File font tersimpan ✓") { FontStore.writeFont(this, it) }
            10 -> {
                Toast.makeText(this, "Memproses foto…", Toast.LENGTH_SHORT).show()
                Thread {
                    val err = BgStore.install(this, uri)
                    runOnUiThread {
                        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        else { Prefs.setInt(this, "bg_mode", 2); Toast.makeText(this, "Foto background terpasang ✓", Toast.LENGTH_SHORT).show() }
                        renderBg()
                    }
                }.start()
            }
        }
    }

    private fun writeOut(uri: Uri, okMsg: String, block: (java.io.OutputStream) -> Unit) {
        Thread {
            val err = try {
                contentResolver.openOutputStream(uri, "wt")?.use { block(it) } ?: throw IllegalStateException("gak bisa nulis")
                null
            } catch (e: Exception) { "Gagal menyimpan: ${e.javaClass.simpleName}" }
            runOnUiThread { Toast.makeText(this, err ?: okMsg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "font"
            }
        } catch (e: Exception) {}
        return "font"
    }

    private fun showFontDialog(st: FontStore.Staged) {
        val kindName = if (st.kind == FontStore.KIND_EMOJI) "font EMOJI berwarna" else "font HURUF (teks)"
        val items = arrayOf(
            "Pakai di keyboard saja",
            "Simpan ke perangkat: modul Magisk (.zip)",
            "Simpan ke perangkat: file font (.ttf)",
            "Pakai di keyboard + simpan modul Magisk"
        )
        AlertDialog.Builder(this)
            .setTitle("${st.name}\nTerdeteksi: $kindName")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> applyToKeyboard(st.kind)
                    1 -> exportModule(st)
                    2 -> startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("font/ttf").putExtra(Intent.EXTRA_TITLE, safeName(st.name, ".ttf")), 9)
                    3 -> { applyToKeyboard(st.kind); exportModule(st) }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun safeName(n: String, ext: String): String {
        val base = n.substringBeforeLast('.').ifBlank { "font" }
        return base + ext
    }

    private fun applyToKeyboard(kind: Int) {
        val err = FontStore.commit(this, kind)
        Toast.makeText(this, err ?: "Font terpasang di keyboard ✓", Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun exportModule(st: FontStore.Staged) {
        exportKind = st.kind
        val n = if (st.kind == FontStore.KIND_EMOJI) "V4RR-Emoji-Font-Magisk.zip" else "V4RR-Font-Magisk.zip"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, n), 8)
    }

    // ------------------------------------------------------------ helper UI
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
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, 0, 0, dp(14))
        parent.addView(c, lp)
        return c
    }

    private fun mp() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }

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

    /** Slider dengan label + nilai. min..max, onChange dipanggil tiap geser. */
    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, cur: Int, unit: String, onChange: (Int) -> Unit) {
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        val v = text("$cur$unit", 14f, pal.hint, true)
        head.addView(text(label, 14f, pal.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(v)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (cur - min).coerceIn(0, max - min)
        sb.progressTintList = ColorStateList.valueOf(pal.accent)
        sb.thumbTintList = ColorStateList.valueOf(pal.accent)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val x = p + min
                v.text = "$x$unit"
                if (fromUser) onChange(x)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        parent.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun pct(key: String, def: Float) = (Prefs.float(this, key, def) * 100).toInt()

    // ------------------------------------------------------------ background keyboard
    private fun blurSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try { getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true } catch (e: Exception) { false }
    }

    private fun alphaAndBlur(box: LinearLayout) {
        slider(box, "Transparansi background", 30, 100, Prefs.int(this, "bg_alpha", 70), "%") { Prefs.setInt(this, "bg_alpha", it) }
        box.addView(sw("Efek blur kaca (Android 12+)", "blur", true))
        slider(box, "Kekuatan blur", 10, 80, Prefs.int(this, "blur_radius", 40), "") { Prefs.setInt(this, "blur_radius", it) }
        val ok = blurSupported()
        box.addView(text(
            if (ok) "✓ HP ini mendukung blur sistem. Kalau mau blur lebih kelihatan, turunin Transparansi."
            else "✗ Blur sistem gak aktif di HP ini (butuh Android 12+ dan efek blur/transparansi nyala, bukan mode hemat baterai). Keyboard pakai transparan biasa. Alternatif: pakai Foto + Blur foto, itu jalan di semua HP.",
            12f, if (ok) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt()
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun renderBg() {
        val box = bgBox ?: return
        box.removeAllViews()
        val mode = Prefs.bgMode(this)

        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        listOf("Bawaan", "Warna", "Foto").forEachIndexed { i, n ->
            val b = btn(n, i == mode) { Prefs.setInt(this, "bg_mode", i); renderBg() }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            seg.addView(b, lp)
        }
        box.addView(seg)

        when (mode) {
            0 -> {
                box.addView(text("Warna ikut tema (terang/gelap) dengan efek kaca.", 13f, pal.hint), mp())
                alphaAndBlur(box)
            }
            1 -> {
                val cur = Prefs.int(this, "bg_color", 0xFF1C1C1E.toInt()) or (0xFF shl 24)
                box.addView(text("Pilih warna:", 13f, pal.hint), mp())
                val hs = HorizontalScrollView(this)
                hs.isHorizontalScrollBarEnabled = false
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val colors = listOf(
                    0xFF000000, 0xFF1C1C1E, 0xFF2C2C2E, 0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF453A,
                    0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF, 0xFFFFFFFF, 0xFFF2F2F7, 0xFFFFD1DC, 0xFFC7F9CC
                ).map { it.toInt() }
                for (c in colors) {
                    val sv = View(this)
                    sv.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(dp(if (c == cur) 3 else 1), if (c == cur) pal.accent else pal.hint)
                    }
                    sv.setOnClickListener { Prefs.setInt(this, "bg_color", c); renderBg() }
                    row.addView(sv, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) })
                }
                hs.addView(row)
                box.addView(hs)

                val hexRow = LinearLayout(this)
                hexRow.orientation = LinearLayout.HORIZONTAL
                hexRow.gravity = Gravity.CENTER_VERTICAL
                val et = EditText(this)
                et.hint = "#RRGGBB"
                et.setText(String.format("#%06X", cur and 0xFFFFFF))
                et.setTextColor(pal.text)
                et.setHintTextColor(pal.hint)
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                et.setSingleLine()
                hexRow.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
                hexRow.addView(btn("Terapkan") {
                    try {
                        var t = et.text.toString().trim()
                        if (!t.startsWith("#")) t = "#$t"
                        val c = Color.parseColor(t)
                        Prefs.setInt(this, "bg_color", c or (0xFF shl 24))
                        renderBg()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Kode warna salah. Contoh: #1C1C1E", Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
                box.addView(hexRow, mp())
                alphaAndBlur(box)
            }
            else -> {
                val has = BgStore.file(this).exists()
                box.addView(text(
                    if (has) "✓ Foto terpasang. Dipotong otomatis (center-crop) pas dengan keyboard."
                    else "✗ Belum ada foto. Pilih dari galeri.", 13f,
                    if (has) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt()
                ), mp())
                box.addView(btn("Pilih foto dari galeri") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 10)
                }, mp())
                if (has) box.addView(btn("Hapus foto", false) {
                    BgStore.delete(this); Prefs.setInt(this, "bg_mode", 0); renderBg()
                }, mp())
                slider(box, "Gelapkan foto", 0, 80, Prefs.int(this, "bg_dim", 25), "%") { Prefs.setInt(this, "bg_dim", it) }
                slider(box, "Blur foto", 0, 100, Prefs.int(this, "bg_blur", 0), "") { Prefs.setInt(this, "bg_blur", it) }
                box.addView(text("Tips: kalau tulisan susah kebaca, naikin Gelapkan foto atau turunin Transparansi tombol di kartu Ukuran Tombol.", 12f, pal.hint)
                    .apply { setPadding(0, dp(8), 0, 0) })
            }
        }
    }

    // ------------------------------------------------------------ UI utama
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.page)

        // kolom coba ketik, nempel di atas biar efek pengaturan langsung kelihatan
        val et = EditText(this)
        et.hint = "Coba ketik di sini… (pengaturan langsung berlaku)"
        et.setTextColor(pal.text)
        et.setHintTextColor(pal.hint)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.maxLines = 3
        et.setPadding(dp(14), dp(10), dp(14), dp(10))
        et.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(pal.card) }
        root.addView(et, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(36), dp(16), dp(6)) })

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(24))
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        col.addView(text("V4RR Keyboard", 30f, pal.text, true))
        col.addView(text("Keyboard bergaya iOS, Bahasa Indonesia", 14f, pal.hint).apply {
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
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            seg.addView(b, lp)
        }
        t.addView(seg)

        // --- background
        val bgCard = card(col, "BACKGROUND KEYBOARD")
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        bgCard.addView(box)
        bgBox = box
        renderBg()

        // --- ukuran tombol
        val z = card(col, "UKURAN TOMBOL")
        z.addView(text("Geser, langsung berlaku di keyboard. Bisa juga tahan tombol spasi (tulisan V4RR) buat ubah ukuran & posisi pakai tangan.", 13f, pal.hint))
        slider(z, "Tinggi tombol", 70, 145, pct("kb_h", 1f), "%") { Prefs.setFloat(this, "kb_h", it / 100f) }
        slider(z, "Lebar keyboard", 60, 100, pct("kb_w", 1f), "%") {
            Prefs.setFloat(this, "kb_w", it / 100f)
            val x = Prefs.float(this, "kb_x", 0f)
            if (x > 1f - it / 100f) Prefs.setFloat(this, "kb_x", 1f - it / 100f)
        }
        slider(z, "Ukuran huruf", 70, 150, Prefs.int(this, "kb_text", 100), "%") { Prefs.setInt(this, "kb_text", it) }
        slider(z, "Jarak antar tombol", 0, 10, Prefs.int(this, "kb_gap", 3), "dp") { Prefs.setInt(this, "kb_gap", it) }
        slider(z, "Lengkung sudut tombol", 0, 24, Prefs.int(this, "kb_rad", 10), "dp") { Prefs.setInt(this, "kb_rad", it) }
        slider(z, "Transparansi tombol", 20, 100, Prefs.int(this, "key_alpha", 100), "%") { Prefs.setInt(this, "key_alpha", it) }
        z.addView(btn("Reset ukuran & tampilan tombol", false) {
            Prefs.setFloat(this, "kb_h", 1f); Prefs.setFloat(this, "kb_w", 1f); Prefs.setFloat(this, "kb_x", 0f)
            Prefs.remove(this, "kb_text", "kb_gap", "kb_rad", "key_alpha")
            Toast.makeText(this, "Direset", Toast.LENGTH_SHORT).show()
            recreate()
        }, mp())

        // --- font
        val f = card(col, "FONT")
        f.addView(text("Pilih file .ttf / .otf atau .zip (termasuk zip module Magisk). Jenisnya (emoji / huruf) dideteksi otomatis, lalu pilih mau dipasang ke keyboard dan/atau disimpan ke perangkat.", 13f, pal.hint))
        statusFont = text("", 14f, pal.text, true)
        statusTFont = text("", 14f, pal.text, true)
        f.addView(statusFont, mp())
        f.addView(statusTFont)
        f.addView(btn("Pilih file font") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7)
        }, mp())
        f.addView(btn("Hapus font emoji", false) { FontStore.delete(this, FontStore.KIND_EMOJI); refreshStatus() }, mp())
        f.addView(btn("Hapus font huruf", false) { FontStore.delete(this, FontStore.KIND_TEXT); refreshStatus() }, mp())
        f.addView(sw("Pakai font emoji di keyboard", "font", true))
        f.addView(sw("Pakai font huruf di keyboard", "tfont", true))
        f.addView(text("PRATINJAU", 12f, pal.hint, true).apply { setPadding(0, dp(12), 0, dp(4)) })
        sampleText = text("Halo dunia! Aa Bb Cc 123", 22f, pal.text)
        sampleEmoji = text("😀😍🔥👍❤️🥳", 26f, pal.text)
        f.addView(sampleText)
        f.addView(sampleEmoji)
        f.addView(text("Catatan: \"Simpan ke perangkat\" bikin modul Magisk (butuh root + Magisk/KernelSU) buat ganti font SISTEM. Tanpa root, Android gak mengizinkan aplikasi biasa mengganti font sistem, jadi tanpa root font cuma kepakai di keyboard ini.", 12f, pal.hint)
            .apply { setPadding(0, dp(10), 0, 0) })

        // --- kata saran
        val k = card(col, "KATA SARAN & KOREKSI (BAHASA INDONESIA)")
        k.addView(sw("Tampilkan kata saran", "suggest", true))
        k.addView(sw("Koreksi otomatis saat tekan spasi", "autocorrect", true))
        k.addView(text("Typo yang jelas (misal \"bsok\" → \"besok\") dibenerin pas spasi. Salah koreksi? Tekan backspace sekali, balik ke kata asli, dan keyboard belajar kata itu. Kata yang sering lu ketik otomatis dipelajari (cuma di HP ini).", 12f, pal.hint))
        k.addView(text("PINTASAN TEKS (satu per baris: singkatan=kata)", 12f, pal.hint, true).apply { setPadding(0, dp(12), 0, dp(4)) })
        val sc = EditText(this)
        sc.hint = "yg=yang\ngmn=gimana\ntdk=tidak"
        sc.setText(Prefs.str(this, "shortcuts", ""))
        sc.setTextColor(pal.text)
        sc.setHintTextColor(pal.hint)
        sc.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        sc.minLines = 3
        sc.gravity = Gravity.TOP
        sc.setPadding(dp(14), dp(10), dp(14), dp(10))
        sc.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(pal.sel) }
        k.addView(sc)
        k.addView(btn("Simpan pintasan") {
            Prefs.setStr(this, "shortcuts", sc.text.toString())
            Toast.makeText(this, "Pintasan tersimpan ✓", Toast.LENGTH_SHORT).show()
        }, mp())
        k.addView(btn("Hapus semua kata yang dipelajari", false) {
            getSharedPreferences("v4rr_learn", Context.MODE_PRIVATE).edit().clear().apply()
            Prefs.setInt(this, "learn_epoch", Prefs.int(this, "learn_epoch", 0) + 1)
            Toast.makeText(this, "Kata yang dipelajari dihapus", Toast.LENGTH_SHORT).show()
        }, mp())

        // --- pengaturan
        val s = card(col, "PENGATURAN")
        s.addView(sw("Getar saat mengetik", "haptic", true))
        s.addView(sw("Suara tombol", "sound", false))
        s.addView(sw("Huruf besar otomatis", "autocap", true))
        s.addView(sw("Balik ke huruf otomatis (setelah simbol / spasi di layar angka)", "autoabc", true))
        s.addView(sw("Spasi 2x jadi titik", "dotspace", false))

        col.addView(text("V4RR Keyboard v1.1", 12f, pal.hint).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        return root
    }
}
