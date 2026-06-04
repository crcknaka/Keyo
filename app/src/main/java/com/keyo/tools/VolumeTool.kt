package com.keyo.tools

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

class VolumeTool : Tool {
    override val name = "volume"
    override val uiLabel = "🔊 Volume"
    override val uiExample = "Set volume to 50%"
    override val description = "Control phone volume. Set media volume level (0-100%) or mute/unmute."
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "level": {
                    "type": "integer",
                    "description": "Volume level 0-100 percent. Use 0 for mute."
                }
            },
            "required": ["level"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val level = args.getInt("level").coerceIn(0, 100)
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val vol = (level * maxVol / 100).coerceIn(0, maxVol)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
            ToolResult(true, "🔊 Volume: $level%")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't change volume: ${e.message}")
        }
    }
}

