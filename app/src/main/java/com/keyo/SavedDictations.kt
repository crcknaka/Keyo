package com.keyo

import android.content.Context
import java.io.File

/**
 * Recordings that have not become text yet. A dictation lives here from the moment recording starts
 * until its text has been delivered, so a failed request, a dead network or a killed keyboard never
 * costs the user what they said. Private app storage; anything older than [KEEP_DAYS] is removed,
 * because this is the user's voice sitting on disk.
 */
object SavedDictations {
    private const val DIR = "dictations"
    private const val KEEP_DAYS = 7

    fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** The language is kept in the name: retrying later must not ask a Russian model about Latvian. */
    fun newSpool(context: Context, lang: String) =
        File(dir(context), "voice_${System.currentTimeMillis()}_$lang.pcm")

    /** The language a recording was made in, or null for a name without one. */
    fun languageOf(wav: File): String? =
        wav.nameWithoutExtension.substringAfterLast('_', "").takeIf { it.length == 2 }

    fun wavFor(spool: File) = File(spool.parentFile, spool.nameWithoutExtension + ".wav")

    /** Finished recordings waiting for text, oldest first. */
    fun pending(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()

    /**
     * Turns spools left by a recording that never finished (the process died mid-dictation) into
     * normal saved recordings, and removes expired ones. [activeSpool] is the one being written now.
     */
    fun housekeeping(context: Context, activeSpool: File?) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600_000L
        dir(context).listFiles()?.forEach { f ->
            when {
                f.lastModified() < cutoff -> f.delete()
                f.name.endsWith(".pcm") && f != activeSpool -> {
                    if (f.length() >= AudioRecorder.MIN_BYTES) AudioRecorder.pcmFileToWav(f, wavFor(f))
                    f.delete()
                }
            }
        }
    }

    /** Recording length in seconds, from the file size. */
    fun seconds(wav: File): Int = ((wav.length() - 44).coerceAtLeast(0) / 32000).toInt()
}
