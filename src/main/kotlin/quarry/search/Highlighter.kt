package quarry.search

import quarry.analysis.Analyzer

/**
 * Builds a short snippet of a stored field around the query's terms, with
 * matches wrapped in <mark>. The text is re-analyzed with offsets, so a
 * query for "run" highlights "Running" and "runs".
 */
class Highlighter(private val analyzer: Analyzer, private val fragmentChars: Int = 220) {
    /** The query's terms per field (what highlighting looks for). */
    fun termsOf(q: Query, out: MutableMap<String, MutableSet<String>> = HashMap()): Map<String, Set<String>> {
        when (q) {
            is TermQuery -> out.getOrPut(q.field) { HashSet() } += q.term
            is PhraseQuery -> out.getOrPut(q.field) { HashSet() } += q.terms
            is FuzzyQuery -> out.getOrPut(q.field) { HashSet() } += q.term
            is BooleanQuery -> q.clauses.filter { it.occur != Occur.MUST_NOT }.forEach { termsOf(it.query, out) }
            is PrefixQuery, is MatchAllQuery -> {}
        }
        return out
    }

    /** HTML snippet, or null when the field has no matching term. */
    fun highlight(text: String, terms: Set<String>, prefixes: List<String> = emptyList()): String? {
        val spans = ArrayList<IntArray>()
        analyzer.analyze(text) { t, _, s, e ->
            if (t in terms || prefixes.any { t.startsWith(it) }) spans += intArrayOf(s, e)
        }
        if (spans.isEmpty()) return null
        // The window of fragmentChars with the most distinct matched terms.
        var best = 0
        var bestCount = -1
        var j = 0
        for (i in spans.indices) {
            while (j < spans.size && spans[j][1] - spans[i][0] <= fragmentChars) j++
            val distinct = (i until j).map { text.substring(spans[it][0], spans[it][1]).lowercase() }.toSet().size
            if (distinct > bestCount) {
                bestCount = distinct
                best = i
            }
        }
        var start = maxOf(0, spans[best][0] - fragmentChars / 4)
        var end = minOf(text.length, start + fragmentChars)
        // Snap to word boundaries.
        while (start > 0 && !text[start - 1].isWhitespace() && spans[best][0] - start < fragmentChars / 2) start--
        while (end < text.length && !text[end].isWhitespace() && end - start < fragmentChars + 30) end++
        val sb = StringBuilder()
        if (start > 0) sb.append("… ")
        var p = start
        for (s in spans) {
            if (s[0] < start || s[1] > end) continue
            escape(text, p, s[0], sb)
            sb.append("<mark>")
            escape(text, s[0], s[1], sb)
            sb.append("</mark>")
            p = s[1]
        }
        escape(text, p, end, sb)
        if (end < text.length) sb.append(" …")
        return sb.toString()
    }

    private fun escape(text: String, from: Int, to: Int, sb: StringBuilder) {
        for (k in from until to) {
            when (val c = text[k]) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
    }
}
