package com.keyo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * System-dictation fallback: when there is no Groq API key or no network, dictation runs through
 * the device's own [SpeechRecognizer] (e.g. Google voice input, which works offline with a
 * downloaded language pack) instead of failing. Main-thread only; one session at a time.
 */
class OfflineDictation(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    var active = false
        private set

    fun isAvailable(): Boolean =
        try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Throwable) { false }

    /** Start listening. [onPartial] streams interim text; [onFinal] fires exactly once with the
     *  result (null = nothing recognised / error). [preferOffline] forces the on-device model. */
    fun start(
        langTag: String,
        preferOffline: Boolean,
        onPartial: (String) -> Unit,
        onFinal: (String?) -> Unit
    ) {
        cancel()
        val r = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: Throwable) { null }
        if (r == null) { onFinal(null); return }
        recognizer = r
        active = true
        var finished = false
        fun finish(text: String?) {
            if (finished) return
            finished = true
            active = false
            onFinal(text)
            destroy()
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank() && active) onPartial(t)
            }
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onError(error: Int) = finish(null)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { r.startListening(intent) } catch (_: Throwable) { finish(null) }
    }

    /** Finish listening; the final result still arrives via the session's onFinal. */
    fun stop() {
        try { recognizer?.stopListening() } catch (_: Throwable) {}
    }

    /** Abort without a result. */
    fun cancel() {
        active = false
        try { recognizer?.cancel() } catch (_: Throwable) {}
        destroy()
    }

    private fun destroy() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }
}
