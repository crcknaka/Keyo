package com.keyo

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Enter key decision, against the EditorInfo values real apps actually send.
 *
 * These are plain int constants, so they inline at compile time and need no Android runtime — which
 * is the whole reason the rule was pulled out of the service: it is the kind of logic that is easy
 * to get subtly wrong in a way only one app reveals, and impossible to check by looking at it.
 */
class EnterBehaviorTest {

    private fun opts(action: Int, vararg flags: Int) = flags.fold(action) { acc, f -> acc or f }

    // --- The report: Play Store search showed Enter and inserted a newline instead of searching ---

    @Test fun composeSearchField_searches_evenThoughItIsMultiLine() {
        // Compose sets IME_FLAG_NO_ENTER_ACTION on a multi-line field ONLY when the action is left
        // at its default. An explicit ImeAction.Search therefore arrives multi-line and WITHOUT the
        // flag — the field is asking for Search on purpose.
        val imeOptions = opts(EditorInfo.IME_ACTION_SEARCH)
        assertTrue("a field asking for Search must search", EnterBehavior.firesAction(imeOptions, symbolMode = false))
        assertEquals("search", EnterBehavior.labelKind(imeOptions, symbolMode = false))
    }

    // --- and the cases that must keep giving a newline, which is what the old rule was protecting ---

    @Test fun multiLineTextView_stillBreaksTheLine() {
        // TextView sets this flag itself on multi-line views, which is why dropping the input-type
        // check costs nothing here: the field states its wish the documented way.
        val imeOptions = opts(EditorInfo.IME_ACTION_DONE, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertFalse(EnterBehavior.firesAction(imeOptions, symbolMode = false))
        assertEquals("return", EnterBehavior.labelKind(imeOptions, symbolMode = false))
    }

    @Test fun composeMultiLineWithDefaultAction_stillBreaksTheLine() {
        val imeOptions = opts(EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertFalse(EnterBehavior.firesAction(imeOptions, symbolMode = false))
    }

    @Test fun messengerThatOptsOut_stillBreaksTheLine() {
        // A chat composer that considers Send too consequential for the Enter key.
        val imeOptions = opts(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertFalse(EnterBehavior.firesAction(imeOptions, symbolMode = false))
    }

    @Test fun fieldWithNoOpinion_breaksTheLine() {
        assertFalse(EnterBehavior.firesAction(EditorInfo.IME_ACTION_UNSPECIFIED, symbolMode = false))
        assertFalse(EnterBehavior.firesAction(EditorInfo.IME_ACTION_NONE, symbolMode = false))
        assertEquals("return", EnterBehavior.labelKind(EditorInfo.IME_ACTION_UNSPECIFIED, symbolMode = false))
    }

    // --- the ordinary actions ---

    @Test fun eachActionGetsItsOwnKey() {
        assertEquals("search", EnterBehavior.labelKind(EditorInfo.IME_ACTION_SEARCH, false))
        assertEquals("send", EnterBehavior.labelKind(EditorInfo.IME_ACTION_SEND, false))
        assertEquals("send", EnterBehavior.labelKind(EditorInfo.IME_ACTION_GO, false))
        assertEquals("next", EnterBehavior.labelKind(EditorInfo.IME_ACTION_NEXT, false))
        // Done fires, but wears the plain arrow: it usually just closes the keyboard.
        assertEquals("return", EnterBehavior.labelKind(EditorInfo.IME_ACTION_DONE, false))
        assertTrue(EnterBehavior.firesAction(EditorInfo.IME_ACTION_DONE, false))
    }

    @Test fun symbolPagesAlwaysTypeANewline() {
        for (a in listOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO)) {
            assertFalse("symbol pages compose text, they do not submit it",
                EnterBehavior.firesAction(a, symbolMode = true))
            assertEquals("return", EnterBehavior.labelKind(a, symbolMode = true))
        }
    }

    /** The label and the behaviour come from one function, so they cannot contradict each other —
     *  the "shows Search, inserts a newline" class of bug is structurally impossible now. */
    @Test fun labelNeverContradictsBehaviour() {
        val actions = listOf(
            EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS
        )
        for (a in actions) for (flag in listOf(0, EditorInfo.IME_FLAG_NO_ENTER_ACTION)) for (sym in listOf(false, true)) {
            val o = a or flag
            if (EnterBehavior.labelKind(o, sym) != "return")
                assertTrue("action glyph on a key that types a newline", EnterBehavior.firesAction(o, sym))
        }
    }
}
