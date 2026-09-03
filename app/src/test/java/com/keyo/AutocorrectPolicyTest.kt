package com.keyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rules that decide WHETHER to correct, as opposed to which correction is closest. Every case
 * here was a real failure found by reading the shipped dictionaries and the code side by side:
 * misspellings shipped as words, a rank-12756 surname "correcting" a city, a pair typed twice
 * becoming sentence context, a learned name failing to protect its ё-less spelling.
 *
 * Runs against the real bundled lists so a regression in the DATA is caught as well as one in
 * the code — the English dictionary had never been under test before, which is how "teh" sat in
 * it as a word (and therefore could never be corrected) for months.
 */
class AutocorrectPolicyTest {

    private fun load(lang: String) = File("src/main/assets/dict/$lang.txt").readLines()
        .mapNotNull { it.trim().lowercase().ifEmpty { null } }

    private val en by lazy { load("en") }
    private val enSet by lazy { en.map { SuggestionEngine.fold(it) }.toHashSet() }
    private val ru by lazy { load("ru") }
    private val ruSet by lazy { ru.map { SuggestionEngine.fold(it) }.toHashSet() }

    private val qwerty = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val jcuken = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
    private val enNeighbors: Map<Char, Set<Char>> by lazy { neighborsOf(qwerty) }
    private val ruNeighbors: Map<Char, Set<Char>> by lazy { neighborsOf(jcuken) }

    private fun neighborsOf(rows: List<String>): Map<Char, Set<Char>> {
        val pos = HashMap<Char, Pair<Int, Float>>()
        rows.forEachIndexed { r, row ->
            val indent = (rows[0].length - row.length) / 2f
            row.forEachIndexed { i, c -> pos[c] = r to (indent + i) }
        }
        return pos.keys.associateWith { a ->
            val (ra, ca) = pos.getValue(a)
            pos.entries.filter { (b, p) ->
                b != a && kotlin.math.abs(p.first - ra) <= 1 && kotlin.math.abs(p.second - ca) <= 1.5f
            }.map { it.key }.toSet()
        }
    }

    /** What finishWord does: corrections ranked, then the policy pick with a real rank function. */
    private fun autocorrect(typed: String, words: List<String>, set: Set<String>, neighbors: Map<Char, Set<Char>>,
                            learned: Map<String, Int> = emptyMap()): String? {
        val cands = SuggestionEngine.correctionsFrom(typed, words, set, learned, 12, maxEdits = 2)
        return SuggestionEngine.pickAutocorrect(typed, cands, neighbors) { c ->
            if (learned.containsKey(c)) 0 else SuggestionEngine.rankIn(c, words)
        }
    }

    // ---- the dictionaries themselves ---------------------------------------------------------

    @Test fun misspellingsAreNoLongerWords() {
        for (w in listOf("teh", "thier", "seperate", "definately", "alot", "dont", "thats"))
            assertFalse("\"$w\" must not be in en.txt — a listed word can never be corrected", w in enSet)
        for (w in listOf("здраствуйте", "пожалуста", "вобще", "щрн"))
            assertFalse("\"$w\" must not be in ru.txt", w in ruSet)
    }

    @Test fun everydayAbbreviationsAreKnown() {
        for (w in listOf("idk", "brb", "thx", "lol", "btw")) assertTrue("\"$w\" should be known", w in enSet)
        for (w in listOf("спс", "пж", "хз", "имхо", "ок")) assertTrue("\"$w\" should be known", w in ruSet)
    }

    @Test fun russianListHasNoYoDuplicates() {
        val seen = HashSet<String>()
        val dupes = ru.filter { !seen.add(SuggestionEngine.fold(it)) }
        assertTrue("ё/е twins still present: ${dupes.take(5)}", dupes.isEmpty())
    }

    // ---- what now gets corrected, in English, on the real list ---------------------------------

    @Test fun classicEnglishTyposGetFixed() {
        assertEquals("the", autocorrect("teh", en, enSet, enNeighbors))
        assertEquals("receive", autocorrect("recieve", en, enSet, enNeighbors))
        assertEquals("separate", autocorrect("seperate", en, enSet, enNeighbors))
        assertEquals("don't", autocorrect("dont", en, enSet, enNeighbors))
        assertEquals("definitely", autocorrect("definately", en, enSet, enNeighbors))
    }

    // ---- what must be left alone ------------------------------------------------------------

    @Test fun shortUnknownWordsAreNotCorrectedIntoRareWords() {
        // "rita" is rank ~12700, "sigma" ~15400: one adjacent-key edit away, and nothing a person
        // typing a city or a product name has ever meant.
        assertNull("riga must stay riga", autocorrect("riga", en, enSet, enNeighbors))
        assertNull("figma must stay figma", autocorrect("figma", en, enSet, enNeighbors))
        assertNull("рига must stay рига", autocorrect("рига", ru, ruSet, ruNeighbors))
    }

    @Test fun longWordsStillGetRareCorrections() {
        // The frequency floor is for SHORT words only: a single edit from a 9-letter dictionary
        // word is evidence enough, however rare the word — this is the case the user reported.
        val got = autocorrect("подсказкт", ru, ruSet, ruNeighbors)
        assertEquals("подсказки", got)
    }

    @Test fun punctuationIsNeverJustStripped() {
        val p = SuggestionEngine.pickAutocorrect("wi-fi", listOf("wifi"), enNeighbors)
        assertNull("a correction that only removes the hyphen is not a correction", p)
        assertNull(SuggestionEngine.pickAutocorrect("co-op", listOf("coop", "cop"), enNeighbors))
        assertNull(SuggestionEngine.pickAutocorrect("rock'n'roll", listOf("rocknroll"), enNeighbors))
    }

    @Test fun learnedWordProtectsItsYoTwin() {
        // "артём" learned; "артем" typed. The learned form is skipped as a candidate (it IS the
        // word), so before the fold check the closest OTHER word won: "прием".
        val learned = mapOf("артём" to 3)
        assertTrue(SuggestionEngine.correctionsFrom("артем", ru, ruSet, learned, 12, maxEdits = 2).isEmpty())
        assertTrue(SuggestionEngine.correctionsFrom("семенов", listOf("семенова"), setOf("семенова"),
            mapOf("семёнов" to 3), 12, maxEdits = 2).isEmpty())
    }

    // ---- context ----------------------------------------------------------------------------

    @Test fun aPairTypedTwiceIsNotSentenceContext() {
        // Bundled top-2 followers qualify; a personal pair needs STRONG_LEARNED_MIN sightings.
        val strong = SuggestionEngine.strongFollowersFrom(
            bundled = listOf("не", "думаю", "бы", "считаю"),
            learned = mapOf("сто" to 2, "люблю" to SuggestionEngine.STRONG_LEARNED_MIN)
        )
        assertEquals(setOf("не", "думаю", "люблю"), strong)
        assertFalse("two sightings must not override a valid word", "сто" in strong)
    }

    @Test fun preferMatchesAcrossYoSpelling() {
        // The bigram model spells followers the corpus way ("еще"); the cleaned list keeps one
        // spelling per word. Context must still find it.
        val ranked = SuggestionEngine.rankCompletions(listOf("ежедневно", "ещё"), "и", setOf("еще"))
        assertEquals("ещё", ranked.first())
    }

    @Test fun pluralNudgeOnlyAfterPluralWords() {
        val comps = listOf("стоять", "стоял", "стояли")
        assertEquals("a singular neuter noun is not a plural determiner",
            "стоять", SuggestionEngine.rankCompletions(comps, "здание", emptySet()).first())
        assertEquals("стояли", SuggestionEngine.rankCompletions(comps, "какие", emptySet()).first())
        assertEquals("стояли", SuggestionEngine.rankCompletions(comps, "новые", emptySet()).first())
    }

    // ---- completion ---------------------------------------------------------------------------

    @Test fun completionsDedupeAcrossYo() {
        val out = SuggestionEngine.completeFrom("ещ", listOf("еще", "ещё", "ещевский"), mapOf("ещё" to 5), 3)
        assertEquals(1, out.count { SuggestionEngine.fold(it) == "еще" })
    }

    @Test fun realRussianCompletionShowsOneYo() {
        val out = SuggestionEngine.completeFrom("ещ", ru, emptyMap(), 3)
        assertEquals("ещ → $out", 1, out.count { SuggestionEngine.fold(it) == "еще" })
    }
}
