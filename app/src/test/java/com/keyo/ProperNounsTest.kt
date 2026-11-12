package com.keyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The proper-noun lists (assets/dict/caps_<lang>.txt, built by tools/gen_caps.py) against the
 * dictionaries they belong to, and the rule that decides the case a word is shown in.
 */
class ProperNounsTest {

    private fun dict(lang: String) =
        File("src/main/assets/dict/$lang.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
            .mapTo(HashSet()) { SuggestionEngine.fold(it) }

    private fun caps(lang: String): Map<String, String> =
        File("src/main/assets/dict/caps_$lang.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
            .associateBy { SuggestionEngine.fold(it.lowercase()) }

    @Test fun everyListedName_isADictionaryWord_andActuallyCapitalized() {
        for (lang in listOf("en", "ru", "lv")) {
            val d = dict(lang)
            val c = caps(lang)
            assertTrue("$lang list is suspiciously small: ${c.size}", c.size > 1000)
            for ((lower, cased) in c) {
                assertTrue("$lang: '$cased' is not in the dictionary", lower in d)
                assertTrue("$lang: '$cased' has no capital", cased != cased.lowercase())
            }
        }
    }

    @Test fun namesPeopleType_areWrittenProperly() {
        val ru = caps("ru"); val en = caps("en"); val lv = caps("lv")
        for ((w, want) in listOf("москва" to "Москва", "рига" to "Рига", "риге" to "Риге", "латвия" to "Латвия",
                                 "илья" to "Илья", "сша" to "США", "юрмала" to "Юрмала"))
            assertEquals(want, ru[w])
        for ((w, want) in listOf("monday" to "Monday", "london" to "London", "riga" to "Riga",
                                 "english" to "English", "iphone" to "iPhone", "google" to "Google"))
            assertEquals(want, en[w])
        for ((w, want) in listOf("rīga" to "Rīga", "latvija" to "Latvija", "sia" to "SIA", "jānis" to "Jānis"))
            assertEquals(want, lv[w])
    }

    @Test fun ordinaryWordsThatAreAlsoNames_stayLowercase() {
        // The words where a capital would be wrong more often than right.
        val ru = caps("ru"); val en = caps("en")
        for (w in listOf("вера", "роман", "мир", "надежда", "любовь", "лев", "привет", "что", "эм", "ай"))
            assertFalse("ru: '$w' must not be force-capitalized", w in ru)
        for (w in listOf("may", "march", "will", "mark", "bill", "rose", "apple", "us", "it", "the",
                         "united", "advisors", "eagles"))
            assertFalse("en: '$w' must not be force-capitalized", w in en)
    }

    @Test fun casedLike_followsShoutingThenTheNameThenTheTypedCapital() {
        assertEquals("Рига", SuggestionEngine.casedLike("риг", "рига", "Рига"))
        assertEquals("Рига", SuggestionEngine.casedLike("Риг", "рига", "Рига"))
        assertEquals("РИГА", SuggestionEngine.casedLike("РИГ", "рига", "Рига"))
        assertEquals("iPhone", SuggestionEngine.casedLike("Ip", "iphone", "iPhone"))
        assertEquals("привет", SuggestionEngine.casedLike("прив", "привет", null))
        assertEquals("Привет", SuggestionEngine.casedLike("Прив", "привет", null))
        assertEquals("ПРИВЕТ", SuggestionEngine.casedLike("ПРИВ", "привет", null))
        // one capital letter is a sentence start, not shouting
        assertEquals("Привет", SuggestionEngine.casedLike("П", "привет", null))
    }
}
