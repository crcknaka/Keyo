package com.keyo.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of all available tools. Provides Groq-compatible tool definitions.
 */
object ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /**
     * Generate Groq/OpenAI-compatible tools array for the API request.
     */
    fun toGroqToolsArray(): JSONArray {
        return JSONArray().apply {
            for (tool in tools.values) {
                put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    })
                })
            }
        }
    }

    fun init() {
        register(PhoneCallTool())
        register(SendSmsTool())
        register(SetAlarmTool())
        register(SetTimerTool())
        register(OpenAppTool())
        register(FlashlightTool())
        register(ClipboardTool())
        register(WebSearchTool())
        register(VolumeTool())
        register(BrightnessInfoTool())
        register(BatteryInfoTool())
        register(DateTimeTool())
    }
}
