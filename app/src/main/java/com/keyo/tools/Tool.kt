package com.keyo.tools

import android.content.Context
import org.json.JSONObject

/**
 * Base interface for all keyboard tools.
 * Each tool has a name, description, parameters schema, and execute function.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JSONObject  // JSON Schema for parameters

    /** Short label for settings UI, e.g. "📞 Call" */
    val uiLabel: String get() = name

    /** Example phrase for settings UI, e.g. "Call Mom" */
    val uiExample: String get() = ""

    /**
     * Execute the tool with given arguments.
     * Returns a result string to feed back to the LLM.
     */
    suspend fun execute(context: Context, args: JSONObject): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val output: String,
    val silent: Boolean = false  // if true, don't show output to user, just feed to LLM
)
