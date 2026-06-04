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

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val action = args.getString("action")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        return when (action) {
            "read" -> {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    ToolResult(true, "Clipboard: $text", silent = true)
                } else {
                    ToolResult(true, "Clipboard is empty", silent = true)
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

