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
    private fun messageField(label: String, action: Int, multi: Boolean): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val log = android.widget.TextView(this).apply { text = "$label — waiting"; textSize = 12f }
        var fired = 0
        val field = EditText(this).apply {
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
        root.addView(messageField("SEND, single line", EditorInfo.IME_ACTION_SEND, multi = false))
        root.addView(messageField("DONE, single line", EditorInfo.IME_ACTION_DONE, multi = false))
        root.addView(messageField("SEND, multi-line", EditorInfo.IME_ACTION_SEND, multi = true))
        // NO_ENTER_ACTION as well: with a bare UNSPECIFIED the framework substitutes NEXT (there are
        // focusable views below) or DONE, so the field would silently test the opposite of its name.
        root.addView(messageField("no action declared",
            EditorInfo.IME_ACTION_UNSPECIFIED or EditorInfo.IME_FLAG_NO_ENTER_ACTION, multi = false))

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

        // Scrollable: with the keyboard up the later cases (the WhatsApp PIN field and its dialog
        // button — the reason this activity exists) would otherwise sit off-screen.
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        inline.requestFocus()
        // WhatsApp-style delayed show (their helper posts showSoftInput after focus)
        inline.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(inline, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }
}
