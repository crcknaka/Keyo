package com.keyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure suggestion algorithms (no Android dependency). */
class SuggestionEngineTest {

    // --- editDistanceAtMost ---

    @Test fun editDistance_identical_isZero() =
        assertEquals(0, SuggestionEngine.editDistanceAtMost("keyboard", "keyboard", 2))

    @Test fun editDistance_oneSubstitution_isOne() =
        assertEquals(1, SuggestionEngine.editDistanceAtMost("abc", "abd", 2))

    @Test fun editDistance_oneInsertion_isOne() =
        assertEquals(1, SuggestionEngine.editDistanceAtMost("helo", "hello", 2))

    @Test fun editDistance_overCap_returnsMaxPlusOne() =
        assertTrue(SuggestionEngine.editDistanceAtMost("abc", "xyz", 1) > 1)

    // --- completeFrom ---

    private val vocab = listOf("there", "they", "them", "then", "the", "world", "would", "work")

    @Test fun complete_returnsFrequencyOrderedMatches() {
        val out = SuggestionEngine.completeFrom("th", vocab, emptyMap(), 3)
        assertEquals(listOf("there", "they", "them"), out)
    }

    @Test fun complete_learnedWordsComeFirst() {
        val out = SuggestionEngine.completeFrom("the", vocab, mapOf("themed" to 10), 3)
        assertEquals("themed", out.first())
    }

    @Test fun complete_excludesExactPrefix_andEmptyPrefix() {
        // "the" is in the vocab but completions must be *longer* than the prefix.
        assertTrue(SuggestionEngine.completeFrom("the", vocab, emptyMap(), 5).none { it == "the" })
        assertTrue(SuggestionEngine.completeFrom("", vocab, emptyMap(), 3).isEmpty())
    }

    // --- correctFrom ---

    private val words = listOf("hello", "help", "world", "keyboard")
    private val wordSet = words.toSet()

    @Test fun correct_fixesSingleTypo() {
        assertEquals("hello", SuggestionEngine.correctFrom("helo", words, wordSet, emptyMap()))
    }

    @Test fun correct_returnsNullForKnownWord() {
        assertNull(SuggestionEngine.correctFrom("world", words, wordSet, emptyMap()))
    }

    @Test fun correct_returnsNullForShortWord() {
        assertNull(SuggestionEngine.correctFrom("wo", words, wordSet, emptyMap()))
    }

    @Test fun correct_returnsNullWhenNothingClose() {
        assertNull(SuggestionEngine.correctFrom("zzzzz", words, wordSet, emptyMap()))
    }

    @Test fun correct_skipsWordsAlreadyLearned() {
        assertNull(SuggestionEngine.correctFrom("teh", words, wordSet, mapOf("teh" to 3)))
    }

    @Test fun correct_fixesTypoOfLearnedWordOutsideBundledDict() {
        // "awesome" lives only in the user's learned vocab; a typo of it must still correct.
        assertEquals("awesome", SuggestionEngine.correctFrom("awesom", words, wordSet, mapOf("awesome" to 4)))
    }

    // --- correctionsFrom (multiple typo candidates for the suggestion strip) ---

    @Test fun corrections_returnsMultipleByDistanceThenFrequency() {
        // both "hello" (insertion) and "help" (substitution) are one edit from "helo"
        assertEquals(listOf("hello", "help"), SuggestionEngine.correctionsFrom("helo", words, wordSet, emptyMap(), 2))
    }

    @Test fun corrections_prefersLearnedAtEqualDistance() {
        // a learned word one edit away outranks an equally-close bundled word
        val out = SuggestionEngine.correctionsFrom("helo", words, wordSet, mapOf("helm" to 5), 3)
        assertEquals("helm", out.first())
    }

    @Test fun corrections_emptyForKnownWord() {
        assertTrue(SuggestionEngine.correctionsFrom("world", words, wordSet, emptyMap(), 3).isEmpty())
    }

    // --- nextFrom (bigram prediction) ---

    private val bigrams = mapOf("good" to mapOf("morning" to 5, "night" to 3, "luck" to 1))

    @Test fun next_returnsTopFollowersByCount() {
        assertEquals(listOf("morning", "night"), SuggestionEngine.nextFrom("good", bigrams, 2))
    }

    @Test fun next_returnsEmptyForUnknownPrev() {
        assertTrue(SuggestionEngine.nextFrom("bad", bigrams, 3).isEmpty())
    }

    // --- mergeRanked (bilingual Latin keyboard: English + Latvian word lists) ---

    @Test fun merge_interleavesByRankAndDedups() {
        val en = listOf("the", "and", "test")
        val lv = listOf("un", "test", "ir")
        // round-robin by rank: the, un, and, test(en), [test(lv) deduped], ir
        assertEquals(listOf("the", "un", "and", "test", "ir"), SuggestionEngine.mergeRanked(listOf(en, lv)))
    }

    @Test fun merge_singleOrEmptyListsPassThrough() {
        val en = listOf("hello", "world")
        assertEquals(en, SuggestionEngine.mergeRanked(listOf(en, emptyList())))
        assertTrue(SuggestionEngine.mergeRanked(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test fun merge_keepsBothLanguagesReachable() {
        // A frequent English word and a frequent Latvian word both survive the merge.
        val merged = SuggestionEngine.mergeRanked(listOf(listOf("hello"), listOf("paldies")))
        assertTrue(merged.contains("hello") && merged.contains("paldies"))
    }

    // --- ё / е equivalence (Russian) ---

    private val ru = listOf("ещё", "её", "всё", "серьёзно", "это")

    @Test fun fold_replacesYo() {
        assertEquals("еще", SuggestionEngine.fold("ещё"))
        assertEquals("hello", SuggestionEngine.fold("hello")) // no-op for non-ё text
    }

    @Test fun complete_matchesAcrossYoYe() {
        // Typing with plain е must still surface the dictionary's ё-spelled word.
        assertTrue(SuggestionEngine.completeFrom("сер", ru, emptyMap(), 3).contains("серьёзно"))
        assertTrue(SuggestionEngine.completeFrom("ещ", ru, emptyMap(), 3).contains("ещё"))
    }

    @Test fun correct_treatsYoYeAsSameLetter() {
        // "серьезно" (typed with е) should be treated as already correct -> no correction.
        val foldedSet = ru.map { SuggestionEngine.fold(it) }.toSet()
        assertNull(SuggestionEngine.correctFrom("серьезно", ru, foldedSet, emptyMap()))
    }
}
