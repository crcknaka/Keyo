package com.keyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic unit tests for [KeyboardPrefs]. These cover the parts that do not touch a
 * [android.content.Context] / SharedPreferences, so they run on the plain JVM (no Robolectric).
 */
class KeyboardPrefsTest {

    // --- fontSizeSp: glyph size derived from the visible key height ---

    @Test
    fun fontSizeSp_defaultHeight_isWithinBounds() {
        // 48dp height, 2dp vGap -> visible 44 -> 22sp
        assertEquals(22, KeyboardPrefs.fontSizeSp(48, 2))
    }

    @Test
    fun fontSizeSp_clampsToMaximum() {
        // A very tall key must not produce an oversized font.
        assertEquals(24, KeyboardPrefs.fontSizeSp(KeyboardPrefs.KEY_HEIGHT_RANGE.last, 0))
    }

    @Test
    fun fontSizeSp_clampsToMinimum() {
        // A tiny key (or large gaps) must not drop below the readable minimum.
        assertEquals(13, KeyboardPrefs.fontSizeSp(KeyboardPrefs.KEY_HEIGHT_RANGE.first, 10))
    }

    // --- Haptics mapping ---

    @Test
    fun hapticDuration_off_isZero() {
        assertEquals(0L, KeyboardPrefs.hapticDurationMs("off"))
        assertEquals(0L, KeyboardPrefs.hapticDurationMs("unknown-level"))
    }

    @Test
    fun hapticDuration_increasesWithStrength() {
        val light = KeyboardPrefs.hapticDurationMs("light")
        val medium = KeyboardPrefs.hapticDurationMs("medium")
        val strong = KeyboardPrefs.hapticDurationMs("strong")
        assertTrue(light in 1..medium)
        assertTrue(medium < strong)
    }

    @Test
    fun hapticAmplitude_isValidRangeForActiveLevels() {
        for ((level, _) in KeyboardPrefs.HAPTIC_LEVELS.filter { it.first != "off" }) {
            val amp = KeyboardPrefs.hapticAmplitude(level)
            assertTrue("amplitude for '$level' must be in 1..255", amp in 1..255)
        }
        assertEquals(0, KeyboardPrefs.hapticAmplitude("off"))
    }

    // --- Theme catalogue integrity ---

    @Test
    fun themes_haveUniqueIds() {
        val ids = KeyboardPrefs.THEMES.map { it.id }
        assertEquals("theme ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun themes_includeDefault() {
        assertTrue(KeyboardPrefs.THEMES.any { it.id == "catppuccin" })
    }

    // --- Model / language catalogues ---

    @Test
    fun availableModels_containDefaults() {
        val ids = KeyboardPrefs.AVAILABLE_MODELS.map { it.first }
        assertTrue(ids.contains("openai/gpt-oss-20b"))
        assertTrue(ids.contains("openai/gpt-oss-120b"))
    }

    @Test
    fun supportedLanguages_areEnRuLv() {
        val codes = KeyboardPrefs.SUPPORTED_LANGUAGES.map { it.first }
        assertEquals(listOf("en", "ru", "lv"), codes)
    }
}
