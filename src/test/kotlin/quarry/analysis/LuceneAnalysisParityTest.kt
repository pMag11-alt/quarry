package quarry.analysis

import org.apache.lucene.analysis.Analyzer as LuceneAnalyzer
import org.apache.lucene.analysis.core.KeywordTokenizer
import org.apache.lucene.analysis.en.PorterStemFilter
import org.apache.lucene.analysis.standard.StandardTokenizer as LuceneTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import java.io.StringReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.apache.lucene.analysis.en.EnglishAnalyzer as LuceneEnglish

/** Compares quarry's analysis chain with Lucene's, token for token. */
class LuceneAnalysisParityTest {
    private val lucene = LuceneEnglish()

    /** Lucene's (package-private) PorterStemmer, through its public filter. */
    private val luceneStemmer = object : LuceneAnalyzer() {
        override fun createComponents(fieldName: String): TokenStreamComponents {
            val source = KeywordTokenizer()
            return TokenStreamComponents(source, PorterStemFilter(source))
        }
    }

    private fun luceneStem(word: String): String {
        val ts = luceneStemmer.tokenStream("f", word)
        val term = ts.addAttribute(CharTermAttribute::class.java)
        ts.reset()
        check(ts.incrementToken())
        val out = term.toString()
        ts.end()
        ts.close()
        return out
    }

    private fun luceneSpans(text: String): List<Triple<String, Int, Int>> {
        val t = LuceneTokenizer()
        t.setReader(StringReader(text))
        val term = t.addAttribute(CharTermAttribute::class.java)
        val off = t.addAttribute(OffsetAttribute::class.java)
        t.reset()
        val out = ArrayList<Triple<String, Int, Int>>()
        while (t.incrementToken()) out += Triple(term.toString(), off.startOffset(), off.endOffset())
        t.end()
        t.close()
        return out
    }

    private fun quarrySpans(text: String): List<Triple<String, Int, Int>> {
        val t = StandardTokenizer(text)
        val out = ArrayList<Triple<String, Int, Int>>()
        while (t.next()) out += Triple(text.substring(t.start, t.end), t.start, t.end)
        return out
    }

    /** (term, position, start, end) from Lucene's EnglishAnalyzer. */
    private fun luceneTerms(a: LuceneAnalyzer, text: String): List<List<Any>> {
        val ts = a.tokenStream("f", text)
        val term = ts.addAttribute(CharTermAttribute::class.java)
        val off = ts.addAttribute(OffsetAttribute::class.java)
        val inc = ts.addAttribute(PositionIncrementAttribute::class.java)
        ts.reset()
        var pos = -1
        val out = ArrayList<List<Any>>()
        while (ts.incrementToken()) {
            pos += inc.positionIncrement
            out += listOf(term.toString(), pos, off.startOffset(), off.endOffset())
        }
        ts.end()
        ts.close()
        return out
    }

    private fun quarryTerms(text: String): List<List<Any>> {
        val out = ArrayList<List<Any>>()
        EnglishAnalyzer().analyze(text) { t, p, s, e -> out += listOf(t, p, s, e) }
        return out
    }

    private val tricky = listOf(
        "The quick brown fox's jumps over the lazy dogs' backs.",
        "U.S.A. vs. e.g. i.e. Ph.D. 3.14159 1,000,000.50 -42 +7 1e10 0x1F",
        "don't won't it's O'Reilly rock'n'roll 'quoted' \"double\" ‘curly’ “dq”",
        "snake_case __init__ _leading trailing_ a-b-c well-known C++ C# .NET node.js",
        "email: someone@example.com https://example.org/path?q=1&r=2#frag 192.168.0.1",
        "naïve café résumé façade coöperate Ünïcödé é combining",
        "中文分词测试 日本語のテキスト カタカナ ﾊﾝｶｸ 한국어 텍스트 ภาษาไทย ພາສາລາວ",
        "שלום עולם צה\"ל ה'תשפ\"ד",
        "emoji 😀👍🏽 👨‍👩‍👧 🇮🇳🇺🇸 1️⃣ #️⃣ © ® ™ ❤️ ☺",
        "math α-β ≤ ≥ ± × μm² H₂O CO2 10⁻³ 3/4 ½",
        "tabs\tand\nnewlines\r\nand nbsp thin​zwsp",
        "x".repeat(300) + " " + "ab".repeat(200),
        "Generalizations, nationalization; relational: conditional? hopefully!",
        "possessives James's boss’s ＪＡＭＥＳ＇Ｓ IT'S",
        "1.2.3 a.b.c 1,2,3 a,b 1:2 a:b a'b 1'2 a_1 1_a ア_1",
    )

    @Test
    fun `tokenizer spans match Lucene on tricky text`() {
        for (text in tricky) assertEquals(luceneSpans(text), quarrySpans(text), text)
    }

    @Test
    fun `english analyzer matches Lucene on tricky text`() {
        for (text in tricky) assertEquals(luceneTerms(lucene, text), quarryTerms(text), text)
    }

    @Test
    fun `tokenizer matches Lucene on random mixed-script text`() {
        // Characters chosen to hit every branch of the grammar.
        val alphabet = (
            "abcXYZ019 ._'\",:;-/@#*" +
                "éǘ̈‍​­️⃣" + // marks, ZWJ, ZWSP, soft hyphen, VS16, keycap
                "אבג׳״" + // Hebrew with geresh and gershayim
                "カタナー・ｶﾞ" + "ひらが" + "漢字" + "한글" + "กขฃั" + "٣٤" + "²½" + "’‘“”"
            ).codePoints().toArray().toList() +
            listOf(0x1F600, 0x1F44D, 0x1F3FD, 0x1F1EE, 0x1F1F3, 0x2764, 0xA9, 0xE0067, 0xE007F, 0x1F3F4)
        // QUARRY_FUZZ=<n> runs a longer search (n strings, varied seeds).
        val iterations = System.getenv("QUARRY_FUZZ")?.toInt() ?: 20_000
        val rnd = Random(System.getenv("QUARRY_FUZZ_SEED")?.toLong() ?: 42L)
        repeat(iterations) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(1, if (iterations > 20_000) 60 else 24)) { sb.appendCodePoint(alphabet[rnd.nextInt(alphabet.size)]) }
            val text = sb.toString()
            if (luceneSpans(text) != quarrySpans(text)) {
                val small = shrink(text)
                assertEquals(luceneSpans(small), quarrySpans(small), "minimal input: " + hex(small))
            }
        }
    }

    private fun hex(s: String) = s.codePoints().toArray().joinToString(" ") { "%04X".format(it) }

    /** Remove code points while quarry and Lucene still disagree. */
    private fun shrink(input: String): String {
        var cps = input.codePoints().toArray().toList()
        var changed = true
        while (changed) {
            changed = false
            for (i in cps.indices.reversed()) {
                val candidate = cps.filterIndexed { j, _ -> j != i }
                val text = String(candidate.toIntArray(), 0, candidate.size)
                if (luceneSpans(text) != quarrySpans(text)) {
                    cps = candidate
                    changed = true
                    break
                }
            }
        }
        return String(cps.toIntArray(), 0, cps.size)
    }

    @Test
    fun `porter stemmer matches Lucene on a systematic vocabulary`() {
        val roots = listOf(
            "abl", "agre", "allow", "analys", "arch", "bake", "bat", "bl", "car", "cat", "centr", "cond", "connect",
            "control", "cri", "decis", "differ", "digit", "effect", "elect", "fall", "feed", "fil", "formal", "gener",
            "happ", "hop", "hope", "iz", "journal", "kind", "lie", "log", "mean", "nation", "need", "oper", "plan",
            "posit", "process", "radi", "rate", "relat", "sens", "sky", "stud", "sky", "troubl", "valu", "w", "y",
        )
        val suffixes = listOf(
            "", "s", "es", "ies", "sses", "ss", "ed", "eed", "ing", "ly", "y", "ational", "tional", "enci", "anci",
            "izer", "bli", "alli", "entli", "eli", "ousli", "ization", "ation", "ator", "alism", "iveness",
            "fulness", "ousness", "aliti", "iviti", "biliti", "logi", "icate", "ative", "alize", "iciti", "ical",
            "ful", "ness", "al", "ance", "ence", "er", "ic", "able", "ible", "ant", "ement", "ment", "ent", "sion",
            "tion", "ou", "ism", "ate", "iti", "ous", "ive", "ize", "e", "ll", "at", "bl", "iz", "ying", "ied",
        )
        val words = LinkedHashSet<String>()
        for (r in roots) for (s in suffixes) for (s2 in listOf("", "s", "ing", "ed", "ly", "ness")) words += r + s + s2
        words += listOf("a", "aa", "is", "as", "ss", "sky", "skies", "y", "yy", "eye", "eyes", "caresses", "ponies",
            "ties", "caress", "cats", "feed", "agreed", "plastered", "bled", "motoring", "sing", "conflated", "troubled",
            "sized", "hopping", "tanned", "falling", "hissing", "fizzed", "failing", "filing", "happy", "généralisation",
            "naïveté", "x2ing", "123ed", "c3po")
        var n = 0
        for (w in words) {
            assertEquals(luceneStem(w), PorterStemmer.stem(w), w)
            n++
        }
        assert(n > 15_000) { "only $n words" }
    }

    @Test
    fun `stop words are Lucene's`() {
        val luceneSet = LuceneEnglish.ENGLISH_STOP_WORDS_SET.map { String(it as CharArray) }.toSet()
        assertEquals(luceneSet, EnglishAnalyzer.STOP_WORDS)
    }
}
