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
    // Cache of merged dictionaries keyed by the sorted language set (e.g. "en+lv"), so a
    // multilingual keyboard (English + Latvian share one Latin layout) builds the union once.
    private val mergedByLangs = HashMap<String, Vocab>()

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

    /** Vocab covering every language in [langs], merged. One language returns its own Vocab; several
     *  (e.g. English + Latvian on a shared Latin keyboard) are interleaved by frequency rank and
     *  deduped, then cached. Empty/unknown langs fall back to English. */
    private fun vocab(langs: List<String>): Vocab? {
        if (langs.size <= 1) return vocab(langs.firstOrNull() ?: return byLang["en"])
        val key = langs.toSortedSet().joinToString("+")
        mergedByLangs[key]?.let { return it }
        val parts = langs.toSortedSet().mapNotNull { byLang[it] }.filter { it.words.isNotEmpty() }
        if (parts.size <= 1) return parts.firstOrNull() ?: byLang["en"]
        val words = mergeRanked(parts.map { it.words })
        val folded = HashSet<String>(words.size * 2)
        words.forEach { folded.add(fold(it)) }
        return Vocab(words, folded).also { mergedByLangs[key] = it }
    }

    /** Completions that extend [prefixLower] (all lowercase), personalised words first. */
    fun complete(prefixLower: String, lang: String, learnedUni: Map<String, Int>, limit: Int = 3): List<String> =
        complete(prefixLower, listOf(lang), learnedUni, limit)
    fun complete(prefixLower: String, langs: List<String>, learnedUni: Map<String, Int>, limit: Int = 3): List<String> {
        val v = vocab(langs) ?: return emptyList()
        return completeFrom(prefixLower, v.words, learnedUni, limit)
    }

    /** Best correction for [wordLower], or null if it is already known or nothing close is found. */
    fun correct(wordLower: String, lang: String, learnedUni: Map<String, Int>): String? =
        correct(wordLower, listOf(lang), learnedUni)
    fun correct(wordLower: String, langs: List<String>, learnedUni: Map<String, Int>): String? {
        val v = vocab(langs) ?: return null
        return correctFrom(wordLower, v.words, v.set, learnedUni)
    }

    /** Up to [limit] closest known words to [wordLower] (typo candidates), best first; empty if the
     *  word is already known. The user's learned words are included as high-priority targets. */
    fun corrections(wordLower: String, lang: String, learnedUni: Map<String, Int>, limit: Int = 2): List<String> =
        corrections(wordLower, listOf(lang), learnedUni, limit)
    fun corrections(wordLower: String, langs: List<String>, learnedUni: Map<String, Int>, limit: Int = 2): List<String> {
        val v = vocab(langs) ?: return emptyList()
        return correctionsFrom(wordLower, v.words, v.set, learnedUni, limit)
    }

    /** Predicted next words after [prevLower], from the user's learned bigrams. */
    fun nextWords(prevLower: String, learnedBi: Map<String, Map<String, Int>>, limit: Int = 3): List<String> =
        nextFrom(prevLower, learnedBi, limit)

    /** The frequency-ordered bundled word list for [langs] (most frequent first). For glide typing. */
    fun wordList(lang: String): List<String> = vocab(lang)?.words ?: emptyList()
    fun wordList(langs: List<String>): List<String> = vocab(langs)?.words ?: emptyList()

    fun isKnown(wordLower: String, lang: String, learnedUni: Map<String, Int>): Boolean =
        isKnown(wordLower, listOf(lang), learnedUni)
    fun isKnown(wordLower: String, langs: List<String>, learnedUni: Map<String, Int>): Boolean {
        if (learnedUni.containsKey(wordLower)) return true
        return vocab(langs)?.set?.contains(fold(wordLower)) == true
    }

    /** Treat ё and е as the same letter (Russian text routinely omits ё). No-op for en/lv. */
    internal fun fold(s: String): String =
        if (s.indexOf('ё') >= 0 || s.indexOf('Ё') >= 0) s.replace('ё', 'е').replace('Ё', 'Е') else s

    /** Accented letters reachable via long-press, mapped to the base key they sit on. A glide only
     *  ever crosses base keys (you can't slide to a long-press accent), so for glide matching a word
     *  like "ēst" is the e-s-t skeleton. Mirrors the accents in KeyoService.altChars. */
    private val accentBase = mapOf(
        'ā' to 'a','à' to 'a','á' to 'a','â' to 'a','ã' to 'a','å' to 'a','æ' to 'a',
        'ē' to 'e','è' to 'e','é' to 'e','ê' to 'e','ë' to 'e','ė' to 'e','ę' to 'e',
        'ī' to 'i','ì' to 'i','í' to 'i','î' to 'i','ï' to 'i','į' to 'i',
        'ō' to 'o','ö' to 'o','ò' to 'o','ó' to 'o','ô' to 'o','õ' to 'o','ø' to 'o',
        'ū' to 'u','ü' to 'u','ù' to 'u','ú' to 'u','û' to 'u','ų' to 'u',
        'š' to 's','ś' to 's','ß' to 's',
        'č' to 'c','ç' to 'c','ć' to 'c',
        'ņ' to 'n','ñ' to 'n','ń' to 'n',
        'ž' to 'z','ź' to 'z','ż' to 'z',
        'ģ' to 'g','ğ' to 'g',
        'ķ' to 'k',
        'ļ' to 'l','ł' to 'l',
        'ŗ' to 'r',
        'ý' to 'y','ÿ' to 'y',
        'đ' to 'd'
    )

    /** Fold for glide geometry/matching: [fold] (ё→е) plus every long-press accent collapsed to its
     *  base key, so diacritic words (Latvian ā č ē ģ ī ķ ļ ņ š ū ž …) match a base-letter swipe. Used
     *  ONLY for glide — tap typing and suggestions keep ē and e distinct via [fold]. */
    fun foldKey(s: String): String {
        val f = fold(s)
        var i = 0
        while (i < f.length) { if (accentBase.containsKey(f[i])) break; i++ }
        if (i == f.length) return f   // pure base letters — nothing to collapse
        val sb = StringBuilder(f.length)
        for (c in f) sb.append(accentBase[c] ?: c)
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------------
    // Pure algorithms (no Android dependency) — unit-tested directly.
    // ---------------------------------------------------------------------------------------------

    /** Merge several frequency-ordered word lists into one. Round-robins by rank (each list's rank-0
     *  word, then every rank-1 word, …) so both languages keep equal footing, and dedupes by exact
     *  word — the first (best-ranked) occurrence wins. Backs the bilingual Latin keyboard. */
    internal fun mergeRanked(lists: List<List<String>>): List<String> {
        val parts = lists.filter { it.isNotEmpty() }
        if (parts.size <= 1) return parts.firstOrNull() ?: emptyList()
        val out = ArrayList<String>(parts.sumOf { it.size })
        val seen = HashSet<String>(out.size)
        var i = 0
        var more = true
        while (more) {
            more = false
            for (l in parts) if (i < l.size) { val w = l[i]; if (seen.add(w)) out.add(w); more = true }
            i++
        }
        return out
    }

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

    /** Closest dictionary/learned word to [word] within a small edit distance, or null when [word]
     *  is already known. Thin wrapper over [correctionsFrom]. */
    internal fun correctFrom(
        word: String,
        words: List<String>,
        vocabSet: Set<String>,
        learnedUni: Map<String, Int>,
        scanLimit: Int = 6000
    ): String? = correctionsFrom(word, words, vocabSet, learnedUni, 1, scanLimit).firstOrNull()

    /** Ranked typo corrections for [word]: closest edit distance first, and at equal distance the
     *  user's learned words rank above the bundled list, which is ordered by frequency. Returns an
     *  empty list when [word] is already known (bundled or learned) or shorter than 3 letters.
     *  Scans the user's whole learned vocab plus the most frequent [scanLimit] bundled words. */
    internal fun correctionsFrom(
        word: String,
        words: List<String>,
        vocabSet: Set<String>,
        learnedUni: Map<String, Int>,
        limit: Int = 2,
        scanLimit: Int = 6000
    ): List<String> {
        if (word.length < 3 || limit <= 0) return emptyList()
        val fw = fold(word)
        if (vocabSet.contains(fw) || learnedUni.containsKey(word)) return emptyList()
        val maxDist = if (word.length <= 4) 1 else 2
        // Each candidate scored by (distance, source rank). Learned words use rank -1 so they win ties.
        val seen = HashSet<String>()
        val scored = ArrayList<Triple<String, Int, Int>>()
        for (lw in learnedUni.keys) {
            if (lw == word || kotlin.math.abs(lw.length - word.length) > maxDist) continue
            val d = editDistanceAtMost(fw, fold(lw), maxDist)
            if (d in 1..maxDist && seen.add(fold(lw))) scored.add(Triple(lw, d, -1))
        }
        val n = minOf(scanLimit, words.size)
        for (rank in 0 until n) {
            val w = words[rank]
            if (kotlin.math.abs(w.length - word.length) > maxDist) continue
            val d = editDistanceAtMost(fw, fold(w), maxDist)
            if (d in 1..maxDist && seen.add(fold(w))) scored.add(Triple(w, d, rank))
        }
        return scored.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }.take(limit)
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
