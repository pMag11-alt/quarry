package quarry.search

import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.NoMergePolicy
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.similarities.BM25Similarity
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.util.SmallFloat as LuceneSmallFloat
import quarry.analysis.EnglishAnalyzer
import quarry.index.Document
import quarry.index.IndexReader
import quarry.index.IndexWriter
import quarry.index.SmallFloat
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.apache.lucene.analysis.en.EnglishAnalyzer as LuceneEnglish
import org.apache.lucene.document.Document as LuceneDoc
import org.apache.lucene.index.IndexWriter as LuceneWriter
import org.apache.lucene.search.BooleanQuery as LBool
import org.apache.lucene.search.PhraseQuery as LPhrase
import org.apache.lucene.search.Query as LQuery
import org.apache.lucene.search.TermQuery as LTerm

/**
 * Indexes the same documents with quarry and with Lucene and checks that
 * every query returns the same documents with bit-identical scores.
 */
class LuceneScoringParityTest {
    private val dir = Files.createTempDirectory("quarry-parity")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `length encoding matches Lucene's SmallFloat`() {
        val values = (0..100_000).toList() + (0..30).map { 1 shl it } + listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1)
        for (v in values) {
            val b = LuceneSmallFloat.intToByte4(v).toInt() and 0xFF
            assertEquals(b, SmallFloat.intToByte4(v), "intToByte4($v)")
        }
        for (b in 0..255) assertEquals(LuceneSmallFloat.byte4ToInt(b.toByte()), SmallFloat.byte4ToInt(b), "byte4ToInt($b)")
    }

    /** Zipf-ish random text over a vocabulary that exercises stemming and stop words. */
    private fun corpus(n: Int, rnd: Random): List<Document> {
        val vocab = listOf(
            "search", "searching", "searches", "engine", "engines", "index", "indexing", "indexed", "query", "queries",
            "ranking", "rank", "score", "scores", "document", "documents", "term", "terms", "posting", "postings", "the",
            "of", "and", "a", "to", "in", "is", "for", "with", "lucene", "kotlin", "segment", "segments", "merge",
            "merging", "compression", "block", "blocks", "skip", "relevance", "relevant", "bm25", "okapi", "probability",
            "retrieval", "information", "system", "systems", "fast", "faster", "speed", "memory", "disk", "cache",
        ) + (0 until 300).map { "w$it" }
        return (0 until n).map { i ->
            val len = 1 + (rnd.nextDouble().let { it * it * 120 }).toInt()
            val words = (0 until len).map {
                val r = rnd.nextDouble()
                vocab[((r * r * r) * vocab.size).toInt().coerceAtMost(vocab.size - 1)]
            }
            Document("d$i", mapOf("body" to words.joinToString(" ") + if (i % 7 == 0) ". The Engine's ranking!" else ""))
        }
    }

    private class Both(val quarry: Query, val lucene: LQuery)

    private fun term(t: String, boost: Float = 1f): Both {
        val lt: LQuery = LTerm(Term("body", t))
        return Both(TermQuery("body", t, boost), if (boost == 1f) lt else BoostQuery(lt, boost))
    }

    private fun bool(vararg clauses: Pair<Both, Occur>): Both {
        val b = LBool.Builder()
        for ((q, o) in clauses) {
            b.add(
                q.lucene,
                when (o) {
                    Occur.MUST -> BooleanClause.Occur.MUST
                    Occur.SHOULD -> BooleanClause.Occur.SHOULD
                    Occur.MUST_NOT -> BooleanClause.Occur.MUST_NOT
                    Occur.FILTER -> BooleanClause.Occur.FILTER
                },
            )
        }
        return Both(BooleanQuery(clauses.map { Clause(it.first.quarry, it.second) }), b.build())
    }

    private fun phrase(vararg terms: String): Both {
        val b = LPhrase.Builder()
        terms.forEachIndexed { i, t -> b.add(Term("body", t), i) }
        return Both(PhraseQuery("body", terms.toList()), b.build())
    }

    @Test
    fun `scores and rankings match Lucene across segments and deletions`() {
        val rnd = Random(7)
        val docs = corpus(3000, rnd)
        val deleted = docs.indices.filter { it % 11 == 3 }.map { "d$it" }

        IndexWriter(dir, listOf("body"), maxBufferedDocs = 400, mergeFactor = 1000).use { w ->
            for (d in docs) w.addNew(d)
            w.commit()
            for (id in deleted) w.delete(id)
            w.commit()
        }
        val luceneDir = ByteBuffersDirectory()
        val cfg = IndexWriterConfig(LuceneEnglish()).setSimilarity(BM25Similarity(0.9f, 0.4f))
            .setMaxBufferedDocs(400).setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH.toDouble()).setMergePolicy(NoMergePolicy.INSTANCE)
        LuceneWriter(luceneDir, cfg).use { w ->
            for (d in docs) {
                val ld = LuceneDoc()
                ld.add(StringField("id", d.id, Field.Store.YES))
                ld.add(TextField("body", d.fields.getValue("body"), Field.Store.NO))
                w.addDocument(ld)
            }
            w.commit()
            for (id in deleted) w.deleteDocuments(Term("id", id))
            w.commit()
        }

        val analyzer = EnglishAnalyzer()
        val stem = { w: String -> analyzer.terms(w).single() }
        val queries = mutableListOf(
            term(stem("search")),
            term(stem("ranking"), 3f),
            bool(term(stem("engine")) to Occur.SHOULD, term(stem("index")) to Occur.SHOULD, term("w7") to Occur.SHOULD),
            bool(term(stem("engine")) to Occur.MUST, term(stem("index")) to Occur.SHOULD, term("w3") to Occur.SHOULD),
            bool(term(stem("query")) to Occur.MUST, term(stem("score")) to Occur.MUST, term("w1") to Occur.MUST_NOT),
            bool(term(stem("document")) to Occur.FILTER, term(stem("relevance")) to Occur.SHOULD),
            bool(term(stem("segment")) to Occur.MUST, term(stem("merging")) to Occur.FILTER, term(stem("block")) to Occur.SHOULD),
            phrase(stem("engine"), stem("ranking")),
            phrase(stem("search"), stem("engine")),
            bool(phrase(stem("search"), stem("engine")) to Occur.SHOULD, term(stem("lucene")) to Occur.SHOULD),
        )
        // Random bag-of-words queries, the BEIR workload.
        repeat(200) {
            val n = rnd.nextInt(1, 6)
            val words = (0 until n).map { "w" + rnd.nextInt(0, 60) } + listOf(stem("search"), stem("fast")).take(rnd.nextInt(0, 3))
            val counts = words.groupingBy { it }.eachCount()
            queries += bool(*counts.map { (t, c) -> term(t, c.toFloat()) to Occur.SHOULD }.toTypedArray())
        }

        IndexReader.open(dir).use { reader ->
            val searcher = Searcher(reader, BM25(0.9f, 0.4f))
            DirectoryReader.open(luceneDir).use { lr ->
                val ls = IndexSearcher(lr)
                ls.similarity = BM25Similarity(0.9f, 0.4f)
                val stored = lr.storedFields()
                for (q in queries) {
                    for (exhaustive in listOf(false, true)) {
                        val ours = searcher.search(q.quarry, 50, exhaustive).hits.map { reader.id(it.doc) to it.score }
                        val theirs = ls.search(q.lucene, 50).scoreDocs.map { stored.document(it.doc).get("id") to it.score }
                        assertEquals(theirs, ours, "query ${q.quarry} (exhaustive=$exhaustive)")
                    }
                    assertEquals(ls.count(q.lucene).toLong(), searcher.count(q.quarry), "count of ${q.quarry}")
                }
            }
        }
    }
}
