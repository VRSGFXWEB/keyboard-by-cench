package com.v4rr.keyboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.view.inputmethod.InputConnection
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

class V4rrIME : InputMethodService() {

    companion object {
        var instance: V4rrIME? = null
        var pendingVoice: String? = null
        fun deliver(t: String) {
            pendingVoice = t
            instance?.let { i -> i.handler.postDelayed({ i.flushVoice() }, 700) }
        }
    }

    private class KeyCell(val cell: FrameLayout, val label: TextView?, val icon: ImageView?, val bg: GradientDrawable)

    /** Balon preview huruf (kayak iOS): badan lebar + leher yang nyambung ke tombol. */
    private class PreviewView(c: Context) : View(c) {
        var text = ""; var keyW = 0; var keyH = 0; var keyX = 0
        var fill = 0xFF636366.toInt(); var txt = 0xFFFFFFFF.toInt()
        var face: Typeface? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val path = Path()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = keyH * 0.30f
            val rk = keyH * 0.22f
            val bodyH = keyH * 1.30f
            val nl = keyX.toFloat()
            val nr = keyX + keyW.toFloat()
            val y1 = bodyH - r
            val y2 = bodyH + r * 0.9f
            path.reset()
            path.moveTo(r, 0f)
            path.lineTo(w - r, 0f)
            path.quadTo(w, 0f, w, r)
            path.lineTo(w, y1)
            path.cubicTo(w, y1 + r * 1.2f, nr, y2 - r * 0.9f, nr, y2)
            path.lineTo(nr, h - rk)
            path.quadTo(nr, h, nr - rk, h)
            path.lineTo(nl + rk, h)
            path.quadTo(nl, h, nl, h - rk)
            path.lineTo(nl, y2)
            path.cubicTo(nl, y2 - r * 0.9f, 0f, y1 + r * 1.2f, 0f, y1)
            path.lineTo(0f, r)
            path.quadTo(0f, 0f, r, 0f)
            path.close()
            p.style = Paint.Style.FILL
            p.color = fill
            c.drawPath(path, p)
            tp.color = txt
            tp.typeface = face ?: Typeface.DEFAULT
            tp.textSize = keyH * 0.78f
            val fm = tp.fontMetrics
            c.drawText(text, w / 2f, bodyH / 2f - (fm.ascent + fm.descent) / 2f, tp)
        }
    }

    /** Background foto keyboard (center-crop, pojok atas membulat, bisa di-dim). */
    private class BgView(c: Context, val bmp: Bitmap, val dim: Int, val radius: Float) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip = Path()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            clip.reset()
            clip.addRoundRect(RectF(0f, 0f, w, h), floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val sc = maxOf(w / bmp.width, h / bmp.height)
            val sw = bmp.width * sc; val sh = bmp.height * sc
            val l = (w - sw) / 2f; val t = (h - sh) / 2f
            c.drawBitmap(bmp, null, RectF(l, t, l + sw, t + sh), p)
            if (dim > 0) c.drawColor((dim * 255 / 100) shl 24)
            c.restore()
        }
    }

    // ---- state
    private lateinit var pal: Palette
    private var host: FrameLayout? = null
    private lateinit var frame: FrameLayout
    private lateinit var suggestBar: LinearLayout
    private lateinit var body: FrameLayout
    private var builtSig = ""

    private var layer = 0            // 0 huruf, 1 angka, 2 simbol
    private var shift = 0            // 0 off, 1 sekali, 2 caps lock
    private var emojiMode = false
    private var searchMode = false
    private var searchQuery = ""
    private var emojiCat = 1
    private var lastShiftTap = 0L
    private var lastSpace = 0L
    private var noLearn = false

    private var hs = 1f   // skala tinggi
    private var wf = 1f   // skala lebar
    private var xf = 0f   // posisi kiri (fraksi lebar layar)
    private var resizeOv: FrameLayout? = null

    private val handler = Handler(Looper.getMainLooper())
    private val letterViews = mutableListOf<TextView>()
    private var shiftKey: KeyCell? = null
    private var searchText: TextView? = null
    private var resultsRow: LinearLayout? = null

    private var previewPop: PopupWindow? = null
    private var previewView: PreviewView? = null

    // ---- font
    private var fontCache: Typeface? = null
    private var fontStamp = -1L
    private fun emojiFace(): Typeface? {
        if (!Prefs.bool(this, "font", true)) return null
        val s = FontStore.stamp(this)
        if (s != fontStamp) { fontStamp = s; fontCache = FontStore.load(this) }
        return fontCache
    }

    private var textFontCache: Typeface? = null
    private var textFontStamp = -1L
    private fun textFace(): Typeface? {
        if (!Prefs.bool(this, "tfont", true)) return null
        val st = FontStore.stamp(this, FontStore.KIND_TEXT)
        if (st != textFontStamp) { textFontStamp = st; textFontCache = FontStore.load(this, FontStore.KIND_TEXT) }
        return textFontCache
    }

    private val emojiIndex: List<Pair<String, List<String>>> by lazy {
        try {
            assets.open("emoji_keywords.txt").bufferedReader().readLines().mapNotNull { l ->
                val p = l.split("\t")
                if (p.size < 2) null else Pair(p[0], p[1].split(" "))
            }
        } catch (e: Exception) { emptyList() }
    }

    // ---- kata saran (mesin: SuggestEngine, 100% lokal)
    private lateinit var engine: SuggestEngine
    private var learnEpoch = 0
    private var undoOrig: String? = null
    private var suppressWord = ""
    private var undoNew: String? = null
    private var tsc = 1f       // skala ukuran huruf tombol
    private var keyGap = 3     // dp
    private var keyRad = 10    // dp
    private var keyAlpha = 100
    private lateinit var suggestScroll: HorizontalScrollView
    private var bgBitmap: Bitmap? = null
    private var bgStamp = -1L
    private var bgBlurSig = -1

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.removeCallbacks(rebuildRun)
        handler.postDelayed(rebuildRun, 150)
    }
    private val rebuildRun = Runnable {
        if (host != null && resizeOv == null && builtSig != sig()) {
            setInputView(build())
            applyWindowStyle()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun rowH() = (dp(50) * hs).toInt()
    private fun sig() = "${Themes.isDark(this)}|${FontStore.stamp(this, FontStore.KIND_EMOJI)}|${FontStore.stamp(this, FontStore.KIND_TEXT)}|${BgStore.stamp(this)}|${Prefs.sigString(this)}"

    // ------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        instance = this
        val lines = { f: String -> try { assets.open(f).bufferedReader().readLines() } catch (e: Exception) { emptyList<String>() } }
        engine = SuggestEngine(lines("words.txt"), lines("bigrams.txt"))
        learnEpoch = Prefs.int(this, "learn_epoch", 0)
        loadLearn()
        Prefs.registerListener(this, prefListener)
    }

    override fun onDestroy() {
        saveLearn()
        Prefs.unregisterListener(this, prefListener)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (host != null) setInputView(build())
    }

    override fun onCreateInputView(): View = build()

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val ep = Prefs.int(this, "learn_epoch", 0)
        if (ep != learnEpoch) { engine.clearLearn(); learnEpoch = ep }
        undoNew = null; undoOrig = null
        if (host != null && builtSig != sig()) setInputView(build())
        val ty = info?.inputType ?: 0
        val cls = ty and InputType.TYPE_MASK_CLASS
        val v = ty and InputType.TYPE_MASK_VARIATION
        noLearn = (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        emojiMode = false; searchMode = false; searchQuery = ""
        shift = 0
        applyWindowStyle()
        autoShift()
        renderAll()
        flushVoice()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        exitResize()
        hidePreview()
        saveLearn()
        super.onFinishInputView(finishingInput)
    }

    fun flushVoice() {
        val t = pendingVoice ?: return
        val ic = currentInputConnection ?: return
        ic.commitText("$t ", 1)
        pendingVoice = null
    }

    // ------------------------------------------------------------ build
    private fun build(): View {
        pal = Themes.keyboardPalette(this)
        tsc = Prefs.int(this, "kb_text", 100).coerceIn(70, 150) / 100f
        keyGap = Prefs.int(this, "kb_gap", 3).coerceIn(0, 10)
        keyRad = Prefs.int(this, "kb_rad", 10).coerceIn(0, 24)
        keyAlpha = Prefs.int(this, "key_alpha", 100).coerceIn(20, 100)
        engine.setShortcuts(Prefs.str(this, "shortcuts", ""))
        hs = Prefs.float(this, "kb_h", 1f).coerceIn(0.7f, 1.45f)
        wf = Prefs.float(this, "kb_w", 1f).coerceIn(0.6f, 1f)
        xf = Prefs.float(this, "kb_x", 0f).coerceIn(0f, 1f - wf)
        resizeOv = null

        val h = FrameLayout(this)
        frame = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 0, dp(6))
        suggestBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(suggestBar, FrameLayout.LayoutParams(WRAP, MATCH))
        }
        body = FrameLayout(this)
        col.addView(suggestScroll, LinearLayout.LayoutParams(MATCH, dp(42)))
        col.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        val bmp = loadBg()
        if (bmp != null) frame.addView(BgView(this, bmp, Prefs.int(this, "bg_dim", 25), dp(22).toFloat()), FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        h.addView(frame)
        host = h
        applyFrame()
        applyWindowStyle()
        builtSig = sig()
        renderAll()
        return h
    }

    private fun applyFrame() {
        val sw = resources.displayMetrics.widthPixels
        val lp = FrameLayout.LayoutParams((sw * wf).toInt(), WRAP, Gravity.BOTTOM or Gravity.START)
        lp.leftMargin = (sw * xf).toInt()
        frame.layoutParams = lp
    }

    private fun loadBg(): Bitmap? {
        if (Prefs.bgMode(this) != 2) return null
        val st = BgStore.stamp(this)
        val bl = Prefs.int(this, "bg_blur", 0)
        if (st != bgStamp || bl != bgBlurSig || bgBitmap == null) {
            bgStamp = st; bgBlurSig = bl
            bgBitmap = try {
                val f = BgStore.file(this)
                if (!f.exists()) null else {
                    val b = BitmapFactory.decodeFile(f.absolutePath)
                    if (b == null) null else BgStore.blurred(b, bl)
                }
            } catch (e: Throwable) { null }
        }
        return bgBitmap
    }

    /** Background jendela keyboard: transparan + blur bawaan sistem (Android 12+), diproses compositor. */
    private fun applyWindowStyle() {
        val w = window?.window ?: return
        val c = dp(22).toFloat()
        val d = GradientDrawable()
        d.cornerRadii = floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f)
        if (Prefs.bgMode(this) == 2 && loadBg() != null) {
            // foto digambar sendiri oleh BgView
            d.setColor(0)
            w.setBackgroundDrawable(d)
            setBlur(w, 0)
            return
        }
        var blur = false
        if (Build.VERSION.SDK_INT >= 31 && Prefs.bool(this, "blur", true)) {
            blur = try { getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true } catch (e: Exception) { false }
        }
        val a = if (blur) (Prefs.int(this, "bg_alpha", 70) * 255 / 100).coerceIn(40, 255) else 0xF2
        d.setColor((pal.bg and 0x00FFFFFF) or (a shl 24))
        w.setBackgroundDrawable(d)
        setBlur(w, if (blur) dp(Prefs.int(this, "blur_radius", 40)) else 0)
    }

    private fun setBlur(w: android.view.Window, radius: Int) {
        if (Build.VERSION.SDK_INT < 31) return
        try { w.setBackgroundBlurRadius(radius) } catch (e: Throwable) {}
        try {
            val lp = w.attributes
            if (radius > 0) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = radius
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
            }
            w.attributes = lp
        } catch (e: Throwable) {}
    }

    private fun renderAll() {
        if (host == null) return
        suggestScroll.visibility = if (emojiMode) View.GONE else View.VISIBLE
        when {
            emojiMode && searchMode -> renderSearch()
            emojiMode -> renderEmoji()
            else -> { renderKeys(); updateSuggestions() }
        }
    }

    // ------------------------------------------------------------ helper UI
    private fun haptic(v: View) {
        if (Prefs.bool(this, "haptic", true)) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (Prefs.bool(this, "sound", false))
            (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    private fun iconView(res: Int, color: Int): ImageView {
        val iv = ImageView(this)
        iv.setImageResource(res)
        iv.setColorFilter(color)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        return iv
    }

    private fun showPreview(v: View, text: String) {
        val hst = host ?: return
        try {
            val kw = v.width; val kh = v.height
            val pw = (kw * 1.55f).toInt(); val ph = (kh * 2.35f).toInt()
            val pv = previewView ?: PreviewView(this).also { previewView = it }
            pv.fill = if (pal.dark) 0xFF636366.toInt() else 0xFFFFFFFF.toInt()
            pv.txt = if (pal.dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            pv.face = textFace()
            pv.text = text; pv.keyW = kw; pv.keyH = kh
            // koordinat popup itu relatif ke JENDELA keyboard (bukan layar), jadi pakai getLocationInWindow
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val winW = maxOf(hst.rootView.width, resources.displayMetrics.widthPixels)
            val x = (loc[0] + kw / 2 - pw / 2).coerceIn(0, maxOf(0, winW - pw))
            val y = loc[1] + kh - ph
            pv.keyX = loc[0] - x
            pv.invalidate()
            val pop = previewPop ?: PopupWindow(pv, pw, ph).apply {
                isTouchable = false; isFocusable = false; isClippingEnabled = false
                setBackgroundDrawable(null)
            }.also { previewPop = it }
            if (pop.isShowing) pop.update(x, y, pw, ph) else pop.showAtLocation(hst, Gravity.NO_GRAVITY, x, y)
        } catch (e: Exception) {}
    }

    private fun hidePreview() { try { previewPop?.dismiss() } catch (e: Exception) {} }

    private fun attach(
        cell: View, bg: GradientDrawable, normal: () -> Int, pressed: Int, repeat: Boolean,
        preview: (() -> String)?, onLong: (() -> Unit)?, onTap: () -> Unit
    ) {
        var rep: Runnable? = null
        var longFired = false
        rep = Runnable { onTap(); handler.postDelayed(rep!!, 50) }
        val longRun = Runnable { longFired = true; hidePreview(); bg.setColor(normal()); onLong?.invoke() }
        cell.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    bg.setColor(pressed); haptic(v)
                    if (preview != null) showPreview(v, preview())
                    if (repeat) { onTap(); handler.postDelayed(rep!!, 400) }
                    if (onLong != null) handler.postDelayed(longRun, 500)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(rep!!); handler.removeCallbacks(longRun)
                    hidePreview(); bg.setColor(normal())
                    if (!repeat && !longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(rep!!); handler.removeCallbacks(longRun)
                    hidePreview(); bg.setColor(normal())
                }
            }
            true
        }
    }

    private fun newRow(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        parent.addView(r, LinearLayout.LayoutParams(MATCH, rowH()))
        return r
    }

    /** terapkan transparansi tombol ke warna */
    private fun ca(col: Int): Int {
        if (keyAlpha >= 100) return col
        val al = ((col ushr 24) and 0xFF) * keyAlpha / 100
        return (col and 0x00FFFFFF) or (al shl 24)
    }

    private fun key(
        row: LinearLayout, weight: Float, label: String? = null, icon: Int = 0,
        special: Boolean = false, size: Float = 24f, repeat: Boolean = false,
        colorOverride: Int? = null, normalFn: (() -> Int)? = null,
        preview: (() -> String)? = null, onLong: (() -> Unit)? = null, onTap: () -> Unit
    ): KeyCell {
        val cell = FrameLayout(this)
        val base = colorOverride ?: if (special) pal.special else pal.key
        val normal: () -> Int = if (colorOverride != null) { { base } } else { { ca(normalFn?.invoke() ?: base) } }
        val pressedC = colorOverride ?: ca(if (special) pal.key else pal.special)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(keyRad).toFloat()
        bg.setColor(normal())
        cell.background = bg
        val fg = if (colorOverride != null) 0xFFFFFFFF.toInt() else pal.text
        var tv: TextView? = null
        var iv: ImageView? = null
        if (label != null) {
            tv = TextView(this)
            tv.text = label
            tv.gravity = Gravity.CENTER
            tv.setTextColor(fg)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * tsc)
            textFace()?.let { tv.typeface = it }
            cell.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH))
        } else if (icon != 0) {
            iv = iconView(icon, fg)
            cell.addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        val lp = LinearLayout.LayoutParams(0, MATCH, weight)
        lp.setMargins(dp(keyGap), dp(keyGap + 1), dp(keyGap), dp(keyGap + 1))
        row.addView(cell, lp)
        attach(cell, bg, normal, pressedC, repeat, preview, onLong, onTap)
        return KeyCell(cell, tv, iv, bg)
    }

    private fun spacer(row: LinearLayout, w: Float) =
        row.addView(View(this), LinearLayout.LayoutParams(0, MATCH, w))

    // ------------------------------------------------------------ keyboard rows
    private fun shiftIcon() = when (shift) { 0 -> R.drawable.ic_shift; 1 -> R.drawable.ic_shift_on; else -> R.drawable.ic_caps }

    private fun charKey(row: LinearLayout, c: String) {
        val k = key(row, 1f, label = if (layer == 0 && shift > 0) c.uppercase() else c,
            preview = { if (layer == 0 && shift > 0) c.uppercase() else c }) {
            typeChar(if (layer == 0 && shift > 0) c.uppercase() else c)
        }
        if (layer == 0) { k.label?.tag = c; k.label?.let { letterViews.add(it) } }
    }

    private fun charRow(parent: LinearLayout, chars: String, pad: Float) {
        val r = newRow(parent)
        if (pad > 0) spacer(r, pad)
        for (c in chars) charKey(r, c.toString())
        if (pad > 0) spacer(r, pad)
    }

    private fun backKey(row: LinearLayout, w: Float) =
        key(row, w, icon = R.drawable.ic_backspace, special = true, repeat = true) { backspace() }

    private fun buildKeyRows(col: LinearLayout, withUtil: Boolean) {
        letterViews.clear()
        shiftKey = null
        when (layer) {
            0 -> {
                charRow(col, "qwertyuiop", 0f)
                charRow(col, "asdfghjkl", 0.5f)
                val r = newRow(col)
                shiftKey = key(r, 1.5f, icon = shiftIcon(), special = true,
                    normalFn = { if (shift > 0) pal.key else pal.special }) { onShift() }
                for (c in "zxcvbnm") charKey(r, c.toString())
                backKey(r, 1.5f)
            }
            1 -> {
                charRow(col, "1234567890", 0f)
                charRow(col, "-/:;()\$&@\"", 0f)
                val r = newRow(col)
                key(r, 1.5f, "#+=", special = true, size = 16f) { layer = 2; renderAll() }
                for (c in ".,?!'") key(r, 1.4f, c.toString(), preview = { c.toString() }) { typeChar(c.toString()) }
                backKey(r, 1.5f)
            }
            else -> {
                charRow(col, "[]{}#%^*+=", 0f)
                charRow(col, "_\\|~<>€£¥•", 0f)
                val r = newRow(col)
                key(r, 1.5f, "123", special = true, size = 16f) { layer = 1; renderAll() }
                for (c in ".,?!'") key(r, 1.4f, c.toString(), preview = { c.toString() }) { typeChar(c.toString()) }
                backKey(r, 1.5f)
            }
        }

        val b = newRow(col)
        if (layer == 0) key(b, 1.4f, "123", special = true, size = 16f) { layer = 1; renderAll() }
        else key(b, 1.4f, "ABC", special = true, size = 16f) { layer = 0; renderAll() }
        // tahan spasi (tulisan V4RR) = ubah ukuran keyboard
        val sp = key(b, 5f, "V4RR", size = 13f, onLong = { enterResize() }) { space() }
        sp.label?.setTextColor(pal.hint)
        val ea = actionId()
        if (searchMode) {
            key(b, 1.8f, icon = R.drawable.ic_search, special = true, colorOverride = pal.accent) {
                searchMode = false; renderAll()
            }
        } else if (ea != 0) {
            key(b, 1.8f, enterLabel(ea), special = true, size = 16f, colorOverride = pal.accent) { enter() }
        } else {
            key(b, 1.8f, icon = R.drawable.ic_return, special = true) { enter() }
        }

        if (withUtil) {
            val u = LinearLayout(this)
            u.orientation = LinearLayout.HORIZONTAL
            col.addView(u, LinearLayout.LayoutParams(MATCH, dp(40)))
            val e = iconView(R.drawable.ic_emoji, pal.text)
            e.setPadding(dp(18), dp(8), dp(18), dp(8))
            e.setOnClickListener { haptic(it); emojiMode = true; searchMode = false; renderAll() }
            e.setOnLongClickListener { pickIme(); true }
            u.addView(e, LinearLayout.LayoutParams(dp(68), MATCH))
            u.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f))
            val m = iconView(R.drawable.ic_mic, pal.text)
            m.setPadding(dp(18), dp(8), dp(18), dp(8))
            m.setOnClickListener { haptic(it); startVoice() }
            u.addView(m, LinearLayout.LayoutParams(dp(68), MATCH))
        }
    }

    private fun renderKeys() {
        body.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(3), dp(2), dp(3), 0)
        buildKeyRows(col, true)
        body.addView(col)
    }

    private fun pickIme() =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()

    private fun startVoice() {
        try { startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) {}
    }

    // ------------------------------------------------------------ emoji panel
    private fun pill(): GradientDrawable = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(pal.sel) }

    private fun renderEmoji() {
        body.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        // kolom cari
        val sb = LinearLayout(this)
        sb.orientation = LinearLayout.HORIZONTAL
        sb.gravity = Gravity.CENTER_VERTICAL
        sb.background = pill()
        sb.setPadding(dp(10), 0, dp(10), 0)
        sb.addView(iconView(R.drawable.ic_search, pal.hint), LinearLayout.LayoutParams(dp(18), dp(18)))
        sb.addView(TextView(this).apply {
            text = "Cari emoji"; setTextColor(pal.hint); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), 0, 0, 0)
        })
        sb.setOnClickListener { searchMode = true; searchQuery = ""; renderAll() }
        val slp = LinearLayout.LayoutParams(MATCH, dp(34))
        slp.setMargins(dp(10), dp(5), dp(10), dp(1))
        col.addView(sb, slp)

        val header = TextView(this)
        header.text = EmojiData.names[emojiCat]
        header.setTextColor(pal.hint)
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        header.typeface = Typeface.DEFAULT_BOLD
        header.setPadding(dp(14), dp(4), 0, 0)
        col.addView(header, LinearLayout.LayoutParams(MATCH, dp(22)))

        val list = if (emojiCat == 0) Prefs.recents(this) else EmojiData.sets[emojiCat - 1]
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        for (chunk in list.chunked(8)) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (i in 0 until 8) {
                val e = chunk.getOrNull(i)
                val tv = TextView(this)
                tv.gravity = Gravity.CENTER
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                emojiFace()?.let { tv.typeface = it }
                if (e != null) {
                    tv.text = e
                    tv.setOnClickListener {
                        haptic(it)
                        currentInputConnection?.commitText(e, 1)
                        Prefs.addRecent(this, e)
                    }
                }
                r.addView(tv, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            grid.addView(r, LinearLayout.LayoutParams(MATCH, dp(44)))
        }
        val gridH = maxOf(dp(110), dp(90) + 4 * rowH() - dp(136))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(grid)
        col.addView(sv, LinearLayout.LayoutParams(MATCH, gridH))

        // bar kategori
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), 0, dp(6), 0)
        val cats = intArrayOf(R.drawable.cat_recent, R.drawable.cat_smile, R.drawable.cat_animal, R.drawable.cat_food,
            R.drawable.cat_sport, R.drawable.cat_travel, R.drawable.cat_object, R.drawable.cat_symbol, R.drawable.cat_flag)
        for (i in cats.indices) {
            val iv = iconView(cats[i], if (i == emojiCat) pal.text else pal.hint)
            iv.setPadding(dp(7), dp(7), dp(7), dp(7))
            if (i == emojiCat) iv.background = pill()
            iv.setOnClickListener { haptic(it); emojiCat = i; renderEmoji() }
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            bar.addView(iv, lp)
        }
        val del = iconView(R.drawable.ic_backspace, pal.text)
        del.setPadding(dp(7), dp(7), dp(7), dp(7))
        val delBg = pill(); delBg.setColor(0)
        del.background = delBg
        attach(del, delBg, { 0 }, pal.sel, true, null, null) { backspace() }
        val dlp = LinearLayout.LayoutParams(0, dp(36), 1.2f)
        dlp.setMargins(dp(2), 0, dp(2), 0)
        bar.addView(del, dlp)
        col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(40)))

        // ABC + mic
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        val abc = TextView(this)
        abc.text = "ABC"
        abc.gravity = Gravity.CENTER
        abc.setTextColor(pal.text)
        abc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        abc.typeface = Typeface.DEFAULT_BOLD
        abc.paintFlags = abc.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        abc.setOnClickListener { haptic(it); emojiMode = false; renderAll() }
        bottom.addView(abc, LinearLayout.LayoutParams(dp(80), MATCH))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f))
        val m = iconView(R.drawable.ic_mic, pal.text)
        m.setPadding(dp(18), dp(6), dp(18), dp(6))
        m.setOnClickListener { haptic(it); startVoice() }
        bottom.addView(m, LinearLayout.LayoutParams(dp(68), MATCH))
        col.addView(bottom, LinearLayout.LayoutParams(MATCH, dp(34)))

        body.addView(col)
    }

    // ------------------------------------------------------------ cari emoji
    private fun renderSearch() {
        body.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(3), dp(2), dp(3), 0)

        val sb = LinearLayout(this)
        sb.orientation = LinearLayout.HORIZONTAL
        sb.gravity = Gravity.CENTER_VERTICAL
        sb.background = pill()
        sb.setPadding(dp(10), 0, dp(4), 0)
        sb.addView(iconView(R.drawable.ic_search, pal.hint), LinearLayout.LayoutParams(dp(18), dp(18)))
        val st = TextView(this)
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        st.setPadding(dp(8), 0, 0, 0)
        searchText = st
        sb.addView(st, LinearLayout.LayoutParams(0, WRAP, 1f))
        val x = TextView(this)
        x.text = "×"
        x.gravity = Gravity.CENTER
        x.setTextColor(pal.hint)
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        x.setOnClickListener { searchMode = false; renderAll() }
        sb.addView(x, LinearLayout.LayoutParams(dp(44), MATCH))
        val slp = LinearLayout.LayoutParams(MATCH, dp(38))
        slp.setMargins(dp(6), dp(4), dp(6), dp(2))
        col.addView(sb, slp)

        val hs2 = HorizontalScrollView(this)
        hs2.isHorizontalScrollBarEnabled = false
        val rr = LinearLayout(this)
        rr.orientation = LinearLayout.HORIZONTAL
        resultsRow = rr
        hs2.addView(rr)
        col.addView(hs2, LinearLayout.LayoutParams(MATCH, dp(48)))

        buildKeyRows(col, false)
        body.addView(col)
        updateSearch()
    }

    private fun searchEmoji(q: String): List<String> {
        val toks = q.lowercase().split(" ").filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        return emojiIndex.filter { (_, kw) -> toks.all { t -> kw.any { it.startsWith(t) } } }
            .map { it.first }.take(80)
    }

    private fun updateSearch() {
        searchText?.let {
            if (searchQuery.isEmpty()) { it.text = "Cari emoji (contoh: senyum, love, api)"; it.setTextColor(pal.hint) }
            else { it.text = searchQuery + "|"; it.setTextColor(pal.text) }
        }
        val r = resultsRow ?: return
        r.removeAllViews()
        val list = if (searchQuery.isBlank()) Prefs.recents(this).take(20) else searchEmoji(searchQuery)
        if (list.isEmpty()) {
            r.addView(TextView(this).apply {
                text = "Gak ada hasil"; setTextColor(pal.hint); gravity = Gravity.CENTER
                setPadding(dp(16), 0, 0, 0)
            }, LinearLayout.LayoutParams(WRAP, MATCH))
            return
        }
        for (e in list) {
            val tv = TextView(this)
            tv.text = e
            tv.gravity = Gravity.CENTER
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            emojiFace()?.let { tv.typeface = it }
            tv.setOnClickListener {
                haptic(it)
                currentInputConnection?.commitText(e, 1)
                Prefs.addRecent(this, e)
            }
            r.addView(tv, LinearLayout.LayoutParams(dp(46), MATCH))
        }
    }

    // ------------------------------------------------------------ ubah ukuran
    private fun saveSize() {
        Prefs.setFloat(this, "kb_h", hs); Prefs.setFloat(this, "kb_w", wf); Prefs.setFloat(this, "kb_x", xf)
        builtSig = sig()
    }

    private fun exitResize() {
        val ov = resizeOv ?: return
        saveSize()
        try { frame.removeView(ov) } catch (e: Exception) {}
        resizeOv = null
    }

    private fun enterResize() {
        if (resizeOv != null || host == null) return
        emojiMode = false; searchMode = false
        renderAll()
        val blue = 0xFF6EA8FF.toInt()
        val ov = FrameLayout(this)
        ov.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat(); setColor(0x66000000); setStroke(dp(2), pal.accent)
        }
        ov.isClickable = true

        fun pillBtn(t: String): TextView {
            val b = TextView(this)
            b.text = t
            b.setTextColor(0xFFFFFFFF.toInt())
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            b.typeface = Typeface.DEFAULT_BOLD
            b.gravity = Gravity.CENTER
            b.setPadding(dp(26), dp(13), dp(26), dp(13))
            b.background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(blue) }
            return b
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val reset = pillBtn("Reset")
        val done = pillBtn("Selesai")
        val mv = ImageView(this)
        mv.setImageResource(R.drawable.ic_move)
        mv.setColorFilter(0xFFFFFFFF.toInt())
        mv.setPadding(dp(13), dp(13), dp(13), dp(13))
        mv.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(blue) }
        row.addView(reset, LinearLayout.LayoutParams(WRAP, WRAP))
        row.addView(mv, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(12), 0, dp(12), 0) })
        row.addView(done, LinearLayout.LayoutParams(WRAP, WRAP))
        ov.addView(row, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        fun handle(w: Int, h: Int, pw: Int, ph: Int, g: Int): FrameLayout {
            val a = FrameLayout(this)
            val p = View(this)
            p.background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(blue) }
            a.addView(p, FrameLayout.LayoutParams(dp(pw), dp(ph), Gravity.CENTER))
            ov.addView(a, FrameLayout.LayoutParams(dp(w), dp(h), g))
            return a
        }
        val top = handle(120, 36, 44, 5, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        val left = handle(36, 120, 5, 44, Gravity.START or Gravity.CENTER_VERTICAL)
        val right = handle(36, 120, 5, 44, Gravity.END or Gravity.CENTER_VERTICAL)
        val sw = resources.displayMetrics.widthPixels.toFloat()

        var sy = 0f; var sh = 1f
        top.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sy = e.rawY; sh = hs }
                MotionEvent.ACTION_MOVE -> {
                    val n = (sh + (sy - e.rawY) / dp(290)).coerceIn(0.7f, 1.45f)
                    if (Math.abs(n - hs) > 0.015f) { hs = n; renderAll() }
                }
            }
            true
        }
        var sx = 0f; var sxf = 0f; var swf = 1f
        left.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sxf = xf; swf = wf }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (sxf + (e.rawX - sx) / sw).coerceIn(0f, sxf + swf - 0.6f)
                    wf = swf - (nx - sxf); xf = nx; applyFrame()
                }
            }
            true
        }
        right.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sxf = xf; swf = wf }
                MotionEvent.ACTION_MOVE -> {
                    wf = (swf + (e.rawX - sx) / sw).coerceIn(0.6f, 1f - xf); applyFrame()
                }
            }
            true
        }
        mv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sxf = xf }
                MotionEvent.ACTION_MOVE -> {
                    xf = (sxf + (e.rawX - sx) / sw).coerceIn(0f, 1f - wf); applyFrame()
                }
            }
            true
        }
        reset.setOnClickListener { hs = 1f; wf = 1f; xf = 0f; applyFrame(); renderAll() }
        done.setOnClickListener { exitResize() }

        frame.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        resizeOv = ov
    }

    // ------------------------------------------------------------ logika input
    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        when {
            shift == 2 -> setShift(0)
            shift == 1 && now - lastShiftTap < 350 -> setShift(2)
            shift == 1 -> setShift(0)
            else -> setShift(1)
        }
        lastShiftTap = now
    }

    private fun setShift(v: Int) {
        shift = v
        if (layer != 0) return
        for (t in letterViews) {
            val s = t.tag as? String ?: continue
            t.text = if (shift > 0) s.uppercase() else s
        }
        shiftKey?.let { it.bg.setColor(ca(if (shift > 0) pal.key else pal.special)); it.icon?.setImageResource(shiftIcon()) }
    }

    private fun autoShift() {
        if (shift == 2) return
        if (searchMode) { setShift(0); return }
        val ei = currentInputEditorInfo
        val ic = currentInputConnection
        var want = 0
        if (Prefs.bool(this, "autocap", true) && ei != null && ic != null && ei.inputType != 0)
            want = ic.getCursorCapsMode(ei.inputType)
        setShift(if (want != 0) 1 else 0)
    }

    private fun typeChar(s: String) {
        if (searchMode) { searchQuery += s.lowercase(); updateSearch(); if (shift == 1) setShift(0); return }
        val ic = currentInputConnection ?: return
        ic.commitText(s, 1)
        lastSpace = 0
        undoNew = null
        if (layer == 0) autoShift()
        updateSuggestions()
        // dari angka/simbol: setelah ketik simbol (bukan angka) otomatis balik ke huruf
        if (layer != 0 && Prefs.bool(this, "autoabc", true) && !s[0].isDigit()) { layer = 0; renderAll() }
    }

    private fun backspace() {
        if (searchMode) {
            if (searchQuery.isNotEmpty()) searchQuery = searchQuery.dropLast(1)
            updateSearch(); return
        }
        val un = undoNew
        val uo = undoOrig
        undoNew = null; undoOrig = null
        if (un != null && uo != null) {
            val ic = currentInputConnection
            val before = ic?.getTextBeforeCursor(un.length + 1, 0)?.toString()
            if (ic != null && before == "$un ") {
                // batalin koreksi otomatis: balik ke kata asli
                ic.deleteSurroundingText(un.length + 1, 0)
                ic.commitText(uo, 1)
                engine.reject(uo)
                suppressWord = uo.lowercase()
                autoShift(); updateSuggestions()
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (!emojiMode) { autoShift(); updateSuggestions() }
    }

    private fun space() {
        if (searchMode) { searchQuery += " "; updateSearch(); return }
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        // spasi ganda -> titik: MATI secara default (nyalain di setting)
        if (Prefs.bool(this, "dotspace", false) && now - lastSpace < 400) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpace = now; autoShift(); updateSuggestions(); return
            }
        }
        undoNew = null; undoOrig = null
        if (Prefs.bool(this, "autocorrect", true) && !noLearn && layer == 0 && !emojiMode) tryAutoCorrect(ic)
        ic.commitText(" ", 1)
        lastSpace = now
        learnWord()
        autoShift()
        updateSuggestions()
        if (layer != 0 && Prefs.bool(this, "autoabc", true)) { layer = 0; renderAll() }
    }

    private fun actionId(): Int {
        val ei = currentInputEditorInfo ?: return 0
        if ((ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return 0
        val a = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        return if (a == EditorInfo.IME_ACTION_UNSPECIFIED || a == EditorInfo.IME_ACTION_NONE) 0 else a
    }

    private fun enterLabel(a: Int) = when (a) {
        EditorInfo.IME_ACTION_GO -> "go"
        EditorInfo.IME_ACTION_SEARCH -> "search"
        EditorInfo.IME_ACTION_SEND -> "send"
        EditorInfo.IME_ACTION_NEXT -> "next"
        EditorInfo.IME_ACTION_DONE -> "done"
        else -> "↵"
    }

    private fun enter() {
        undoNew = null
        val a = actionId()
        if (a != 0) currentInputConnection?.performEditorAction(a)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        autoShift()
        updateSuggestions()
    }

    // ------------------------------------------------------------ kata saran
    // Saran muncul di bar (bisa digeser). Koreksi otomatis cuma terjadi pas tekan spasi, dan bisa dibatalin
    // dengan backspace langsung setelahnya.
    private fun currentWord(): String {
        val b = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: ""
        return b.takeLastWhile { it.isLetter() || it == '\'' }
    }

    private fun prevWord(): String {
        val t = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: return ""
        val ws = t.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        // kalau lagi ngetik kata, kata sebelumnya = yang kedua dari belakang; kalau habis spasi = yang terakhir
        return if (currentWord().isNotEmpty()) ws.getOrNull(ws.size - 2) ?: "" else ws.lastOrNull() ?: ""
    }

    private fun tryAutoCorrect(ic: InputConnection) {
        val w = currentWord()
        if (w.length < 2 || w.lowercase() == suppressWord) return
        val before = ic.getTextBeforeCursor(w.length + 40, 0)?.toString() ?: return
        val head = before.dropLast(w.length)
        val pc = head.lastOrNull()
        // jangan sentuh email, hashtag, mention, url, atau kata nempel angka
        if (pc != null && (pc == '@' || pc == '#' || pc == '/' || pc.isDigit())) return
        if (pc == '.' && head.length >= 2 && head[head.length - 2].isLetter()) return
        if (w.drop(1).any { it.isUpperCase() }) return
        if (w[0].isUpperCase()) {
            // huruf kapital di tengah kalimat = nama orang/tempat, jangan dikoreksi
            val t = head.trimEnd(' ')
            val sentenceStart = t.isEmpty() || t.last() == '.' || t.last() == '!' || t.last() == '?' || t.last() == '\n'
            if (!sentenceStart) return
        }
        val fix = engine.autoFix(w) ?: return
        if (fix == w) return
        ic.deleteSurroundingText(w.length, 0)
        ic.commitText(fix, 1)
        undoOrig = w
        undoNew = fix
    }

    private fun updateSuggestions() {
        if (host == null || emojiMode) return
        suggestBar.removeAllViews()
        suggestScroll.scrollTo(0, 0)
        if (noLearn || !Prefs.bool(this, "suggest", true)) return
        val w = currentWord()
        val res = engine.suggest(w, prevWord())
        val minW = (resources.displayMetrics.widthPixels * wf / 3f).toInt()
        res.list.forEachIndexed { i, s ->
            if (i > 0) {
                val d = View(this)
                d.setBackgroundColor(pal.sel)
                val lp = LinearLayout.LayoutParams(dp(1), dp(24))
                lp.gravity = Gravity.CENTER_VERTICAL
                suggestBar.addView(d, lp)
            }
            val tv = TextView(this)
            tv.text = s
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            tv.minWidth = minW
            tv.setPadding(dp(12), 0, dp(12), 0)
            tv.setTextColor(if (w.isEmpty()) pal.hint else pal.text)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val isAuto = res.auto != null && s == res.auto
            tv.typeface = if (isAuto) Typeface.DEFAULT_BOLD else (textFace() ?: Typeface.DEFAULT)
            tv.setOnClickListener { pick(s) }
            suggestBar.addView(tv, LinearLayout.LayoutParams(WRAP, MATCH))
        }
    }

    private fun pick(s: String) {
        val ic = currentInputConnection ?: return
        val w = currentWord()
        if (w.isNotEmpty()) ic.deleteSurroundingText(w.length, 0)
        val out = if (w.isNotEmpty() && w[0].isUpperCase()) s.replaceFirstChar { it.uppercase() } else s
        ic.commitText("$out ", 1)
        undoNew = null; undoOrig = null
        learnWord()
        autoShift()
        updateSuggestions()
    }

    // ------------------------------------------------------------ belajar kata (lokal, gak ke mana-mana)
    private fun learnWord() {
        if (noLearn || !Prefs.bool(this, "suggest", true)) return
        val t = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString() ?: return
        engine.learnFrom(t)
        if (engine.dirty >= 15) saveLearn()
    }

    private fun loadLearn() {
        try {
            val sp = getSharedPreferences("v4rr_learn", MODE_PRIVATE)
            engine.importLearn(sp.getString("w", "") ?: "", sp.getString("b", "") ?: "")
        } catch (e: Exception) {}
    }

    private fun saveLearn() {
        engine.dirty = 0
        if (Prefs.int(this, "learn_epoch", 0) != learnEpoch) return
        try {
            getSharedPreferences("v4rr_learn", MODE_PRIVATE).edit()
                .putString("w", engine.exportWords())
                .putString("b", engine.exportBigrams()).apply()
        } catch (e: Exception) {}
    }
}
