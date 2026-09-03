package com.keyo

import android.content.Context

/**
 * Offline word suggestions: prefix completion, spelling correction and next-word prediction.
 *
 * Two data sources are merged:
 *  - a bundled frequency word list per language (assets/dict/<lang>.txt, most-frequent first), and
 *  - the user's learned vocabulary ([UserDictionary]) which personalises and improves over time.
 *
 * The ranking/matching algorithms ([completeFrom], [correctionsFrom], [nextFrom], [editDistanceAtMost])
 * are pure functions that take their data as parameters, so they are unit-tested directly without a
 * Context. The public methods just feed them the loaded dictionary + learned maps.
 */
object SuggestionEngine {

    private class Vocab(val words: List<String>, val set: HashSet<String>)

    // Loaded lazily PER LANGUAGE (only the user's enabled languages are paid for in RAM — the
    // Russian dictionary + bigram model alone are several MB parsed). ConcurrentHashMap because
    // suggestions are computed on background dispatchers while another language may still be loading.
    private val byLang = java.util.concurrent.ConcurrentHashMap<String, Vocab>()
    // Bundled bigram model per language: prev-word -> followers, most-frequent first (from a corpus,
    // assets/dict/bigram_<lang>.txt). Gives next-word prediction / context from day one, before the
    // user's own [UserDictionary] bigrams have learned anything.
    private val bundledBigrams = java.util.concurrent.ConcurrentHashMap<String, Map<String, List<String>>>()
    // foldKey skeleton -> most frequent dictionary word that carries diacritics for that skeleton
    // (e.g. "cau" -> "čau", "ludzu" -> "lūdzu"). Backs Latvian-style diacritic restoration; built
    // lazily per language. Finds the word no matter how rare it is — NOT limited to top candidates.
    private val diacriticByLang = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun isReady(): Boolean = byLang.isNotEmpty()

    /** True once [lang]'s own dictionary is parsed. While false, vocab(lang) silently falls back to
     *  English — callers gating on this avoid English suggestions/autocorrect on e.g. a Latvian
     *  keyboard during the first moments after a cold start. */
    fun isLangLoaded(lang: String): Boolean = byLang.containsKey(lang)

    /** Drop everything parsed for languages the user no longer has enabled. Each one holds a word
     *  list, a folded lookup set, a bigram model and a diacritic index — several MB for Russian —
     *  and before this they stayed resident for the life of the process, so trying a language once
     *  and turning it off cost that memory forever. Reloading is a background parse if it ever
     *  comes back. Never drops the language being typed in. */
    fun unloadAllExcept(keep: Collection<String>) {
        val keepSet = keep.toSet()
        synchronized(this) {
            val gone = byLang.keys.filter { it !in keepSet }
            for (lang in gone) {
                byLang.remove(lang)
                bundledBigrams.remove(lang)
                diacriticByLang.remove(lang)
            }
        }
    }

    /** Loads the bundled dictionaries + bigram models for [langs] from assets (all languages when
     *  omitted). Call from a background thread; idempotent and cheap once loaded. */
    fun ensureLoaded(context: Context, langs: List<String> = listOf("en", "ru", "lv")) {
        for (lang in langs.distinct()) {
            if (byLang.containsKey(lang)) continue
            synchronized(this) {
                if (byLang.containsKey(lang)) return@synchronized
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
                byLang[lang] = Vocab(words, folded)   // publish last: isReady/vocab see a complete pair
                diacriticIndex(lang)   // pre-build here on the IO thread — the first word boundary
                                       // shouldn't pay a full-dict fold scan on the main thread
            }
        }
    }

    private fun vocab(lang: String): Vocab? = byLang[lang] ?: byLang["en"]

    /** Vocab for the active language. [langs] is a list only because a keyboard could once merge two
     *  dictionaries (the reverted EN+LV experiment); today it always holds exactly one language and
     *  anything past the first is ignored. Empty/unknown falls back to English. */
    private fun vocab(langs: List<String>): Vocab? {
        val lang = langs.firstOrNull() ?: return byLang["en"]
        return vocab(lang)
    }

    /** Completions that extend [prefixLower] (all lowercase), personalised words first. */
    fun complete(prefixLower: String, langs: List<String>, learnedUni: Map<String, Int>, limit: Int = 3): List<String> {
        val v = vocab(langs) ?: return emptyList()
        return completeFrom(prefixLower, v.words, learnedUni, limit)
    }

    /** Up to [limit] closest known words to [wordLower] (typo candidates), best first; empty if the
     *  word is already known. The user's learned words are included as high-priority targets. */
    fun corrections(wordLower: String, langs: List<String>, learnedUni: Map<String, Int>, limit: Int = 2, maxEdits: Int = 0, prefer: Set<String> = emptySet()): List<String> {
        val v = vocab(langs) ?: return emptyList()
        return correctionsFrom(wordLower, v.words, v.set, learnedUni, limit, maxEdits = maxEdits, prefer = prefer)
    }

    /** The followers of [prevLower] that the sentence context expects STRONGLY enough to override
     *  a word that is itself valid: the top two of the bundled model, plus personal pairs seen at
     *  least [minLearned] times. [followerWeights] cannot be used for this — there a personal pair
     *  weighs count × 4, so a pair typed TWICE already matched the bundled rank-0 follower, and
     *  "я что" became "я сто" after two "я сто процентов". */
    fun strongFollowers(prevLower: String, langs: List<String>, learnedBi: Map<String, Map<String, Int>>): Set<String> {
        val p = fold(prevLower)
        val bundled = ArrayList<String>(4)
        for (lang in langs) bundledBigrams[lang]?.get(p)?.let { bundled.addAll(it) }
        val learned = HashMap<String, Int>()
        for (key in if (p == prevLower) listOf(p) else listOf(prevLower, p)) {
            learnedBi[key]?.forEach { (w, c) -> learned[w] = maxOf(learned[w] ?: 0, c) }
        }
        return strongFollowersFrom(bundled, learned)
    }

    internal const val STRONG_LEARNED_MIN = 4

    internal fun strongFollowersFrom(bundled: List<String>, learned: Map<String, Int>, minLearned: Int = STRONG_LEARNED_MIN): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until minOf(2, bundled.size)) out.add(bundled[i])
        for ((w, c) in learned) if (c >= minLearned) out.add(w)
        return out
    }

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
        // Personal pairs win — and are looked up folded like the bundled model, so counts learned
        // after "ещё" still apply when the user types the (equally correct) "еще".
        learnedBi[prevLower]?.forEach { (w, c) -> out[w] = (out[w] ?: 0) + c * 4 }
        if (p != prevLower) learnedBi[p]?.forEach { (w, c) -> out[w] = (out[w] ?: 0) + c * 4 }
        return out
    }

    /** Context-aware next-word prediction: bundled model + learned bigrams, best first. */
    fun nextWords(prevLower: String, langs: List<String>, learnedBi: Map<String, Map<String, Int>>, limit: Int = 3): List<String> {
        val w = followerWeights(prevLower, langs, learnedBi)
        if (w.isEmpty()) return emptyList()
        return w.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** The frequency-ordered bundled word list for [langs] (most frequent first). For glide typing. */
    fun wordList(langs: List<String>): List<String> = vocab(langs)?.words ?: emptyList()

    fun isKnown(wordLower: String, langs: List<String>, learnedUni: Map<String, Int>): Boolean {
        if (learnedUni.containsKey(wordLower)) return true
        val f = fold(wordLower)
        // Personal words fold too (ё == е), the same way the bundled set does. Without this, a name
        // learned as "семёнов" left the everyday ё-less spelling "семенов" unknown — and so fair game
        // for autocorrect. Only worth scanning when an е/ё is actually in play.
        if ((wordLower.indexOf('е') >= 0 || wordLower.indexOf('ё') >= 0) &&
            learnedUni.keys.any { fold(it) == f }) return true
        return vocab(langs)?.set?.contains(f) == true
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

    /** Latvian-style diacritic restoration: the most frequent dictionary word whose base-letter
     *  skeleton equals [typedLower] but with diacritics added ("cau" → "čau", "ludzu" → "lūdzu"),
     *  or null if there is none (or [typedLower] already has them). Independent of frequency rank, so
     *  even a rare word like "čau" is found where the normal correction candidate list would miss it. */
    fun diacriticRestore(typedLower: String, langs: List<String>): String? {
        if (typedLower.isEmpty()) return null
        val key = foldKey(typedLower)
        for (l in langs) {
            val w = diacriticIndex(l)[key]
            if (w != null && w != typedLower) return w
        }
        return null
    }

    private fun diacriticIndex(lang: String): Map<String, String> {
        diacriticByLang[lang]?.let { return it }
        val v = byLang[lang] ?: return emptyMap()   // not loaded yet — don't cache an empty index
        val m = HashMap<String, String>(v.words.size)
        for (w in v.words) {                 // words are frequency-sorted → first wins (most frequent)
            val k = foldKey(w)
            if (k != w) m.putIfAbsent(k, w)  // only words that actually carry diacritics
        }
        diacriticByLang[lang] = m
        return m
    }

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
        // Deduplicated on the FOLDED form: a word learned as "ещё" and the bundled "еще" are one
        // word, and showing both spent two of the three chips on it.
        val seen = HashSet<String>()
        val out = ArrayList<String>(limit)
        // Collect the learned matches first, THEN sort just those. Sorting the whole personal
        // dictionary — up to 4000 entries — ran on every keystroke, while the number of words that
        // actually start with the prefix is nearly always a handful.
        val hits = ArrayList<Map.Entry<String, Int>>(8)
        for (e in learnedUni.entries) {
            if (e.key.length > prefix.length && fold(e.key).startsWith(fp)) hits.add(e)
        }
        if (hits.size > 1) hits.sortByDescending { it.value }
        for (e in hits) { if (out.size >= limit) break; if (seen.add(fold(e.key))) out.add(e.key) }
        if (out.size < limit) {
            for (w in words) {
                if (w.length > prefix.length) {
                    val fwd = fold(w)
                    if (fwd.startsWith(fp) && seen.add(fwd)) {
                        out.add(w)
                        if (out.size >= limit) break
                    }
                }
            }
        }
        return out
    }

    /** How deep the rank lookup below is willing to look. Rank is only ever used to compare two
     *  candidates roughly, so anything past this counts as "rare" and the answer is the same. */
    internal const val RANK_SCAN = 8000

    /** Frequency rank of [word] in the bundled list; [RANK_SCAN] when it is rarer than that or
     *  absent. The list is rank-ordered so a common word is found almost immediately, and the bound
     *  keeps the miss case from walking all 48k entries — this runs on every keystroke. */
    internal fun rankIn(word: String, words: List<String>): Int {
        val n = minOf(RANK_SCAN, words.size)
        for (i in 0 until n) if (words[i] == word) return i
        return RANK_SCAN
    }

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
        if (vocabSet.contains(fw) || learnedUni.containsKey(word) || learnedUni.containsKey(fw)) return emptyList()
        // Personal words protect their ё/е twin exactly as [isKnown] says they do. Without this a
        // name learned as "артём" left "артем" unknown here — and since the learned form itself is
        // skipped (it is the word, distance 0), the typed one was "corrected" into "прием".
        if ((word.indexOf('е') >= 0 || word.indexOf('ё') >= 0) &&
            learnedUni.keys.any { fold(it) == fw }) return emptyList()
        // Context is matched folded too: the bundled bigram model spells followers the corpus way
        // ("еще"), the cleaned word list spells them the dictionary way ("ещё").
        val preferF = if (prefer.isEmpty()) prefer else prefer.mapTo(HashSet(prefer.size * 2)) { fold(it) }
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
            if (d in 1..maxDist && seen.add(fold(lw))) scored.add(Triple(lw, d, if (fold(lw) in preferF) -2 else -1))
        }
        // The list is frequency-ordered, so the fix for a common word is found in the first few
        // thousand entries and scanning further is wasted work on the typing path. But a word that
        // is merely uncommon sits far down it — "подсказки" is rank 20214 of 48249 — and stopping at
        // the limit meant those words simply could not be corrected, however obvious the typo.
        // So: scan the cheap prefix first, and only if that found no single-edit fix (the thing
        // autocorrect actually applies) pay for the rest of the dictionary.
        var bestDist = maxDist + 1
        // [endsOnly] restricts the scan to words sharing this one's first or last letter. For a
        // SINGLE edit that is lossless — one edit cannot change both ends of a word — and it is all
        // the deep pass is looking for. It cuts that pass by more than an order of magnitude, which
        // matters because it runs on the main thread at a word boundary.
        fun scan(from: Int, to: Int, endsOnly: Boolean) {
            val first = fw.first()
            val last = fw.last()
            for (rank in from until to) {
                val w = words[rank]
                if (kotlin.math.abs(w.length - word.length) > maxDist) continue
                val fwd = fold(w)
                if (endsOnly && fwd.first() != first && fwd.last() != last) continue
                val d = editDistanceAtMost(fw, fwd, maxDist)
                if (d in 1..maxDist && seen.add(fwd)) {
                    scored.add(Triple(w, d, if (fwd in preferF) -2 else rank))
                    if (d < bestDist) bestDist = d
                }
            }
        }
        val n = minOf(scanLimit, words.size)
        scan(0, n, endsOnly = false)
        if (bestDist > 1 && n < words.size) scan(n, words.size, endsOnly = true)
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

    /** Fingers land BELOW the point the user is aiming at — the contact patch sits behind the
     *  fingertip — so a key's effective target centre is this fraction of a key height lower than
     *  its drawn centre. Only affects the spatial tie-breaking below, never which key Android's own
     *  hit-testing reports, so an imperfect value can't misroute a plain tap. */
    const val TOUCH_BIAS_Y = 0.12f

    /** Distance from a touch to a key centre measured in CELLS: horizontal error normalised by key
     *  width, vertical by key height. Keyboard cells aren't square (a key is wider than it is tall,
     *  or the reverse), and the old width-only normalisation therefore understated vertical error —
     *  a tap barely below centre already put the key in the row below into contention. */
    fun cellDistance(
        px: Float, py: Float, cx: Float, cy: Float,
        keyW: Float, keyH: Float, biasY: Float = TOUCH_BIAS_Y
    ): Float {
        val dx = (px - cx) / keyW
        val dy = (py - (cy + biasY * keyH)) / keyH
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Gboard-style dynamic key targets: decide which key a tap near a key boundary actually meant.
     *  [cands] = the two nearest keys with their distances from the tap point in cells
     *  (nearest first); [strength] scores how well a key continues the word being typed (e.g.
     *  [prefixStrength] of prefix+key). Keeps [tapped] unless the alternative wins clearly on the
     *  combined spatial × language score — dead-centre taps are never second-guessed. */
    /** How close the runner-up must be before it is even considered, and how decisively it must win
     *  to override the key the finger actually landed on.
     *
     *  Both were hand-picked (0.55 / 1.3) until SpatialTuningTest swept them. Loosening helps on both
     *  the toy and the real 8000-word dictionary (+1.3 and +3.7 points respectively) and — the part
     *  that had to be checked before adopting it — costs nothing on words the dictionary does NOT
     *  know: names and slang come out mangled 2.1% of the time either way, because a careful finger
     *  lands deep inside its key and never reaches the gate at all. The curve keeps improving down to
     *  margin 1.0, but that would mean the tap loses every tie; 1.10 keeps the finger's own choice
     *  winning unless the language evidence is clearly against it. */
    internal const val NEAR_GATE = 0.75f
    internal const val WIN_MARGIN = 1.10f

    internal fun chooseKey(
        tapped: Char,
        cands: List<Pair<Char, Float>>,
        nearGate: Float = NEAR_GATE,
        winMargin: Float = WIN_MARGIN,
        strength: (Char) -> Float
    ): Char {
        if (cands.size < 2) return tapped
        val (a, da) = cands[0]
        val (b, db) = cands[1]
        if (tapped != a && tapped != b) return tapped
        if (db - da > nearGate) return tapped       // comfortably inside the nearest key — unambiguous
        // Spatial confidence falls off with distance from the key centre; the 0.15 floor keeps
        // geometry meaningful even when neither letter continues a known word.
        fun f(c: Char, d: Float) = kotlin.math.exp(-d * d * 1.8f) * (0.15f + strength(c))
        val fa = f(a, da)
        val fb = f(b, db)
        val ft = if (tapped == a) fa else fb
        val fo = if (tapped == a) fb else fa
        return if (fo > ft * winMargin) (if (tapped == a) b else a) else tapped
    }

    /** Re-rank completions by sentence context (stable): completions that the bundled/learned
     *  bigrams expect after the previous word come first, then — for Russian — a light
     *  number-agreement nudge: after a plural determiner/adjective ("какие", "эти", "новые", …)
     *  plural-looking forms (-и/-ы) outrank singular ones, so "какие иде…" completes to "идеи",
     *  not "идея". Order-only — it never invents or drops candidates. */
    internal fun rankCompletions(comps: List<String>, prevLower: String?, prefer: Set<String>): List<String> {
        if (comps.size < 2) return comps
        val p = prevLower ?: ""
        // A closed list of plural determiners and pronouns. It used to be "anything ending in -ие",
        // which is also the ending of hundreds of singular neuter nouns — "здание", "решение",
        // "мнение" — so after "здание" the strip pushed "стояли" above "стоять".
        val plural = p in PLURAL_DETERMINERS || (p.length >= 4 && p.endsWith("ые"))
        val preferF = if (prefer.isEmpty()) prefer else prefer.mapTo(HashSet(prefer.size * 2)) { fold(it) }
        return comps.sortedWith(
            compareByDescending<String> { fold(it) in preferF }
                .thenByDescending { plural && (it.endsWith("и") || it.endsWith("ы")) }
        )
    }

    private val PLURAL_DETERMINERS = hashSetOf(
        "все", "эти", "те", "какие", "такие", "другие", "многие", "некоторые", "любые", "сами",
        "мои", "твои", "наши", "ваши", "свои", "его", "её", "их", "оба", "обе", "несколько", "много"
    )

    /** True when [b] is a single ADJACENT-KEY substitution or a single transposition of [a] — the
     *  only slips confident enough to let context override a word that is itself valid. */
    internal fun isConfidentSlip(a: String, b: String, neighbors: Map<Char, Set<Char>>): Boolean {
        val fa = fold(a); val fb = fold(b)
        if (fa == fb || fa.length != fb.length) return false
        val diffs = fa.indices.filter { fa[it] != fb[it] }
        return when (diffs.size) {
            1 -> fb[diffs[0]] in (neighbors[fa[diffs[0]]] ?: emptySet())
            2 -> diffs[1] == diffs[0] + 1 &&
                 fa[diffs[0]] == fb[diffs[1]] && fa[diffs[1]] == fb[diffs[0]]
            else -> false
        }
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

    /** For a word this short or shorter, a single-edit candidate is only applied when it is at least
     *  this common (or personal / expected by context). Short words are where autocorrect does its
     *  damage: "riga" is one edit from "rita" (rank 12756), "figma" from "sigma", "keyo" from "key"
     *  — and a 3-5 letter word simply does not carry enough evidence to override the finger with a
     *  word the user has plausibly never seen. A longer word is different: one edit away from a
     *  9-letter dictionary word IS strong evidence, however rare the word ("подсказкт"). */
    internal const val SHORT_WORD_MAX_LEN = 5
    internal const val SHORT_WORD_RANK_CAP = 4000
    private const val VOWELS = "aeiouyаеёиоуыэюяāēīūàáâäèéêëìíîïòóôöùúûü"

    /** Choose a correction to auto-apply for [typed] on a word boundary, or null to leave it as-is.
     *  [cands] is ranked best-first (as from [corrections]). Accepts the top single-edit candidate
     *  (any edit kind), OR a two-edit candidate when both edits are adjacent-key substitutions
     *  ([allAdjacentSubs]) — a confident double fat-finger the old single-edit rule missed.
     *  [rankOf] is the candidate's frequency rank, 0 for words that need no such evidence (learned,
     *  or expected by the sentence context); it gates short words, see [SHORT_WORD_RANK_CAP]. */
    internal fun pickAutocorrect(
        typed: String,
        cands: List<String>,
        neighbors: Map<Char, Set<Char>>,
        rankOf: (String) -> Int = { 0 }
    ): String? {
        val ft = fold(typed)
        // "Wi-Fi" → "wifi", "co-op" → "coop", "rock'n'roll" → "rocknroll": a candidate that is the
        // typed word with its hyphens/apostrophes stripped is not a correction of anything — the
        // punctuation was typed on purpose, and the edit distance only sees it as a deletion.
        val hasPunct = typed.indexOf('-') >= 0 || typed.indexOf('\'') >= 0
        val stripped = if (hasPunct) ft.filter { it != '-' && it != '\'' } else ft
        val short = typed.length <= SHORT_WORD_MAX_LEN
        // A short string with a vowel in it could be a word the dictionary lacks ("figma", "riga");
        // one without ("rwst") could not, and may be repaired more boldly.
        val wordLike = typed.any { it.lowercaseChar() in VOWELS }
        val singles = ArrayList<String>()
        for (c in cands) {
            val fc = fold(c)
            if (hasPunct && fc == stripped) continue
            if (short && rankOf(c) >= SHORT_WORD_RANK_CAP) continue
            when (editDistanceAtMost(ft, fc, 2)) {
                1 -> singles.add(c)
                // Two edits need more letters of evidence than a short WORD-LIKE string has: in a
                // 5-letter word that is 40% of it changed, and "figma" is two adjacent slips from
                // "firms". "rwst" is not a word in any reading, so it may still become "test".
                2 -> if ((!short || !wordLike) && singles.isEmpty() && allAdjacentSubs(typed, c, neighbors)) return c
            }
        }
        // Among equally-close candidates, prefer the one whose changed letter sits on a key NEXT TO
        // the one that was typed — that is what a mistype physically is. "подсказкт" is one edit
        // from both "подсказку" and "подсказки", but и is the key beside т while у is nowhere near
        // it, so и is the fix the finger meant. Frequency order decides only when no candidate is a
        // neighbour (a dropped or doubled letter, a transposition).
        return singles.firstOrNull { isConfidentSlip(typed, it, neighbors) } ?: singles.firstOrNull()
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
