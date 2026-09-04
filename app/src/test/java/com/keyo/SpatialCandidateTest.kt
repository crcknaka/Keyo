package com.keyo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Does generating correction candidates from the TOUCH POINTS beat generating them from the typed
 * string? This harness runs both pipelines on the same synthetic taps and prints both numbers, on
 * the toy vocabulary and on the real English list, at three levels of finger precision — and
 * then measures the two ways the new stage could do harm: mangling unknown words (names) and
 * overriding valid rare words the user typed on purpose.
 *
 * Same finger model as [TypingAccuracyTest]: Gaussian scatter around each key plus the downward
 * offset real thumbs have. Fixed seeds.
 */
class SpatialCandidateTest {

    private val keyW = 40.8f
    private val keyH = 56f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private val centers: Map<Char, Pair<Float, Float>> = buildMap {
        rows.forEachIndexed { r, row ->
            val indent = (10 - row.length) / 2f
            row.forEachIndexed { i, c -> put(c, (indent + i + 0.5f) * keyW to (r + 0.5f) * keyH) }
        }
    }
    private val neighbors: Map<Char, Set<Char>> = centers.keys.associateWith { a ->
        val (ax, ay) = centers.getValue(a)
        centers.entries.filter { (b, p) ->
            b != a && sqrt((ax - p.first) * (ax - p.first) + (ay - p.second) * (ay - p.second)) <= 1.7f * keyW
        }.map { it.key }.toSet()
    }

    private val toy = listOf(
        "the", "and", "you", "that", "was", "for", "are", "with", "his", "they",
        "this", "have", "from", "one", "had", "word", "but", "not", "what", "all",
        "were", "when", "your", "can", "said", "there", "use", "each", "which", "she",
        "how", "their", "will", "other", "about", "out", "many", "then", "them", "these",
        "some", "her", "would", "make", "like", "him", "into", "time", "has", "look",
        "two", "more", "write", "see", "number", "way", "could", "people", "than", "first",
        "water", "been", "call", "who", "now", "find", "long", "down", "day", "did",
        "get", "come", "made", "may", "part", "hello", "world", "keyboard", "message", "please"
    )
    private val real: List<String> by lazy {
        File("src/main/assets/dict/en.txt").readLines().map { it.trim().lowercase() }
            .filter { it.length in 2..14 && it.all { c -> c in 'a'..'z' } }
    }
    private val realSet by lazy { real.map { SuggestionEngine.fold(it) }.toHashSet() }
    /** Everyday words to type: frequent, 4–8 letters. */
    private val realWords by lazy { real.filter { it.length in 4..8 }.take(300) }
    /** Valid but RARE words the user might type on purpose (rank 20000–40000). */
    private val rareWords by lazy { real.drop(20000).filter { it.length in 4..9 }.filterIndexed { i, _ -> i % 67 == 0 }.take(120) }
    /** Real unknown words at scale: Latvian dictionary words (folded to base keys) that the English
     *  list does not contain, typed on the English layout — exactly what a user in Riga does all
     *  day. The OOV metric that 24 names cannot give. */
    private val foreign: List<String> by lazy {
        File("src/main/assets/dict/lv.txt").readLines().map { SuggestionEngine.foldKey(it.trim().lowercase()) }
            .filter { it.length in 4..9 && it.all { c -> c in 'a'..'z' } && it !in realSet }
            .distinct().take(3000).filterIndexed { i, _ -> i % 10 == 0 }
    }
    private val names = listOf(
        "kovalenko", "keyo", "gboard", "sasha", "vilnius", "figma", "nginx", "kotlin",
        "zurich", "matrix", "kafka", "grafana", "salsa", "lasagna", "quokka", "riga",
        "jelgava", "liepaja", "ventspils", "coolify", "groq", "whisper", "hetzner", "vercel"
    )

    private fun gauss(rnd: Random): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9); val u2 = rnd.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
    }
    private fun tap(c: Char, rnd: Random, sigma: Float, bias: Float): Pair<Float, Float> {
        val (cx, cy) = centers.getValue(c)
        return (cx + gauss(rnd) * sigma * keyW) to (cy + gauss(rnd) * sigma * keyH + bias * keyH)
    }

    private fun keyAt(px: Float, py: Float): Char = centers.entries.minByOrNull { (_, p) ->
        val dx = px - p.first; val dy = py - p.second; dx * dx + dy * dy
    }!!.key

    private fun decideKey(px: Float, py: Float, prefix: String, vocab: List<String>): Char {
        val tapped = keyAt(px, py)
        if (prefix.isEmpty()) return tapped
        val near = centers.entries
            .map { it.key to SuggestionEngine.cellDistance(px, py, it.value.first, it.value.second, keyW, keyH) }
            .sortedBy { it.second }.take(2)
        return SuggestionEngine.chooseKey(tapped, near) { c ->
            SuggestionEngine.prefixStrengthFrom(prefix + c, vocab, emptyMap())
        }
    }

    private class Typed(val text: String, val taps: List<Pair<Float, Float>>)

    private fun type(word: String, rnd: Random, sigma: Float, bias: Float, vocab: List<String>): Typed {
        val sb = StringBuilder(); val taps = ArrayList<Pair<Float, Float>>(word.length)
        for (c in word) {
            val p = tap(c, rnd, sigma, bias); taps.add(p)
            sb.append(decideKey(p.first, p.second, sb.toString(), vocab))
        }
        return Typed(sb.toString(), taps)
    }

    /** The shipped word-boundary pipeline: known words untouched, else string-edit candidates. */
    private fun baseline(t: Typed, vocab: List<String>, set: Set<String>): String {
        if (set.contains(SuggestionEngine.fold(t.text))) return t.text
        val cands = SuggestionEngine.correctionsFrom(t.text, vocab, set, emptyMap(), 12, maxEdits = 2)
        return SuggestionEngine.pickAutocorrect(t.text, cands, neighbors) { SuggestionEngine.rankIn(it, vocab) } ?: t.text
    }

    /** The same, with the touch-point stage deciding first (this is how finishWord will wire it). */
    private fun withSpatial(t: Typed, vocab: List<String>, set: Set<String>, p: Params): String {
        val known = set.contains(SuggestionEngine.fold(t.text))
        val picks = SuggestionEngine.spatialCandidates(t.taps, centers, keyW, keyH, vocab,
            sigma = p.sigma, prior = p.prior, worstGate = p.gate)
        val typedCost = SuggestionEngine.spatialCostOf(t.text, t.taps, centers, keyW, keyH)
        val typedRank = if (known) SuggestionEngine.rankIn(t.text, vocab) else vocab.size
        val d = SuggestionEngine.spatialDecision(t.text, typedCost, typedRank, known, picks,
            sigma = p.sigma, prior = p.prior, marginUnknown = p.mUnknown, marginKnown = p.mKnown)
        return d ?: baseline(t, vocab, set)
    }

    private data class Params(
        val sigma: Float = SuggestionEngine.SPATIAL_SIGMA, val prior: Float = SuggestionEngine.SPATIAL_PRIOR,
        val gate: Float = SuggestionEngine.SPATIAL_WORST_GATE,
        val mUnknown: Float = SuggestionEngine.SPATIAL_MARGIN_UNKNOWN, val mKnown: Float = SuggestionEngine.SPATIAL_MARGIN_KNOWN
    )

    /** (baseline accuracy, spatial accuracy) over [words] typed with the given finger. */
    private fun measure(words: List<String>, vocab: List<String>, set: Set<String>, sigma: Float, bias: Float,
                        p: Params, reps: Int, seed: Int = 20260904): Pair<Float, Float> {
        val rnd = Random(seed)
        var b = 0; var s = 0; var total = 0
        repeat(reps) {
            for (w in words) {
                val t = type(w, rnd, sigma, bias, vocab)
                total++
                if (baseline(t, vocab, set) == w) b++
                if (withSpatial(t, vocab, set, p) == w) s++
            }
        }
        return b.toFloat() / total to s.toFloat() / total
    }

    @Test fun toyVocabulary() {
        val set = toy.map { SuggestionEngine.fold(it) }.toSet()
        println("=== toy vocabulary: baseline -> with touch points ===")
        for (sigma in listOf(0.20f, 0.28f, 0.36f)) {
            val (b, s) = measure(toy, toy, set, sigma, 0.12f, Params(), reps = 20)
            println("  sigma=%.2f  %.1f%% -> %.1f%%".format(sigma, b * 100, s * 100))
            assertTrue("sigma=$sigma: touch points made things worse ($b -> $s)", s >= b - 0.005f)
        }
    }

    @Test fun realDictionary() {
        println("=== real en dictionary (%d words typed, %d known): baseline -> with touch points ===".format(realWords.size, real.size))
        for (sigma in listOf(0.20f, 0.28f, 0.36f)) {
            val (b, s) = measure(realWords, real, realSet, sigma, 0.12f, Params(), reps = 3)
            println("  sigma=%.2f  %.1f%% -> %.1f%%".format(sigma, b * 100, s * 100))
            assertTrue("sigma=$sigma: touch points made things worse ($b -> $s)", s >= b - 0.005f)
            // Floors just under the measured values, so a change that quietly gives the gain back
            // fails here rather than in someone's chat.
            if (sigma == 0.28f) assertTrue("normal finger: $s", s >= 0.95f)
            if (sigma == 0.36f) assertTrue("sloppy finger: $s", s >= 0.90f)
        }
    }

    @Test fun damage_namesTypedCarefully() {
        // Unknown words with a careful finger: the typed string IS the intent.
        val (b, s) = measure(names, real, realSet, 0.18f, 0.12f, Params(), reps = 8)
        println("=== unknown names, careful finger: survive %.1f%% -> %.1f%% ===".format(b * 100, s * 100))
        assertTrue("touch points mangle names more than the string path ($b -> $s)", s >= b - 0.02f)
    }

    @Test fun damage_rareValidWordsTypedCarefully() {
        // A valid word the user typed on purpose, however rare, must not be swapped for a
        // frequent neighbour. The string path never touches known words, so its number is 100%.
        val (b, s) = measure(rareWords, real, realSet, 0.18f, 0.12f, Params(), reps = 4)
        println("=== rare valid words, careful finger: survive %.1f%% -> %.1f%% ===".format(b * 100, s * 100))
        assertTrue("valid rare words are being overridden ($s)", s >= 0.98f)
    }

    @Test fun sweep() {
        println("=== sweep, real dictionary: accuracy @0.28 / @0.36  |  survival: rare valid @0.18 / foreign @0.18 / foreign @0.25 ===")
        for (prior in listOf(0.55f, 0.8f, 1.1f, 1.5f, 2.0f)) for (gate in listOf(0.95f, 1.3f)) for (mu in listOf(1.0f, 2.5f)) {
            val p = Params(prior = prior, gate = gate, mUnknown = mu)
            val a28 = measure(realWords, real, realSet, 0.28f, 0.12f, p, reps = 2).second
            val a36 = measure(realWords, real, realSet, 0.36f, 0.12f, p, reps = 2).second
            val rare = measure(rareWords, real, realSet, 0.18f, 0.12f, p, reps = 2).second
            val f18 = measure(foreign, real, realSet, 0.18f, 0.12f, p, reps = 1).second
            val f25 = measure(foreign, real, realSet, 0.25f, 0.12f, p, reps = 1).second
            println("  prior=%.2f gate=%.2f mUnknown=%.1f   %.1f%% / %.1f%%  |  %.1f%% / %.1f%% / %.1f%%".format(prior, gate, mu, a28 * 100, a36 * 100, rare * 100, f18 * 100, f25 * 100))
        }
        val b18 = measure(foreign, real, realSet, 0.18f, 0.12f, Params(), reps = 1).first
        val b25 = measure(foreign, real, realSet, 0.25f, 0.12f, Params(), reps = 1).first
        println("  (string-path baseline: %d foreign words survive %.1f%% @0.18, %.1f%% @0.25)".format(foreign.size, b18 * 100, b25 * 100))
    }

    @Test fun damage_foreignWordsOnTheEnglishLayout() {
        val (b, s) = measure(foreign, real, realSet, 0.22f, 0.12f, Params(), reps = 1)
        println("=== %d Latvian words on the EN layout, sigma 0.22: survive %.1f%% -> %.1f%% ===".format(foreign.size, b * 100, s * 100))
        assertTrue("touch points mangle foreign words more than the string path ($b -> $s)", s >= b - 0.02f)
    }

    @Test fun costPerBoundary() {
        val t = type("keyboard", Random(1), 0.25f, 0.12f, real)
        repeat(20) { SuggestionEngine.spatialCandidates(t.taps, centers, keyW, keyH, real) }
        val t0 = System.nanoTime()
        repeat(100) { SuggestionEngine.spatialCandidates(t.taps, centers, keyW, keyH, real) }
        val ms = (System.nanoTime() - t0) / 1e6 / 100
        println("touch-point candidates over %d words: %.2f ms per word boundary".format(real.size, ms))
        assertTrue("too slow for the main thread: %.1fms".format(ms), ms < 8.0)
    }
}
