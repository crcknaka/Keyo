package com.keyo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserDictionary] is a singleton with no Context dependency for anything except load/save, so the
 * in-memory behaviour (validation, caps, merge rules, import of untrusted files) is testable
 * directly. Each test starts from a clean store via [reset].
 */
class UserDictionaryTest {

    /** Empty the singleton: every learned word AND every word appearing in a bigram (removeWord
     *  drops a word's own followers and any pair pointing at it). Built into a list first so the
     *  removals don't mutate what we're iterating. */
    private fun reset() {
        val all = (UserDictionary.wordsByFrequency() +
            UserDictionary.bigrams().keys.toList() +
            UserDictionary.bigrams().values.flatMap { it.keys.toList() }).distinct()
        all.forEach { UserDictionary.removeWord(it) }
    }

    @After fun tearDown() = reset()

    // --- addWord validation ---

    @Test fun addWord_acceptsOrdinaryWords() {
        reset()
        assertTrue(UserDictionary.addWord("keyo"))
        assertTrue(UserDictionary.addWord("don't"))
        assertTrue(UserDictionary.addWord("well-known"))
        assertTrue(UserDictionary.addWord("привет"))
        assertTrue(UserDictionary.unigrams().containsKey("keyo"))
    }

    @Test fun addWord_rejectsJunk() {
        reset()
        assertFalse(UserDictionary.addWord("a"))                 // too short
        assertFalse(UserDictionary.addWord("x".repeat(33)))      // too long
        assertFalse(UserDictionary.addWord("two words"))         // space
        assertFalse(UserDictionary.addWord("line\nbreak"))       // control char
        assertFalse(UserDictionary.addWord("--"))                // no letter at all
        assertFalse(UserDictionary.addWord("123"))
    }

    @Test fun addWord_normalizesCaseAndWhitespace() {
        reset()
        assertTrue(UserDictionary.addWord("  Keyo  "))
        assertTrue(UserDictionary.unigrams().containsKey("keyo"))
    }

    // --- learn ---

    @Test fun learn_recordsBigramWithoutAddingTheWord() {
        reset()
        UserDictionary.learn("good", "morning", bumpUnigram = false)
        assertFalse(UserDictionary.unigrams().containsKey("morning"))
        assertEquals(1, UserDictionary.bigrams()["good"]?.get("morning"))
    }

    @Test fun learn_bumpUnigramAddsTheWord() {
        reset()
        UserDictionary.learn(null, "zebra", bumpUnigram = true)
        UserDictionary.learn(null, "zebra", bumpUnigram = true)
        assertEquals(2, UserDictionary.unigrams()["zebra"])
    }

    @Test fun learn_atCapKeepsTheWordJustLearned() {
        reset()
        // Fill past the cap with once-seen words, then learn one more: the fresh word must survive
        // the prune (every entry has the same count, so an unqualified prune could evict it).
        for (i in 0 until 4100) UserDictionary.learn(null, "wordfiller$i", bumpUnigram = true)
        UserDictionary.learn(null, "survivor", bumpUnigram = true)
        assertTrue(UserDictionary.unigrams().containsKey("survivor"))
        assertTrue(UserDictionary.count() <= 4000)
    }

    // --- export / import round-trip ---

    @Test fun exportImport_roundTripsWordsAndPairs() {
        reset()
        UserDictionary.addWord("kotlin")
        UserDictionary.learn("hello", "world", bumpUnigram = false)
        val json = UserDictionary.exportJson()

        reset()
        assertEquals(0, UserDictionary.count())
        val added = UserDictionary.importJson(json)
        assertTrue(added >= 1)
        assertTrue(UserDictionary.unigrams().containsKey("kotlin"))
        assertEquals(1, UserDictionary.bigrams()["hello"]?.get("world"))
    }

    @Test fun import_mergesInsteadOfReplacing() {
        reset()
        UserDictionary.addWord("alpha")
        val json = UserDictionary.exportJson()
        reset()
        UserDictionary.addWord("beta")
        UserDictionary.importJson(json)
        assertTrue(UserDictionary.unigrams().containsKey("alpha"))
        assertTrue(UserDictionary.unigrams().containsKey("beta"))
    }

    // --- import of hostile / malformed input ---

    @Test fun import_rejectsNonKeyoFiles() {
        reset()
        assertEquals(-1, UserDictionary.importJson(""))
        assertEquals(-1, UserDictionary.importJson("not json at all"))
        assertEquals(-1, UserDictionary.importJson("""{"u":{"word":1}}"""))       // no marker
        assertEquals(-1, UserDictionary.importJson("""{"keyo_dictionary":1}"""))  // no words
    }

    @Test fun import_rejectsOversizedPayload() {
        reset()
        val huge = """{"keyo_dictionary":1,"u":{"a":1},"pad":"${"x".repeat(4_000_001)}"}"""
        assertEquals(-1, UserDictionary.importJson(huge))
    }

    @Test fun import_skipsWordsThatArentWords() {
        reset()
        val json = """{"keyo_dictionary":1,"u":{
            "valid":3,
            "two words":5,
            "with\nnewline":5,
            "${"x".repeat(40)}":5,
            "12345":5,
            "negative":-1
        }}"""
        UserDictionary.importJson(json)
        val u = UserDictionary.unigrams()
        assertTrue(u.containsKey("valid"))
        assertEquals(1, u.size)
    }

    @Test fun import_skipsJunkBigramKeysAndFollowers() {
        reset()
        val json = """{"keyo_dictionary":1,"u":{"ok":1},"b":{
            "good":{"morning":2,"a giant follower with spaces":9},
            "bad key":{"morning":2}
        }}"""
        UserDictionary.importJson(json)
        assertEquals(2, UserDictionary.bigrams()["good"]?.get("morning"))
        assertEquals(null, UserDictionary.bigrams()["good"]?.get("a giant follower with spaces"))
        assertEquals(null, UserDictionary.bigrams()["bad key"])
    }

    @Test fun import_clampsCountsInsteadOfOverflowing() {
        reset()
        val json = """{"keyo_dictionary":1,"u":{"ok":1},"b":{"good":{"morning":2147483647}}}"""
        UserDictionary.importJson(json)
        UserDictionary.importJson(json)   // adding twice would wrap a raw Int negative
        val c = UserDictionary.bigrams()["good"]?.get("morning") ?: 0
        assertTrue("count must stay positive, was $c", c > 0)
    }

    @Test fun import_stopsAtTheUnigramCap() {
        reset()
        val sb = StringBuilder("""{"keyo_dictionary":1,"u":{""")
        for (i in 0 until 6000) {
            if (i > 0) sb.append(',')
            sb.append("\"importword$i\":1")
        }
        sb.append("}}")
        UserDictionary.importJson(sb.toString())
        assertTrue("cap must hold, was ${UserDictionary.count()}", UserDictionary.count() <= 4000)
    }

    // --- removeWord ---

    @Test fun removeWord_alsoDropsBigramsReferencingIt() {
        reset()
        UserDictionary.addWord("target")
        UserDictionary.learn("target", "follower", bumpUnigram = false)
        UserDictionary.learn("other", "target", bumpUnigram = false)
        UserDictionary.removeWord("target")
        assertFalse(UserDictionary.unigrams().containsKey("target"))
        assertEquals(null, UserDictionary.bigrams()["target"])
        assertEquals(null, UserDictionary.bigrams()["other"]?.get("target"))
    }
}
