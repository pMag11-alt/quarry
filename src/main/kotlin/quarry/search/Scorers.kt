package quarry.search

import quarry.index.FieldReader
import quarry.index.NO_MORE_DOCS
import quarry.index.Postings
import quarry.index.TermInfo

/**
 * Iterates the matching docs of one segment in increasing order and scores
 * them. Like Lucene's Scorer: nextDoc/advance move forward only.
 */
abstract class Scorer {
    abstract val doc: Int
    abstract fun nextDoc(): Int

    /** First doc >= target. Only called with target > doc. */
    abstract fun advance(target: Int): Int
    abstract fun score(): Float

    /** No doc scores higher than this. */
    abstract val maxScore: Float

    /** Rough number of docs this scorer may visit, for ordering. */
    abstract val cost: Long
}

class TermScorer(
    private val postings: Postings,
    private val field: FieldReader,
    info: TermInfo,
    private val bm25: BM25,
    private val weight: Float,
    private val normInv: FloatArray,
) : Scorer() {
    override val doc get() = postings.doc
    override fun nextDoc() = postings.nextDoc()
    override fun advance(target: Int) = postings.advance(target)
    val freq get() = postings.freq
    private val norms = field.norms()
    override fun score() = bm25.score(weight, postings.freq.toFloat(), normInv[norms[postings.doc].toInt() and 0xFF])

    // The impacts (Pareto front of freq/norm) give the exact maximum.
    override val maxScore: Float = (info.impactFreqs.indices).maxOfOrNull {
        bm25.score(weight, info.impactFreqs[it].toFloat(), normInv[info.impactNorms[it]])
    } ?: 0f
    override val cost = info.docFreq.toLong()
}

/** Every doc of the segment, constant score. */
class AllDocsScorer(private val maxDoc: Int, private val value: Float) : Scorer() {
    override var doc = -1
        private set
    override fun nextDoc(): Int {
        doc = if (doc + 1 < maxDoc) doc + 1 else NO_MORE_DOCS
        return doc
    }
    override fun advance(target: Int): Int {
        doc = if (target < maxDoc) target else NO_MORE_DOCS
        return doc
    }
    override fun score() = value
    override val maxScore = value
    override val cost = maxDoc.toLong()
}

/** Wraps a scorer, replacing its score with a constant. */
class ConstantScorer(private val inner: Scorer, private val value: Float) : Scorer() {
    override val doc get() = inner.doc
    override fun nextDoc() = inner.nextDoc()
    override fun advance(target: Int) = inner.advance(target)
    override fun score() = value
    override val maxScore = value
    override val cost get() = inner.cost
}

/** Docs matched by all sub-scorers; scores add up. */
class ConjunctionScorer(scorers: List<Scorer>, private val scoring: List<Boolean>) : Scorer() {
    private val subs = scorers.indices.sortedBy { scorers[it].cost }.map { scorers[it] }
    private val scores = scorers.indices.sortedBy { scorers[it].cost }.map { scoring[it] }
    override var doc = -1
        private set

    private fun align(start: Int): Int {
        var target = start
        outer@ while (target != NO_MORE_DOCS) {
            for (i in 1 until subs.size) {
                val s = subs[i]
                val d = if (s.doc < target) s.advance(target) else s.doc
                if (d > target) {
                    target = subs[0].advance(d)
                    continue@outer
                }
            }
            break
        }
        doc = target
        return doc
    }

    override fun nextDoc() = align(subs[0].nextDoc())
    override fun advance(target: Int) = align(subs[0].advance(target))
    override fun score(): Float {
        var s = 0.0
        for (i in subs.indices) if (scores[i]) s += subs[i].score()
        return s.toFloat()
    }
    override val maxScore: Float = subs.indices.sumOf { if (scores[it]) subs[it].maxScore.toDouble() else 0.0 }.toFloat()
    override val cost = subs.minOf { it.cost }
}

/** Docs matched by at least `minMatch` sub-scorers; scores of matching ones add up. */
class DisjunctionScorer(private val subs: List<Scorer>, private val minMatch: Int = 1) : Scorer() {
    // A binary heap of scorers ordered by current doc.
    private val heap = subs.toTypedArray()
    private var size = heap.size
    override var doc = -1
        private set

    private fun siftDown(i0: Int) {
        var i = i0
        val x = heap[i]
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && heap[c + 1].doc < heap[c].doc) c++
            if (heap[c].doc >= x.doc) break
            heap[i] = heap[c]
            i = c
        }
        heap[i] = x
    }

    private fun heapify() {
        for (i in size / 2 - 1 downTo 0) siftDown(i)
    }

    private var started = false

    private fun matchCount(d: Int): Int = subs.count { it.doc == d }

    private fun settle(): Int {
        while (true) {
            if (size == 0 || heap[0].doc == NO_MORE_DOCS) {
                doc = NO_MORE_DOCS
                return doc
            }
            val d = heap[0].doc
            if (minMatch <= 1 || matchCount(d) >= minMatch) {
                doc = d
                return doc
            }
            moveBeyond(d)
        }
    }

    /** Advance every scorer positioned at or before d to the next doc. */
    private fun moveBeyond(d: Int) {
        while (size > 0 && heap[0].doc <= d) {
            heap[0].nextDoc()
            siftDown(0)
        }
    }

    override fun nextDoc(): Int {
        if (!started) {
            started = true
            for (s in heap) s.nextDoc()
            heapify()
            return settle()
        }
        moveBeyond(doc)
        return settle()
    }

    override fun advance(target: Int): Int {
        if (!started) {
            started = true
            for (s in heap) s.advance(target)
            heapify()
            return settle()
        }
        while (size > 0 && heap[0].doc < target) {
            heap[0].advance(target)
            siftDown(0)
        }
        return settle()
    }

    override fun score(): Float {
        var s = 0.0
        for (sub in subs) if (sub.doc == doc) s += sub.score()
        return s.toFloat()
    }

    override val maxScore: Float = subs.sumOf { it.maxScore.toDouble() }.toFloat()
    override val cost = subs.sumOf { it.cost }
}

/** Docs of `req` that `excl` does not match. */
class ReqExclScorer(private val req: Scorer, private val excl: Scorer) : Scorer() {
    override val doc get() = req.doc

    private fun skipExcluded(d0: Int): Int {
        var d = d0
        while (d != NO_MORE_DOCS) {
            val e = if (excl.doc < d) excl.advance(d) else excl.doc
            if (e != d) return d
            d = req.nextDoc()
        }
        return d
    }

    override fun nextDoc() = skipExcluded(req.nextDoc())
    override fun advance(target: Int) = skipExcluded(req.advance(target))
    override fun score() = req.score()
    override val maxScore get() = req.maxScore
    override val cost get() = req.cost
}

/** Docs of `req`, plus the score of `opt` where it also matches. */
class ReqOptScorer(private val req: Scorer, private val opt: Scorer) : Scorer() {
    override val doc get() = req.doc
    override fun nextDoc() = req.nextDoc()
    override fun advance(target: Int) = req.advance(target)
    override fun score(): Float {
        var s = req.score().toDouble()
        val d = req.doc
        val o = if (opt.doc < d) opt.advance(d) else opt.doc
        if (o == d) s += opt.score()
        return s.toFloat()
    }
    override val maxScore = (req.maxScore.toDouble() + opt.maxScore).toFloat()
    override val cost get() = req.cost
}

/**
 * Phrase matching over positions. The phrase frequency is the number of
 * matches (exact) or the sum of 1 / (distance + 1) over matches (sloppy),
 * and is scored like a term frequency.
 */
class PhraseScorer(
    private val postings: List<Postings>,
    private val offsets: IntArray, // each term's position within the phrase
    private val slop: Int,
    private val field: FieldReader,
    private val bm25: BM25,
    private val weight: Float,
    private val normInv: FloatArray,
    maxFreq: Int,
) : Scorer() {
    private val conj = ConjunctionScorer(postings.map { PostingsScorer(it) }, postings.map { false })
    private var buffers = Array(postings.size) { IntArray(8) }
    private var phraseFreq = 0f
    override var doc = -1
        private set

    private fun matches(): Boolean {
        for ((i, p) in postings.withIndex()) buffers[i] = p.positions(buffers[i])
        phraseFreq = if (slop == 0) exactFreq() else sloppyFreq()
        return phraseFreq > 0f
    }

    private fun exactFreq(): Float {
        // Count start positions where every term sits at its offset.
        var count = 0
        val first = buffers[0]
        val idx = IntArray(postings.size)
        for (k in 0 until postings[0].freq) {
            val start = first[k] - offsets[0]
            var ok = true
            for (t in 1 until postings.size) {
                val want = start + offsets[t]
                val b = buffers[t]
                var j = idx[t]
                while (j < postings[t].freq && b[j] < want) j++
                idx[t] = j
                if (j >= postings[t].freq || b[j] != want) {
                    ok = false
                    break
                }
            }
            if (ok) count++
        }
        return count.toFloat()
    }

    private fun sloppyFreq(): Float {
        // For each occurrence of the first term, take the closest occurrence
        // of every other term relative to its expected spot; the match
        // distance is the spread of the shifted positions.
        var freq = 0f
        for (k in 0 until postings[0].freq) {
            val anchor = buffers[0][k] - offsets[0]
            var lo = 0
            var hi = 0
            var ok = true
            for (t in 1 until postings.size) {
                var best = Int.MAX_VALUE
                var bestShift = 0
                for (j in 0 until postings[t].freq) {
                    val shift = buffers[t][j] - offsets[t] - anchor
                    if (Math.abs(shift) < best) {
                        best = Math.abs(shift)
                        bestShift = shift
                    }
                }
                if (best > slop) {
                    ok = false
                    break
                }
                lo = minOf(lo, bestShift)
                hi = maxOf(hi, bestShift)
            }
            val dist = hi - lo
            if (ok && dist <= slop) freq += 1f / (dist + 1)
        }
        return freq
    }

    private fun next(d0: Int): Int {
        var d = d0
        while (d != NO_MORE_DOCS && !matches()) d = conj.nextDoc()
        doc = d
        return d
    }

    override fun nextDoc() = next(conj.nextDoc())
    override fun advance(target: Int) = next(conj.advance(target))
    override fun score() = bm25.score(weight, phraseFreq, normInv[field.norm(doc)])
    override val maxScore = bm25.score(weight, maxFreq.toFloat(), normInv.max())
    override val cost get() = conj.cost
}

/** A bare postings list as a (non-scoring) scorer. */
class PostingsScorer(private val p: Postings) : Scorer() {
    override val doc get() = p.doc
    override fun nextDoc() = p.nextDoc()
    override fun advance(target: Int) = p.advance(target)
    override fun score() = 0f
    override val maxScore = 0f
    override val cost = p.docFreq.toLong()
}
