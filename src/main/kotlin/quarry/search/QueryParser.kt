package quarry.search

import quarry.analysis.Analyzer

/**
 * Parses a practical subset of Lucene's classic query syntax:
 *
 *   rust memory safety          any of the words (OR is the default)
 *   +rust -java  rust AND java  required / prohibited / both required
 *   NOT java  (a OR b) c        negation and grouping
 *   "memory safety"~2           phrase, optionally sloppy
 *   title:rust  title:(a b)     field selection
 *   tok*  colour~1  rust^2      prefix, fuzzy (max edits, default 2), boost
 *
 * Unfielded words search every default field (scores add up, with each
 * field's boost). Words go through the analyzer, so they match what was
 * indexed: "Running" finds "run".
 */
class QueryParser(private val analyzer: Analyzer, private val defaultFields: Map<String, Float>) {
    class ParseException(message: String) : RuntimeException(message)

    private sealed class Tok {
        data class Word(val text: String, val quoted: Boolean) : Tok()
        data class Op(val op: String) : Tok() // ( ) + - : ^ ~ AND OR NOT
    }

    private lateinit var toks: List<Tok>
    private var i = 0

    fun parse(text: String): Query {
        toks = lex(text)
        i = 0
        val q = parseClauses(null, depth = 0) ?: return BooleanQuery(emptyList())
        if (i < toks.size) throw ParseException("unexpected '${show(toks[i])}'")
        return q
    }

    private fun show(t: Tok) = when (t) {
        is Tok.Word -> t.text
        is Tok.Op -> t.op
    }

    private fun lex(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var p = 0
        while (p < s.length) {
            val c = s[p]
            when {
                c.isWhitespace() -> p++
                c == '"' -> {
                    val end = s.indexOf('"', p + 1)
                    if (end < 0) throw ParseException("unterminated phrase")
                    out += Tok.Word(s.substring(p + 1, end), quoted = true)
                    p = end + 1
                }
                c in "()+-:^~" && !(c == '-' && p > 0 && !s[p - 1].isWhitespace() && s[p - 1] != '(') -> {
                    out += Tok.Op(c.toString())
                    p++
                }
                else -> {
                    val start = p
                    while (p < s.length && !s[p].isWhitespace() && s[p] !in "()\"^~" && !(s[p] == ':' && p > start)) {
                        if (s[p] == '\\' && p + 1 < s.length) p++
                        p++
                    }
                    if (p == start) {
                        p++
                        continue
                    }
                    val w = s.substring(start, p).replace("\\", "")
                    out += if (w == "AND" || w == "OR" || w == "NOT") Tok.Op(w) else Tok.Word(w, quoted = false)
                }
            }
        }
        return out
    }

    private fun peekOp(op: String) = i < toks.size && toks[i] == Tok.Op(op)

    /** Clauses until ')' or the end. */
    private fun parseClauses(field: String?, depth: Int): Query? {
        if (depth > 50) throw ParseException("nesting too deep")
        val clauses = ArrayList<Clause>()
        var conjunction = false
        while (i < toks.size && !peekOp(")")) {
            var occur = Occur.SHOULD
            when {
                peekOp("AND") -> {
                    i++
                    conjunction = true
                    // "a AND b": the previous clause becomes required too.
                    if (clauses.isNotEmpty() && clauses.last().occur == Occur.SHOULD) clauses[clauses.size - 1] = clauses.last().copy(occur = Occur.MUST)
                    occur = Occur.MUST
                }
                peekOp("OR") -> {
                    i++
                    conjunction = false
                }
            }
            when {
                peekOp("+") -> { i++; occur = Occur.MUST }
                peekOp("-") || peekOp("NOT") -> { i++; occur = Occur.MUST_NOT }
                conjunction && occur == Occur.SHOULD -> occur = Occur.MUST
            }
            val q = parseClause(field, depth) ?: continue
            clauses += Clause(q, occur)
        }
        if (clauses.isEmpty()) return null
        if (clauses.size == 1 && clauses[0].occur != Occur.MUST_NOT) return clauses[0].query
        // A purely negative query matches everything except the excluded.
        if (clauses.all { it.occur == Occur.MUST_NOT }) clauses += Clause(MatchAllQuery(), Occur.SHOULD)
        return BooleanQuery(clauses)
    }

    private fun parseClause(field: String?, depth: Int): Query? {
        if (i >= toks.size) throw ParseException("expected a term")
        var fieldHere = field
        // field:...
        val t = toks[i]
        if (t is Tok.Word && !t.quoted && i + 1 < toks.size && toks[i + 1] == Tok.Op(":")) {
            fieldHere = t.text
            i += 2
            if (i >= toks.size) throw ParseException("expected a term after '${t.text}:'")
        }
        var q: Query? = when (val tok = toks[i]) {
            Tok.Op("(") -> {
                i++
                val inner = parseClauses(fieldHere, depth + 1)
                if (!peekOp(")")) throw ParseException("missing ')'")
                i++
                inner
            }
            is Tok.Word -> {
                i++
                var fuzzy = -1
                var slop = 0
                if (peekOp("~")) {
                    i++
                    val n = (toks.getOrNull(i) as? Tok.Word)?.text?.toIntOrNull()
                    if (n != null) i++
                    if (tok.quoted) slop = n ?: 0 else fuzzy = n ?: 2
                }
                if (tok.quoted) phrase(fieldHere, tok.text, slop) else word(fieldHere, tok.text, fuzzy)
            }
            else -> throw ParseException("unexpected '${show(tok)}'")
        }
        if (peekOp("^")) {
            i++
            val b = (toks.getOrNull(i) as? Tok.Word)?.text?.toFloatOrNull() ?: throw ParseException("expected a number after '^'")
            i++
            q = q?.let { withBoost(it, b) }
        }
        return q
    }

    private fun withBoost(q: Query, b: Float): Query = when (q) {
        is TermQuery -> q.copy(boost = q.boost * b)
        is PhraseQuery -> q.copy(boost = q.boost * b)
        is PrefixQuery -> q.copy(boost = q.boost * b)
        is FuzzyQuery -> q.copy(boost = q.boost * b)
        is BooleanQuery -> q.copy(boost = q.boost * b)
        is MatchAllQuery -> q.copy(boost = q.boost * b)
    }

    private fun fields(field: String?): Map<String, Float> = if (field != null) mapOf(field to 1f) else defaultFields

    private fun perField(field: String?, make: (String) -> Query?): Query? {
        val qs = fields(field).mapNotNull { (f, boost) -> make(f)?.let { if (boost == 1f) it else withBoost(it, boost) } }
        return when (qs.size) {
            0 -> null
            1 -> qs[0]
            else -> BooleanQuery(qs.map { Clause(it, Occur.SHOULD) })
        }
    }

    private fun word(field: String?, text: String, fuzzy: Int): Query? {
        if (text == "*" && field == null) return MatchAllQuery()
        if (text.endsWith("*") && text.length > 1) {
            // Prefixes are lowercased but not stemmed or tokenized.
            val prefix = quarry.analysis.EnglishAnalyzer.lowercase(text.dropLast(1))
            return perField(field) { PrefixQuery(it, prefix) }
        }
        if (fuzzy >= 0) {
            val t = analyzer.terms(text).firstOrNull() ?: return null
            return perField(field) { FuzzyQuery(it, t, fuzzy.coerceIn(0, 2)) }
        }
        val terms = analyzer.terms(text)
        return perField(field) { f ->
            when (terms.size) {
                0 -> null
                1 -> TermQuery(f, terms[0])
                // "e-mail" analyzes to two terms; like Lucene's classic parser
                // (without auto phrases), either may match.
                else -> BooleanQuery(terms.map { Clause(TermQuery(f, it), Occur.SHOULD) })
            }
        }
    }

    private fun phrase(field: String?, text: String, slop: Int): Query? {
        val terms = ArrayList<String>()
        val positions = ArrayList<Int>()
        analyzer.analyze(text) { t, p, _, _ ->
            terms += t
            positions += p
        }
        if (terms.isEmpty()) return null
        return perField(field) { f ->
            if (terms.size == 1) TermQuery(f, terms[0]) else PhraseQuery(f, terms.toList(), positions.map { it - positions[0] }, slop)
        }
    }
}
