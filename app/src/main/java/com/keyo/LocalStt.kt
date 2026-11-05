package com.keyo

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * On-device Russian recognition: GigaAM v3 (e2e RNNT, int8) through sherpa-onnx. It works from a
 * finished WAV, so a dictation the cloud failed on can be recognised from the same recording.
 *
 * The model is about 326 MB, so it is downloaded once from Settings rather than shipped in the APK.
 * Every file is pinned by SHA-256. It is loaded on first use and released after [IDLE_UNLOAD_MS],
 * because a keyboard holding hundreds of megabytes all day is the first thing Android kills.
 */
object LocalStt {
    private const val BASE = "https://huggingface.co/pantinor/gigaam-v3/resolve/main/"
    private const val DIR = "gigaam-v3"
    private const val ENCODER = "gigaam_v3_e2e_rnnt_encoder_int8.onnx"
    private const val DECODER = "gigaam_v3_e2e_rnnt_decoder.onnx"
    private const val JOINER = "gigaam_v3_e2e_rnnt_joint.onnx"
    private const val TOKENS = "gigaam_v3_e2e_rnnt_tokens.txt"
    private const val IDLE_UNLOAD_MS = 3 * 60_000L
    private const val SAMPLE_RATE = 16000

    private class ModelFile(val name: String, val size: Long, val sha256: String)

    private val FILES = listOf(
        ModelFile(TOKENS, 13_353, "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d"),
        ModelFile(JOINER, 2_712_896, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
        ModelFile(DECODER, 4_600_058, "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860"),
        ModelFile(ENCODER, 318_995_997, "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1"),
    )
    val totalBytes: Long = FILES.sumOf { it.size }

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var lastUsedMs = 0L
    private val unloader = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "keyo-stt-unload").apply { isDaemon = true }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Sizes only: the digests were checked when the files were downloaded. */
    fun isInstalled(context: Context): Boolean =
        FILES.all { File(dir(context), it.name).length() == it.size }

    fun deleteModel(context: Context) {
        release()
        dir(context).deleteRecursively()
    }

    /** Blocking. Returns the text; throws when the model is missing or cannot run. */
    fun transcribe(context: Context, wav: File): String = synchronized(lock) {
        val samples = readWavSamples(wav)
        val rec = recognizer ?: load(context).also { recognizer = it }
        lastUsedMs = System.currentTimeMillis()
        val text = StringBuilder()
        for ((from, to) in splitForRecognition(samples, SAMPLE_RATE)) {
            val floats = FloatArray(to - from) { samples[from + it] / 32768f }
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(floats, SAMPLE_RATE)
                rec.decode(stream)
                val part = rec.getResult(stream).text.trim()
                appendPiece(text, part)
            } finally {
                stream.release()
            }
        }
        lastUsedMs = System.currentTimeMillis()
        unloader.schedule({ unloadIfIdle() }, IDLE_UNLOAD_MS + 1000, TimeUnit.MILLISECONDS)
        text.toString()
    }

    /** The model capitalises the start of each piece, but a cut can land mid-sentence. */
    internal fun appendPiece(text: StringBuilder, piece: String) {
        if (piece.isEmpty()) return
        if (text.isEmpty()) { text.append(piece); return }
        val sentenceEnded = text.last() in ".!?…"
        text.append(' ')
        text.append(if (sentenceEnded) piece else piece.replaceFirstChar { it.lowercase() })
    }

    private fun load(context: Context): OfflineRecognizer {
        check(isInstalled(context)) { "on-device model is not downloaded" }
        val d = dir(context)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(d, ENCODER).path,
                        decoder = File(d, DECODER).path,
                        joiner = File(d, JOINER).path,
                    ),
                    tokens = File(d, TOKENS).path,
                    numThreads = threads,
                    modelType = "nemo_transducer",
                ),
                decodingMethod = "greedy_search",
            )
        )
    }

    private fun unloadIfIdle() {
        synchronized(lock) {
            if (System.currentTimeMillis() - lastUsedMs >= IDLE_UNLOAD_MS) release()
        }
    }

    private fun release() {
        synchronized(lock) {
            try { recognizer?.release() } catch (_: Throwable) {}
            recognizer = null
        }
    }

    /**
     * Downloads the model, resuming any partial file, so a dropped connection costs only what had
     * not arrived yet. Blocking. [onProgress] gets 0..100. Returns null on success, else the reason.
     */
    fun download(context: Context, onProgress: (Int) -> Unit): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val d = dir(context).apply { mkdirs() }
        var finished = 0L
        for (f in FILES) {
            val target = File(d, f.name)
            if (target.length() == f.size) { finished += f.size; continue }
            target.delete()
            val part = File(d, f.name + ".part")
            if (part.length() > f.size) part.delete()
            try {
                if (part.length() < f.size) fetch(client, f, part) { got ->
                    onProgress((((finished + got) * 100) / totalBytes).toInt())
                }
                if (sha256(part) != f.sha256) {
                    part.delete()
                    return "checksum mismatch in ${f.name}"
                }
                if (!part.renameTo(target)) return "could not save ${f.name}"
            } catch (e: Exception) {
                return e.message ?: "download failed"
            }
            finished += f.size
        }
        onProgress(100)
        return null
    }

    private fun fetch(client: OkHttpClient, f: ModelFile, part: File, onBytes: (Long) -> Unit) {
        val have = part.length()
        val builder = Request.Builder().url(BASE + f.name)
        if (have > 0) builder.header("Range", "bytes=$have-")
        client.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) error("server returned ${r.code}")
            val body = r.body ?: error("empty response")
            // A server that ignored the Range header sends the whole file again (200, not 206).
            val offset = if (r.code == 206) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                var got = offset
                var lastReport = 0L
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastReport > 1_000_000) { lastReport = got; onBytes(got) }
                    }
                }
            }
        }
        if (part.length() != f.size) error("connection dropped, tap Download to resume")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Reads what [AudioRecorder] writes: a 44-byte header, then 16 kHz mono 16-bit PCM. */
    private fun readWavSamples(wav: File): ShortArray {
        val bytes = wav.readBytes()
        require(bytes.size > 44) { "recording is empty" }
        return ShortArray((bytes.size - 44) / 2) { i ->
            val lo = bytes[44 + i * 2].toInt() and 0xFF
            val hi = bytes[45 + i * 2].toInt()
            ((hi shl 8) or lo).toShort()
        }
    }

    /**
     * GigaAM is documented for clips up to 25 seconds, so a long dictation is cut into pieces of at
     * most [maxSec]. Each cut goes at the quietest moment of the last [searchSec] of the piece, which
     * lands between words whenever the speaker paused at all. Returns [from, to) sample ranges that
     * cover the whole recording without gaps.
     */
    internal fun splitForRecognition(
        samples: ShortArray,
        sampleRate: Int,
        maxSec: Int = 20,
        searchSec: Int = 6
    ): List<Pair<Int, Int>> {
        val max = maxSec * sampleRate
        val search = searchSec * sampleRate
        val frame = sampleRate / 10
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (samples.size - start > max) {
            val end = start + max
            var bestCut = end
            var bestEnergy = Long.MAX_VALUE
            var p = end - search
            while (p + frame <= end) {
                var energy = 0L
                var i = p
                while (i < p + frame) { energy += kotlin.math.abs(samples[i].toInt()); i += 8 }
                if (energy < bestEnergy) { bestEnergy = energy; bestCut = p + frame / 2 }
                p += frame
            }
            out.add(start to bestCut)
            start = bestCut
        }
        if (start < samples.size) out.add(start to samples.size)
        return out
    }
}
