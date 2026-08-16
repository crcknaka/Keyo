package com.keyo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.text.method.PasswordTransformationMethod
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Debug-only playground replicating problem fields from real apps (not shipped in release).
 * Case "wa": WhatsApp's two-step-verification PIN prompt — CodeInputField is an EditText with
 * inputType=number, digits="0123456789", password dots via transformation (NOT numberPassword),
 * imeOptions=NO_EXTRACT_UI|NO_FULLSCREEN, auto-focused in a dialog that force-shows the IME.
 */
class FieldTestActivity : Activity() {

    private fun waPinField(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        keyListener = DigitsKeyListener.getInstance("0123456789")
        transformationMethod = PasswordTransformationMethod.getInstance()
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isSingleLine = true
        textSize = 22f
        minWidth = 400
    }

    /** A messenger-style message box. [multi] mirrors apps whose input grows to several lines;
     *  [action] is the imeOptions action they declare. Between them these cover what Enter has to
     *  decide: fire the action, or insert a line break. The label reports what actually happened,
     *  so a press can be judged without guessing. */
    private fun messageField(label: String, action: Int, multi: Boolean, viewId: Int): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val log = android.widget.TextView(this).apply { text = "$label — waiting"; textSize = 12f }
        var fired = 0
        val field = EditText(this).apply {
            // A STABLE id, so Android saves and restores the text and the focus across a rotation —
            // without one these fields come back empty and unfocused, which hides every bug that
            // only shows up after the screen turns.
            id = viewId
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            imeOptions = action
            textSize = 18f
            setOnEditorActionListener { _, actionId, _ ->
                // Counted, so a SECOND press is distinguishable from the first — and any text edit
                // resets the label, so "the action fired" and "a newline was typed" never look alike.
                fired++
                log.text = "$label — action fired: $actionId (x$fired)"
                true          // consume it, like a messenger that sends on the action
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    log.text = "$label — text changed (${s?.count { it == '\n' } ?: 0} newlines)"
                }
            })
        }
        box.addView(log)
        box.addView(field)
        return box
    }

    /**
     * A stand-in for a remote-desktop client: declares inputType=TYPE_NULL (so it accepts key
     * events, not text) and LOGS every InputConnection call the keyboard makes. Those calls are
     * what a real client replays on the host machine, so anything other than plain characters and
     * unmodified keys showing up here is a phantom keystroke waiting to happen on the remote Mac.
     *
     * LIMIT: it shows what the keyboard SENDS, not what a host RECEIVES. The overrides return early
     * instead of delegating to BaseInputConnection, so the KeyCharacterMap conversion a real
     * TYPE_NULL consumer uses to turn a glyph into keystrokes never runs here — `commit("\n")` and
     * `commit("♥")` both look equally harmless in the log. Use it to catch DELETE/COMPOSING/modifier
     * traffic; the real machine is still the only judge of how a committed glyph is typed.
     */
    private inner class RawKeyView(private val log: android.widget.TextView) :
        android.view.View(this@FieldTestActivity) {

        private val seen = StringBuilder()

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF203040.toInt())
            minimumHeight = (72 * resources.displayMetrics.density).toInt()
        }

        private fun note(s: String) {
            seen.append(s).append(' ')
            if (seen.length > 200) seen.delete(0, seen.length - 200)
            log.text = "RAW (TYPE_NULL, like remote desktop) — $seen"
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection {
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
            return object : android.view.inputmethod.BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    note("commit(\"$text\")"); return true
                }
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    note("COMPOSING(\"$text\")!"); return true
                }
                override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                    note("DELETE($before,$after)!"); return true
                }
                override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean {
                    if (event?.action == android.view.KeyEvent.ACTION_DOWN) {
                        val meta = if (event.metaState != 0) "+meta${event.metaState}" else ""
                        note("key(${android.view.KeyEvent.keyCodeToString(event.keyCode)}$meta)")
                    }
                    return true
                }
            }
        }

        override fun onCheckIsTextEditor() = true

        // A bare View doesn't take focus on touch by itself, so it would never open the keyboard.
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                requestFocus()
                (context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Top padding keeps the first field clear of the status bar, where taps don't reach it.
        val pad = (48 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }

        // Messenger message boxes — the Enter-key decision table.
        root.addView(messageField("SEND, single line", EditorInfo.IME_ACTION_SEND, multi = false, viewId = 1001))
        root.addView(messageField("DONE, single line", EditorInfo.IME_ACTION_DONE, multi = false, viewId = 1002))
        root.addView(messageField("SEND, multi-line", EditorInfo.IME_ACTION_SEND, multi = true, viewId = 1003))
        // NO_ENTER_ACTION as well: with a bare UNSPECIFIED the framework substitutes NEXT (there are
        // focusable views below) or DONE, so the field would silently test the opposite of its name.
        root.addView(messageField("no action declared",
            EditorInfo.IME_ACTION_UNSPECIFIED or EditorInfo.IME_FLAG_NO_ENTER_ACTION, multi = false, viewId = 1004))

        // A search box that RESTARTS THE INPUT on every keystroke, the way a field showing live
        // suggestions does (Play Store's search is one). Anything the keyboard does on a restart
        // runs here on every single character — which is how a fix meant for "once, after the screen
        // rotates" can silently destroy ordinary typing.
        val searchLog = android.widget.TextView(this).apply {
            text = "search — restarts input on every keystroke"; textSize = 12f
        }
        val search = EditText(this).apply {
            id = 1006
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 18f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchLog.text = "search — text now \"$s\""
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this@apply)
                }
            })
        }
        root.addView(searchLog)
        root.addView(search)

        // The platform SearchView — what the Play Store's search box is built on. It brings its own
        // AutoCompleteTextView, its own InputConnection handling and a suggestions dropdown, so it
        // exercises a different path from a plain EditText.
        val svLog = android.widget.TextView(this).apply {
            text = "SearchView — nothing typed yet"; textSize = 12f
        }
        val sv = android.widget.SearchView(this).apply {
            id = 1007
            isIconified = false
            queryHint = "SearchView (like Play Store)"
            setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean {
                    svLog.text = "SearchView — query \"$q\""
                    return true
                }
            })
        }
        root.addView(svLog)
        root.addView(sv)

        // Remote-desktop stand-in: shows exactly what the keyboard sends down the wire.
        val rawLog = android.widget.TextView(this).apply {
            text = "RAW (TYPE_NULL, like remote desktop) — tap it, then type"
            textSize = 12f
        }
        root.addView(rawLog)
        root.addView(RawKeyView(rawLog))

        // Case 1: the field directly in the activity, auto-focused, IME force-shown on start
        val inline = waPinField()
        root.addView(inline)

        // Case 2: the same field inside a dialog (how WhatsApp's periodic PIN reminder appears)
        root.addView(Button(this).apply {
            text = "Open PIN dialog"
            setOnClickListener {
                val field = waPinField()
                val dlg = AlertDialog.Builder(this@FieldTestActivity)
                    .setTitle("Enter your two-step verification PIN")
                    .setView(field)
                    .setPositiveButton("OK", null)
                    .create()
                dlg.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                dlg.setOnShowListener { field.requestFocus() }
                dlg.show()
            }
        })

        // A chat-style composer PINNED to the bottom, directly against the keyboard, the way
        // WhatsApp's message box sits. Everything else here is near the top of the screen, and a
        // field hard against the keyboard edge is a different case: it is the one that can be
        // clipped or mispositioned by the insets the keyboard reports.
        val chat = EditText(this).apply {
            id = 1005
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            textSize = 18f
            hint = "chat composer (pinned to the bottom, like WhatsApp)"
        }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(
            android.widget.ScrollView(this).apply { addView(root) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        screen.addView(
            chat,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        setContentView(screen)
        inline.requestFocus()
        // WhatsApp-style delayed show (their helper posts showSoftInput after focus)
        inline.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(inline, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }
}
