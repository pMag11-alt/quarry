package quarry.search

/** A search query. `boost` multiplies the scores a query produces. */
sealed class Query {
    abstract val boost: Float
}

data class TermQuery(val field: String, val term: String, override val boost: Float = 1f) : Query() {
    override fun toString() = "$field:$term" + boostSuffix(boost)
}

/** Terms at fixed relative positions; `slop` allows that many position moves. */
data class PhraseQuery(
    val field: String,
    val terms: List<String>,
    val positions: List<Int> = terms.indices.toList(),
    val slop: Int = 0,
    override val boost: Float = 1f,
) : Query() {
    init {
        require(terms.size >= 2 && terms.size == positions.size) { "a phrase needs two or more terms" }
    }

    override fun toString() = "$field:\"${terms.joinToString(" ")}\"" + (if (slop > 0) "~$slop" else "") + boostSuffix(boost)
}

/** Matches terms with the prefix, each scoring a constant `boost` (like Lucene's default rewrite). */
data class PrefixQuery(val field: String, val prefix: String, override val boost: Float = 1f) : Query() {
    override fun toString() = "$field:$prefix*" + boostSuffix(boost)
}

/** Matches terms within `maxEdits` (Damerau-)Levenshtein edits; constant score. */
data class FuzzyQuery(val field: String, val term: String, val maxEdits: Int = 2, override val boost: Float = 1f) : Query() {
    override fun toString() = "$field:$term~$maxEdits" + boostSuffix(boost)
}

data class MatchAllQuery(override val boost: Float = 1f) : Query() {
    override fun toString() = "*:*"
}

enum class Occur { MUST, SHOULD, MUST_NOT, FILTER }

data class Clause(val query: Query, val occur: Occur)

data class BooleanQuery(val clauses: List<Clause>, val minShouldMatch: Int = 0, override val boost: Float = 1f) : Query() {
    override fun toString() = clauses.joinToString(" ", "(", ")") {
        when (it.occur) {
            Occur.MUST -> "+${it.query}"
            Occur.FILTER -> "#${it.query}"
            Occur.MUST_NOT -> "-${it.query}"
            Occur.SHOULD -> it.query.toString()
        }
    } + (if (minShouldMatch > 0) "~$minShouldMatch" else "") + boostSuffix(boost)
}

private fun boostSuffix(b: Float) = if (b == 1f) "" else "^$b"

/**
 * The query BM25 retrieval runs for a text, as Anserini/Pyserini build it:
 * analyze the text, then one SHOULD clause per distinct term, boosted by
 * how often the term occurs in the text.
 */
fun bagOfWords(field: String, terms: List<String>): Query {
    val counts = LinkedHashMap<String, Int>()
    for (t in terms) counts[t] = (counts[t] ?: 0) + 1
    return BooleanQuery(counts.map { (t, c) -> Clause(TermQuery(field, t, c.toFloat()), Occur.SHOULD) })
}
