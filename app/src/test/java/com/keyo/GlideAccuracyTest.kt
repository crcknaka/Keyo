package com.keyo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Glide (swipe) decode accuracy against the REAL shipped dictionaries.
 *
 * [GlideDecoderTest] proves the decoder on perfect straight lines through key centres over an
 * eleven-word list. Nobody swipes like that, and the hard part of glide typing is not finding the
 * word whose keys were crossed — it is choosing among the dozens of dictionary words whose keys
 * were ALSO crossed. So this harness synthesises the way a finger actually moves and decodes
 * against the full word list:
 *
 *  - each letter is aimed at with Gaussian scatter, plus the downward offset real thumbs have;
 *  - corners are CUT — the finger turns before it reaches a key, the more so the faster it goes
 *    (a moving average along the stroke, which also makes a reversal stop short of its key);
 *  - the lift-off under- or overshoots along the direction of travel;
 *  - samples arrive unevenly spaced, with sensor jitter.
 *
 * Deterministic (fixed seeds). Prints top-1 / top-3 per language, per frequency band and per
 * finger, and asserts floors so a change that makes glide measurably worse fails the build.
 */
class GlideAccuracyTest {

    // The shipped geometry on a 411dp-wide phone, in dp (see KeyTables / KeyboardLayout).
    private val width = 411f
    private val rowPitch = 56f
    private val hGap = 3f
    private val vGap = 5.5f

    private class Layout(val bounds: Map<Char, Rect>, val keyW: Float)

    private fun layout(rows: List<String>, sides: List<Float>): Layout {
        val b = HashMap<Char, Rect>()
        var wSum = 0f
        var n = 0
        rows.forEachIndexed { r, row ->
            val unit = width / (row.length + 2 * sides[r])
            row.forEachIndexed { i, c ->
                val left = (sides[r] + i) * unit
                b[c] = Rect(left + hGap, r * rowPitch + vGap, left + unit - hGap, (r + 1) * rowPitch - vGap)
                wSum += unit - 2 * hGap; n++
            }
        }
        return Layout(b, wSum / n)
    }

    private val en = layout(EN_ROWS, EN_SIDES)
    private val ru = layout(RU_ROWS, RU_SIDES)

    private fun load(lang: String): List<String> =
        File("src/main/assets/dict/$lang.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private class Finger(val name: String, val aim: Float, val cut: Float, val lift: Float)

    private val fingers = listOf(
        Finger("careful", aim = 0.14f, cut = 0.35f, lift = 0.15f),
        Finger("normal", aim = 0.22f, cut = 0.60f, lift = 0.25f),
        Finger("sloppy", aim = 0.30f, cut = 0.90f, lift = 0.35f)
    )

    private fun gauss(rnd: Random): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
        val u2 = rnd.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
    }

    /** A synthetic swipe of [word], or null when it cannot be glided (one key, or a letter that
     *  has no key on this layout). */
    private fun swipe(word: String, l: Layout, f: Finger, rnd: Random): List<Offset>? {
        val fk = SuggestionEngine.foldKey(word)
        val keys = ArrayList<Offset>()
        var last = '\u0000'
        for (c in fk) {
            if (c == last) continue
            last = c
            keys.add(l.bounds[c]?.center ?: return null)
        }
        if (keys.size < 2) return null
        val kw = l.keyW
        // Where the finger aims for each letter: scatter + the low landing of a thumb.
        val aims = keys.map { Offset(it.x + gauss(rnd) * f.aim * kw, it.y + gauss(rnd) * f.aim * rowPitch * 0.8f + 0.08f * rowPitch) }
            .toMutableList()
        // Lift-off: short of, or past, the last key along the direction of travel.
        val n = aims.size
        val dir = aims[n - 1] - aims[n - 2]
        val dl = dir.getDistance().coerceAtLeast(1f)
        aims[n - 1] = aims[n - 1] + Offset(dir.x / dl, dir.y / dl) * (gauss(rnd) * f.lift * kw)
        // Dense polyline through the aims.
        val step = kw * 0.04f
        val dense = ArrayList<Offset>()
        for (i in 1 until n) {
            val a = aims[i - 1]; val b = aims[i]
            val segs = ((b - a).getDistance() / step).toInt().coerceAtLeast(1)
            for (s in 0 until segs) dense.add(a + (b - a) * (s.toFloat() / segs))
        }
        dense.add(aims[n - 1])
        // Corner cutting: a moving average along the stroke. The ends are pinned (the window
        // shrinks toward them) — a finger starts ON its first key.
        val win = (f.cut * kw / step / 2f).toInt()
        val smooth = ArrayList<Offset>(dense.size)
        for (i in dense.indices) {
            val w = minOf(win, i, dense.size - 1 - i)
            var sx = 0f; var sy = 0f
            for (j in i - w..i + w) { sx += dense[j].x; sy += dense[j].y }
            smooth.add(Offset(sx / (2 * w + 1), sy / (2 * w + 1)))
        }
        // Pointer events: unevenly spaced samples with a little sensor jitter.
        val out = ArrayList<Offset>()
        var i = 0
        while (i < smooth.size) {
            out.add(smooth[i] + Offset(gauss(rnd) * kw * 0.015f, gauss(rnd) * kw * 0.015f))
            i += (3 + rnd.nextInt(4))   // 0.12–0.24 key widths between events
        }
        out.add(smooth.last())
        return out
    }

    private class Score(var n: Int = 0, var top1: Int = 0, var top3: Int = 0) {
        fun p1() = if (n == 0) 0.0 else 100.0 * top1 / n
        fun p3() = if (n == 0) 0.0 else 100.0 * top3 / n
    }

    /** The words a person actually glides, by frequency band. A word that is one key long cannot
     *  be glided, and neither can one with a letter the layout has no key for. */
    private fun bands(words: List<String>, l: Layout): List<Pair<String, List<String>>> {
        fun glidable(w: String): Boolean {
            val fk = SuggestionEngine.foldKey(w)
            return fk.all { l.bounds.containsKey(it) } && fk.toSet().size >= 2
        }
        val ok = words.withIndex().filter { glidable(it.value) }
        fun pick(from: Int, to: Int, count: Int): List<String> {
            val slice = ok.filter { it.index in from until to }.map { it.value }
            val stride = (slice.size / count).coerceAtLeast(1)
            return slice.filterIndexed { i, _ -> i % stride == 0 }.take(count)
        }
        return listOf(
            "top 300" to pick(0, 300, 250),
            "300-3000" to pick(300, 3000, 250),
            "3000-15000" to pick(3000, 15000, 250)
        )
    }

    private fun measure(words: List<String>, l: Layout, f: Finger, params: GlideParams): Score {
        val all = Score()
        for ((band, list) in bands(words, l)) {
            val rnd = Random(20261112 + f.name.hashCode() + band.hashCode())
            for (w in list) repeat(2) {
                val path = swipe(w, l, f, rnd) ?: return@repeat
                val got = decodeGlideOver(path, null, l.bounds, words, params)
                all.n++
                if (got.isNotEmpty() && SuggestionEngine.fold(got[0]) == SuggestionEngine.fold(w)) all.top1++
                if (got.any { SuggestionEngine.fold(it) == SuggestionEngine.fold(w) }) all.top3++
            }
        }
        return all
    }

    /** Sweep of the score weights. Not a test of anything — it prints the table the defaults in
     *  [GlideParams] were read off. Run by hand with GLIDE_SWEEP=1 in the environment. */
    @Test fun sweep() {
        if (System.getenv("GLIDE_SWEEP") != "1") return
        val enW = load("en"); val ruW = load("ru")
        fun row(name: String, p: GlideParams) {
            val sb = StringBuilder(String.format("%-34s", name))
            for (f in fingers) {
                val a = measure(enW, en, f, p); val b = measure(ruW, ru, f, p)
                sb.append(String.format("  %s %5.1f/%5.1f %5.1f/%5.1f", f.name.take(4), a.p1(), a.p3(), b.p1(), b.p3()))
            }
            println(sb)
        }
        println("=== sweep (top1/top3: en, ru) ===")
        for (extra in listOf(0f, 0.1f, 0.2f, 0.3f, 0.45f, 0.6f)) for (freq in listOf(0.15f, 0.22f))
            row("extra=$extra freq=$freq", GlideParams(extra = extra, freq = freq))
        println("=== context sweep, normal finger (expected with/without | unrelated with/without) ===")
        for (c in listOf(0.02f, 0.04f, 0.06f, 0.09f, 0.13f)) for ((lang, l) in listOf("en" to en, "ru" to ru)) {
            val (ew, eo, cw, co) = contextEffect(lang, l, fingers[1], GlideParams(context = c))
            println(String.format("context=%.2f %s  expected %.1f / %.1f   unrelated %.1f / %.1f", c, lang, ew.p1(), eo.p1(), cw.p1(), co.p1()))
        }
    }

    /** Sentence context: followers of a previous word, weighted the way
     *  SuggestionEngine.followerWeights weighs the bundled model (8 for the likeliest … 1). */
    private fun bigrams(lang: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        File("src/main/assets/dict/bigram_$lang.txt").forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab > 0 && tab < line.length - 1) out[line.substring(0, tab)] = line.substring(tab + 1).split(' ')
        }
        return out
    }

    /** (with context, without) on words the context expects, and the same pair on words it does
     *  NOT expect — the second is the harm metric: context must not hijack an unrelated word. */
    private fun contextEffect(lang: String, l: Layout, f: Finger, params: GlideParams): List<Score> {
        val words = load(lang)
        val bg = bigrams(lang)
        val control = bands(words, l).flatMap { it.second }
        val expWith = Score(); val expWithout = Score(); val ctlWith = Score(); val ctlWithout = Score()
        val rnd = Random(99)
        var ci = 0
        for ((_, foll) in bg.entries.take(120)) {
            val weights = HashMap<String, Int>()
            foll.forEachIndexed { i, w -> weights[w] = (8 - i).coerceAtLeast(1) }
            fun tally(w: String, withCtx: Score, without: Score) {
                val path = swipe(w, l, f, rnd) ?: return
                val a = decodeGlideOver(path, weights, l.bounds, words, params)
                val b = decodeGlideOver(path, null, l.bounds, words, params)
                withCtx.n++; without.n++
                if (a.firstOrNull()?.let { SuggestionEngine.fold(it) } == SuggestionEngine.fold(w)) withCtx.top1++
                if (b.firstOrNull()?.let { SuggestionEngine.fold(it) } == SuggestionEngine.fold(w)) without.top1++
            }
            for (w in foll) tally(w, expWith, expWithout)
            repeat(6) { val w = control[ci++ % control.size]; if (w !in weights) tally(w, ctlWith, ctlWithout) }
        }
        return listOf(expWith, expWithout, ctlWith, ctlWithout)
    }

    @Test fun context_helpsExpectedWords_withoutHijackingOthers() {
        for ((lang, l) in listOf("en" to en, "ru" to ru)) {
            val (ew, eo, cw, co) = contextEffect(lang, l, fingers[1], GlideParams.DEFAULT)
            println(String.format("=== glide context, %s (normal finger): expected words %.1f%% -> %.1f%% | unrelated words %.1f%% -> %.1f%% ===",
                lang, eo.p1(), ew.p1(), co.p1(), cw.p1()))
            assertTrue("context made expected words worse ($lang)", ew.top1 >= eo.top1)
            assertTrue("context hijacked unrelated words ($lang): ${co.p1()} -> ${cw.p1()}", cw.p1() >= co.p1() - 1.0)
        }
    }

    private fun run(lang: String, l: Layout): Map<String, Score> {
        val words = load(lang)
        val total = HashMap<String, Score>()
        println("=== glide accuracy, $lang.txt (${words.size} words) ===")
        for (f in fingers) {
            val all = Score()
            val line = StringBuilder(String.format("%-8s", f.name))
            for ((band, list) in bands(words, l)) {
                val s = Score()
                val rnd = Random(20261112 + f.name.hashCode() + band.hashCode())
                for (w in list) repeat(2) {
                    val path = swipe(w, l, f, rnd) ?: return@repeat
                    val got = decodeGlideOver(path, null, l.bounds, words)
                    val want = SuggestionEngine.foldKey(w)
                    // Same-skeleton spellings ("ее"/"её") are the same glide; either is a hit.
                    s.n++
                    if (got.isNotEmpty() && SuggestionEngine.fold(got[0]) == SuggestionEngine.fold(w)) s.top1++
                    if (got.any { SuggestionEngine.fold(it) == SuggestionEngine.fold(w) }) s.top3++
                    if (want.isEmpty()) error("empty word")
                }
                all.n += s.n; all.top1 += s.top1; all.top3 += s.top3
                line.append(String.format("  %-10s %5.1f / %5.1f", band, s.p1(), s.p3()))
            }
            line.append(String.format("   | all %5.1f / %5.1f", all.p1(), all.p3()))
            println(line)
            total[f.name] = all
        }
        return total
    }

    @Test fun english() {
        val t = run("en", en)
        assertTrue("normal finger top-1 fell to ${t.getValue("normal").p1()}", t.getValue("normal").p1() >= FLOOR_TOP1)
        assertTrue("normal finger top-3 fell to ${t.getValue("normal").p3()}", t.getValue("normal").p3() >= FLOOR_TOP3)
    }

    @Test fun russian() {
        val t = run("ru", ru)
        assertTrue("normal finger top-1 fell to ${t.getValue("normal").p1()}", t.getValue("normal").p1() >= FLOOR_TOP1)
        assertTrue("normal finger top-3 fell to ${t.getValue("normal").p3()}", t.getValue("normal").p3() >= FLOOR_TOP3)
    }

    /** Latvian shares the Latin layout and is glided by its base letters ("viņš" is v-i-n-s), so
     *  it competes with every plain-letter word of the same skeleton — reported, with a lower floor. */
    @Test fun latvian() {
        val t = run("lv", en)
        assertTrue("normal finger top-3 fell to ${t.getValue("normal").p3()}", t.getValue("normal").p3() >= 95.0)
    }

    @Test fun decodeIsFastEnoughForTheLivePreview() {
        val words = load("ru")
        val rnd = Random(7)
        val paths = words.take(4000).filter { it.length in 4..9 }.take(60).mapNotNull { swipe(it, ru, fingers[1], rnd) }
        repeat(2) { for (p in paths) decodeGlideOver(p, null, ru.bounds, words) }   // warm up the JIT
        val t0 = System.nanoTime()
        for (p in paths) decodeGlideOver(p, null, ru.bounds, words)
        val ms = (System.nanoTime() - t0) / 1e6 / paths.size
        println(String.format("=== decode cost: %.2f ms per glide (ru, %d words) ===", ms, words.size))
        // A desktop JVM is several times faster than a phone; this only catches an order of
        // magnitude going wrong (a quadratic slip in the candidate loop).
        assertTrue("decode takes $ms ms on a desktop JVM", ms < 25.0)
    }

    private companion object {
        // Floors, not targets: set a little under what the decoder measures, see the printed table.
        const val FLOOR_TOP1 = 93.0
        const val FLOOR_TOP3 = 98.5
    }
}
