package com.keyo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Sweeps the spatial constants that decide which key a near-boundary tap meant, and prints the
 * accuracy each setting produces. They were all picked by hand and never measured:
 *
 *   hitBiasY                  how far the hit grid sits below the drawn keys   (KeyoService)
 *   SuggestionEngine.NEAR_GATE   how close the runner-up must be to count      (chooseKey)
 *   SuggestionEngine.WIN_MARGIN  how decisively it must win to override the tap (chooseKey)
 *
 * The finger model is the same as [TypingAccuracyTest]: Gaussian scatter around each key plus the
 * systematic downward offset real thumbs have. Fixed seed, so a change in the numbers is a change
 * in the code and not in the dice.
 *
 * Two vocabularies are used, because they pull in opposite directions:
 *  - a small hand-written list, where the language signal is unrealistically clean, and
 *  - the real bundled English dictionary, where thousands of words compete for every prefix.
 * A constant is only worth adopting if it wins on the real dictionary too.
 */
class SpatialTuningTest {

    private val keyW = 41f
    private val keyH = 56f          // row pitch, matching the shipped default
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private data class Key(val cx: Float, val cy: Float)

    private val centers: Map<Char, Key> = buildMap {
        rows.forEachIndexed { r, row ->
            val indent = (10 - row.length) / 2f
            row.forEachIndexed { i, c ->
                put(c, Key((indent + i + 0.5f) * keyW, (r + 0.5f) * keyH))
            }
        }
    }

    private val toyVocab = listOf(
        "the", "and", "you", "that", "was", "for", "are", "with", "his", "they",
        "this", "have", "from", "one", "had", "word", "but", "not", "what", "all",
        "were", "when", "your", "can", "said", "there", "use", "each", "which", "she",
        "how", "their", "will", "other", "about", "out", "many", "then", "them", "these",
        "some", "her", "would", "make", "like", "him", "into", "time", "has", "look",
        "two", "more", "write", "see", "number", "way", "could", "people", "than", "first",
        "water", "been", "call", "who", "now", "find", "long", "down", "day", "did",
        "get", "come", "made", "may", "part", "hello", "world", "keyboard", "message", "please"
    )

    /** The real bundled dictionary, cut at the same 8000-word limit prefixStrengthFrom scans, so
     *  the language signal is exactly the one production computes. */
    private val realVocab: List<String> by lazy {
        File("src/main/assets/dict/en.txt").readLines()
            .map { it.trim().lowercase() }
            .filter { it.length in 2..12 && it.all { c -> c in 'a'..'z' } }
            .take(8000)
    }

    /** Words to type on the real dictionary: frequent enough to be everyday typing, long enough
     *  that a mid-word slip has context to work with. */
    private val realWords: List<String> by lazy { realVocab.filter { it.length in 4..8 }.take(300) }

    private fun gauss(rnd: Random): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
        val u2 = rnd.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
    }

    /** Where a finger aiming at [c] actually lands. [fingerBias] is the real downward offset. */
    private fun tap(c: Char, rnd: Random, sigma: Float, fingerBias: Float): Pair<Float, Float> {
        val k = centers.getValue(c)
        return (k.cx + gauss(rnd) * sigma * keyW) to (k.cy + gauss(rnd) * sigma * keyH + fingerBias * keyH)
    }

    /** The letter the keyboard commits, with the grid offset by [hitBias] and chooseKey's two gates. */
    private fun decide(
        px: Float, py: Float, prefix: String,
        hitBias: Float, near: Float, margin: Float, vocab: List<String>
    ): Char {
        // Rectangular hit grid shifted down by hitBias — same rule as KeyoService.biasedKey.
        val y = py - keyH * hitBias
        val rowY = centers.values.minByOrNull { kotlin.math.abs(it.cy - y) }!!.cy
        val tapped = centers.entries
            .filter { kotlin.math.abs(it.value.cy - rowY) < keyH * 0.5f }
            .minByOrNull { kotlin.math.abs(it.value.cx - px) }!!.key
        if (prefix.isEmpty()) return tapped
        val cands = centers.entries
            .map { it.key to SuggestionEngine.cellDistance(px, py, it.value.cx, it.value.cy, keyW, keyH) }
            .sortedBy { it.second }.take(2)
        return SuggestionEngine.chooseKey(tapped, cands, near, margin) { c ->
            SuggestionEngine.prefixStrengthFrom(prefix + c, vocab, emptyMap())
        }
    }

    /** Share of words typed exactly right. [words] is what the user intends to type, [vocab] is what
     *  the keyboard knows — pass a word list the vocab does NOT contain to measure damage instead. */
    private fun accuracy(
        sigma: Float, fingerBias: Float, hitBias: Float, near: Float, margin: Float,
        words: List<String> = toyVocab, vocab: List<String> = toyVocab, reps: Int = 30
    ): Float {
        val rnd = Random(20260817)
        var hit = 0
        var total = 0
        repeat(reps) {
            for (w in words) {
                val sb = StringBuilder()
                for (c in w) {
                    val (px, py) = tap(c, rnd, sigma, fingerBias)
                    sb.append(decide(px, py, sb.toString(), hitBias, near, margin, vocab))
                }
                total++
                if (sb.toString() == w) hit++
            }
        }
        return hit.toFloat() / total
    }

    @Test fun sweep_hitBias() {
        println("=== hit-grid offset (finger lands 0.12 key-heights low, sigma 0.28) ===")
        var best = -1f; var bestVal = 0f
        for (b in listOf(0f, 0.05f, 0.10f, 0.15f, 0.20f, 0.25f)) {
            val a = accuracy(0.28f, 0.12f, b, SuggestionEngine.NEAR_GATE, SuggestionEngine.WIN_MARGIN)
            println("  hitBiasY=%.2f -> %.1f%%".format(b, a * 100))
            if (a > bestVal) { bestVal = a; best = b }
        }
        println("  best: %.2f (%.1f%%), shipping 0.15".format(best, bestVal * 100))
        assertTrue(bestVal > 0f)
    }

    @Test fun sweep_chooseKeyGates() {
        println("=== chooseKey gates, toy vocabulary (sigma 0.28, finger bias 0.12, hitBiasY 0.15) ===")
        for (near in listOf(0.35f, 0.55f, 0.75f, 1.0f)) {
            val line = listOf(1.05f, 1.15f, 1.3f, 1.6f, 2.0f).joinToString("  ") { m ->
                "margin=%.2f: %.1f%%".format(m, accuracy(0.28f, 0.12f, 0.15f, near, m) * 100)
            }
            println("  near=%.2f   ".format(near) + line)
        }
    }

    /** The same sweep where every prefix has thousands of real continuations competing — the toy
     *  list makes the language signal far cleaner than production ever sees. */
    @Test fun sweep_chooseKeyGates_realDictionary() {
        println("=== chooseKey gates, real en dictionary (%d words typed, %d known) ==="
            .format(realWords.size, realVocab.size))
        for (near in listOf(0.55f, 0.75f, 1.0f)) {
            val line = listOf(1.0f, 1.05f, 1.1f, 1.15f, 1.3f).joinToString("  ") { m ->
                "margin=%.2f: %.1f%%".format(
                    m, accuracy(0.28f, 0.12f, 0.15f, near, m, realWords, realVocab, reps = 3) * 100)
            }
            println("  near=%.2f   ".format(near) + line)
        }
    }

    /**
     * The other half of the trade-off. The sweeps above only ever type words the vocabulary knows,
     * so a looser gate always looks better there: overriding the tap is free when the answer is
     * guaranteed to be a real word. In real use a large share of typing is names, logins, slang and
     * foreign words the dictionary has never seen, and there overriding the tap is pure damage —
     * the finger was right and the keyboard "fixed" it into something else.
     *
     * So this types unknown words with a careful finger (small scatter — the user was being precise
     * exactly because it is an unusual word) and counts how often the result comes out wrong. A
     * setting is only worth adopting if it wins above without losing much here.
     */
    @Test fun oovDamage_acrossGates() {
        val oov = listOf(
            "kovalenko", "keyo", "gboard", "sasha", "vilnius", "figma", "nginx", "kotlin",
            "podcast", "zurich", "matrix", "kafka", "grafana", "salsa", "lasagna", "quokka"
        )
        fun damage(near: Float, margin: Float) =
            1f - accuracy(0.18f, 0.12f, 0.15f, near, margin, oov, realVocab, reps = 6)

        println("=== damage to unknown words (names/slang, careful finger sigma 0.18) ===")
        for (near in listOf(0.55f, 0.75f, 1.0f)) {
            val line = listOf(1.0f, 1.05f, 1.1f, 1.15f, 1.3f).joinToString("  ") { m ->
                "margin=%.2f: %.1f%% mangled".format(m, damage(near, m) * 100)
            }
            println("  near=%.2f   ".format(near) + line)
        }
        val shipped = damage(SuggestionEngine.NEAR_GATE, SuggestionEngine.WIN_MARGIN)
        println("  shipped near=%.2f margin=%.2f -> %.1f%% mangled"
            .format(SuggestionEngine.NEAR_GATE, SuggestionEngine.WIN_MARGIN, shipped * 100))
        // The shipped setting must not be actively hostile to words it does not know.
        assertTrue("shipped gates mangle unknown words too often: %.1f%%".format(shipped * 100), shipped < 0.20f)
    }

    @Test fun sweep_acrossFingerPrecision() {
        println("=== shipping settings across finger precision ===")
        for (sigma in listOf(0.15f, 0.22f, 0.28f, 0.35f)) {
            val off = accuracy(sigma, 0.12f, 0f, SuggestionEngine.NEAR_GATE, SuggestionEngine.WIN_MARGIN)
            val on = accuracy(sigma, 0.12f, 0.15f, SuggestionEngine.NEAR_GATE, SuggestionEngine.WIN_MARGIN)
            println("  sigma=%.2f   no offset %.1f%%  ->  shipped offset %.1f%%".format(sigma, off * 100, on * 100))
            assertTrue("the hit-grid offset should not hurt at sigma=$sigma", on >= off - 0.005f)
        }
    }
}
