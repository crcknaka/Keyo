package com.keyo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the per-keystroke and per-word work actually costs, against the real dictionaries.
 *
 * `finishWord` runs the whole correction cascade on the MAIN thread at every word boundary, and the
 * correction scan now falls through to the rest of the dictionary when the cheap prefix finds no
 * single-edit fix. That is the case worth measuring: it is the one that walks 48k words.
 *
 * Numbers are printed rather than asserted tightly — this is a budget check, not a benchmark, and
 * CI machines vary. The assertions only catch a change that makes something wildly slower.
 */
class HotPathCostTest {

    private val ru by lazy {
        File("src/main/assets/dict/ru.txt").readLines().mapNotNull {
            it.trim().substringBefore('\t').substringBefore(' ').lowercase().ifEmpty { null }
        }
    }
    private val ruSet by lazy { ru.map { SuggestionEngine.fold(it) }.toSet() }

    private fun millis(reps: Int, body: () -> Unit): Double {
        repeat(5) { body() }                      // warm the JIT
        val t0 = System.nanoTime()
        repeat(reps) { body() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    @Test fun correctionScan_costs() {
        // "привте" is fixed inside the cheap prefix; "подсказкт" only by reading the whole list.
        val quick = millis(50) {
            SuggestionEngine.correctionsFrom("привте", ru, ruSet, emptyMap(), 12, maxEdits = 2)
        }
        val deep = millis(20) {
            SuggestionEngine.correctionsFrom("подсказкт", ru, ruSet, emptyMap(), 12, maxEdits = 2)
        }
        val known = millis(200) {
            SuggestionEngine.correctionsFrom("привет", ru, ruSet, emptyMap(), 12, maxEdits = 2)
        }
        println("correction scan: common typo %.2fms | rare typo (full dictionary) %.2fms | known word %.3fms"
            .format(quick, deep, known))
        // A known word must cost essentially nothing — that is every second keystroke.
        assertTrue("a known word should short-circuit, took %.3fms".format(known), known < 1.0)
        assertTrue("the deep scan got out of hand: %.1fms".format(deep), deep < 250.0)
    }

    @Test fun completion_costs() {
        val learned = (1..4000).associate { "слово$it" to (it % 7) }
        val cold = millis(100) { SuggestionEngine.completeFrom("при", ru, learned, 12) }
        println("completion with a full 4000-word personal dictionary: %.2fms".format(cold))
        assertTrue("completion got expensive: %.1fms".format(cold), cold < 40.0)
    }

    @Test fun rankLookup_isBounded() {
        val common = millis(500) { SuggestionEngine.rankIn("привет", ru) }
        val absent = millis(500) { SuggestionEngine.rankIn("щщщщщщ", ru) }
        println("rank lookup: common %.3fms | absent (bounded scan) %.3fms".format(common, absent))
        assertTrue("rank lookup should stay bounded: %.2fms".format(absent), absent < 10.0)
    }
}
