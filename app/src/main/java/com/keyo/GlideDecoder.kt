package com.keyo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

// Pure geometry over a snapshot of the key bounds: no keyboard state, safe on any thread.

/** Physical plausibility of [cand] as a SUBSTITUTION slip of [typed]: the WORST distance (in
 *  cells — see SuggestionEngine.cellDistance) between the finger's tap and [cand]'s key, over only the positions where they
 *  differ. Small = the finger really hovered near the other key at every differing spot.
 *  Judged on the worst position, never the average — an average dilutes over the matching
 *  letters and would favour a wrong substitution over a true transposition (device-verified:
 *  "teh" was "corrected" to "ten" instead of "the"). Null when not a same-length substitution
 *  pattern or any tap/key is missing. */
internal fun spatialSlipCost(typed: String, cand: String, points: List<Offset>, bounds: Map<Char, Rect>): Float? {
    if (cand.length != typed.length || typed.length != points.size || bounds.isEmpty()) return null
    val ft = SuggestionEngine.foldKey(typed)
    val fc = SuggestionEngine.foldKey(cand)
    if (ft.length != fc.length) return null
    var kwSum = 0f
    var khSum = 0f
    for (r in bounds.values) { kwSum += r.width; khSum += r.height }
    val kw = (kwSum / bounds.size).coerceAtLeast(1f)
    val kh = (khSum / bounds.size).coerceAtLeast(1f)
    var worst = -1f
    for (i in fc.indices) {
        if (ft[i] == fc[i]) continue
        val ctr = bounds[cand[i]]?.center ?: bounds[fc[i]]?.center ?: return null
        val d = SuggestionEngine.cellDistance(points[i].x, points[i].y, ctr.x, ctr.y, kw, kh)
        if (d > worst) worst = d
    }
    return if (worst < 0f) null else worst
}

/** Resample a polyline to exactly [n] points spaced evenly along its length (a $1-recognizer step). */
internal fun resample(pts: List<Offset>, n: Int): List<Offset> {
    if (pts.isEmpty()) return emptyList()
    if (pts.size == 1) return List(n) { pts[0] }
    var total = 0f
    for (i in 1 until pts.size) total += (pts[i] - pts[i - 1]).getDistance()
    if (total <= 0f) return List(n) { pts[0] }
    val interval = total / (n - 1)
    val out = ArrayList<Offset>(n)
    out.add(pts[0])
    var prev = pts[0]; var acc = 0f; var i = 1
    while (i < pts.size && out.size < n) {
        val curr = pts[i]; val seg = (curr - prev).getDistance()
        if (acc + seg >= interval && seg > 0f) {
            val t = (interval - acc) / seg
            val np = Offset(prev.x + t * (curr.x - prev.x), prev.y + t * (curr.y - prev.y))
            out.add(np); prev = np; acc = 0f
        } else { acc += seg; prev = curr; i++ }
    }
    while (out.size < n) out.add(pts.last())
    return out
}

/**
 * Decode a glide path (window coords) into up to 3 candidate words, best first.
 *
 * A word is scored by how well the stroke can be explained as "the finger went to each of its
 * keys in turn", as one alignment solved exactly (dynamic programming over the resampled path):
 *
 *  - KEYS: every key of the word is visited, in order, the first at the touch-down and the last at
 *    the lift-off — the squared distance from each key to the path point it is matched with;
 *  - PATH: every stretch of the stroke between two matched keys runs along the straight line
 *    between them — the squared distance of the path from that line, integrated along the stroke.
 *    This is what tells "the" from "there" and "in" from "information": a short word leaves most
 *    of a long stroke unexplained;
 *  - a frequency prior, and the sentence context when there is one.
 *
 * The matching used to be greedy (each key took its nearest path point from where the previous
 * one stopped), which fell apart on any word that visits a key twice — "data", "there", "тебе" —
 * because the first visit grabbed the stroke's end; and it was averaged per key, so a long word's
 * one wild miss was diluted into nothing ("привет" lost to "писает"). Measured in
 * GlideAccuracyTest against the real dictionaries.
 */
internal fun decodeGlide(
    path: List<Offset>,
    followers: Map<String, Int>?,
    bounds: Map<Char, Rect>,
    langs: List<String>
): List<String> =
    if (!SuggestionEngine.isReady()) emptyList()
    else decodeGlideOver(path, followers, bounds, SuggestionEngine.wordList(langs))

/** The weights of the glide score, in one place so the harness can sweep them. Distances are in
 *  key widths, so every term is in the same unit. */
internal class GlideParams(
    /** Each key's squared distance to the path point it is matched with. */
    val key: Float = 1.0f,
    /** The first key weighs extra: a finger LANDS on its first key with tap accuracy. */
    val start: Float = 1.0f,
    /** Squared distance of the stroke from the word's key-to-key line, integrated over its length. */
    val path: Float = 1.0f,
    /** Stroke the word does not account for: how much LONGER the finger travelled than the word's
     *  key-to-key route, per key width. Only that direction — a correct stroke is routinely shorter
     *  than its route (corners are cut), but it is never much longer. Without it a detour to the
     *  next key and back costs almost nothing, being close to the line already drawn: "there"
     *  (t-h-e-r-e) read as "the" with a wobble at the end. */
    val extra: Float = 0.3f,
    /** ln(rank) — how much a word's frequency buys against geometry. */
    val freq: Float = 0.15f,
    /** A key may be missed by this much for free: corners are cut, the finger turns early. */
    val slack: Float = 0.15f,
    /** Bonus per unit of sentence-context weight (see followerWeights), capped at [contextCap]. */
    val context: Float = 0.08f,
    val contextCap: Int = 8
) {
    companion object { val DEFAULT = GlideParams() }
}

/** Per word list: the key skeleton of every word (base keys, doubled letters collapsed — a glide
 *  crosses a repeated letter once) and the ranks grouped by first+last key, so a decode only ever
 *  looks at the words that start and end where the stroke did. Built once per list (one pass). */
private class GlideIndex(words: List<String>) {
    val skeleton = arrayOfNulls<String>(words.size)
    val byEnds = HashMap<Int, IntArray>()

    init {
        val buckets = HashMap<Int, ArrayList<Int>>()
        val sb = StringBuilder()
        for (rank in words.indices) {
            val fw = SuggestionEngine.foldKey(words[rank])
            var doubled = false
            for (i in 1 until fw.length) if (fw[i] == fw[i - 1]) { doubled = true; break }
            val sk = if (!doubled) fw else {
                sb.setLength(0)
                for (i in fw.indices) if (i == 0 || fw[i] != fw[i - 1]) sb.append(fw[i])
                sb.toString()
            }
            if (sk.length < 2) continue
            skeleton[rank] = sk
            buckets.getOrPut(endsKey(sk[0], sk[sk.length - 1])) { ArrayList() }.add(rank)
        }
        for ((k, list) in buckets) byEnds[k] = list.toIntArray()
    }

    companion object { fun endsKey(first: Char, last: Char): Int = (first.code shl 16) or last.code }
}

// The list and its index travel together in ONE reference: two decodes run concurrently (the live
// preview and the final one), and with separate fields a language switch between them could pair
// one list with the other's index.
private class CachedGlideIndex(val words: List<String>, val index: GlideIndex)
@Volatile private var glideIndexCache: CachedGlideIndex? = null
private fun glideIndex(words: List<String>): GlideIndex {
    glideIndexCache?.let { if (it.words === words) return it.index }
    val built = GlideIndex(words)
    glideIndexCache = CachedGlideIndex(words, built)
    return built
}

/** Squared distance from point (px,py) to the segment (ax,ay)-(bx,by). */
private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val vx = bx - ax; val vy = by - ay
    val len2 = vx * vx + vy * vy
    val t = if (len2 <= 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
    val dx = px - (ax + t * vx); val dy = py - (ay + t * vy)
    return dx * dx + dy * dy
}

/** [decodeGlide] over an explicit word list, most frequent first. */
internal fun decodeGlideOver(
    path: List<Offset>,
    followers: Map<String, Int>?,
    bounds: Map<Char, Rect>,
    words: List<String>,
    params: GlideParams = GlideParams.DEFAULT
): List<String> {
    if (path.size < 2 || bounds.isEmpty() || words.isEmpty()) return emptyList()
    // Self-contained over the snapshotted [bounds] so it can run on a background dispatcher
    // while the live keyBounds keep mutating with the layout.
    var kwSum = 0f
    for (r in bounds.values) kwSum += r.width
    val keyW = (kwSum / bounds.size).coerceAtLeast(1f)
    var pathLen = 0f
    for (i in 1 until path.size) pathLen += (path[i] - path[i - 1]).getDistance()
    if (pathLen <= 0f) return emptyList()
    // The stroke as the decoder sees it: the pointer samples themselves, thinned where the finger
    // dawdled and filled in where it flew, so neighbours are never more than a third of a key
    // apart. NOT an even resample: that steps right over the tip of a reversal — the one sample
    // that says how far the finger actually went — and a slow finger leaves several there.
    val minGap = maxOf(keyW * 0.1f, pathLen / 120f)
    val maxGap = maxOf(keyW * 0.3f, minGap * 2f)
    val pts = ArrayList<Offset>(path.size + 16)
    pts.add(path[0])
    for (i in 1 until path.size) {
        val p = path[i]
        val from = pts[pts.size - 1]
        val d = (p - from).getDistance()
        if (d < minGap && i != path.size - 1) continue
        val steps = kotlin.math.ceil(d / maxGap).toInt()
        for (s in 1 until steps) pts.add(from + (p - from) * (s.toFloat() / steps))
        pts.add(p)
    }
    val n = pts.size
    if (n < 2) return emptyList()
    val px = FloatArray(n) { pts[it].x / keyW }
    val py = FloatArray(n) { pts[it].y / keyW }
    // The stretch of stroke each point stands for (half the way to each neighbour), in key widths.
    val ds = FloatArray(n) { j ->
        var d = 0f
        if (j > 0) d += kotlin.math.sqrt((px[j] - px[j - 1]) * (px[j] - px[j - 1]) + (py[j] - py[j - 1]) * (py[j] - py[j - 1]))
        if (j < n - 1) d += kotlin.math.sqrt((px[j + 1] - px[j]) * (px[j + 1] - px[j]) + (py[j + 1] - py[j]) * (py[j + 1] - py[j]))
        d / 2f
    }

    // Keys, indexed densely; per key the squared distance to every path point (shared by every
    // candidate), and the closest the stroke ever came to it (a cheap way to rule a word out).
    val keys = bounds.keys.toCharArray()
    val kx = FloatArray(keys.size) { bounds.getValue(keys[it]).center.x / keyW }
    val ky = FloatArray(keys.size) { bounds.getValue(keys[it]).center.y / keyW }
    val indexOf = HashMap<Char, Int>(keys.size * 2)
    for (i in keys.indices) indexOf[keys[i]] = i
    val slack = params.slack
    val keyDist = Array(keys.size) { k ->
        FloatArray(n) { j ->
            val dx = px[j] - kx[k]; val dy = py[j] - ky[k]
            val d = kotlin.math.sqrt(dx * dx + dy * dy) - slack
            if (d <= 0f) 0f else d * d
        }
    }
    val nearest = FloatArray(keys.size) { k -> var m = Float.MAX_VALUE; for (v in keyDist[k]) if (v < m) m = v; m }
    // Stroke-to-line distances per key PAIR, filled in as candidates ask for them: the same letter
    // pairs come up in word after word.
    val segCache = HashMap<Int, FloatArray>()
    fun segCum(a: Int, b: Int): FloatArray = segCache.getOrPut(a * 64 + b) {
        // cum[j] = the squared distance of the stroke from the line a-b, integrated up to point j
        val cum = FloatArray(n)
        var acc = 0f
        for (j in 0 until n) { acc += segDist2(px[j], py[j], kx[a], ky[a], kx[b], ky[b]) * ds[j]; cum[j] = acc }
        cum
    }

    // Only words that start near the touch-down and end near the lift-off. The lift-off net is
    // wider: a finger lands on its first key but leaves the last one in passing.
    fun near(j: Int, reach: Float): List<Int> {
        val r2 = reach * reach
        val out = ArrayList<Int>(6)
        for (k in keys.indices) {
            val dx = px[j] - kx[k]; val dy = py[j] - ky[k]
            if (dx * dx + dy * dy <= r2) out.add(k)
        }
        if (out.isEmpty()) {   // touch outside every key's reach: fall back to the nearest one
            var best = 0; var bd = Float.MAX_VALUE
            for (k in keys.indices) {
                val dx = px[j] - kx[k]; val dy = py[j] - ky[k]
                val d = dx * dx + dy * dy
                if (d < bd) { bd = d; best = k }
            }
            out.add(best)
        }
        return out
    }
    val firsts = near(0, START_REACH)
    val lasts = near(n - 1, END_REACH)

    val index = glideIndex(words)
    val strokeLen = pathLen / keyW
    val wPath = params.path
    var prev = FloatArray(n)
    var cur = FloatArray(n)
    val ki = IntArray(48)
    // Top three by score, kept sorted (a linear insert into a list of three).
    val topW = arrayOfNulls<String>(3)
    val topS = FloatArray(3) { Float.MAX_VALUE }
    val pruneAt = PRUNE_REACH * PRUNE_REACH

    for (f in firsts) for (l in lasts) {
        val ranks = index.byEnds[GlideIndex.endsKey(keys[f], keys[l])] ?: continue
        candidates@ for (rank in ranks) {
            val sk = index.skeleton[rank] ?: continue
            val m = sk.length
            if (m > ki.size) continue
            for (i in 0 until m) {
                val k = indexOf[sk[i]] ?: continue@candidates       // a letter with no key here
                if (nearest[k] > pruneAt) continue@candidates       // the stroke never came near it
                ki[i] = k
            }
            // prev[j] = best cost of matching keys 0..i-1 with key i-1 sitting on path point j.
            var route = 0f
            for (i in 1 until m) {
                val dx = kx[ki[i]] - kx[ki[i - 1]]; val dy = ky[ki[i]] - ky[ki[i - 1]]
                route += kotlin.math.sqrt(dx * dx + dy * dy)
            }
            val first = keyDist[ki[0]]
            prev[0] = (params.key + params.start) * first[0]
            for (j in 1 until n) prev[j] = Float.MAX_VALUE
            for (i in 1 until m) {
                val dk = keyDist[ki[i]]
                val cum = segCum(ki[i - 1], ki[i])
                // run = min over j' < j of ( prev[j'] - wPath * cum[j'] ): the cheapest place to
                // have left the previous key, with the stroke since then charged to this line.
                var run = Float.MAX_VALUE
                for (j in 0 until n) {
                    var best = prev[j]                                  // both keys on one point
                    if (j > 0) {
                        val pj = prev[j - 1]
                        if (pj != Float.MAX_VALUE) {
                            val v = pj - wPath * cum[j - 1]
                            if (v < run) run = v
                        }
                        if (run != Float.MAX_VALUE) {
                            val v = run + wPath * cum[j - 1]
                            if (v < best) best = v
                        }
                    }
                    cur[j] = if (best == Float.MAX_VALUE) Float.MAX_VALUE else best + params.key * dk[j]
                }
                val t = prev; prev = cur; cur = t
            }
            var score = prev[n - 1]
            if (score == Float.MAX_VALUE) continue
            if (strokeLen > route) score += params.extra * (strokeLen - route)
            score += params.freq * kotlin.math.ln(rank + 2f)
            if (followers != null) {
                val bg = followers[words[rank]] ?: 0
                if (bg > 0) score -= params.context * minOf(bg, params.contextCap)
            }
            if (score < topS[2]) {
                var at = 2
                while (at > 0 && topS[at - 1] > score) { topS[at] = topS[at - 1]; topW[at] = topW[at - 1]; at-- }
                topS[at] = score; topW[at] = words[rank]
            }
        }
    }
    return topW.filterNotNull()
}

/** How far (key widths) the first key may be from the touch-down, and the last from the lift-off. */
private const val START_REACH = 1.3f
private const val END_REACH = 1.7f
/** A word is out as soon as the stroke never came within this of one of its keys. */
private const val PRUNE_REACH = 1.6f
