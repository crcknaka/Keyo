package com.keyo.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONObject

class SetTimerTool : Tool {
    override val name = "set_timer"
    override val uiLabel = "⏱ Timer"
    override val uiExample = "Timer for 5 minutes"
    override val description = "Set a countdown timer for a specified duration in seconds"
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "seconds": { "type": "integer", "description": "Timer duration in seconds" },
                "label": { "type": "string", "description": "Optional timer label" }
            },
            "required": ["seconds"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val seconds = args.getInt("seconds")
        val label = args.optString("label", "")

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val min = seconds / 60
            val sec = seconds % 60
            val timeStr = if (min > 0) "${min}m ${sec}s" else "${sec}s"
            ToolResult(true, "Timer set for $timeStr")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't start timer: ${e.message}")
        }
    }
}

