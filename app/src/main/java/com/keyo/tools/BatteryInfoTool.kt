package com.keyo.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

class BatteryInfoTool : Tool {
    override val name = "battery_info"
    override val uiLabel = "🔋 Battery"
    override val uiExample = "How much battery is left?"
    override val description = "Get current battery level and charging status"
    override val parameters = JSONObject("""{ "type": "object", "properties": {} }""")

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, filter)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (scale > 0) level * 100 / scale else -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
            val statusStr = if (charging) "charging" else "discharging"
            ToolResult(true, "🔋 Battery: $pct%, $statusStr", silent = true)
        } catch (e: Exception) {
            ToolResult(false, "Couldn't read battery info: ${e.message}")
        }
    }
}

