package quarry.search

import quarry.index.IndexReader
import quarry.index.NO_MORE_DOCS
import quarry.index.Segment

data class Hit(val doc: Int, val score: Float)

/** Top hits. `totalHits` is exact unless `totalIsLowerBound` (MaxScore skips docs it can prove irrelevant). */
data class TopDocs(val hits: List<Hit>, val totalHits: Long, val totalIsLowerBound: Boolean)

/**
 * Runs queries against an IndexReader with BM25. Scores and ranking (score
 * descending, then doc id ascending) match Lucene's IndexSearcher.
 */
class Searcher(val reader: IndexReader, val bm25: BM25 = BM25()) {
    private val normCache = HashMap<String, FloatArray>()

    private fun normInverses(field: String): FloatArray = normCache.getOrPut(field) { bm25.normInverses(reader.stats(field)) }

    // ---- scorers --------------------------------------------------------------

    /** A scorer for the query on segment s, or null if nothing can match there. */
    fun scorer(q: Query, s: Segment, boost: Float = 1f, scoring: Boolean = true): Scorer? {
        val b = boost * q.boost
        return when (q) {
            is TermQuery -> termScorer(q.field, q.term, s, b)
            is PhraseQuery -> phraseScorer(q, s, b)
            is MatchAllQuery -> AllDocsScorer(s.maxDoc, b)
            is PrefixQuery -> expandedScorer(q.field, s, b) { it.startsWith(q.prefix) }
            is FuzzyQuery -> {
                val minLen = maxOf(0, q.term.length - q.maxEdits)
                expandedScorer(q.field, s, b) {
                    Math.abs(it.length - q.term.length) <= q.maxEdits && it.length >= minLen && editDistance(q.term, it, q.maxEdits) <= q.maxEdits
                }
            }
            is BooleanQuery -> booleanScorer(q, s, b, scoring)
        }
    }

    private fun termScorer(field: String, term: String, s: Segment, boost: Float): TermScorer? {
        val fr = s.fields[field] ?: return null
        val info = fr.lookup(term) ?: return null
        val stats = reader.stats(field)
        val weight = boost * bm25.idf(reader.docFreq(field, term), stats.docCount)
        return TermScorer(fr.postings(info), fr, info, bm25, weight, normInverses(field))
    }

    private fun phraseScorer(q: PhraseQuery, s: Segment, boost: Float): Scorer? {
        val fr = s.fields[q.field] ?: return null
        val infos = q.terms.map { fr.lookup(it) ?: return null }
        val stats = reader.stats(q.field)
        var idf = 0.0 // summed in double, like Lucene
        for (t in q.terms) idf += bm25.idf(reader.docFreq(q.field, t), stats.docCount)
        val maxFreq = infos.minOf { info -> info.impactFreqs.maxOrNull() ?: 0 }
        return PhraseScorer(
            infos.map { fr.postings(it, positions = true) },
            q.positions.toIntArray(), q.slop, fr, bm25, boost * idf.toFloat(), normInverses(q.field), maxFreq,
        )
    }

    /** Multi-term queries: the union of matching terms, constant score. */
    private fun expandedScorer(field: String, s: Segment, boost: Float, accept: (String) -> Boolean): Scorer? {
        val fr = s.fields[field] ?: return null
        val subs = ArrayList<Scorer>()
        for ((t, info) in fr.terms()) {
            if (accept(t)) subs += PostingsScorer(fr.postings(info))
            if (subs.size > 4096) break
        }
        if (subs.isEmpty()) return null
        return ConstantScorer(if (subs.size == 1) subs[0] else DisjunctionScorer(subs), boost)
    }

    private fun booleanScorer(q: BooleanQuery, s: Segment, boost: Float, scoring: Boolean): Scorer? {
        val required = ArrayList<Scorer>()
        val requiredScoring = ArrayList<Boolean>()
        val optional = ArrayList<Scorer>()
        val prohibited = ArrayList<Scorer>()
        for (c in q.clauses) {
            val sc = scorer(c.query, s, boost, scoring && c.occur != Occur.FILTER)
            when (c.occur) {
                Occur.MUST, Occur.FILTER -> {
                    sc ?: return null // a required clause matches nothing here
                    required += sc
                    requiredScoring += c.occur == Occur.MUST && scoring
                }
                Occur.SHOULD -> if (sc != null) optional += sc
                Occur.MUST_NOT -> if (sc != null) prohibited += sc
            }
        }
        val minShould = q.minShouldMatch
        if (optional.size < minShould) return null
        var main: Scorer = when {
            required.isEmpty() -> {
                if (optional.isEmpty()) return null
                if (optional.size == 1 && minShould <= 1) optional[0] else DisjunctionScorer(optional, maxOf(1, minShould))
            }
            optional.isEmpty() -> if (required.size == 1 && requiredScoring[0]) required[0] else ConjunctionScorer(required, requiredScoring)
            minShould > 0 -> ConjunctionScorer(required + DisjunctionScorer(optional, minShould), requiredScoring + true)
            else -> ReqOptScorer(
                if (required.size == 1 && requiredScoring[0]) required[0] else ConjunctionScorer(required, requiredScoring),
                if (optional.size == 1) optional[0] else DisjunctionScorer(optional),
            )
        }
        if (prohibited.isNotEmpty()) {
            main = ReqExclScorer(main, if (prohibited.size == 1) prohibited[0] else DisjunctionScorer(prohibited))
        }
        return main
    }

    // ---- collection -----------------------------------------------------------

    /**
     * Keeps the best k hits (score descending, doc ascending) in a binary
     * min-heap of primitives; the root is the worst kept hit. Docs arrive in
     * increasing order, so a newcomer only wins with a strictly higher score.
     */
    private class TopK(val k: Int) {
        private val scores = FloatArray(maxOf(1, k))
        private val docs = IntArray(maxOf(1, k))
        private var size = 0
        var total = 0L

        /** The score a new (later, so higher-numbered) doc must beat. */
        fun threshold(): Float = if (size < k) Float.NEGATIVE_INFINITY else scores[0]

        /** a is worse than b: lower score, or the same score and a later doc. */
        private fun worse(sa: Float, da: Int, sb: Float, db: Int) = sa < sb || (sa == sb && da > db)

        fun offer(doc: Int, score: Float) {
            total++
            if (size < k) {
                var i = size++
                while (i > 0) {
                    val parent = (i - 1) / 2
                    if (!worse(score, doc, scores[parent], docs[parent])) break
                    scores[i] = scores[parent]
                    docs[i] = docs[parent]
                    i = parent
                }
                scores[i] = score
                docs[i] = doc
            } else if (score > scores[0]) {
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && worse(scores[c + 1], docs[c + 1], scores[c], docs[c])) c++
                    if (!worse(scores[c], docs[c], score, doc)) break
                    scores[i] = scores[c]
                    docs[i] = docs[c]
                    i = c
                }
                scores[i] = score
                docs[i] = doc
            }
        }

        fun result(lowerBound: Boolean): TopDocs {
            val hits = (0 until size).map { Hit(docs[it], scores[it]) }
                .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.doc })
            return TopDocs(hits, total, lowerBound)
        }
    }

    /** The k best documents for the query. */
    fun search(q: Query, k: Int = 10, exhaustive: Boolean = false): TopDocs {
        val terms = pureDisjunction(q)
        if (!exhaustive && terms != null && k > 0) return maxScoreSearch(terms, k)
        val top = TopK(k)
        for ((i, s) in reader.segments.withIndex()) {
            val sc = scorer(q, s) ?: continue
            val base = reader.docBases[i]
            while (sc.nextDoc() != NO_MORE_DOCS) {
                if (s.isLive(sc.doc)) top.offer(base + sc.doc, sc.score())
            }
        }
        return top.result(lowerBound = false)
    }

    /** Number of live documents matching the query. */
    fun count(q: Query): Long {
        var n = 0L
        for (s in reader.segments) {
            val sc = scorer(q, s, scoring = false) ?: continue
            while (sc.nextDoc() != NO_MORE_DOCS) if (s.isLive(sc.doc)) n++
        }
        return n
    }

    /** Term queries OR-ed together (a bag of words), or null. */
    private fun pureDisjunction(q: Query): List<TermQuery>? = when {
        q is TermQuery -> listOf(q)
        q is BooleanQuery && q.minShouldMatch <= 1 && q.clauses.isNotEmpty() &&
            q.clauses.all { it.occur == Occur.SHOULD && it.query is TermQuery } -> q.clauses.map { (it.query as TermQuery).let { t -> t.copy(boost = t.boost * q.boost) } }
        else -> null
    }

    /** Docs scored by MaxScore so far (for reporting how much it prunes). */
    var candidates = 0L
        private set

    /**
     * MaxScore (Turtle and Flood, 1995). Terms are sorted by their maximum
     * possible score, which the impacts give exactly. Once the top-k is
     * full, the low-scoring terms whose maxima sum to at most the k-th best
     * score cannot make a new hit on their own, so only docs from the
     * remaining "essential" terms are visited, and the others are probed
     * only while a doc can still win. Same top-k as exhaustive evaluation.
     *
     * (A block-max variant with per-block bounds was tried as well. On BEIR
     * it scored only 2-8% fewer docs and was slower overall, so it was left out.)
     */
    private fun maxScoreSearch(terms: List<TermQuery>, k: Int): TopDocs {
        val top = TopK(k)
        for ((si, s) in reader.segments.withIndex()) {
            val scorers = terms.mapNotNull { termScorer(it.field, it.term, s, it.boost) }.sortedBy { it.maxScore }.toTypedArray()
            if (scorers.isEmpty()) continue
            val n = scorers.size
            val bounds = DoubleArray(n)
            var acc = 0.0
            for (i in 0 until n) {
                acc += scorers[i].maxScore
                bounds[i] = acc
            }
            val base = reader.docBases[si]
            val docs = IntArray(n) { scorers[it].nextDoc() } // current doc per scorer, cached
            // Scorers below `firstEssential` cannot produce a hit alone.
            var firstEssential = 0
            fun updateEssential() {
                val t = top.threshold().toDouble()
                while (firstEssential < n && bounds[firstEssential] <= t) firstEssential++
            }
            updateEssential()
            while (firstEssential < n) {
                var doc = NO_MORE_DOCS
                for (i in firstEssential until n) if (docs[i] < doc) doc = docs[i]
                if (doc == NO_MORE_DOCS) break
                candidates++
                var score = 0.0
                for (i in firstEssential until n) if (docs[i] == doc) score += scorers[i].score()
                // Probe non-essential scorers, best first, while the doc can still win.
                val t = top.threshold().toDouble()
                var i = firstEssential - 1
                while (i >= 0 && score + bounds[i] > t) {
                    if (docs[i] < doc) docs[i] = scorers[i].advance(doc)
                    if (docs[i] == doc) score += scorers[i].score()
                    i--
                }
                if (i < 0 && s.isLive(doc)) {
                    top.offer(base + doc, score.toFloat())
                    updateEssential()
                }
                for (j in firstEssential until n) if (docs[j] == doc) docs[j] = scorers[j].nextDoc()
            }
        }
        return top.result(lowerBound = true)
    }

    companion object {
        /** Optimal string alignment distance (adjacent transpositions count once), capped at max + 1. */
        fun editDistance(a: String, b: String, max: Int): Int {
            val n = a.length
            val m = b.length
            if (Math.abs(n - m) > max) return max + 1
            var prev2 = IntArray(m + 1)
            var prev = IntArray(m + 1) { it }
            var cur = IntArray(m + 1)
            for (i in 1..n) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..m) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                    cur[j] = v
                    rowMin = minOf(rowMin, v)
                }
                if (rowMin > max) return max + 1
                val t = prev2
                prev2 = prev
                prev = cur
                cur = t
            }
            return prev[m]
        }
    }
}
