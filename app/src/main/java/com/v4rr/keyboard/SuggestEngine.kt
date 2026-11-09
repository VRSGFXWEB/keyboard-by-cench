package com.v4rr.keyboard

/**
 * Mesin kata saran + koreksi otomatis (Bahasa Indonesia).
 * Murni Kotlin, gak pakai API Android, jadi gampang dites.
 *  - kamus bawaan (words.txt, urut dari yang paling sering)
 *  - kata yang sering diketik user (learn) dan pasangan kata (bigram)
 *  - pintasan teks buatan user (mis. yg=yang)
 *  - koreksi typo pakai jarak edit (Damerau-Levenshtein)
 */
class SuggestEngine(wordLines: List<String>, bigramLines: List<String>) {

    class Result(val list: List<String>, val auto: String?)

    private val words = ArrayList<String>()
    private val rank = HashMap<String, Int>()
    private val builtin = HashMap<String, List<String>>()
    private val learn = HashMap<String, Int>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private var shortcuts: Map<String, String> = emptyMap()
    var dirty = 0

    init {
        for (l in wordLines) {
            val w = l.trim().lowercase()
            if (w.isNotEmpty() && !rank.containsKey(w)) { rank[w] = words.size; words.add(w) }
        }
        for (l in bigramLines) {
            val p = l.trim().split("\t").filter { it.isNotEmpty() }
            if (p.size >= 2) builtin[p[0].lowercase()] = p.drop(1)
        }
    }

    // ------------------------------------------------------------ pintasan
    fun setShortcuts(text: String) {
        val m = HashMap<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
        }
        shortcuts = m
    }

    // ------------------------------------------------------------ pengetahuan
    fun isKnown(lw: String) = rank.containsKey(lw) || (learn[lw] ?: 0) >= 2

    private fun weight(w: String): Int {
        val r = rank[w]
        val base = if (r != null) (6000 - minOf(r, 5999)) / 60 else 0
        return (learn[w] ?: 0) * 100 + base
    }

    private fun dist(a: String, b: String, max: Int): Int {
        val n = a.length
        val m = b.length
        if (Math.abs(n - m) > max) return max + 1
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** Kandidat koreksi untuk kata yang gak dikenal, urut dari yang paling mungkin. */
    private fun fixes(lw: String, maxD: Int): List<String> {
        class C(val w: String, val d: Int, val firstOk: Boolean, val wt: Int)
        val out = ArrayList<C>()
        fun consider(w: String) {
            if (w == lw || Math.abs(w.length - lw.length) > maxD) return
            val d = dist(lw, w, maxD)
            if (d <= maxD) out.add(C(w, d, w[0] == lw[0], weight(w)))
        }
        for (w in words) consider(w)
        for ((w, c) in learn) if (c >= 2 && !rank.containsKey(w)) consider(w)
        return out.sortedWith(compareBy<C>({ it.d }, { if (it.firstOk) 0 else 1 }, { -it.wt })).map { it.w }.take(4)
    }

    private fun matchCase(src: String, tgt: String): String = when {
        src.length > 1 && src.all { it.isUpperCase() } -> tgt.uppercase()
        src.isNotEmpty() && src[0].isUpperCase() -> tgt.replaceFirstChar { it.uppercase() }
        else -> tgt
    }

    /** Hasil koreksi otomatis yang dipasang pas spasi ditekan (null = biarin). */
    fun autoFix(w: String): String? {
        if (w.length < 2) return null
        val lw = w.lowercase()
        shortcuts[lw]?.let { return it }
        if (w.length < 3 || isKnown(lw)) return null
        val maxD = if (lw.length >= 7) 2 else 1
        val f = fixes(lw, maxD).firstOrNull() ?: return null
        if (maxD == 2 && dist(lw, f, 2) == 2 && f[0] != lw[0]) return null
        return matchCase(w, f)
    }

    // ------------------------------------------------------------ saran
    private fun nextWords(prev: String): List<String> {
        val user = bigram[prev]?.entries?.sortedByDescending { it.value }?.map { it.key } ?: emptyList()
        val bi = builtin[prev] ?: emptyList()
        val top = learn.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.map { it.key }.take(3)
        val base = listOf("saya", "aku", "yang", "ini", "itu", "tidak", "kamu", "ada")
        return (user + bi + top + base).distinct().take(8)
    }

    private fun completions(lw: String, prev: String): List<String> {
        val user = learn.entries.filter { it.value >= 2 && it.key.length > lw.length && it.key.startsWith(lw) }
            .sortedByDescending { it.value }.map { it.key }
        val dict = ArrayList<String>()
        for (w in words) {
            if (w.length > lw.length && w.startsWith(lw)) { dict.add(w); if (dict.size >= 12) break }
        }
        val big = HashSet<String>()
        bigram[prev]?.let { big.addAll(it.keys) }
        builtin[prev]?.let { big.addAll(it) }
        return (user + dict).distinct().sortedBy { if (it in big) 0 else 1 }
    }

    /** list[0] = kata yang lagi diketik, sisanya saran. auto = yang bakal dipasang pas spasi. */
    fun suggest(w: String, prev: String): Result {
        if (w.isEmpty()) return Result(nextWords(prev), null)
        val lw = w.lowercase()
        val auto = autoFix(w)
        val out = LinkedHashSet<String>()
        out.add(w)
        if (auto != null) out.add(auto)
        val known = isKnown(lw)
        val comps = completions(lw, prev).map { matchCase(w, it) }
        if (known) {
            out.addAll(comps)
        } else {
            val fx = if (lw.length >= 2) fixes(lw, if (lw.length >= 7) 2 else 1).map { matchCase(w, it) } else emptyList()
            out.addAll(comps.take(3))
            out.addAll(fx)
            out.addAll(comps)
        }
        return Result(out.take(8), auto)
    }

    // ------------------------------------------------------------ belajar
    /** Dipanggil habis spasi. before = teks sebelum kursor. */
    fun learnFrom(before: String) {
        val ws = before.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val last = ws.lastOrNull() ?: return
        if (last.length < 2 || last.length > 20) return
        learn[last] = (learn[last] ?: 0) + 1
        if (ws.size >= 2) {
            val m = bigram.getOrPut(ws[ws.size - 2]) { HashMap() }
            m[last] = (m[last] ?: 0) + 1
        }
        if (learn.size > 3000) learn.entries.removeAll { it.value <= 1 }
        if (bigram.size > 2500) bigram.entries.removeAll { e -> e.value.values.sum() <= 1 }
        dirty++
    }

    /** User batalin koreksi otomatis (backspace): anggap kata aslinya sah. */
    fun reject(orig: String) {
        val lw = orig.lowercase()
        if (lw.length < 2) return
        learn[lw] = maxOf(learn[lw] ?: 0, 1) + 1
        dirty++
    }

    fun clearLearn() { learn.clear(); bigram.clear(); dirty = 0 }

    fun exportWords(): String = learn.entries.joinToString(" ") { "${it.key}=${it.value}" }
    fun exportBigrams(): String {
        val sb = StringBuilder()
        for ((p, m) in bigram) for ((n, c) in m) { if (sb.isNotEmpty()) sb.append(' '); sb.append(p).append('>').append(n).append('=').append(c) }
        return sb.toString()
    }

    fun importLearn(w: String, b: String) {
        for (p in w.split(" ")) {
            val i = p.lastIndexOf('=')
            if (i > 0) learn[p.substring(0, i)] = p.substring(i + 1).toIntOrNull() ?: 1
        }
        for (p in b.split(" ")) {
            val g = p.indexOf('>'); val e = p.lastIndexOf('=')
            if (g > 0 && e > g) {
                val m = bigram.getOrPut(p.substring(0, g)) { HashMap() }
                m[p.substring(g + 1, e)] = p.substring(e + 1).toIntOrNull() ?: 1
            }
        }
    }
}
