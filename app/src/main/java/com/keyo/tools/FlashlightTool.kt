package com.keyo.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import org.json.JSONObject

class FlashlightTool : Tool {
    override val name = "flashlight"
    override val uiLabel = "🔦 Flashlight"
    override val uiExample = "Turn on the flashlight"
    override val description = "Turn the phone flashlight on or off"
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "enabled": {
                    "type": "boolean",
                    "description": "true to turn on, false to turn off"
                }
            },
            "required": ["enabled"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val enabled = args.getBoolean("enabled")
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolResult(false, "Camera not found")
            cameraManager.setTorchMode(cameraId, enabled)
            ToolResult(true, if (enabled) "🔦 Flashlight on" else "Flashlight off")
        } catch (e: Exception) {
            ToolResult(false, "Failed: ${e.message}")
        }
    }
}

