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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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

        setContentView(root)
        inline.requestFocus()
        // WhatsApp-style delayed show (their helper posts showSoftInput after focus)
        inline.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(inline, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }
}
