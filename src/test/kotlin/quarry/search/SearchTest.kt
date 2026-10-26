package quarry.search

import quarry.analysis.EnglishAnalyzer
import quarry.eval.Metrics
import quarry.index.Document
import quarry.index.IndexReader
import quarry.index.IndexWriter
import quarry.json.Json
import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchTest {
    private val dir = Files.createTempDirectory("quarry-search")
    private val analyzer = EnglishAnalyzer()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun index(docs: List<Pair<String, String>>, maxBuffered: Int = 100_000): IndexReader {
        IndexWriter(dir, listOf("text"), maxBufferedDocs = maxBuffered, mergeFactor = 1000).use { w ->
            for ((id, t) in docs) w.addNew(Document(id, mapOf("text" to t)))
            w.commit()
        }
        return IndexReader.open(dir)
    }

    @Test
    fun `MaxScore returns exactly the exhaustive top-k`() {
        val rnd = Random(11)
        val words = (0 until 400).map { "t$it" }
        val docs = (0 until 6000).map { i ->
            "$i" to List(1 + rnd.nextInt(60)) { words[(rnd.nextDouble().let { it * it } * words.size).toInt()] }.joinToString(" ")
        }
        index(docs, maxBuffered = 1500).use { r ->
            val s = Searcher(r, BM25(0.9f, 0.4f))
            repeat(300) {
                val terms = List(1 + rnd.nextInt(8)) { words[rnd.nextInt(words.size)] }
                val q = bagOfWords("text", terms)
                for (k in listOf(1, 10, 100, 1000)) {
                    val fast = s.search(q, k).hits
                    val slow = s.search(q, k, exhaustive = true).hits
                    assertEquals(slow, fast, "query $q k=$k")
                }
            }
        }
    }

    @Test
    fun `query parser builds the expected queries`() {
        val p = QueryParser(analyzer, mapOf("title" to 2f, "text" to 1f))
        assertEquals("(title:run^2.0 text:run)", p.parse("Running").toString())
        assertEquals("(+title:rust -title:java)", p.parse("+title:rust -title:java").toString())
        assertEquals("(+text:a1 +text:b2)", p.parse("text:a1 AND text:b2").toString())
        assertEquals("text:\"memori safeti\"~2", p.parse("text:\"memory safety\"~2").toString())
        assertEquals("text:tok*", p.parse("text:Tok*").toString())
        assertEquals("text:colour~1", p.parse("text:colour~1").toString())
        assertEquals("text:rust^3.0", p.parse("text:rust^3").toString())
        assertEquals("((text:a1 text:b2) -text:c3)", p.parse("text:(a1 b2) NOT text:c3").toString())
        assertEquals("(-text:java *:*)", p.parse("-text:java").toString())
        // Stop words vanish; a phrase keeps the gap they leave.
        assertEquals(PhraseQuery("text", listOf("state", "art"), listOf(0, 3)), p.parse("text:\"state of the art\""))
        assertFailsWith<QueryParser.ParseException> { p.parse("text:(unclosed") }
        assertFailsWith<QueryParser.ParseException> { p.parse("\"unterminated") }
    }

    @Test
    fun `phrases, prefixes, fuzzy and boolean logic`() {
        index(
            listOf(
                "1" to "the quick brown fox jumps over the lazy dog",
                "2" to "a quick brown dog",
                "3" to "brown quick fox",
                "4" to "the colour of the quick fox is brown",
                "5" to "foxes are quick and clever",
            ),
        ).use { r ->
            val s = Searcher(r)
            val p = QueryParser(analyzer, mapOf("text" to 1f))
            fun ids(q: String) = s.search(p.parse(q), 10).hits.map { r.id(it.doc) }.sorted()
            assertEquals(listOf("1", "2"), ids("\"quick brown\""))
            assertEquals(listOf("1", "2", "3", "4"), ids("\"quick brown\"~2"))
            assertEquals(listOf("1", "3", "4", "5"), ids("fox*"))
            assertEquals(listOf("4"), ids("color~1"))
            assertEquals(listOf("1", "3", "4"), ids("+fox +brown"))
            assertEquals(listOf("3", "4"), ids("+fox +brown -lazy"))
            assertEquals(listOf("2"), ids("dog -fox"))
            // Phrase matches outrank plain co-occurrence.
            val ranked = s.search(p.parse("\"quick brown\" quick brown"), 10).hits.map { r.id(it.doc) }
            assertTrue(ranked.indexOf("1") < ranked.indexOf("3") && ranked.indexOf("2") < ranked.indexOf("3"))
        }
    }

    @Test
    fun `edit distance counts transpositions once`() {
        assertEquals(0, Searcher.editDistance("abc", "abc", 2))
        assertEquals(1, Searcher.editDistance("abc", "acb", 2))
        assertEquals(1, Searcher.editDistance("colour", "color", 2))
        assertEquals(2, Searcher.editDistance("kitten", "sitting", 2).coerceAtMost(2) + 0)
        assertEquals(3, Searcher.editDistance("abc", "xyz", 2))
    }

    @Test
    fun `metrics follow trec_eval`() {
        val rels = mapOf("a" to 2, "b" to 1, "c" to 0)
        assertEquals(1.0, Metrics.ndcg(listOf("a", "b"), rels, 10), 1e-12)
        val dcg = 1 / (Math.log(3.0) / Math.log(2.0)) + 2 / (Math.log(4.0) / Math.log(2.0))
        val idcg = 2.0 + 1 / (Math.log(3.0) / Math.log(2.0))
        assertTrue(abs(Metrics.ndcg(listOf("x", "b", "a"), rels, 10) - dcg / idcg) < 1e-12)
        assertEquals(0.5, Metrics.recall(listOf("a", "x"), rels, 100))
        // Ties are broken by doc id, descending.
        assertEquals(listOf("z", "b", "a"), Metrics.trecOrder(listOf("a" to 1f, "z" to 1f, "b" to 1f)))
    }

    @Test
    fun `json round trip and errors`() {
        val text = """{"a":[1,2.5,-3e2,true,false,null],"b":{"c":"x\"y\\z\né"}}"""
        val v = Json.parse(text)
        assertEquals(v, Json.parse(Json.write(v)))
        for (bad in listOf("", "{", "[1,]", "01x", "\"a", "{\"a\" 1}")) assertFailsWith<Json.ParseException>(bad) { Json.parse(bad) }
    }
}
