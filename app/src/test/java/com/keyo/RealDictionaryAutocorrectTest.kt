package com.keyo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Autocorrect against the REAL shipped dictionaries, not a toy word list.
 *
 * The synthetic tests prove the algorithms; this one answers the question a user actually asks —
 * "I mistyped подсказкт, why didn't it become подсказки?" — because the answer depends on the real
 * frequency ordering, which decides how far down the list a word sits and whether the correction
 * scan ever reaches it.
 */
class RealDictionaryAutocorrectTest {

    private fun load(lang: String): List<String> {
        val f = File("src/main/assets/dict/$lang.txt")
        assertTrue("dictionary missing: ${f.absolutePath}", f.exists())
        return f.readLines().mapNotNull { line ->
            line.trim().substringBefore('\t').substringBefore(' ').lowercase().ifEmpty { null }
        }
    }

    private val ru by lazy { load("ru") }
    private val ruSet by lazy { ru.map { SuggestionEngine.fold(it) }.toSet() }

    /** The Cyrillic layout's physical neighbours, so distance-2 fat-finger fixes can be judged. */
    private val ruNeighbors: Map<Char, Set<Char>> by lazy {
        val rows = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        val pos = HashMap<Char, Pair<Int, Double>>()
        rows.forEachIndexed { r, row ->
            val indent = (12 - row.length) / 2.0
            row.forEachIndexed { i, c -> pos[c] = r to (indent + i) }
        }
        pos.keys.associateWith { a ->
            val (ra, xa) = pos.getValue(a)
            pos.entries.filter { (b, p) ->
                b != a && kotlin.math.abs(p.first - ra) <= 1 && kotlin.math.abs(p.second - xa) <= 1.5
            }.map { it.key }.toSet()
        }
    }

    private fun correct(typed: String, scanLimit: Int = 6000): String? {
        val cands = SuggestionEngine.correctionsFrom(
            typed, ru, ruSet, emptyMap(), limit = 12, scanLimit = scanLimit, maxEdits = 2)
        return SuggestionEngine.pickAutocorrect(typed, cands, ruNeighbors)
    }

    @Test fun report_whatTheRealDictionaryDoes() {
        val cases = listOf(
            "вме" to "все", "подсказкт" to "подсказки", "привте" to "привет",
            "спасибл" to "спасибо", "сообщенте" to "сообщение", "поеду" to "поеду",
            "хорошр" to "хорошо", "делаю" to "делаю", "напишк" to "напишу",
            "завтар" to "завтра", "пожалуйств" to "пожалуйста", "конечнл" to "конечно"
        )
        println("=== real ru.txt (${ru.size} words) ===")
        for ((typed, want) in cases) {
            val rank = ru.indexOf(want)
            val got = correct(typed)
            val mark = if (got == want) "OK " else if (SuggestionEngine.fold(typed) in ruSet) "(valid word)" else "MISS"
            println("$mark $typed -> ${got ?: "—"}   (want $want, rank $rank)")
        }
    }

    @Test fun report_scanLimitEffect() {
        println("=== how far the scan has to reach ===")
        for (typed in listOf("подсказкт", "сообщенте", "пожалуйств", "напишк")) {
            val at6k = correct(typed, 6000)
            val at20k = correct(typed, 20000)
            val atAll = correct(typed, ru.size)
            println("$typed:  6k=${at6k ?: "—"}   20k=${at20k ?: "—"}   full=${atAll ?: "—"}")
        }
    }

    @Test fun commonTypos_getFixed() {
        // The everyday cases must work at the shipped scan limit.
        for ((typed, want) in listOf("вме" to "все", "привте" to "привет", "завтар" to "завтра")) {
            assertTrue("$typed should correct to $want, got ${correct(typed)}", correct(typed) == want)
        }
    }
}
