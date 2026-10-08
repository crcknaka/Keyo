package com.keyo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideDecoderTest {

    // A QWERTY grid of 100x140 keys, rows staggered the way the real layout is.
    private val bounds: Map<Char, Rect> = buildMap {
        EN_ROWS.forEachIndexed { row, keys ->
            val left = EN_SIDES[row] * 100f
            keys.forEachIndexed { i, c -> put(c, Rect(left + i * 100f, row * 140f, left + (i + 1) * 100f, (row + 1) * 140f)) }
        }
    }

    /** A finger sliding in straight lines through the centre of each letter of [word]. */
    private fun swipe(word: String): List<Offset> {
        val out = ArrayList<Offset>()
        val keys = word.map { bounds.getValue(it).center }
        for (i in 1 until keys.size) for (t in 0 until 10) {
            val f = t / 10f
            out.add(Offset(keys[i - 1].x + f * (keys[i].x - keys[i - 1].x), keys[i - 1].y + f * (keys[i].y - keys[i - 1].y)))
        }
        out.add(keys.last())
        return out
    }

    private val words = listOf("the", "to", "that", "hello", "help", "held", "world", "word", "would", "tie", "toe")

    @Test fun decodes_theWordWhoseKeysWereCrossed() {
        for (w in listOf("the", "that", "help", "world", "would"))
            assertEquals(w, decodeGlideOver(swipe(w), null, bounds, words).first())
    }

    @Test fun doubledLetter_isSwipedOnce() {
        assertEquals("hello", decodeGlideOver(swipe("helo"), null, bounds, words).first())
    }

    @Test fun longSwipe_doesNotLoseToAShortWordWithTheSameEnds() {
        assertEquals("that", decodeGlideOver(swipe("that"), null, bounds, listOf("tt", "that")).first())
    }

    @Test fun context_breaksANearTie() {
        // "tie" and "toe" differ by one neighbouring key; a path between them follows the context.
        val mid = swipe("tie").zip(swipe("toe")) { a, b -> Offset((a.x + b.x) / 2, (a.y + b.y) / 2) }
        assertEquals("toe", decodeGlideOver(mid, mapOf("toe" to 5), bounds, words).first())
        assertEquals("tie", decodeGlideOver(mid, mapOf("tie" to 5), bounds, words).first())
    }

    @Test fun noPath_noGuess() {
        assertTrue(decodeGlideOver(emptyList(), null, bounds, words).isEmpty())
        assertTrue(decodeGlideOver(swipe("the"), null, emptyMap(), words).isEmpty())
    }

    @Test fun resample_keepsEndsAndCount() {
        val r = resample(listOf(Offset(0f, 0f), Offset(100f, 0f)), 5)
        assertEquals(5, r.size)
        assertEquals(Offset(0f, 0f), r.first())
        assertEquals(100f, r.last().x, 0.01f)
        assertEquals(50f, r[2].x, 0.01f)
    }
}
