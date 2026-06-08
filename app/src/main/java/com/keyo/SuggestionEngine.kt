package com.keyo

import android.content.Context

/**
 * Offline word suggestions: prefix completion, spelling correction and next-word prediction.
 *
 * Two data sources are merged:
 *  - a bundled frequency word list per language (assets/dict/<lang>.txt, most-frequent first), and
 *  - the user's learned vocabulary ([UserDictionary]) which personalises and improves over time.
 *
 * The ranking/matching algorithms ([completeFrom], [correctFrom], [nextFrom], [editDistanceAtMost])
 * are pure functions that take their data as parameters, so they are unit-tested directly without a
 * Context. The public methods just feed them the loaded dictionary + learned maps.
 */
object SuggestionEngine {

    private class Vocab(val words: List<String>, val set: HashSet<String>)

    @Volatile private var loaded = false
    private val byLang = HashMap<String, Vocab>()

    fun isReady(): Boolean = loaded

    /** Loads all bundled dictionaries from assets. Call from a background thread; idempotent. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            for (lang in listOf("en", "ru", "lv")) {
                val words = ArrayList<String>(16000)
                try {
                    context.assets.open("dict/$lang.txt").bufferedReader().useLines { seq ->
                        seq.forEach { line ->
                            val w = line.trim()
                            if (w.isNotEmpty()) words.add(w)
                        }
                    }
                } catch (_: Exception) { /* missing asset -> empty vocab for this language */ }
                // Membership is checked on the folded form so ё/е count as the same letter.
                val folded = HashSet<String>(words.size * 2)
                words.forEach { folded.add(fold(it)) }
                byLang[lang] = Vocab(words, folded)
            }
            loaded = true
        }
    }

    private fun vocab(lang: String): Vocab? = byLang[lang] ?: byLang["en"]

    /** Completions that extend [prefixLower] (all lowercase), personalised words first. */
    fun complete(prefixLower: String, lang: String, learnedUni: Map<String, Int>, limit: Int = 3): List<String> {
        val v = vocab(lang) ?: return emptyList()
        return completeFrom(prefixLower, v.words, learnedUni, limit)
    }

    /** Best correction for [wordLower], or null if it is already known or nothing close is found. */
    fun correct(wordLower: String, lang: String, learnedUni: Map<String, Int>): String? {
        val v = vocab(lang) ?: return null
        return correctFrom(wordLower, v.words, v.set, learnedUni)
    }

    /** Predicted next words after [prevLower], from the user's learned bigrams. */
    fun nextWords(prevLower: String, learnedBi: Map<String, Map<String, Int>>, limit: Int = 3): List<String> =
        nextFrom(prevLower, learnedBi, limit)

    fun isKnown(wordLower: String, lang: String, learnedUni: Map<String, Int>): Boolean {
        if (learnedUni.containsKey(wordLower)) return true
        return vocab(lang)?.set?.contains(fold(wordLower)) == true
    }

    /** Treat ё and е as the same letter (Russian text routinely omits ё). No-op for en/lv. */
    internal fun fold(s: String): String =
        if (s.indexOf('ё') >= 0 || s.indexOf('Ё') >= 0) s.replace('ё', 'е').replace('Ё', 'Е') else s

    // ---------------------------------------------------------------------------------------------
    // Pure algorithms (no Android dependency) — unit-tested directly.
    // ---------------------------------------------------------------------------------------------

    /** Words that start with [prefix] and are longer than it; learned words (by count) first, then
     *  the frequency-ordered [words]. */
    internal fun completeFrom(
        prefix: String,
        words: List<String>,
        learnedUni: Map<String, Int>,
        limit: Int
    ): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val fp = fold(prefix)
        val out = LinkedHashSet<String>()
        learnedUni.entries
            .filter { it.key.length > prefix.length && fold(it.key).startsWith(fp) }
            .sortedByDescending { it.value }
            .forEach { if (out.size < limit) out.add(it.key) }
        if (out.size < limit) {
            for (w in words) {
                if (w.length > prefix.length && fold(w).startsWith(fp)) {
                    out.add(w)
                    if (out.size >= limit) break
                }
            }
        }
        return out.toList()
    }

    /** Closest dictionary word to [word] within a small edit distance, or null. Scans only the most
     *  frequent [scanLimit] words to bound cost; returns null when [word] is already known. */
    internal fun correctFrom(
        word: String,
        words: List<String>,
        vocabSet: Set<String>,
        learnedUni: Map<String, Int>,
        scanLimit: Int = 6000
    ): String? {
        if (word.length < 3) return null
        val fw = fold(word)
        if (vocabSet.contains(fw) || learnedUni.containsKey(word)) return null
        val maxDist = if (word.length <= 4) 1 else 2
        var best: String? = null
        var bestDist = maxDist + 1
        var bestRank = Int.MAX_VALUE
        val limit = minOf(scanLimit, words.size)
        for (rank in 0 until limit) {
            val w = words[rank]
            if (kotlin.math.abs(w.length - word.length) > maxDist) continue
            val d = editDistanceAtMost(fw, fold(w), maxDist)
            if (d <= maxDist && (d < bestDist || (d == bestDist && rank < bestRank))) {
                best = w; bestDist = d; bestRank = rank
                if (d == 1 && rank < 200) break // a very common, one-edit-away word is good enough
            }
        }
        return best
    }

    /** Top next-word predictions for [prev] from the learned bigram table. */
    internal fun nextFrom(prev: String, bigrams: Map<String, Map<String, Int>>, limit: Int): List<String> {
        if (prev.isEmpty() || limit <= 0) return emptyList()
        val m = bigrams[prev] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** Levenshtein distance between [a] and [b], capped: returns [max]+1 as soon as it is exceeded. */
    internal fun editDistanceAtMost(a: String, b: String, max: Int): Int {
        val n = a.length; val m = b.length
        if (kotlin.math.abs(n - m) > max) return max + 1
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,        // deletion
                    curr[j - 1] + 1,    // insertion
                    prev[j - 1] + cost  // substitution
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[m]
    }
}
