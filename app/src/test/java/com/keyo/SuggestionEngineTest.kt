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

    // --- nextFrom (bigram prediction) ---

    private val bigrams = mapOf("good" to mapOf("morning" to 5, "night" to 3, "luck" to 1))

    @Test fun next_returnsTopFollowersByCount() {
        assertEquals(listOf("morning", "night"), SuggestionEngine.nextFrom("good", bigrams, 2))
    }

    @Test fun next_returnsEmptyForUnknownPrev() {
        assertTrue(SuggestionEngine.nextFrom("bad", bigrams, 3).isEmpty())
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
