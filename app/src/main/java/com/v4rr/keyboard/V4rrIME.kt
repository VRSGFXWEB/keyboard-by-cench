package com.v4rr.keyboard

import android.content.Context
import android.content.Intent
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
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
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

    /** Balon preview huruf (kayak iOS) */
    private class PreviewView(c: Context) : View(c) {
        var text = ""; var keyW = 0; var keyH = 0
        var fill = 0xFF636366.toInt(); var txt = 0xFFFFFFFF.toInt()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = keyH * 0.24f
            val bodyH = keyH * 1.35f
            p.color = fill
            c.drawRoundRect(RectF(0f, 0f, w, bodyH), r, r, p)
            c.drawRoundRect(RectF((w - keyW) / 2f, bodyH * 0.6f, (w + keyW) / 2f, h), r, r, p)
            tp.color = txt
            tp.textSize = keyH * 0.78f
            val fm = tp.fontMetrics
            c.drawText(text, w / 2f, bodyH / 2f - (fm.ascent + fm.descent) / 2f, tp)
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

    // ---- kata saran
    private val learn = HashMap<String, Int>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private var learnDirty = 0
    private val words: List<String> by lazy {
        try { assets.open("words.txt").bufferedReader().readLines().filter { it.isNotBlank() } } catch (e: Exception) { emptyList() }
    }
    private val emojiIndex: List<Pair<String, List<String>>> by lazy {
        try {
            assets.open("emoji_keywords.txt").bufferedReader().readLines().mapNotNull { l ->
                val p = l.split("\t")
                if (p.size < 2) null else Pair(p[0], p[1].split(" "))
            }
        } catch (e: Exception) { emptyList() }
    }
    private var spell: SpellCheckerSession? = null
    private var spellFor = ""
    private var spellRes: List<String> = emptyList()
    private var spellAsked = ""
    private val spellListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val r = results?.firstOrNull() ?: return
            val out = ArrayList<String>()
            if (r.suggestionsCount > 0) {
                val info = r.getSuggestionsInfoAt(0)
                for (i in 0 until info.suggestionsCount) out.add(info.getSuggestionAt(i))
            }
            handler.post {
                spellFor = spellAsked
                spellRes = out
                if (spellAsked == currentWord().lowercase()) updateSuggestions(false)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun rowH() = (dp(50) * hs).toInt()
    private fun sig() = "${Prefs.theme(this)}|${Themes.isDark(this)}|${Prefs.bool(this, "font", true)}|${FontStore.stamp(this)}|${Prefs.bool(this, "blur", true)}"

    // ------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        instance = this
        loadLearn()
    }

    override fun onDestroy() {
        saveLearn()
        try { spell?.close() } catch (e: Exception) {}
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
        pal = Themes.palette(this)
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
        body = FrameLayout(this)
        col.addView(suggestBar, LinearLayout.LayoutParams(MATCH, dp(42)))
        col.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
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

    /** Background jendela keyboard: transparan + blur bawaan sistem (Android 12+), ringan karena diproses compositor. */
    private fun applyWindowStyle() {
        val w = window?.window ?: return
        var blur = false
        if (Build.VERSION.SDK_INT >= 31 && Prefs.bool(this, "blur", true)) {
            blur = try { getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true } catch (e: Exception) { false }
        }
        val a = if (blur) 0xB0 else 0xF2
        val c = dp(22).toFloat()
        val d = GradientDrawable()
        d.setColor((pal.bg and 0x00FFFFFF) or (a shl 24))
        d.cornerRadii = floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f)
        w.setBackgroundDrawable(d)
        if (Build.VERSION.SDK_INT >= 31) {
            try { w.setBackgroundBlurRadius(if (blur) dp(30) else 0) } catch (e: Exception) {}
        }
    }

    private fun renderAll() {
        if (host == null) return
        suggestBar.visibility = if (emojiMode) View.GONE else View.VISIBLE
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
            val pw = (kw * 1.5f).toInt(); val ph = (kh * 2.3f).toInt()
            val pv = previewView ?: PreviewView(this).also { previewView = it }
            pv.fill = if (pal.dark) 0xFF636366.toInt() else 0xFFFFFFFF.toInt()
            pv.txt = pal.text; pv.text = text; pv.keyW = kw; pv.keyH = kh
            pv.invalidate()
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val x = (loc[0] + kw / 2 - pw / 2).coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - pw))
            val y = loc[1] + kh - ph
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

    private fun key(
        row: LinearLayout, weight: Float, label: String? = null, icon: Int = 0,
        special: Boolean = false, size: Float = 24f, repeat: Boolean = false,
        colorOverride: Int? = null, normalFn: (() -> Int)? = null,
        preview: (() -> String)? = null, onLong: (() -> Unit)? = null, onTap: () -> Unit
    ): KeyCell {
        val cell = FrameLayout(this)
        val base = colorOverride ?: if (special) pal.special else pal.key
        val normal: () -> Int = normalFn ?: { base }
        val pressedC = colorOverride ?: if (special) pal.key else pal.special
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
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
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            cell.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH))
        } else if (icon != 0) {
            iv = iconView(icon, fg)
            cell.addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        val lp = LinearLayout.LayoutParams(0, MATCH, weight)
        lp.setMargins(dp(3), dp(4), dp(3), dp(4))
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
                for (c in ".,?!'") key(r, 1.4f, c.toString()) { typeChar(c.toString()) }
                backKey(r, 1.5f)
            }
            else -> {
                charRow(col, "[]{}#%^*+=", 0f)
                charRow(col, "_\\|~<>€£¥•", 0f)
                val r = newRow(col)
                key(r, 1.5f, "123", special = true, size = 16f) { layer = 1; renderAll() }
                for (c in ".,?!'") key(r, 1.4f, c.toString()) { typeChar(c.toString()) }
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
        shiftKey?.let { it.bg.setColor(if (shift > 0) pal.key else pal.special); it.icon?.setImageResource(shiftIcon()) }
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
        if (layer == 0) autoShift()
        updateSuggestions()
    }

    private fun backspace() {
        if (searchMode) {
            if (searchQuery.isNotEmpty()) searchQuery = searchQuery.dropLast(1)
            updateSearch(); return
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
        ic.commitText(" ", 1)
        lastSpace = now
        learnWord()
        autoShift()
        updateSuggestions()
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
        val a = actionId()
        if (a != 0) currentInputConnection?.performEditorAction(a)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        autoShift()
        updateSuggestions()
    }

    // ------------------------------------------------------------ kata saran
    // Catatan: saran HANYA muncul di bar, TIDAK pernah mengganti kata otomatis. Ganti kata cuma kalau lu tap sarannya.
    private fun currentWord(): String {
        val b = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: ""
        return b.takeLastWhile { it.isLetter() || it == '\'' }
    }

    private fun prevWord(): String {
        val t = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: return ""
        return t.lowercase().split(Regex("[^\\p{L}']+")).lastOrNull { it.isNotEmpty() } ?: ""
    }

    private fun ensureSpell() {
        if (spell != null) return
        try {
            val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            spell = tsm.newSpellCheckerSession(null, null, spellListener, true)
        } catch (e: Exception) { spell = null }
    }

    private fun candidates(w: String): List<String> {
        if (w.isEmpty()) {
            val nxt = bigram[prevWord()]?.entries?.sortedByDescending { it.value }?.map { it.key }?.take(3) ?: emptyList()
            return (nxt + listOf("aku", "yang", "i")).distinct().take(3)
        }
        val lw = w.lowercase()
        val comp = (learn.entries.filter { it.key.startsWith(lw) && it.key != lw }
            .sortedByDescending { it.value }.map { it.key } +
                words.filter { it.startsWith(lw) && it != lw }).distinct()
        val sp = if (spellFor == lw) spellRes else emptyList()
        val rest = (comp.take(2) + sp).filter { it.lowercase() != lw }.distinct().take(2)
        return listOf(w) + rest
    }

    private fun updateSuggestions(request: Boolean = true) {
        if (host == null || emojiMode) return
        suggestBar.removeAllViews()
        if (noLearn || !Prefs.bool(this, "suggest", true)) return
        val w = currentWord()
        val items = candidates(w)
        items.forEachIndexed { i, s ->
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
            tv.setTextColor(if (w.isEmpty()) pal.hint else pal.text)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            tv.setOnClickListener { pick(s) }
            suggestBar.addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        if (request && w.length >= 2) {
            ensureSpell()
            spellAsked = w.lowercase()
            try { spell?.getSentenceSuggestions(arrayOf(TextInfo(w)), 3) } catch (e: Exception) {}
        }
    }

    private fun pick(s: String) {
        val ic = currentInputConnection ?: return
        val w = currentWord()
        if (w.isNotEmpty()) ic.deleteSurroundingText(w.length, 0)
        val out = if (w.isNotEmpty() && w[0].isUpperCase()) s.replaceFirstChar { it.uppercase() } else s
        ic.commitText("$out ", 1)
        learnWord()
        autoShift()
        updateSuggestions()
    }

    // ------------------------------------------------------------ belajar kata (lokal, gak ke mana-mana)
    private fun learnWord() {
        if (noLearn || !Prefs.bool(this, "suggest", true)) return
        val t = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString() ?: return
        val ws = t.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val last = ws.lastOrNull() ?: return
        if (last.length < 2 || last.length > 20) return
        learn[last] = (learn[last] ?: 0) + 1
        if (ws.size >= 2) {
            val m = bigram.getOrPut(ws[ws.size - 2]) { HashMap() }
            m[last] = (m[last] ?: 0) + 1
        }
        if (learn.size > 3000) learn.entries.removeAll { it.value <= 1 }
        if (++learnDirty >= 15) saveLearn()
    }

    private fun loadLearn() {
        try {
            val s = getSharedPreferences("v4rr_learn", MODE_PRIVATE).getString("w", "") ?: ""
            for (p in s.split(" ")) {
                val i = p.lastIndexOf('=')
                if (i > 0) learn[p.substring(0, i)] = p.substring(i + 1).toIntOrNull() ?: 1
            }
        } catch (e: Exception) {}
    }

    private fun saveLearn() {
        learnDirty = 0
        try {
            getSharedPreferences("v4rr_learn", MODE_PRIVATE).edit()
                .putString("w", learn.entries.joinToString(" ") { "${it.key}=${it.value}" }).apply()
        } catch (e: Exception) {}
    }
}
