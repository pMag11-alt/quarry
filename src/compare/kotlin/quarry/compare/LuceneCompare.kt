package quarry.compare

import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.similarities.BM25Similarity
import org.apache.lucene.store.FSDirectory
import quarry.eval.BeirDataset
import quarry.eval.Metrics
import quarry.index.IndexReader
import quarry.index.IndexWriter
import quarry.index.Manifest
import quarry.search.BM25
import quarry.search.Searcher
import quarry.search.bagOfWords
import java.nio.file.Files
import java.nio.file.Paths
import org.apache.lucene.document.Document as LuceneDocument
import org.apache.lucene.index.IndexWriter as LuceneWriter

/**
 * Indexes BEIR datasets with quarry and with Lucene (EnglishAnalyzer,
 * BM25 k1 = 0.9, b = 0.4, bag-of-words queries built the way Anserini and
 * Pyserini build them) and compares every query's ranking and scores, the
 * evaluation metrics, and indexing and query speed. Retrieves 1000 hits
 * per query unless -Pk says otherwise.
 */
private val K = System.getProperty("quarry.k")?.toInt() ?: 1000
private const val K1 = 0.9f
private const val B = 0.4f

private class Run(val ids: List<String>, val scores: List<Float>)

private fun luceneTerms(analyzer: EnglishAnalyzer, text: String): List<String> {
    val out = ArrayList<String>()
    analyzer.tokenStream("contents", text).use { ts ->
        val term = ts.addAttribute(CharTermAttribute::class.java)
        ts.reset()
        while (ts.incrementToken()) out += term.toString()
        ts.end()
    }
    return out
}

private fun timeMs(block: () -> Unit): Double {
    val t0 = System.nanoTime()
    block()
    return (System.nanoTime() - t0) / 1e6
}

private var mismatches = 0

fun main(args: Array<String>) {
    println("| dataset | docs | queries | nDCG@10 quarry | nDCG@10 Lucene | R@100 quarry | R@100 Lucene | identical top-10 | identical top-$K | max score diff | index s (q / L) | ms/query (q / L) |")
    println("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for (arg in args) compare(BeirDataset(Paths.get(arg)))
    if (mismatches > 0) {
        System.err.println("$mismatches queries differ from Lucene")
        kotlin.system.exitProcess(1)
    }
}

private fun compare(data: BeirDataset) {
    val work = Paths.get("build/compare/${data.name}")
    work.toFile().deleteRecursively()
    Files.createDirectories(work)
    val docs = data.corpus().toList()
    val queries = data.queries()
    val qrels = data.qrels()
    val evalQueries = qrels.keys.filter { it in queries }

    // ---- indexing ----
    val quarryDir = work.resolve("quarry")
    val quarryIndexMs = timeMs {
        IndexWriter(quarryDir, listOf("contents")).use { w ->
            for (d in docs) w.addNew(d)
            w.forceMerge(1)
        }
    }
    check(Manifest.read(quarryDir) != null)
    val luceneDir = FSDirectory.open(work.resolve("lucene"))
    val luceneAnalyzer = EnglishAnalyzer()
    val luceneIndexMs = timeMs {
        val cfg = IndexWriterConfig(luceneAnalyzer).setSimilarity(BM25Similarity(K1, B)).setRAMBufferSizeMB(512.0)
        LuceneWriter(luceneDir, cfg).use { w ->
            for (d in docs) {
                val doc = LuceneDocument()
                doc.add(StoredField("id", d.id))
                doc.add(TextField("contents", d.fields["contents"].orEmpty(), Field.Store.NO))
                w.addDocument(doc)
            }
            w.forceMerge(1)
        }
    }

    // ---- searching ----
    val quarryRuns = HashMap<String, Run>()
    val luceneRuns = HashMap<String, Run>()
    val analyzer = quarry.analysis.EnglishAnalyzer()
    var quarryMs = 0.0
    var luceneMs = 0.0
    IndexReader.open(quarryDir).use { reader ->
        val searcher = Searcher(reader, BM25(K1, B))
        // Warm up both engines before timing.
        for (qid in evalQueries.take(50)) searcher.search(bagOfWords("contents", analyzer.terms(queries.getValue(qid))), K)
        for (qid in evalQueries) {
            val q = bagOfWords("contents", analyzer.terms(queries.getValue(qid)))
            lateinit var top: quarry.search.TopDocs
            quarryMs += timeMs { top = searcher.search(q, K + 1) }
            val hits = top.hits.map { reader.id(it.doc) to it.score }.filter { it.first != qid }.take(K)
            quarryRuns[qid] = Run(hits.map { it.first }, hits.map { it.second })
        }
    }
    DirectoryReader.open(luceneDir).use { reader ->
        val searcher = IndexSearcher(reader)
        searcher.similarity = BM25Similarity(K1, B)
        val stored = reader.storedFields()
        fun query(text: String): BooleanQuery {
            // Anserini's BagOfWordsQueryGenerator: one SHOULD clause per
            // distinct term, boosted by its count.
            val counts = luceneTerms(luceneAnalyzer, text).groupingBy { it }.eachCount()
            val b = BooleanQuery.Builder()
            for ((t, c) in counts) b.add(BoostQuery(TermQuery(Term("contents", t)), c.toFloat()), BooleanClause.Occur.SHOULD)
            return b.build()
        }
        for (qid in evalQueries.take(50)) searcher.search(query(queries.getValue(qid)), K)
        for (qid in evalQueries) {
            val q = query(queries.getValue(qid))
            lateinit var top: org.apache.lucene.search.TopDocs
            luceneMs += timeMs { top = searcher.search(q, K + 1) }
            val hits = top.scoreDocs.map { stored.document(it.doc).get("id") to it.score }.filter { it.first != qid }.take(K)
            luceneRuns[qid] = Run(hits.map { it.first }, hits.map { it.second })
        }
    }

    // ---- comparison ----
    var same10 = 0
    var sameAll = 0
    var maxDiff = 0f
    var nq = 0.0
    var nl = 0.0
    var rq = 0.0
    var rl = 0.0
    val firstDiffs = ArrayList<String>()
    for (qid in evalQueries) {
        val a = quarryRuns.getValue(qid)
        val b = luceneRuns.getValue(qid)
        if (a.ids.take(10) == b.ids.take(10)) same10++ else if (firstDiffs.size < 3) firstDiffs += qid
        if (a.ids == b.ids && a.scores == b.scores) sameAll++ else mismatches++
        val byId = b.ids.zip(b.scores).toMap()
        for ((id, s) in a.ids.zip(a.scores)) byId[id]?.let { maxDiff = maxOf(maxDiff, Math.abs(it - s)) }
        val rels = qrels.getValue(qid)
        val ra = Metrics.trecOrder(a.ids.zip(a.scores))
        val rb = Metrics.trecOrder(b.ids.zip(b.scores))
        nq += Metrics.ndcg(ra, rels, 10)
        nl += Metrics.ndcg(rb, rels, 10)
        rq += Metrics.recall(ra, rels, 100)
        rl += Metrics.recall(rb, rels, 100)
    }
    val n = evalQueries.size
    println(
        "| %s | %d | %d | %.4f | %.4f | %.4f | %.4f | %d/%d | %d/%d | %.1e | %.1f / %.1f | %.2f / %.2f |".format(
            data.name, docs.size, n, nq / n, nl / n, rq / n, rl / n, same10, n, sameAll, n, maxDiff,
            quarryIndexMs / 1e3, luceneIndexMs / 1e3, quarryMs / n, luceneMs / n,
        ),
    )
    for (qid in firstDiffs) {
        val a = quarryRuns.getValue(qid)
        val b = luceneRuns.getValue(qid)
        System.err.println("  differs: query $qid: quarry ${a.ids.take(10).zip(a.scores.take(10))}\n                     lucene ${b.ids.take(10).zip(b.scores.take(10))}")
    }
    work.resolve("quarry").toFile().deleteRecursively()
    work.resolve("lucene").toFile().deleteRecursively()
}
