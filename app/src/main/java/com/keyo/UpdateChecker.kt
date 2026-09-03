package com.keyo

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates, straight from the project's GitHub releases.
 *
 * Keyo is installed by hand rather than from a store, so there is nothing to tell the user a newer
 * build exists. This asks GitHub for the latest published release, compares it with the running
 * version and — when the user asks for it — downloads the APK and hands it to the system installer.
 */
object UpdateChecker {

    private const val LATEST = "https://api.github.com/repos/crcknaka/Keyo/releases/latest"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** [sha256] is the hex digest GitHub publishes for the asset, when it does; null otherwise. */
    data class Release(val version: String, val apkUrl: String, val notes: String, val sizeBytes: Long, val sha256: String? = null)

    /** Compare dotted versions ("1.10" is newer than "1.9"), tolerating a leading "v" and extra parts. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").split('.', '-')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Latest release, or null when there is none newer / the network is unavailable. */
    fun check(currentVersion: String, callback: (Release?, String?) -> Unit) {
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) =
                callback(null, e.message ?: "no connection")

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use { r ->
                        val body = r.body?.string()
                        if (!r.isSuccessful || body == null) {
                            callback(null, "GitHub returned ${r.code}"); return
                        }
                        val json = JSONObject(body)
                        val tag = json.optString("tag_name").ifEmpty { json.optString("name") }
                        val assets = json.optJSONArray("assets")
                        var url = ""
                        var size = 0L
                        var sha: String? = null
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.getJSONObject(i)
                                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                    url = a.optString("browser_download_url")
                                    size = a.optLong("size")
                                    // "sha256:<hex>" — computed by GitHub when the asset was uploaded.
                                    sha = a.optString("digest").removePrefix("sha256:").lowercase().ifBlank { null }
                                    break
                                }
                            }
                        }
                        // Only ever install what GitHub serves over TLS; a redirect elsewhere is refused.
                        if (url.isNotEmpty() && !url.startsWith("https://github.com/") &&
                            !url.startsWith("https://objects.githubusercontent.com/")) {
                            callback(null, "unexpected download location"); return
                        }
                        when {
                            tag.isEmpty() || url.isEmpty() -> callback(null, "no APK in the latest release")
                            !isNewer(tag, currentVersion) -> callback(null, null)   // already up to date
                            else -> callback(
                                Release(tag.removePrefix("v"), url, json.optString("body"), size, sha), null)
                        }
                    }
                } catch (e: Exception) {
                    callback(null, e.message ?: "bad response")
                }
            }
        })
    }

    /** Why [file] must not be installed as [release], or null when it checks out: the size GitHub
     *  reported and, when it published one, the sha256 digest. Before this the downloaded bytes
     *  were handed to the installer unread — a truncated download or a swapped asset would have
     *  gone straight through to the one confirmation tap. Hashes 4 MB; call off the main thread. */
    fun verify(file: File, release: Release): String? {
        if (!file.isFile) return "file missing"
        if (release.sizeBytes > 0 && file.length() != release.sizeBytes)
            return "download incomplete (${file.length()} of ${release.sizeBytes} bytes)"
        val want = release.sha256 ?: return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        return if (got == want) null else "checksum mismatch — the file is not what GitHub published"
    }

    /** Download [release]'s APK to [dest] and verify it. [onProgress] gets 0..100, or -1 while the
     *  size is unknown. Anything else in [dest]'s directory — older downloads — is removed first. */
    fun download(release: Release, dest: File, onProgress: (Int) -> Unit, done: (File?, String?) -> Unit) {
        dest.parentFile?.listFiles()?.forEach { if (it != dest) it.delete() }
        val req = Request.Builder().url(release.apkUrl).build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) =
                done(null, e.message ?: "download failed")

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use { r ->
                        val body = r.body
                        if (!r.isSuccessful || body == null) { done(null, "server returned ${r.code}"); return }
                        val total = body.contentLength()
                        dest.parentFile?.mkdirs()
                        body.byteStream().use { input ->
                            dest.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read = 0L
                                var last = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    read += n
                                    val pct = if (total > 0) ((read * 100) / total).toInt() else -1
                                    if (pct != last) { last = pct; onProgress(pct) }
                                }
                            }
                        }
                        val problem = verify(dest, release)
                        if (problem != null) { dest.delete(); done(null, problem) } else done(dest, null)
                    }
                } catch (e: Exception) {
                    done(null, e.message ?: "download failed")
                }
            }
        })
    }
}
