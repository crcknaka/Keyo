package com.keyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- Damerau: adjacent transposition counts as ONE edit (the most common fast-typing typo) ---

    @Test fun editDistance_transposition_isOne() {
        assertEquals(1, SuggestionEngine.editDistanceAtMost("teh", "the", 2))
        assertEquals(1, SuggestionEngine.editDistanceAtMost("пнада", "панда", 2))
        assertEquals(1, SuggestionEngine.editDistanceAtMost("ab", "ba", 2))
    }

    @Test fun editDistance_twoSeparateTranspositions_isTwo() =
        assertEquals(2, SuggestionEngine.editDistanceAtMost("badc", "abcd", 2))

    @Test fun corrections_findTransposedWordEvenForShortWords() {
        // 3-letter words get maxDist=1; "the" is now reachable from "teh" thanks to Damerau.
        val vocabList = listOf("the", "tea")
        val out = SuggestionEngine.correctionsFrom("teh", vocabList, vocabList.toSet(), emptyMap(), 2)
        assertEquals("the", out.first())
    }

    @Test fun pickAutocorrect_appliesTransposition() =
        assertEquals("the", SuggestionEngine.pickAutocorrect("teh", listOf("the"), emptyMap()))

    // --- prefixStrength (probabilistic key targeting: language model side) ---

    @Test fun prefixStrength_zeroForUnknownPrefix() =
        assertEquals(0f, SuggestionEngine.prefixStrengthFrom("zz", listOf("hello", "world"), emptyMap()), 0.0001f)

    @Test fun prefixStrength_higherForMoreFrequentWords() {
        val words = listOf("aaa", "bbb")   // frequency-ordered: "aaa" is the more common word
        val sA = SuggestionEngine.prefixStrengthFrom("aa", words, emptyMap())
        val sB = SuggestionEngine.prefixStrengthFrom("bb", words, emptyMap())
        assertTrue(sA > sB && sB > 0f)
    }

    @Test fun prefixStrength_learnedWordsCountStrongly() =
        assertEquals(0.9f, SuggestionEngine.prefixStrengthFrom("zz", listOf("hello"), mapOf("zzap" to 3)), 0.0001f)

    // --- chooseKey (probabilistic key targeting: decision rule) ---

    @Test fun chooseKey_keepsTappedWhenDeadCentre() {
        // Tap near the centre of 'a': never second-guessed, even if 's' looks better to the LM.
        val out = SuggestionEngine.chooseKey('a', listOf('a' to 0.1f, 's' to 0.9f)) { 1f }
        assertEquals('a', out)
    }

    @Test fun chooseKey_overridesBoundaryTapWhenLanguagePrefersNeighbour() {
        // Boundary tap (0.45 vs 0.55 key-widths): 's' continues the word, 'a' doesn't.
        val out = SuggestionEngine.chooseKey('a', listOf('a' to 0.45f, 's' to 0.55f)) { c ->
            if (c == 's') 1f else 0f
        }
        assertEquals('s', out)
    }

    @Test fun chooseKey_keepsTappedOnBoundaryWhenScoresAreClose() {
        // Equal language strength -> geometry (and the 1.3x margin) keeps what was tapped.
        val out = SuggestionEngine.chooseKey('a', listOf('a' to 0.45f, 's' to 0.55f)) { 0.5f }
        assertEquals('a', out)
    }

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

    @Test fun corrections_contextPreferredWinsTies() {
        val vocabList = listOf("help", "hell")   // "help" ranks higher by frequency (index 0)
        val set = vocabList.toSet()
        // No context: frequency wins -> "help" leads.
        assertEquals("help", SuggestionEngine.correctionsFrom("helo", vocabList, set, emptyMap(), 2).first())
        // Context prefers "hell" (a follower of the previous word) -> it leads despite lower frequency.
        assertEquals("hell", SuggestionEngine.correctionsFrom("helo", vocabList, set, emptyMap(), 2, prefer = setOf("hell")).first())
    }

    // --- spatial autocorrect (adjacent-key fat-finger) ---

    // QWERTY-ish neighbours for the keys used below.
    private val nb = mapOf(
        't' to setOf('r', 'y', 'g'), 'r' to setOf('e', 't', 'f'), 'e' to setOf('w', 'r', 'd'),
        'w' to setOf('q', 'e', 's'), 'y' to setOf('t', 'u', 'h')
    )

    @Test fun adjacentSubs_trueForTwoAdjacentSubstitutions() {
        // "rwst" -> "test": r→t and w→e are both adjacent keys
        assertTrue(SuggestionEngine.allAdjacentSubs("rwst", "test", nb))
    }

    @Test fun adjacentSubs_falseForSameWordOrNonAdjacent() {
        assertFalse(SuggestionEngine.allAdjacentSubs("test", "test", nb))
        assertFalse(SuggestionEngine.allAdjacentSubs("zest", "test", nb))   // z→t not adjacent
        assertFalse(SuggestionEngine.allAdjacentSubs("tests", "test", nb))  // different length
    }

    @Test fun pickAutocorrect_appliesTopSingleEdit() {
        // single insertion is accepted regardless of adjacency (unchanged behaviour)
        assertEquals("hello", SuggestionEngine.pickAutocorrect("helo", listOf("hello"), emptyMap()))
    }

    @Test fun pickAutocorrect_appliesConfidentDoubleFatFinger() {
        assertEquals("test", SuggestionEngine.pickAutocorrect("rwst", listOf("test"), nb))
    }

    @Test fun pickAutocorrect_rejectsNonAdjacentDoubleEdit() {
        // "tezr" -> "test" is two edits but z→s is not an adjacent key -> not auto-applied
        assertNull(SuggestionEngine.pickAutocorrect("tezr", listOf("test"), nb))
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

    // --- foldKey (glide skeleton: diacritics collapse to base keys) ---

    @Test fun foldKey_collapsesAllLatvianDiacritics() {
        // ā č ē ģ ī ķ ļ ņ š ū ž  ->  a c e g i k l n s u z
        assertEquals("acegiklnsuz", SuggestionEngine.foldKey("āčēģīķļņšūž"))
    }

    @Test fun foldKey_realWordsBecomeBaseSkeleton() {
        assertEquals("est", SuggestionEngine.foldKey("ēst"))
        assertEquals("vins", SuggestionEngine.foldKey("viņš"))
        assertEquals("latviesu", SuggestionEngine.foldKey("latviešu"))
    }

    @Test fun foldKey_leavesPlainWordsUntouched_andStillFoldsYo() {
        assertEquals("keyboard", SuggestionEngine.foldKey("keyboard")) // ASCII pass-through
        assertEquals("еще", SuggestionEngine.foldKey("ещё"))           // ё→е from fold() still applies
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
