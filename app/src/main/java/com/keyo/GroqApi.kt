package com.keyo

import android.content.Context
import android.util.Log
import com.keyo.tools.ToolRegistry
import com.keyo.tools.ToolResult
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

object GroqApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    // API key: user-supplied value (from settings) overrides the build-time default.
    var apiKey: String = BuildConfig.GROQ_API_KEY
    var model: String = "openai/gpt-oss-20b"             // for 🎤 dictation cleanup + ✨ Rewrite (fast)
    var aiModel: String = "openai/gpt-oss-120b"           // for 🤖 AI assistant + tools

    // AI assistant conversation history (last N turns)
    private const val MAX_HISTORY = 10 // pairs of user+assistant messages
    private val aiHistory = mutableListOf<JSONObject>()
    private var lastAiActivityMs = 0L
    private const val CONTEXT_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour — clear context after inactivity

    fun clearAiHistory() {
        aiHistory.clear()
    }

    private fun usingDefaultKey() = apiKey == BuildConfig.GROQ_API_KEY

    // Per https://console.groq.com/docs/errors — short, actionable messages.
    fun friendlyError(code: Int, body: String?): String {
        val serverMsg = try {
            org.json.JSONObject(body ?: "").getJSONObject("error").getString("message")
        } catch (_: Exception) { null }
        return when (code) {
            429 -> if (usingDefaultKey())
                "Too many requests on the shared key — add your own free Groq key in Settings"
            else "Too many requests — wait a few seconds and try again"
            401, 403 -> "Invalid API key — check Settings → Groq API key"
            413 -> "Text is too long for the model"
            422 -> serverMsg?.take(100) ?: "Request couldn't be processed (422)"
            498 -> "Groq is at capacity — try again shortly"
            499 -> "Request cancelled"
            500, 502, 503 -> "Groq is temporarily unavailable — try again"
            else -> serverMsg?.take(100) ?: "API error $code"
        }
    }

    /** Validate the current API key with a tiny request. callback(ok, errorMessage). */
    fun testKey(callback: (Boolean, String?) -> Unit) {
        if (apiKey.isBlank()) { callback(false, "No API key set"); return }
        val json = JSONObject().apply {
            put("model", aiModel)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", "ping") })
            })
            put("max_tokens", 1)
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(false, e.message ?: "Network error")
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                if (response.isSuccessful) callback(true, null)
                else callback(false, friendlyError(response.code, b))
            }
        })
    }

    fun transcribe(audioFile: File, callback: (String?, String?) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, "Network error: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val text = JSONObject(responseBody).getString("text")
                        callback(text, null)
                    } catch (e: Exception) {
                        callback(null, "Parse error: ${e.message}")
                    }
                } else {
                    callback(null, friendlyError(response.code, responseBody))
                }
            }
        })
    }

    /** Rewrite/transform text per an instruction (Rewrite menu). Returns only the new text. */
    fun rewrite(text: String, instruction: String, callback: (String?, String?) -> Unit) {
        val sys = "You are a precise text-editing tool. Apply the user's instruction to their text and " +
            "output ONLY the resulting text — no explanations, no quotes, no preamble. " +
            "Keep the original language unless the instruction says to translate. " +
            "NEVER output HTML or markdown tags; for emphasis use markers ⟦b⟧bold⟦/b⟧ and ⟦i⟧italic⟦/i⟧."
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", sys) })
            put(JSONObject().apply { put("role", "user"); put("content", "Instruction: $instruction\n\nText:\n$text") })
        }
        chat(model, messages, 0.4, 2048, 0, callback)
    }

    /** Find emoji matching a query (any language). Returns a string of emoji characters. */
    fun suggestEmojis(query: String, callback: (String?, String?) -> Unit) {
        val sys = "You are an emoji search engine. For the given word or phrase (any language), reply with " +
            "ONLY relevant emoji characters (10-30 of them), most relevant first. No words, no explanations."
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", sys) })
            put(JSONObject().apply { put("role", "user"); put("content", query) })
        }
        chat("llama-3.1-8b-instant", messages, 0.3, 200, 0, callback)
    }

    fun executeTask(
        task: String,
        context: Context? = null,
        confirm: (suspend (String) -> Boolean)? = null,
        callback: (String?, String?) -> Unit
    ) {
        // Clear history if inactive for too long
        val now = System.currentTimeMillis()
        if (now - lastAiActivityMs > CONTEXT_TIMEOUT_MS) {
            aiHistory.clear()
        }
        lastAiActivityMs = now

        val systemPrompt = """You are a keyboard AI assistant with tools. You can execute actions on the user's phone AND generate text.

Rules:
- If the user asks to DO something (alarm, timer, open app, flashlight, search, volume, etc.) — USE THE APPROPRIATE TOOL.
- If the user asks to WRITE/COMPOSE text — output the text directly without tools.
- If user asks to write/say something in a specific language, translate and output in that language.
- If user asks to compose something (email, message, etc.), write it directly.
- If the user refers to a previous answer, use conversation history.
- You can chain: use a tool AND respond with text.
- When using tools, after getting the result, provide a brief human-friendly summary.
- Reply in the same language the user spoke in (English, Russian, or Latvian).
- Be concise and natural.
- NEVER output HTML or markdown tags (no <b>, **, etc.). For emphasis use these exact markers: ⟦b⟧bold text⟦/b⟧ for bold and ⟦i⟧italic text⟦/i⟧ for italic. The keyboard converts them to real formatting."""

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            for (msg in aiHistory) { put(msg) }
            put(JSONObject().apply {
                put("role", "user")
                put("content", task)
            })
        }

        // Use coroutine for tool execution loop
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = executeWithTools(task, messages, context, maxRounds = 5, confirm = confirm)
                callback(result, null)
            } catch (e: Exception) {
                Log.e(TAG, "executeTask failed", e)
                callback(null, e.message ?: "Failed")
            }
        }
    }

    private suspend fun executeWithTools(
        originalTask: String,
        messages: JSONArray,
        context: Context?,
        maxRounds: Int,
        confirm: (suspend (String) -> Boolean)? = null
    ): String {
        var currentMessages = messages
        val hasTools = context != null && ToolRegistry.all().isNotEmpty()

        for (round in 0 until maxRounds) {
            val json = JSONObject().apply {
                put("model", aiModel)
                put("messages", currentMessages)
                put("temperature", 0.3)
                put("max_tokens", 2048)
                if (hasTools) {
                    put("tools", ToolRegistry.toGroqToolsArray())
                    put("tool_choice", "auto")
                }
            }

            val responseBody = callGroqSync(json) ?: throw IOException("Empty response")
            val responseJson = JSONObject(responseBody)
            val choice = responseJson.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val finishReason = choice.optString("finish_reason", "stop")

            // Check for tool calls
            if (finishReason == "tool_calls" || message.has("tool_calls")) {
                val toolCalls = message.getJSONArray("tool_calls")
                Log.d(TAG, "Tool calls: ${toolCalls.length()}")

                // Add assistant message with tool calls to conversation
                currentMessages.put(message)

                // Execute each tool call
                for (i in 0 until toolCalls.length()) {
                    val toolCall = toolCalls.getJSONObject(i)
                    val toolId = toolCall.getString("id")
                    val function = toolCall.getJSONObject("function")
                    val toolName = function.getString("name")
                    val toolArgs = try {
                        JSONObject(function.getString("arguments"))
                    } catch (_: Exception) {
                        JSONObject()
                    }

                    Log.d(TAG, "Executing tool: $toolName($toolArgs)")

                    val toolResult = if (context != null) {
                        val tool = ToolRegistry.get(toolName)
                        if (tool != null) {
                            // Ask the user to approve any tool flagged sensitive before running it.
                            val approved = if (tool.sensitive && confirm != null)
                                confirm(tool.confirmSummary(toolArgs)) else true
                            if (!approved) {
                                ToolResult(false, "User declined the action; do not retry it.")
                            } else try {
                                withContext(Dispatchers.Main) {
                                    tool.execute(context, toolArgs)
                                }
                            } catch (e: Exception) {
                                ToolResult(false, "Tool error: ${e.message}")
                            }
                        } else {
                            ToolResult(false, "Unknown tool: $toolName")
                        }
                    } else {
                        ToolResult(false, "No context for tool execution")
                    }

                    Log.d(TAG, "Tool result: ${toolResult.output}")

                    // Add tool result to messages
                    currentMessages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", toolId)
                        put("content", toolResult.output)
                    })
                }
                // Continue loop — LLM will see tool results and respond
                continue
            }

            // No tool calls — we have a final text response
            val content = message.optString("content", "").trim()

            // Save to history
            aiHistory.add(JSONObject().apply {
                put("role", "user")
                put("content", originalTask)
            })
            aiHistory.add(JSONObject().apply {
                put("role", "assistant")
                put("content", content)
            })
            while (aiHistory.size > MAX_HISTORY * 2) {
                aiHistory.removeAt(0)
                aiHistory.removeAt(0)
            }

            return content
        }

        return "Too many execution steps"
    }

    // Shared chat-completion call with automatic retry/back-off on 429 (rate limit) and 5xx.
    private fun chat(
        model: String,
        messages: JSONArray,
        temperature: Double,
        maxTokens: Int,
        attempt: Int = 0,
        callback: (String?, String?) -> Unit
    ) {
        val json = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(null, "Network error: ${e.message}")
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                when {
                    response.isSuccessful && body != null -> {
                        try {
                            val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                            callback(content.trim(), null)
                        } catch (e: Exception) { callback(null, "Parse error: ${e.message}") }
                    }
                    (response.code == 429 || response.code in 500..599) && attempt < 2 -> {
                        try { Thread.sleep(1000L * (attempt + 1)) } catch (_: InterruptedException) {}
                        chat(model, messages, temperature, maxTokens, attempt + 1, callback)
                    }
                    else -> callback(null, friendlyError(response.code, body))
                }
            }
        })
    }

    private fun callGroqSync(json: JSONObject): String? {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful) return body
            if ((response.code == 429 || response.code in 500..599) && attempt < 2) {
                attempt++
                try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) {}
                continue
            }
            Log.e(TAG, "Groq API error ${response.code}: $body")
            throw IOException(friendlyError(response.code, body))
        }
    }

    private const val TAG = "GroqApi"

    fun cleanupText(rawText: String, callback: (String?, String?) -> Unit) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "You are a speech-to-text cleanup tool. Take raw transcription and output the SAME text but cleaned up. Rules: 1) Keep the EXACT meaning and intent — if the user said a question, output a question. NEVER answer questions, just clean them up. 2) Fix punctuation, capitalization, minor grammar. 3) Remove filler words (uh, um, эээ, ммм), false starts, repetitions. 4) If there's a mix of languages (English, Russian, Latvian), preserve them as spoken. 5) Output ONLY the cleaned text. Do NOT add anything, do NOT answer, do NOT explain.")
            })
            put(JSONObject().apply { put("role", "user"); put("content", rawText) })
        }
        chat(model, messages, 0.3, 2048, 0, callback)
    }
}
