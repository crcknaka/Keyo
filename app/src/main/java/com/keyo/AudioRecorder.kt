package com.keyo

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    // Written on the main thread (start/stop), read by the capture thread's loop condition — without
    // @Volatile the thread can miss the stop and keep reading a released AudioRecord.
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // PCM is collected on a background thread while recording, then written as a WAV file.
    private var pcmData = ByteArrayOutputStream()

    private companion object {
        /** Shortest clip worth sending: 0.25s of 16kHz 16-bit mono. A brush against the mic button
         *  used to be uploaded like any other recording, and a fraction of a second of room noise is
         *  exactly what makes a speech model invent a sentence — in whatever language it guessed.
         *  Short real words ("да", "ok") run past this comfortably. */
        const val MIN_BYTES = 16000 * 2 / 4
    }

    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) return false

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize * 4
            )
            // A recorder that failed to initialize (mic held by another app) still owns a native
            // handle — release it, or repeated retries exhaust the device's recorders.
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                return false
            }

            pcmData = ByteArrayOutputStream()
            audioRecord?.startRecording()
            isRecording = true

            val rec = audioRecord
            recordingThread = Thread {
                try {
                    val buffer = ByteArray(bufferSize)
                    while (isRecording) {
                        // A negative read is an error code (ERROR_INVALID_OPERATION / DEAD_OBJECT) —
                        // leave the loop instead of spinning on it forever.
                        val read = try { rec?.read(buffer, 0, buffer.size) ?: -1 } catch (_: Exception) { -1 }
                        if (read > 0) {
                            synchronized(pcmData) {
                                pcmData.write(buffer, 0, read)
                            }
                        } else if (read < 0) break
                    }
                } finally {
                    // The capture thread OWNS the recorder's teardown: releasing it from stop()
                    // instead would leave this thread reading a freed object whenever it outlives
                    // the join (the join is a safety net, not a guarantee).
                    try { rec?.stop() } catch (_: Exception) {}
                    try { rec?.release() } catch (_: Exception) {}
                }
            }.also { it.start() }

            return true
        } catch (e: SecurityException) {
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            return false
        }
    }

    /** Writes the captured audio as a WAV. False when there is nothing worth transcribing — the
     *  caller shows "Recording too short" and skips the upload. */
    fun stop(outputFile: File): Boolean {
        val pcmBytes = stopAndDrain() ?: return false
        if (pcmBytes.size < MIN_BYTES) return false
        return try { writeWav(outputFile, pcmBytes); true } catch (e: Exception) { false }
    }

    /** Stop recording and DISCARD the audio — nothing is written to disk. Used when the user
     *  cancels a dictation: audio they asked to throw away must not linger in the cache. */
    fun discard() { stopAndDrain() }

    /** Stops the capture thread and returns the captured PCM (null on error). The recorder itself is
     *  stopped and released by that thread as it exits. */
    private fun stopAndDrain(): ByteArray? {
        if (!isRecording) return null
        isRecording = false
        return try {
            // The capture thread notices the flag within one read() (a buffer's worth of audio, tens
            // of ms); the join just makes the common case deterministic. It runs on the main thread,
            // so it must stay short — the audio is already safe either way, since it's collected
            // under the pcmData lock.
            recordingThread?.join(500)
            recordingThread = null
            audioRecord = null
            synchronized(pcmData) { pcmData.toByteArray() }
        } catch (e: Exception) {
            audioRecord = null
            null
        }
    }

    private fun writeWav(file: File, pcmData: ByteArray) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(fileSize))
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16)) // chunk size
            fos.write(shortToByteArray(1)) // PCM format
            fos.write(shortToByteArray(channels))
            fos.write(intToByteArray(sampleRate))
            fos.write(intToByteArray(byteRate))
            fos.write(shortToByteArray(blockAlign))
            fos.write(shortToByteArray(bitsPerSample))

            // data chunk
            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))
            fos.write(pcmData)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
    }

    fun isActive() = isRecording
}
