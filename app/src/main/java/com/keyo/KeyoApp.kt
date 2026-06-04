package com.keyo

import android.app.Application
import com.keyo.tools.ToolRegistry

class KeyoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ToolRegistry.init()
        // Prefer a user-supplied API key (from settings); fall back to the build-time default.
        val savedKey = KeyboardPrefs.getApiKey(this)
        if (savedKey.isNotBlank()) GroqApi.apiKey = savedKey
    }
}
