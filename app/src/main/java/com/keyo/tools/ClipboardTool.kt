package com.keyo.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

class ClipboardTool : Tool {
    override val name = "clipboard"
    override val uiLabel = "📋 Clipboard"
    override val uiExample = "What's in the clipboard?"
    override val description = "Read from or write to the clipboard. Use action 'read' to get current clipboard content, 'write' to copy text to clipboard."
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["read", "write"],
                    "description": "read = get clipboard, write = set clipboard"
                },
                "text": {
                    "type": "string",
                    "description": "Text to copy (only for write action)"
                }
            },
            "required": ["action"]
        }
    """)

    // Overwriting the clipboard is the consequential half; reading it is what the user asked for.
    override fun needsConfirm(args: JSONObject) = args.optString("action") == "write"
    override fun confirmSummary(args: JSONObject) = "Replace the clipboard with \"${args.optString("text").take(60)}\"?"

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val action = args.getString("action")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        return when (action) {
            "read" -> {
                val clip = clipboard.primaryClip
                // Password managers and OTP fields flag their copies; the keyboard's own clip
                // history already refuses those, and what the history won't keep, the model must
                // not be sent either — this text goes to Groq as the tool result.
                val sensitive = clip?.description?.extras
                    ?.getBoolean("android.content.extra.IS_SENSITIVE") == true
                if (sensitive) {
                    ToolResult(true, "The clipboard holds sensitive content (a password or code) and was not read.")
                } else if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    ToolResult(true, "Clipboard: $text")
                } else {
                    ToolResult(true, "Clipboard is empty")
                }
            }
            "write" -> {
                val text = args.optString("text", "")
                if (text.isEmpty()) return ToolResult(false, "No text to copy")
                clipboard.setPrimaryClip(ClipData.newPlainText("keyo", text))
                ToolResult(true, "📋 Copied to clipboard")
            }
            else -> ToolResult(false, "Unknown action: $action")
        }
    }
}

