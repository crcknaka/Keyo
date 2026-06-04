package com.keyo.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

class WebSearchTool : Tool {
    override val name = "web_search"
    override val uiLabel = "🔍 Search"
    override val uiExample = "Google the weather in Riga"
    override val description = "Search the web for something. Opens browser with search results."
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query"
                }
            },
            "required": ["query"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val query = args.getString("query")
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "🔍 Searching: $query")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't open search: ${e.message}")
        }
    }
}

