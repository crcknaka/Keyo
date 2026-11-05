package com.keyo

import org.junit.Assert.assertEquals
import org.junit.Test

class StepLanguageTest {
    private val cycle = listOf("en", "lv", "ru")

    @Test fun rightGoesToTheNextLanguage() = assertEquals("lv", KeyboardPrefs.stepLanguage(cycle, "en", 1))

    @Test fun leftGoesToThePreviousLanguage() = assertEquals("lv", KeyboardPrefs.stepLanguage(cycle, "ru", -1))

    @Test fun bothEndsWrapAround() {
        assertEquals("en", KeyboardPrefs.stepLanguage(cycle, "ru", 1))
        assertEquals("ru", KeyboardPrefs.stepLanguage(cycle, "en", -1))
    }

    @Test fun aLanguageNoLongerEnabledFallsBackToTheFirst() =
        assertEquals("en", KeyboardPrefs.stepLanguage(cycle, "de", 1))
}
