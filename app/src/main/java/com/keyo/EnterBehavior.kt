package com.keyo

import android.view.inputmethod.EditorInfo

/**
 * What the Enter key means in the field that currently has focus.
 *
 * The rule lived in three copies — the full keyboard's label, the mini row's label, and the press
 * handler — and they had already drifted apart from each other. A disagreement between them is
 * exactly the bug users report as "it shows Search but inserts a newline", so there is now one
 * function and it is unit-tested against real EditorInfo bit patterns.
 *
 * ## Why the multi-line flag is deliberately NOT consulted
 *
 * It used to be: a multi-line input type forced a newline whatever action the field asked for. That
 * looks reasonable and is wrong, because the platform already has a flag for saying it —
 * IME_FLAG_NO_ENTER_ACTION, whose own documentation ends with "TextView will automatically set this
 * flag for you on multi-line text views". So a genuinely multi-line field states its wish through
 * the flag, and reading the input type on top of that is redundant where the two agree and harmful
 * where they differ: a field that is multi-line and still asks for Search is asking on purpose.
 *
 * That is not hypothetical — it is the Play Store search box. Compose only sets NO_ENTER_ACTION on a
 * multi-line field when the action is left at its default, so an explicit ImeAction.Search arrives
 * as multi-line WITHOUT the flag. Reading the input type turned that field's Search key into a
 * newline, which is precisely the "it just moves to the second line instead of searching" report.
 */
internal object EnterBehavior {

    /** The action Enter should perform, or [EditorInfo.IME_ACTION_NONE] when it should type a
     *  newline instead. [symbolMode] is true on the 123 / symbols / numpad pages, where Enter is
     *  always a plain newline — those pages are for composing text, not submitting it. */
    fun actionOf(imeOptions: Int, symbolMode: Boolean): Int {
        if (symbolMode) return EditorInfo.IME_ACTION_NONE
        // The field explicitly opted out of having its action on the Enter key. Set by any app that
        // considers its action too consequential to fire by accident — and by TextView itself for
        // multi-line views.
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        // UNSPECIFIED means the app never chose; it is not an instruction to submit anything.
        return if (action == EditorInfo.IME_ACTION_UNSPECIFIED) EditorInfo.IME_ACTION_NONE else action
    }

    /** True when pressing Enter should fire the field's action rather than break the line. */
    fun firesAction(imeOptions: Int, symbolMode: Boolean): Boolean =
        actionOf(imeOptions, symbolMode) != EditorInfo.IME_ACTION_NONE

    /** Which glyph the Enter key wears. Kept in the same object as the behaviour so the key can
     *  never advertise one thing and do another. */
    fun labelKind(imeOptions: Int, symbolMode: Boolean): String = when (actionOf(imeOptions, symbolMode)) {
        EditorInfo.IME_ACTION_SEARCH -> "search"
        EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO -> "send"
        EditorInfo.IME_ACTION_NEXT -> "next"
        // IME_ACTION_DONE and anything else fall back to the familiar Enter arrow: "Done" usually
        // just closes the keyboard, so a distinct icon would promise more than it delivers.
        else -> "return"
    }
}
