package com.keyo.tools

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

class BrightnessInfoTool : Tool {
    override val name = "brightness"
    override val uiLabel = "☀️ Brightness"
    override val uiExample = "What's the brightness?"
    override val description = "Get or set screen brightness level (0-100%)"
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "level": {
                    "type": "integer",
                    "description": "Brightness level 0-100 percent. Omit to just read current brightness."
                }
            }
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        return try {
            if (args.has("level")) {
                val level = args.getInt("level").coerceIn(0, 100)
                val brightness = (level * 255 / 100).coerceIn(0, 255)
                // Note: needs WRITE_SETTINGS permission
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                ToolResult(true, "☀️ Brightness: $level%")
            } else {
                val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                val pct = brightness * 100 / 255
                ToolResult(true, "☀️ Current brightness: $pct%", silent = true)
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed: ${e.message}")
        }
    }
}

