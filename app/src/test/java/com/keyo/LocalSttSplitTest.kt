package com.keyo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSttSplitTest {
    private val rate = 16000

    private fun loud(seconds: Int) = ShortArray(seconds * rate) { 8000 }

    @Test fun shortRecordingStaysWhole() {
        assertEquals(listOf(0 to 10 * rate), LocalStt.splitForRecognition(loud(10), rate))
    }

    @Test fun emptyRecordingHasNoPieces() {
        assertTrue(LocalStt.splitForRecognition(ShortArray(0), rate).isEmpty())
    }

    @Test fun piecesCoverEverythingWithoutGapsAndStayUnderTheLimit() {
        val samples = loud(95)
        val pieces = LocalStt.splitForRecognition(samples, rate)
        assertEquals(0, pieces.first().first)
        assertEquals(samples.size, pieces.last().second)
        pieces.zipWithNext { a, b -> assertEquals(a.second, b.first) }
        pieces.forEach { assertTrue(it.second - it.first <= 20 * rate) }
    }

    @Test fun cutLandsInThePause() {
        val samples = loud(30)
        val pauseStart = 17 * rate
        for (i in pauseStart until pauseStart + rate / 2) samples[i] = 0
        val cut = LocalStt.splitForRecognition(samples, rate).first().second
        assertTrue("cut at $cut", cut in pauseStart..(pauseStart + rate / 2))
    }

    @Test fun piecesJoinWithoutACapitalInTheMiddleOfASentence() {
        val text = StringBuilder()
        LocalStt.appendPiece(text, "Если что-то пойдёт не так,")
        LocalStt.appendPiece(text, "Запись останется.")
        LocalStt.appendPiece(text, "")
        LocalStt.appendPiece(text, "Это новое предложение.")
        assertEquals("Если что-то пойдёт не так, запись останется. Это новое предложение.", text.toString())
    }
}
