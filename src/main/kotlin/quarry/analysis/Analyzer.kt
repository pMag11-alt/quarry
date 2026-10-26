package quarry.analysis

/** Receives each indexed term with its position and character offsets. */
fun interface TermSink {
    fun term(term: String, position: Int, start: Int, end: Int)
}

/** Turns text into the terms that are indexed and searched. */
interface Analyzer {
    fun analyze(text: String, sink: TermSink)

    fun terms(text: String): List<String> {
        val out = ArrayList<String>()
        analyze(text) { t, _, _, _ -> out += t }
        return out
    }
}

/**
 * Lucene's EnglishAnalyzer: StandardTokenizer, then strip English
 * possessives, lowercase, drop stop words and apply the Porter stemmer.
 * Stop words still advance the position, so phrase queries see the gap.
 */
class EnglishAnalyzer(
    private val stopWords: Set<String> = STOP_WORDS,
    private val stem: Boolean = true,
) : Analyzer {
    override fun analyze(text: String, sink: TermSink) {
        val tokenizer = StandardTokenizer(text)
        var position = -1
        while (tokenizer.next()) {
            position++
            var term = text.substring(tokenizer.start, tokenizer.end)
            term = stripPossessive(term)
            term = lowercase(term)
            if (term in stopWords) continue
            if (stem) term = PorterStemmer.stem(term)
            sink.term(term, position, tokenizer.start, tokenizer.end)
        }
    }

    companion object {
        /** Lucene's EnglishAnalyzer.ENGLISH_STOP_WORDS_SET. */
        val STOP_WORDS: Set<String> = hashSetOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is", "it", "no",
            "not", "of", "on", "or", "such", "that", "the", "their", "then", "there", "these", "they", "this",
            "to", "was", "will", "with",
        )

        /** Drop a trailing 's (with an ASCII, typographic or fullwidth apostrophe). */
        fun stripPossessive(t: String): String {
            val n = t.length
            if (n >= 2 && (t[n - 1] == 's' || t[n - 1] == 'S')) {
                val q = t[n - 2]
                if (q == '\'' || q == '’' || q == '＇') return t.substring(0, n - 2)
            }
            return t
        }

        /** Code-point-wise lowercasing, like Lucene's LowerCaseFilter (no locale, no special casing). */
        fun lowercase(t: String): String {
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                if (Character.toLowerCase(cp) != cp) break
                i += Character.charCount(cp)
            }
            if (i == t.length) return t
            val sb = StringBuilder(t.length)
            sb.append(t, 0, i)
            while (i < t.length) {
                val cp = t.codePointAt(i)
                sb.appendCodePoint(Character.toLowerCase(cp))
                i += Character.charCount(cp)
            }
            return sb.toString()
        }
    }
}
