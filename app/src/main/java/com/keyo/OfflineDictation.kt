package com.keyo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null
    // Set by stop(): from then on the result is due, and the RMS ticks a recognizer keeps emitting
    // for a moment after stopListening() must not re-arm the full session timeout.
    private var stopping = false

    companion object {
        /** Some OEM recognizer services are killed (or simply hang) without ever delivering
         *  onResults or onError, which used to leave dictation stuck on "Transcribing…" with a live
         *  mic until the IME was destroyed. The watchdog fires after this long with NO SIGN OF LIFE —
         *  it is re-armed on every partial result / speech event, so it never cuts off someone who
         *  is simply still talking. */
        private const val SESSION_TIMEOUT_MS = 20_000L
        /** Once stopListening() has been called the result is due immediately, so the grace is short. */
        private const val FINISH_TIMEOUT_MS = 6_000L
    }

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
        stopping = false
        val r = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: Throwable) { null }
        if (r == null) { onFinal(null); return }
        recognizer = r
        active = true
        var finished = false
        var lastPartial: String? = null
        fun finish(text: String?) {
            if (finished) return
            finished = true
            active = false
            cancelWatchdog()
            onFinal(text)
            destroy()
        }
        // Keep the best interim text if the service dies silently — better than losing the utterance.
        armWatchdog(SESSION_TIMEOUT_MS) { finish(lastPartial) }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank() && active) { lastPartial = t; onPartial(t) }
                rearmWatchdog(SESSION_TIMEOUT_MS)
            }
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            // A recognizer that heard the whole utterance and then failed (a flaky network,
            // a busy service) still delivered it as interim text; that beats "Didn't catch that".
            // Only a permission error means there never was any audio to trust.
            override fun onError(error: Int) =
                finish(if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) null else lastPartial)
            override fun onReadyForSpeech(params: Bundle?) = rearmWatchdog(SESSION_TIMEOUT_MS)
            override fun onBeginningOfSpeech() = rearmWatchdog(SESSION_TIMEOUT_MS)
            // Fires continuously while the mic is live: the clearest "the service is alive and the
            // user may still be talking" signal, so a long dictation is never cut short.
            override fun onRmsChanged(rmsdB: Float) = rearmWatchdog(SESSION_TIMEOUT_MS)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = rearmWatchdog(FINISH_TIMEOUT_MS)
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
        stopping = true
        try { recognizer?.stopListening() } catch (_: Throwable) {}
        // The result is due now, so shorten the watchdog: a recognizer that never answers should
        // not hold "Transcribing…" and the mic for the full session timeout.
        if (active) rearmWatchdog(FINISH_TIMEOUT_MS)
    }

    /** Abort without a result. */
    fun cancel() {
        active = false
        cancelWatchdog()
        try { recognizer?.cancel() } catch (_: Throwable) {}
        destroy()
    }

    private fun armWatchdog(delay: Long, onTimeout: () -> Unit) {
        cancelWatchdog()
        val r = Runnable { if (active) onTimeout() }
        watchdog = r
        handler.postDelayed(r, delay)
    }

    /** Re-post the existing timeout action with a new delay. */
    private fun rearmWatchdog(delay: Long) {
        val r = watchdog ?: return
        handler.removeCallbacks(r)
        handler.postDelayed(r, if (stopping) minOf(delay, FINISH_TIMEOUT_MS) else delay)
    }

    private fun cancelWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    private fun destroy() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }
}
