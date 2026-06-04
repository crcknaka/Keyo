package com.keyo.tools

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class DateTimeTool : Tool {
    override val name = "datetime"
    override val uiLabel = "📅 Date & time"
    override val uiExample = "What day is it today?"
    override val description = "Get current date, time, and day of the week"
    override val parameters = JSONObject("""{ "type": "object", "properties": {} }""")

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val now = Date()
        val dateFormat = SimpleDateFormat("dd MMMM yyyy, EEEE", Locale.ENGLISH)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        val date = dateFormat.format(now)
        val time = timeFormat.format(now)
        return ToolResult(true, "📅 $date, $time", silent = true)
    }
}

