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
    // Bundled bigram model per language: prev-word -> followers, most-frequent first (from a corpus,
    // assets/dict/bigram_<lang>.txt). Gives next-word prediction / context from day one, before the
    // user's own [UserDictionary] bigrams have learned anything.
    private val bundledBigrams = HashMap<String, Map<String, List<String>>>()

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
                // Bundled bigram model (prev \t f1 f2 … ), if present for this language.
                val bg = HashMap<String, List<String>>()
                try {
                    context.assets.open("dict/bigram_$lang.txt").bufferedReader().useLines { seq ->
                        seq.forEach { line ->
                            val tab = line.indexOf('\t')
                            if (tab > 0 && tab < line.length - 1)
                                bg[line.substring(0, tab)] = line.substring(tab + 1).split(' ')
                        }
                    }
                } catch (_: Exception) { /* missing model -> no bundled context for this language */ }
                bundledBigrams[lang] = bg
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
        // Suggestions are computed off the main thread now, so the merged-vocab cache can be hit
        // from several threads — build/publish under a lock.
        synchronized(mergedByLangs) {
            mergedByLangs[key]?.let { return it }
            val parts = langs.toSortedSet().mapNotNull { byLang[it] }.filter { it.words.isNotEmpty() }
            if (parts.size <= 1) return parts.firstOrNull() ?: byLang["en"]
            val words = mergeRanked(parts.map { it.words })
            val folded = HashSet<String>(words.size * 2)
            words.forEach { folded.add(fold(it)) }
            return Vocab(words, folded).also { mergedByLangs[key] = it }
        }
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
    fun corrections(wordLower: String, langs: List<String>, learnedUni: Map<String, Int>, limit: Int = 2, maxEdits: Int = 0, prefer: Set<String> = emptySet()): List<String> {
        val v = vocab(langs) ?: return emptyList()
        return correctionsFrom(wordLower, v.words, v.set, learnedUni, limit, maxEdits = maxEdits, prefer = prefer)
    }

    /** Predicted next words after [prevLower], from the user's learned bigrams. */
    fun nextWords(prevLower: String, learnedBi: Map<String, Map<String, Int>>, limit: Int = 3): List<String> =
        nextFrom(prevLower, learnedBi, limit)

    /** Merged "what tends to follow [prevLower]" with weights, combining the bundled bigram model
     *  ([langs]) with the user's learned bigrams (weighted heavily, since personal). Empty when
     *  nothing is known. Drives next-word prediction, glide context and context-aware correction. */
    fun followerWeights(prevLower: String, langs: List<String>, learnedBi: Map<String, Map<String, Int>>): Map<String, Int> {
        val p = fold(prevLower)
        val out = HashMap<String, Int>()
        for (lang in langs) {
            val foll = bundledBigrams[lang]?.get(p) ?: continue
            for (i in foll.indices) {
                val weight = (8 - i).coerceAtLeast(1)   // rank 0 -> 8, … tail -> 1
                val w = foll[i]
                if (weight > (out[w] ?: 0)) out[w] = weight
            }
        }
        learnedBi[prevLower]?.forEach { (w, c) -> out[w] = (out[w] ?: 0) + c * 4 }   // personal wins
        return out
    }

    /** Context-aware next-word prediction: bundled model + learned bigrams, best first. */
    fun nextWords(prevLower: String, langs: List<String>, learnedBi: Map<String, Map<String, Int>>, limit: Int = 3): List<String> {
        val w = followerWeights(prevLower, langs, learnedBi)
        if (w.isEmpty()) return emptyList()
        return w.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

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
        scanLimit: Int = 6000,
        maxEdits: Int = 0,
        prefer: Set<String> = emptySet()
    ): List<String> {
        if (word.length < 3 || limit <= 0) return emptyList()
        val fw = fold(word)
        if (vocabSet.contains(fw) || learnedUni.containsKey(word)) return emptyList()
        // Default: 1 edit for short words, 2 for longer. [maxEdits] > 0 overrides — the spatial
        // autocorrect path asks for 2 even on short words, then filters to adjacent-key subs only.
        val maxDist = if (maxEdits > 0) maxEdits else if (word.length <= 4) 1 else 2
        // Each candidate scored by (distance, source rank). At equal distance: words that fit the
        // sentence context ([prefer], i.e. likely followers of the previous word) win (rank -2),
        // then the user's learned words (-1), then the frequency-ordered bundled list.
        val seen = HashSet<String>()
        val scored = ArrayList<Triple<String, Int, Int>>()
        for (lw in learnedUni.keys) {
            if (lw == word || kotlin.math.abs(lw.length - word.length) > maxDist) continue
            val d = editDistanceAtMost(fw, fold(lw), maxDist)
            if (d in 1..maxDist && seen.add(fold(lw))) scored.add(Triple(lw, d, if (lw in prefer) -2 else -1))
        }
        val n = minOf(scanLimit, words.size)
        for (rank in 0 until n) {
            val w = words[rank]
            if (kotlin.math.abs(w.length - word.length) > maxDist) continue
            val d = editDistanceAtMost(fw, fold(w), maxDist)
            if (d in 1..maxDist && seen.add(fold(w))) scored.add(Triple(w, d, if (w in prefer) -2 else rank))
        }
        return scored.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }.take(limit)
    }

    /** Strength of [prefixLower] as the start of a word in [langs]: 0 when no dictionary word
     *  starts with it, otherwise higher for more frequent words; the user's own learned words count
     *  strongly. Cheap enough to call on a keystroke — the bundled list is frequency-ordered, so
     *  the first hit is the best rank and the scan stops there. Drives probabilistic key targeting. */
    fun prefixStrength(prefixLower: String, langs: List<String>, learnedUni: Map<String, Int>): Float {
        val v = vocab(langs) ?: return 0f
        return prefixStrengthFrom(prefixLower, v.words, learnedUni)
    }

    internal fun prefixStrengthFrom(
        prefixLower: String,
        words: List<String>,
        learnedUni: Map<String, Int>,
        scanLimit: Int = 8000
    ): Float {
        if (prefixLower.isEmpty()) return 0f
        val fp = fold(prefixLower)
        var s = 0f
        for (w in learnedUni.keys) if (fold(w).startsWith(fp)) { s = 0.9f; break }
        val n = minOf(scanLimit, words.size)
        for (rank in 0 until n) {
            if (fold(words[rank]).startsWith(fp))
                return maxOf(s, 1f / (1f + kotlin.math.ln(1f + rank)))
        }
        return s
    }

    /** Gboard-style dynamic key targets: decide which key a tap near a key boundary actually meant.
     *  [cands] = the two nearest keys with their distances from the tap point in key-widths
     *  (nearest first); [strength] scores how well a key continues the word being typed (e.g.
     *  [prefixStrength] of prefix+key). Keeps [tapped] unless the alternative wins clearly on the
     *  combined spatial × language score — dead-centre taps are never second-guessed. */
    internal fun chooseKey(tapped: Char, cands: List<Pair<Char, Float>>, strength: (Char) -> Float): Char {
        if (cands.size < 2) return tapped
        val (a, da) = cands[0]
        val (b, db) = cands[1]
        if (tapped != a && tapped != b) return tapped
        if (db - da > 0.55f) return tapped          // comfortably inside the nearest key — unambiguous
        // Spatial confidence falls off with distance from the key centre; the 0.15 floor keeps
        // geometry meaningful even when neither letter continues a known word.
        fun f(c: Char, d: Float) = kotlin.math.exp(-d * d * 1.8f) * (0.15f + strength(c))
        val fa = f(a, da)
        val fb = f(b, db)
        val ft = if (tapped == a) fa else fb
        val fo = if (tapped == a) fb else fa
        return if (fo > ft * 1.3f) (if (tapped == a) b else a) else tapped
    }

    /** True when [b] differs from [a] only by 1–2 substitutions, each onto a *physically adjacent*
     *  key (per [neighbors]). I.e. [b] is a plausible fat-finger mistype of [a]. Same length only —
     *  insertions/deletions are handled by ordinary edit-distance correction, not the spatial path. */
    internal fun allAdjacentSubs(a: String, b: String, neighbors: Map<Char, Set<Char>>): Boolean {
        val fa = fold(a); val fb = fold(b)
        if (fa.length != fb.length || fa == fb) return false
        var subs = 0
        for (i in fa.indices) if (fa[i] != fb[i]) {
            subs++
            if (subs > 2) return false
            if (fb[i] !in (neighbors[fa[i]] ?: return false)) return false
        }
        return subs in 1..2
    }

    /** Choose a correction to auto-apply for [typed] on a word boundary, or null to leave it as-is.
     *  [cands] is ranked best-first (as from [corrections]). Accepts the top single-edit candidate
     *  (any edit kind — unchanged behaviour), OR a two-edit candidate when both edits are adjacent-key
     *  substitutions ([allAdjacentSubs]) — a confident double fat-finger the old single-edit rule missed. */
    internal fun pickAutocorrect(typed: String, cands: List<String>, neighbors: Map<Char, Set<Char>>): String? {
        val ft = fold(typed)
        for (c in cands) {
            when (editDistanceAtMost(ft, fold(c), 2)) {
                1 -> return c
                2 -> if (allAdjacentSubs(typed, c, neighbors)) return c
            }
        }
        return null
    }

    /** Top next-word predictions for [prev] from the learned bigram table. */
    internal fun nextFrom(prev: String, bigrams: Map<String, Map<String, Int>>, limit: Int): List<String> {
        if (prev.isEmpty() || limit <= 0) return emptyList()
        val m = bigrams[prev] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** Damerau-Levenshtein (optimal string alignment) distance between [a] and [b], capped: returns
     *  [max]+1 as soon as it is exceeded. A transposition of adjacent letters ("teh"→"the") counts
     *  as ONE edit — it's the most common fast-typing mistake, so plain Levenshtein (which scores it
     *  as two substitutions) would push such fixes out of autocorrect range. */
    internal fun editDistanceAtMost(a: String, b: String, max: Int): Int {
        val n = a.length; val m = b.length
        if (kotlin.math.abs(n - m) > max) return max + 1
        var prevPrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(
                    prev[j] + 1,        // deletion
                    curr[j - 1] + 1,    // insertion
                    prev[j - 1] + cost  // substitution
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                    v = minOf(v, prevPrev[j - 2] + 1)   // transposition of adjacent letters
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val tmp = prevPrev; prevPrev = prev; prev = curr; curr = tmp
        }
        return prev[m]
    }
}
