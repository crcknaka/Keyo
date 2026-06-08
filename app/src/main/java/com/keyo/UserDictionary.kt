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

    private val unigram = HashMap<String, Int>()
    private val bigram = HashMap<String, HashMap<String, Int>>()

    @Volatile private var loaded = false
    @Volatile var dirty = false
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null)
                if (raw != null) {
                    val root = JSONObject(raw)
                    root.optJSONObject("u")?.let { u ->
                        for (k in u.keys()) unigram[k] = u.getInt(k)
                    }
                    root.optJSONObject("b")?.let { b ->
                        for (prev in b.keys()) {
                            val inner = b.getJSONObject(prev)
                            val map = HashMap<String, Int>()
                            for (next in inner.keys()) map[next] = inner.getInt(next)
                            bigram[prev] = map
                        }
                    }
                }
            } catch (_: Exception) { /* corrupt store -> start fresh */ }
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
    fun addWord(word: String): Boolean {
        val w = word.trim().lowercase()
        if (w.length < 2 || w.length > 32) return false
        if (w.any { !it.isLetter() && it != '\'' && it != '-' }) return false
        unigram[w] = maxOf(unigram[w] ?: 0, 5) // seed a count so it ranks among learned words
        dirty = true
        return true
    }

    /** Remove a word and any bigrams that reference it. */
    fun removeWord(word: String) {
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
    fun learn(prev: String?, word: String, bumpUnigram: Boolean = true) {
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
    fun save(context: Context) {
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

    fun clear(context: Context) {
        unigram.clear(); bigram.clear(); dirty = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DATA).apply()
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
