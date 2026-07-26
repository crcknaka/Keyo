package com.keyo

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Offline decode-accuracy harness.
 *
 * The keyboard's spatial constants (the 0.55 early-out and 1.3x margin in [SuggestionEngine.chooseKey],
 * the touch bias, the candidate scan limits) were all picked by hand and never measured. This test
 * builds a fixture keyboard, synthesises realistic taps for known words — Gaussian scatter around
 * each key plus the systematic downward offset real fingers have — and measures how often the
 * pipeline recovers the intended word.
 *
 * It is deterministic (fixed seed) and asserts on floors, not exact numbers, so it fails when a
 * change makes typing measurably worse rather than on every harmless re-tuning.
 */
class TypingAccuracyTest {

    // ---- Fixture keyboard: the same unified grid the real layout now uses ----
    // 10 units wide; row 1 is 10 keys, row 2 is 9 keys indented half a unit, row 3 is 7 keys
    // indented 1.5 units (where Shift/Backspace sit).
    // The shipped geometry on a 411dp screen: 40.8dp column pitch, 56dp row pitch (both measured
    // off Gboard). The harness models touch CELLS, so these are pitches, not drawn key sizes.
    private val keyW = 40.8f
    private val keyH = 56f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private data class Key(val cx: Float, val cy: Float)

    private val centers: Map<Char, Key> = buildMap {
        rows.forEachIndexed { r, row ->
            val indent = (10 - row.length) / 2f
            row.forEachIndexed { i, c ->
                put(c, Key(cx = (indent + i + 0.5f) * keyW, cy = (r + 0.5f) * keyH))
            }
        }
    }

    /** A small English vocabulary, most-frequent first (the order the real dictionaries use). */
    private val vocab = listOf(
        "the", "and", "you", "that", "was", "for", "are", "with", "his", "they",
        "this", "have", "from", "one", "had", "word", "but", "not", "what", "all",
        "were", "when", "your", "can", "said", "there", "use", "each", "which", "she",
        "how", "their", "will", "other", "about", "out", "many", "then", "them", "these",
        "some", "her", "would", "make", "like", "him", "into", "time", "has", "look",
        "two", "more", "write", "see", "number", "way", "could", "people", "than", "first",
        "water", "been", "call", "who", "oil", "its", "now", "find", "long", "down",
        "day", "did", "get", "come", "made", "may", "part", "hello", "world", "keyboard"
    )
    private val vocabSet = vocab.map { SuggestionEngine.fold(it) }.toSet()

    /** Neighbour map built the way KeyoService.neighbors() does: keys within 1.7 key-widths. */
    private val neighbors: Map<Char, Set<Char>> = centers.keys.associateWith { a ->
        val ka = centers.getValue(a)
        centers.entries.filter { (b, kb) ->
            b != a && sqrt((ka.cx - kb.cx) * (ka.cx - kb.cx) + (ka.cy - kb.cy) * (ka.cy - kb.cy)) <= 1.7f * keyW
        }.map { it.key }.toSet()
    }

    /**
     * Synthesise where a finger aiming at [c] actually lands: Gaussian scatter, plus the systematic
     * downward offset that motivates [SuggestionEngine.TOUCH_BIAS_Y].
     */
    private fun tapFor(c: Char, rnd: Random, sigma: Float, biasY: Float): Pair<Float, Float> {
        val k = centers.getValue(c)
        fun gauss(): Float {
            // Box-Muller; nextDouble() is exclusive of 1.0 and we guard 0.0, so log is finite.
            val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
            val u2 = rnd.nextDouble()
            return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
        }
        return (k.cx + gauss() * sigma * keyW) to (k.cy + gauss() * sigma * keyH + biasY * keyH)
    }

    /** Which key the touch geometrically lands on — what Android's hit-testing would report. */
    private fun keyAt(px: Float, py: Float): Char =
        centers.entries.minByOrNull { (_, k) ->
            val dx = px - k.cx; val dy = py - k.cy; dx * dx + dy * dy
        }!!.key

    /** The letter the keyboard commits for one tap, including prefix-aware disambiguation. */
    private fun decideKey(px: Float, py: Float, prefix: String): Char {
        val tapped = keyAt(px, py)
        if (prefix.isEmpty()) return tapped     // word starts are never second-guessed
        val near = centers.entries
            .map { it.key to SuggestionEngine.cellDistance(px, py, it.value.cx, it.value.cy, keyW, keyH) }
            .sortedBy { it.second }.take(2)
        return SuggestionEngine.chooseKey(tapped, near) { c ->
            SuggestionEngine.prefixStrengthFrom(prefix + c, vocab, emptyMap())
        }
    }

    /** Full pipeline for one word: per-tap key decisions, then the word-boundary autocorrect. */
    private fun typeWord(word: String, rnd: Random, sigma: Float, biasY: Float): String {
        val sb = StringBuilder()
        val pts = ArrayList<Pair<Float, Float>>(word.length)
        for (c in word) {
            val p = tapFor(c, rnd, sigma, biasY)
            pts.add(p)
            sb.append(decideKey(p.first, p.second, sb.toString()))
        }
        val typed = sb.toString()
        if (typed == word) return typed
        if (vocabSet.contains(SuggestionEngine.fold(typed))) return typed  // a real word: left alone
        val cands = SuggestionEngine.correctionsFrom(typed, vocab, vocabSet, emptyMap(), 12, maxEdits = 2)
        return SuggestionEngine.pickAutocorrect(typed, cands, neighbors) ?: typed
    }

    private fun accuracy(sigma: Float, biasY: Float, seed: Int = 20260726): Pair<Float, Float> {
        val rnd = Random(seed)
        var rawHits = 0
        var finalHits = 0
        var total = 0
        repeat(20) {
            for (w in vocab) {
                if (w.any { it !in centers }) continue
                total++
                // raw = what the taps spell before autocorrect; final = what the user ends up with
                val rndRaw = Random(seed * 31 + total)
                val sb = StringBuilder()
                for (c in w) {
                    val p = tapFor(c, rndRaw, sigma, biasY)
                    sb.append(decideKey(p.first, p.second, sb.toString()))
                }
                if (sb.toString() == w) rawHits++
                if (typeWord(w, rnd, sigma, biasY) == w) finalHits++
            }
        }
        val raw = rawHits.toFloat() / total
        val fin = finalHits.toFloat() / total
        // Printed so the numbers show up in the test report — this harness exists to be READ, not
        // only to pass. Re-tuning a spatial constant should move these.
        println("sigma=%.2f bias=%.2f  raw=%.1f%%  after-autocorrect=%.1f%%"
            .format(sigma, biasY, raw * 100, fin * 100))
        return raw to fin
    }

    // ---- The measurements ----

    @Test fun accuracy_isHighForCarefulTyping() {
        // Tight aim, small downward drift: nearly everything should land right.
        val (raw, final) = accuracy(sigma = 0.18f, biasY = 0.10f)
        assertTrue("raw key accuracy $raw too low", raw >= 0.90f)
        assertTrue("final word accuracy $final below raw $raw", final >= raw)
        assertTrue("final word accuracy $final too low", final >= 0.93f)
    }

    @Test fun accuracy_survivesSloppyTyping() {
        // Fast, careless typing: wide scatter and a pronounced low bias.
        val (raw, final) = accuracy(sigma = 0.34f, biasY = 0.16f)
        assertTrue("final word accuracy $final too low for sloppy typing", final >= 0.70f)
        assertTrue("autocorrect must recover words the taps got wrong ($raw -> $final)", final > raw)
    }

    @Test fun autocorrect_neverMakesThingsWorseOnAverage() {
        // Across a range of finger precision, the correction stage must never end up behind the
        // letters the finger actually produced.
        for (sigma in listOf(0.12f, 0.20f, 0.28f, 0.36f)) {
            val (raw, final) = accuracy(sigma = sigma, biasY = 0.12f)
            assertTrue("sigma=$sigma: autocorrect regressed ($raw -> $final)", final >= raw - 0.01f)
        }
    }

    @Test fun touchBias_compensatesForLowTaps() {
        // The whole point of TOUCH_BIAS_Y: when fingers land low, modelling that must beat ignoring
        // it. Compare the shipped bias against a model that assumes taps are centred.
        val withBias = accuracy(sigma = 0.26f, biasY = 0.16f).second
        val ignoringBias = run {
            val rnd = Random(20260726)
            var hits = 0; var total = 0
            repeat(20) {
                for (w in vocab) {
                    total++
                    val sb = StringBuilder()
                    for (c in w) {
                        val (px, py) = tapFor(c, rnd, 0.26f, 0.16f)
                        // Same taps, but every key modelled as if the finger aimed dead centre.
                        val tapped = keyAt(px, py)
                        val near = centers.entries
                            .map { it.key to SuggestionEngine.cellDistance(px, py, it.value.cx, it.value.cy, keyW, keyH, biasY = 0f) }
                            .sortedBy { it.second }.take(2)
                        val prefix = sb.toString()
                        sb.append(if (prefix.isEmpty()) tapped else
                            SuggestionEngine.chooseKey(tapped, near) { c2 ->
                                SuggestionEngine.prefixStrengthFrom(prefix + c2, vocab, emptyMap())
                            })
                    }
                    if (sb.toString() == w) hits++
                }
            }
            hits.toFloat() / total
        }
        assertTrue("bias-aware model ($withBias) should not trail the naive one ($ignoringBias)",
            withBias >= ignoringBias)
    }

    @Test fun cellDistance_treatsAxesByTheirOwnPitch() {
        // A horizontal miss of one key width and a vertical miss of one key height are both
        // "one cell" — the old width-only normalisation understated vertical error on a grid whose
        // keys are taller than they are wide.
        val h = SuggestionEngine.cellDistance(keyW, 0f, 0f, 0f, keyW, keyH, biasY = 0f)
        val v = SuggestionEngine.cellDistance(0f, keyH, 0f, 0f, keyW, keyH, biasY = 0f)
        assertTrue("horizontal cell distance $h", kotlin.math.abs(h - 1f) < 1e-4)
        assertTrue("vertical cell distance $v", kotlin.math.abs(v - 1f) < 1e-4)
    }

    @Test fun cellDistance_shiftsTargetCentreDown() {
        // A tap landing exactly TOUCH_BIAS_Y below the drawn centre is treated as a bullseye.
        val d = SuggestionEngine.cellDistance(
            0f, SuggestionEngine.TOUCH_BIAS_Y * keyH, 0f, 0f, keyW, keyH)
        assertTrue("on-target low tap should read as zero error, was $d", d < 1e-4)
    }
}
