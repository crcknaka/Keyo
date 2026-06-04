package com.keyo.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONObject

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val uiLabel = "⏰ Alarm"
    override val uiExample = "Set an alarm for 7:30"
    override val description = "Set an alarm for a specific time (hours and minutes, 24h format)"
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "hour": { "type": "integer", "description": "Hour (0-23)" },
                "minute": { "type": "integer", "description": "Minute (0-59)" },
                "label": { "type": "string", "description": "Optional alarm label" }
            },
            "required": ["hour", "minute"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val hour = args.getInt("hour")
        val minute = args.getInt("minute")
        val label = args.optString("label", "")

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val timeStr = String.format("%02d:%02d", hour, minute)
            ToolResult(true, "Alarm set for $timeStr" + if (label.isNotEmpty()) " ($label)" else "")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't set alarm: ${e.message}")
        }
    }
}

