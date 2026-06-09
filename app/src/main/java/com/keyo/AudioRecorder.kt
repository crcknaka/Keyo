package com.keyo

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // PCM is collected on a background thread while recording, then written as a WAV file.
    private var pcmData = ByteArrayOutputStream()

    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) return false

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize * 4
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return false

            pcmData = ByteArrayOutputStream()
            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        synchronized(pcmData) {
                            pcmData.write(buffer, 0, read)
                        }
                    }
                }
            }.also { it.start() }

            return true
        } catch (e: SecurityException) {
            return false
        }
    }

    fun stop(outputFile: File): Boolean {
        if (!isRecording) return false
        isRecording = false

        try {
            recordingThread?.join(2000)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val pcmBytes: ByteArray
            synchronized(pcmData) {
                pcmBytes = pcmData.toByteArray()
            }

            if (pcmBytes.isEmpty()) return false

            // Write WAV file
            writeWav(outputFile, pcmBytes)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** Write the audio captured SO FAR to [outputFile] as WAV without stopping the recording.
     *  Used by live dictation to transcribe the growing utterance periodically. */
    fun snapshot(outputFile: File): Boolean {
        if (!isRecording) return false
        val pcmBytes: ByteArray
        synchronized(pcmData) { pcmBytes = pcmData.toByteArray() }
        if (pcmBytes.isEmpty()) return false
        return try { writeWav(outputFile, pcmBytes); true } catch (e: Exception) { false }
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
