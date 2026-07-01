package com.keyo

import android.content.Context
import org.json.JSONObject

/**
 * The user's personal vocabulary, learned from what they type. Holds:
 *  - unigram counts (how often each word is used) — boosts completion ranking, and
 *  - bigram counts (which word tends to follow which) — drives next-word prediction.
 *
 * Kept in memory and persisted to SharedPreferences as JSON, debounced via [save]. Sizes are capped
 * so the store can't grow unbounded. Never call [learn] for text from secure/password fields.
 */
object UserDictionary {

    private const val PREFS = "keyo_userdict"
    private const val KEY_DATA = "data"

    private const val MAX_UNIGRAMS = 4000
    private const val MAX_BIGRAM_KEYS = 2500
    private const val MAX_FOLLOWERS = 12

    // @Volatile vars (not vals): ensureLoaded builds fresh maps on the IO thread and publishes them
    // atomically, so main-thread readers see either the old map or the complete new one — never a
    // HashMap mid-population. All mutation goes through synchronized methods.
    @Volatile private var unigram = HashMap<String, Int>()
    @Volatile private var bigram = HashMap<String, HashMap<String, Int>>()

    @Volatile private var loaded = false
    @Volatile var dirty = false
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val u = HashMap<String, Int>()
            val b = HashMap<String, HashMap<String, Int>>()
            try {
                val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null)
                if (raw != null) {
                    val root = JSONObject(raw)
                    root.optJSONObject("u")?.let { uj ->
                        for (k in uj.keys()) u[k] = uj.getInt(k)
                    }
                    root.optJSONObject("b")?.let { bj ->
                        for (prev in bj.keys()) {
                            val inner = bj.getJSONObject(prev)
                            val map = HashMap<String, Int>()
                            for (next in inner.keys()) map[next] = inner.getInt(next)
                            b[prev] = map
                        }
                    }
                }
            } catch (_: Exception) { /* corrupt store -> start fresh */ }
            // Merge anything learned while the load was still running (the first keystrokes can
            // race the IO load), then publish.
            for ((k, v) in unigram) u[k] = maxOf(u[k] ?: 0, v)
            for ((p, f) in bigram) {
                val t = b.getOrPut(p) { HashMap() }
                for ((k, v) in f) t[k] = (t[k] ?: 0) + v
            }
            unigram = u
            bigram = b
            loaded = true
        }
    }

    fun unigrams(): Map<String, Int> = unigram
    fun bigrams(): Map<String, Map<String, Int>> = bigram

    // --- Management API (used by the Settings screen) ---

    fun count(): Int = unigram.size

    /** Learned words, most-used first. */
    fun wordsByFrequency(): List<String> =
        unigram.entries.sortedByDescending { it.value }.map { it.key }

    /** Manually add a word so it shows up in suggestions. Returns false if it isn't a valid word. */
    fun addWord(word: String): Boolean = synchronized(this) {
        val w = word.trim().lowercase()
        if (w.length < 2 || w.length > 32) return false
        if (w.any { !it.isLetter() && it != '\'' && it != '-' }) return false
        unigram[w] = maxOf(unigram[w] ?: 0, 5) // seed a count so it ranks among learned words
        dirty = true
        return true
    }

    /** Remove a word and any bigrams that reference it. */
    fun removeWord(word: String): Unit = synchronized(this) {
        val w = word.lowercase()
        if (unigram.remove(w) != null) dirty = true
        if (bigram.remove(w) != null) dirty = true
        bigram.values.forEach { if (it.remove(w) != null) dirty = true }
    }

    /**
     * Record that [word] was typed (optionally following [prev]). Both should be lowercase.
     *
     * [bumpUnigram] adds the word to the personal vocabulary so it counts as "known" (shows in
     * completion suggestions, stops being auto-corrected). Pass `false` for plain typing so words
     * only enter the dictionary on an explicit suggestion tap — see KeyoService.finishWord. The
     * next-word (bigram) pattern is always recorded, since that's prediction, not the dictionary.
     */
    fun learn(prev: String?, word: String, bumpUnigram: Boolean = true): Unit = synchronized(this) {
        if (word.length < 2 || word.length > 32) return
        if (bumpUnigram) {
            unigram[word] = (unigram[word] ?: 0) + 1
            if (unigram.size > MAX_UNIGRAMS) pruneSmallest(unigram, MAX_UNIGRAMS)
            dirty = true
        }
        if (!prev.isNullOrEmpty()) {
            val followers = bigram.getOrPut(prev) { HashMap() }
            followers[word] = (followers[word] ?: 0) + 1
            if (followers.size > MAX_FOLLOWERS) pruneSmallest(followers, MAX_FOLLOWERS)
            if (bigram.size > MAX_BIGRAM_KEYS) pruneBigramKeys()
            dirty = true
        }
    }

    /** Persist if there are unsaved changes. Cheap to call repeatedly. */
    fun save(context: Context): Unit = synchronized(this) {
        if (!dirty) return
        try {
            val root = JSONObject()
            val u = JSONObject(); for ((k, v) in unigram) u.put(k, v)
            val b = JSONObject()
            for ((prev, followers) in bigram) {
                val inner = JSONObject(); for ((k, v) in followers) inner.put(k, v)
                b.put(prev, inner)
            }
            root.put("u", u); root.put("b", b)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DATA, root.toString()).apply()
            dirty = false
        } catch (_: Exception) { /* ignore persistence errors */ }
    }

    fun clear(context: Context): Unit = synchronized(this) {
        unigram.clear(); bigram.clear(); dirty = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DATA).apply()
    }

    // --- Backup: export / import (Settings) ---

    /** The whole personal dictionary (words + next-word pairs) as a self-describing JSON string. */
    fun exportJson(): String = synchronized(this) {
        val root = JSONObject()
        root.put("keyo_dictionary", 1)   // format marker + version, checked on import
        val u = JSONObject(); for ((k, v) in unigram) u.put(k, v)
        val b = JSONObject()
        for ((prev, followers) in bigram) {
            val inner = JSONObject(); for ((k, v) in followers) inner.put(k, v)
            b.put(prev, inner)
        }
        root.put("u", u); root.put("b", b)
        return root.toString()
    }

    /** Merge a previously exported dictionary into this one (nothing is deleted: unigram counts keep
     *  the larger value, bigram counts add — same rules as the load-race merge). Returns the number
     *  of words that were NEW, or -1 if [json] is not a Keyo dictionary export. */
    fun importJson(json: String): Int = synchronized(this) {
        val root = try { JSONObject(json) } catch (_: Exception) { return -1 }
        if (!root.has("keyo_dictionary") || !root.has("u")) return -1
        var newWords = 0
        try {
            root.optJSONObject("u")?.let { uj ->
                for (k in uj.keys()) {
                    val v = uj.optInt(k, 0)
                    if (v <= 0 || k.length < 2 || k.length > 32) continue
                    if (!unigram.containsKey(k)) newWords++
                    unigram[k] = maxOf(unigram[k] ?: 0, v)
                }
            }
            root.optJSONObject("b")?.let { bj ->
                for (prev in bj.keys()) {
                    val inner = bj.optJSONObject(prev) ?: continue
                    val followers = bigram.getOrPut(prev) { HashMap() }
                    for (next in inner.keys()) {
                        val v = inner.optInt(next, 0)
                        if (v > 0) followers[next] = (followers[next] ?: 0) + v
                    }
                    if (followers.size > MAX_FOLLOWERS) pruneSmallest(followers, MAX_FOLLOWERS)
                }
            }
        } catch (_: Exception) { return -1 }
        if (unigram.size > MAX_UNIGRAMS) pruneSmallest(unigram, MAX_UNIGRAMS)
        if (bigram.size > MAX_BIGRAM_KEYS) pruneBigramKeys()
        dirty = true
        return newWords
    }

    private fun pruneSmallest(map: HashMap<String, Int>, target: Int) {
        val remove = map.entries.sortedBy { it.value }.take((map.size - target).coerceAtLeast(0)).map { it.key }
        remove.forEach { map.remove(it) }
    }

    private fun pruneBigramKeys() {
        // Drop the least-informative prev words (those with the smallest single follower count).
        val remove = bigram.entries
            .sortedBy { e -> e.value.values.maxOrNull() ?: 0 }
            .take(bigram.size - MAX_BIGRAM_KEYS)
            .map { it.key }
        remove.forEach { bigram.remove(it) }
    }
}
