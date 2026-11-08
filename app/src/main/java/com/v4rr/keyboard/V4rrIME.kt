package com.v4rr.keyboard

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class V4rrIME : InputMethodService() {

    private lateinit var pal: Palette
    private lateinit var suggestBar: LinearLayout
    private lateinit var body: FrameLayout
    private var root: LinearLayout? = null
    private var builtSig = ""

    private var layer = 0            // 0 huruf, 1 angka, 2 simbol
    private var shift = 0            // 0 off, 1 sekali, 2 caps lock
    private var emojiMode = false
    private var emojiCat = 1
    private var lastShiftTap = 0L
    private var lastSpace = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val letterViews = mutableListOf<TextView>()
    private var shiftView: TextView? = null
    private var shiftBg: GradientDrawable? = null

    private val customFont: Typeface? by lazy {
        try { Typeface.createFromAsset(assets, "fonts/NotoColorEmoji.ttf") } catch (e: Exception) { null }
    }
    private fun emojiFace(): Typeface? =
        if (Prefs.bool(this, "font", true)) customFont else null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun sig() = "${Prefs.theme(this)}|${Themes.isDark(this)}|${Prefs.bool(this, "font", true)}"
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View = build()

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (root != null && builtSig != sig()) setInputView(build())
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        emojiMode = false
        shift = 0
        autoShift()
        renderAll()
    }

    // ------------------------------------------------------------ build
    private fun build(): View {
        pal = Themes.palette(this)
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        val c = dp(20).toFloat()
        r.background = GradientDrawable().apply {
            setColor(pal.bg)
            cornerRadii = floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f)
        }
        r.setPadding(0, 0, 0, dp(6))
        suggestBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body = FrameLayout(this)
        r.addView(suggestBar, LinearLayout.LayoutParams(MATCH, dp(42)))
        r.addView(body, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        root = r
        builtSig = sig()
        renderAll()
        return r
    }

    private fun renderAll() {
        if (root == null) return
        suggestBar.visibility = if (emojiMode) View.GONE else View.VISIBLE
        if (emojiMode) renderEmoji() else { renderKeys(); updateSuggestions() }
    }

    // ------------------------------------------------------------ touch helper
    private fun haptic(v: View) {
        if (Prefs.bool(this, "haptic", true)) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (Prefs.bool(this, "sound", false))
            (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    private fun attach(
        tv: TextView, bg: GradientDrawable, normal: () -> Int, pressed: Int,
        repeat: Boolean, onTap: () -> Unit
    ) {
        var rep: Runnable? = null
        rep = Runnable { onTap(); handler.postDelayed(rep!!, 50) }
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bg.setColor(pressed); haptic(v)
                    if (repeat) { onTap(); handler.postDelayed(rep!!, 400) }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(rep!!)
                    bg.setColor(normal())
                    if (!repeat) onTap()
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(rep!!); bg.setColor(normal()) }
            }
            true
        }
    }

    private fun newRow(parent: LinearLayout, h: Int = 48): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        parent.addView(r, LinearLayout.LayoutParams(MATCH, dp(h)))
        return r
    }

    private fun key(
        row: LinearLayout, label: String, weight: Float, special: Boolean = false,
        size: Float = 22f, repeat: Boolean = false, colorOverride: Int? = null,
        normalFn: (() -> Int)? = null, onTap: () -> Unit
    ): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.setTextColor(if (colorOverride != null) 0xFFFFFFFF.toInt() else pal.text)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        val base = colorOverride ?: if (special) pal.special else pal.key
        val normal: () -> Int = normalFn ?: { base }
        val pressed = if (colorOverride != null) colorOverride else if (special) pal.key else pal.special
        val bg = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(normal()) }
        tv.background = bg
        val lp = LinearLayout.LayoutParams(0, MATCH, weight)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        row.addView(tv, lp)
        attach(tv, bg, normal, pressed, repeat, onTap)
        return tv
    }

    private fun spacer(row: LinearLayout, w: Float) =
        row.addView(View(this), LinearLayout.LayoutParams(0, MATCH, w))

    // ------------------------------------------------------------ letters / numbers / symbols
    private fun charKey(row: LinearLayout, c: String) {
        val tv = key(row, if (layer == 0 && shift > 0) c.uppercase() else c, 1f) {
            typeChar(if (layer == 0 && shift > 0) c.uppercase() else c)
        }
        if (layer == 0) { tv.tag = c; letterViews.add(tv) }
    }

    private fun charRow(parent: LinearLayout, chars: String, pad: Float) {
        val r = newRow(parent)
        if (pad > 0) spacer(r, pad)
        for (c in chars) charKey(r, c.toString())
        if (pad > 0) spacer(r, pad)
    }

    private fun backKey(row: LinearLayout, w: Float) =
        key(row, "⌫", w, true, 20f, true) { backspace() }

    private fun renderKeys() {
        body.removeAllViews()
        letterViews.clear()
        shiftView = null; shiftBg = null
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(3), dp(2), dp(3), 0)

        when (layer) {
            0 -> {
                charRow(col, "qwertyuiop", 0f)
                charRow(col, "asdfghjkl", 0.5f)
                val r = newRow(col)
                val s = key(r, if (shift == 2) "⇪" else "⇧", 1.5f, true, 20f,
                    normalFn = { if (shift > 0) pal.key else pal.special }) { onShift() }
                shiftView = s; shiftBg = s.background as GradientDrawable
                for (c in "zxcvbnm") charKey(r, c.toString())
                backKey(r, 1.5f)
            }
            1 -> {
                charRow(col, "1234567890", 0f)
                charRow(col, "-/:;()\$&@\"", 0f)
                val r = newRow(col)
                key(r, "#+=", 1.5f, true, 16f) { layer = 2; renderKeys() }
                for (c in ".,?!'") key(r, c.toString(), 1.4f) { typeChar(c.toString()) }
                backKey(r, 1.5f)
            }
            else -> {
                charRow(col, "[]{}#%^*+=", 0f)
                charRow(col, "_\\|~<>€£¥•", 0f)
                val r = newRow(col)
                key(r, "123", 1.5f, true, 16f) { layer = 1; renderKeys() }
                for (c in ".,?!'") key(r, c.toString(), 1.4f) { typeChar(c.toString()) }
                backKey(r, 1.5f)
            }
        }

        // baris bawah
        val b = newRow(col)
        if (layer == 0) key(b, "123", 1.4f, true, 16f) { layer = 1; renderKeys() }
        else key(b, "ABC", 1.4f, true, 16f) { layer = 0; renderKeys() }
        val sp = key(b, "V4RR", 5f, false, 14f) { space() }
        sp.setTextColor(pal.hint)
        val ea = actionId()
        key(b, enterLabel(ea), 1.8f, true, 16f, colorOverride = if (ea != 0) pal.accent else null) { enter() }

        // baris ikon (emoji kiri, globe kanan)
        val u = newRow(col, 40)
        val e = iconText("😀", 24f, true)
        e.setOnClickListener { haptic(it); emojiMode = true; renderAll() }
        u.addView(e, LinearLayout.LayoutParams(dp(64), MATCH))
        u.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f))
        val g = iconText("🌐", 22f, true)
        g.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        u.addView(g, LinearLayout.LayoutParams(dp(64), MATCH))
        body.addView(col)
    }

    private fun iconText(t: String, sp: Float, emoji: Boolean): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.gravity = Gravity.CENTER
        tv.setTextColor(pal.text)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (emoji) emojiFace()?.let { tv.typeface = it }
        return tv
    }

    // ------------------------------------------------------------ emoji panel
    private fun renderEmoji() {
        body.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(pal.panel)

        val header = TextView(this)
        header.text = EmojiData.names[emojiCat]
        header.setTextColor(pal.hint)
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        header.typeface = Typeface.DEFAULT_BOLD
        header.setPadding(dp(14), dp(8), 0, dp(4))
        col.addView(header, LinearLayout.LayoutParams(MATCH, dp(30)))

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
                r.addView(tv, LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            grid.addView(r, LinearLayout.LayoutParams(MATCH, dp(46)))
        }
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(grid)
        col.addView(sv, LinearLayout.LayoutParams(MATCH, dp(166)))

        // bar kategori
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), 0, dp(6), 0)
        for (i in EmojiData.icons.indices) {
            val t = iconText(EmojiData.icons[i], 20f, true)
            if (i == emojiCat) t.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(pal.sel)
            }
            t.alpha = if (i == emojiCat) 1f else 0.6f
            t.setOnClickListener { emojiCat = i; renderEmoji() }
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            bar.addView(t, lp)
        }
        val del = iconText("⌫", 20f, false)
        val delBg = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0) }
        del.background = delBg
        attach(del, delBg, { 0 }, pal.sel, true) { backspace() }
        val dlp = LinearLayout.LayoutParams(0, dp(36), 1.2f)
        dlp.setMargins(dp(2), 0, dp(2), 0)
        bar.addView(del, dlp)
        col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(44)))

        // ABC + globe
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        val abc = iconText("ABC", 16f, false)
        abc.typeface = Typeface.DEFAULT_BOLD
        abc.paintFlags = abc.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        abc.setOnClickListener { haptic(it); emojiMode = false; renderAll() }
        bottom.addView(abc, LinearLayout.LayoutParams(dp(80), MATCH))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f))
        val g = iconText("🌐", 22f, true)
        g.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        bottom.addView(g, LinearLayout.LayoutParams(dp(64), MATCH))
        col.addView(bottom, LinearLayout.LayoutParams(MATCH, dp(36)))

        body.addView(col)
    }

    // ------------------------------------------------------------ input logic
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
        if (layer != 0 || emojiMode) return
        for (t in letterViews) {
            val s = t.tag as String
            t.text = if (shift > 0) s.uppercase() else s
        }
        shiftView?.text = if (shift == 2) "⇪" else "⇧"
        shiftBg?.setColor(if (shift > 0) pal.key else pal.special)
    }

    private fun autoShift() {
        if (shift == 2) return
        val ei = currentInputEditorInfo
        val ic = currentInputConnection
        var want = 0
        if (Prefs.bool(this, "autocap", true) && ei != null && ic != null && ei.inputType != 0)
            want = ic.getCursorCapsMode(ei.inputType)
        setShift(if (want != 0) 1 else 0)
    }

    private fun typeChar(s: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(s, 1)
        lastSpace = 0
        if (layer == 0) autoShift()
        updateSuggestions()
    }

    private fun backspace() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (!emojiMode) { autoShift(); updateSuggestions() }
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (now - lastSpace < 400 && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else ic.commitText(" ", 1)
        lastSpace = now
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

    // ------------------------------------------------------------ suggestions
    private fun currentWord(): String {
        val b = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: ""
        return b.takeLastWhile { it.isLetter() || it == '\'' }
    }

    private fun updateSuggestions() {
        if (root == null) return
        suggestBar.removeAllViews()
        val w = currentWord()
        val items: List<String> = if (w.isEmpty()) listOf("i", "the", "i'm")
        else {
            val lw = w.lowercase()
            listOf(w) + EmojiData.words.filter { it.startsWith(lw) && it != lw }.distinct().take(2)
        }
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
            tv.setTextColor(if (w.isEmpty()) pal.hint else pal.text)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            tv.setOnClickListener { pick(s) }
            suggestBar.addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    private fun pick(s: String) {
        val ic = currentInputConnection ?: return
        val w = currentWord()
        if (w.isNotEmpty()) ic.deleteSurroundingText(w.length, 0)
        val out = if (w.isNotEmpty() && w[0].isUpperCase()) s.replaceFirstChar { it.uppercase() } else s
        ic.commitText("$out ", 1)
        autoShift()
        updateSuggestions()
    }
}
