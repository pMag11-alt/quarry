package quarry

import quarry.analysis.EnglishAnalyzer
import quarry.eval.BeirDataset
import quarry.eval.Metrics
import quarry.index.Document
import quarry.index.IndexReader
import quarry.index.IndexWriter
import quarry.index.Manifest
import quarry.index.Segment
import quarry.json.Json
import quarry.search.BM25
import quarry.search.Highlighter
import quarry.search.QueryParser
import quarry.search.Searcher
import quarry.search.bagOfWords
import quarry.server.SearchServer
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """quarry: a full-text search engine

usage:
  quarry index  <index-dir> <docs.jsonl>...   add JSON lines documents ({"id": .., "title": .., "text": ..})
  quarry search <index-dir> <query>           search (Lucene-style query syntax)
  quarry serve  <index-dir>                   HTTP API and search page (default port 8080)
  quarry eval   <beir-dataset-dir>            index a BEIR dataset and report nDCG@10 / Recall@100
  quarry stats  <index-dir>                   segments, documents, terms
  quarry delete <index-dir> <id>...           delete documents by id
  quarry check  <index-dir>                   verify segment checksums

options:
  --fields a,b        fields to index from each JSON document (index; default: title,text)
  --id <name>         JSON key holding the document id (index; default: id, then _id)
  --search a^2,b      default search fields with optional boosts (search, serve; default: all)
  -k <n>              results to show (default 10)
  --k1 <x> --b <x>    BM25 parameters (default 0.9 and 0.4, as Pyserini's BEIR runs)
  --port <n>          serve: port (default 8080)
  --index <dir>       eval: where to build the index (default build/beir/<name>)
  --hits <n>          eval: results per query (default 1000)
  --run <file>        eval: also write a TREC run file
"""

private class Args(argv: Array<String>) {
    val positional = ArrayList<String>()
    val opts = HashMap<String, String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("-") && a.length > 1 && a.toDoubleOrNull() == null) {
                val key = a.trimStart('-')
                if (key in setOf("reindex", "help", "h")) opts[key] = "true"
                else {
                    if (i + 1 >= argv.size) fail("$a needs a value")
                    opts[key] = argv[++i]
                }
            } else positional += a
            i++
        }
    }

    fun int(key: String, def: Int) = opts[key]?.toIntOrNull() ?: def
    fun float(key: String, def: Float) = opts[key]?.toFloatOrNull() ?: def
}

private fun fail(msg: String): Nothing {
    System.err.println("quarry: $msg")
    exitProcess(1)
}

private fun bm25(a: Args) = BM25(a.float("k1", 0.9f), a.float("b", 0.4f))

private fun searchFields(a: Args, reader: IndexReader): Map<String, Float> {
    val spec = a.opts["search"] ?: return reader.fields.associateWith { 1f }
    return spec.split(',').associate {
        val (f, b) = if ('^' in it) it.split('^', limit = 2) else listOf(it, "1")
        f.trim() to (b.toFloatOrNull() ?: fail("bad boost in --search $spec"))
    }
}

private fun cmdIndex(a: Args) {
    if (a.positional.size < 2) fail("index needs <index-dir> and at least one file")
    val dir = Paths.get(a.positional[0])
    val fields = a.opts["fields"]?.split(',')?.map { it.trim() } ?: (Manifest.read(dir)?.fields ?: listOf("title", "text"))
    val idKey = a.opts["id"]
    val t0 = System.nanoTime()
    var n = 0
    IndexWriter(dir, fields).use { w ->
        for (file in a.positional.drop(1)) {
            Files.newBufferedReader(Paths.get(file)).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val j = Json.parseObject(line)
                    val id = (idKey?.let { j[it] } ?: j["id"] ?: j["_id"])?.toString() ?: fail("$file: a document has no id")
                    w.add(Document(id, fields.associateWith { (j[it] as? String).orEmpty() }))
                    if (++n % 50_000 == 0) System.err.print("\rindexed $n documents")
                }
            }
        }
        w.commit()
    }
    val s = (System.nanoTime() - t0) / 1e9
    System.err.println("\rindexed $n documents in %.1f s (%.0f docs/s)".format(s, n / s))
}

private fun cmdSearch(a: Args) {
    if (a.positional.size < 2) fail("search needs <index-dir> and a query")
    IndexReader.open(Paths.get(a.positional[0])).use { reader ->
        val analyzer = EnglishAnalyzer()
        val searcher = Searcher(reader, bm25(a))
        val query = QueryParser(analyzer, searchFields(a, reader)).parse(a.positional.drop(1).joinToString(" "))
        val t0 = System.nanoTime()
        val top = searcher.search(query, a.int("k", 10))
        val ms = (System.nanoTime() - t0) / 1e6
        val hl = Highlighter(analyzer)
        val terms = hl.termsOf(query)
        println("query: $query")
        println("${top.hits.size} results in %.1f ms\n".format(ms))
        for ((rank, h) in top.hits.withIndex()) {
            val doc = reader.document(h.doc)
            val title = doc.fields["title"] ?: doc.fields.values.firstOrNull().orEmpty().take(80)
            println("%2d. %-8.4f %s  %s".format(rank + 1, h.score, doc.id, title.take(90)))
            val snippet = doc.fields.entries.firstNotNullOfOrNull { (f, v) -> terms[f]?.let { hl.highlight(v, it) } }
            if (snippet != null) println("    " + snippet.replace("<mark>", "\u001B[1m").replace("</mark>", "\u001B[0m"))
        }
    }
}

private fun cmdStats(a: Args) {
    val dir = Paths.get(a.positional.firstOrNull() ?: fail("stats needs <index-dir>"))
    IndexReader.open(dir).use { r ->
        println("index: $dir (generation ${r.manifest.generation})")
        println("documents: ${r.numDocs} live, ${r.maxDoc - r.numDocs} deleted, ${r.segments.size} segments")
        for (s in r.segments) {
            val size = Files.size(s.path)
            println("  ${s.name}: ${s.maxDoc} docs (${s.maxDoc - s.liveCount()} deleted), %.1f MB".format(size / 1e6))
        }
        for (f in r.fields) {
            val st = r.stats(f)
            val terms = r.segments.sumOf { it.fields[f]?.termCount ?: 0 }
            println("field $f: ${st.docCount} docs, ${st.sumTotalTermFreq} tokens (avg %.1f), $terms terms (summed over segments)".format(st.sumTotalTermFreq.toDouble() / maxOf(1, st.docCount)))
        }
    }
}

private fun cmdDelete(a: Args) {
    if (a.positional.size < 2) fail("delete needs <index-dir> and ids")
    IndexWriter(Paths.get(a.positional[0])).use { w ->
        for (id in a.positional.drop(1)) println("$id: " + if (w.delete(id)) "deleted" else "not found")
        w.commit()
    }
}

private fun cmdCheck(a: Args) {
    val dir = Paths.get(a.positional.firstOrNull() ?: fail("check needs <index-dir>"))
    val m = Manifest.read(dir) ?: fail("no index in $dir")
    var ok = true
    for (s in m.segments) {
        try {
            val seg = Segment(dir.resolve("${s.name}.seg"), s.name, verify = true)
            check(seg.maxDoc == s.maxDoc) { "maxDoc ${seg.maxDoc} != manifest ${s.maxDoc}" }
            println("${s.name}: ok (${s.maxDoc} docs)")
        } catch (e: Exception) {
            ok = false
            println("${s.name}: FAILED: ${e.message}")
        }
    }
    if (!ok) exitProcess(1)
}

private fun cmdEval(a: Args) {
    val data = BeirDataset(Paths.get(a.positional.firstOrNull() ?: fail("eval needs a BEIR dataset directory")))
    val indexDir = Paths.get(a.opts["index"] ?: "build/beir/${data.name}")
    val analyzer = EnglishAnalyzer()
    if (a.opts["reindex"] != null && Files.exists(indexDir)) indexDir.toFile().deleteRecursively()
    if (Manifest.read(indexDir) == null) {
        val t0 = System.nanoTime()
        var n = 0
        IndexWriter(indexDir, listOf("contents"), analyzer).use { w ->
            for (doc in data.corpus()) {
                w.addNew(doc)
                n++
            }
            w.forceMerge(1)
        }
        val s = (System.nanoTime() - t0) / 1e9
        System.err.println("${data.name}: indexed $n documents in %.1f s (%.0f docs/s)".format(s, n / s))
    }
    val queries = data.queries()
    val qrels = data.qrels(a.opts["split"] ?: "test")
    val hits = a.int("hits", 1000)
    IndexReader.open(indexDir).use { reader ->
        val searcher = Searcher(reader, bm25(a))
        val run = a.opts["run"]?.let { Files.newBufferedWriter(Paths.get(it)) }
        var ndcg = 0.0
        var recall = 0.0
        var mrr = 0.0
        var evaluated = 0
        val latencies = ArrayList<Double>()
        for ((qid, rels) in qrels) {
            val text = queries[qid] ?: continue
            val q = bagOfWords("contents", analyzer.terms(text))
            val t0 = System.nanoTime()
            val top = searcher.search(q, hits + 1)
            latencies += (System.nanoTime() - t0) / 1e6
            // BEIR drops results whose id equals the query id (ArguAna, Quora).
            val results = top.hits.map { reader.id(it.doc) to it.score }.filter { it.first != qid }.take(hits)
            run?.let { w -> results.forEachIndexed { i, (d, s) -> w.write("$qid Q0 $d ${i + 1} $s quarry\n") } }
            val ranked = Metrics.trecOrder(results)
            ndcg += Metrics.ndcg(ranked, rels, 10)
            recall += Metrics.recall(ranked, rels, 100)
            mrr += Metrics.mrr(ranked, rels, 10)
            evaluated++
        }
        run?.close()
        latencies.sort()
        val total = latencies.sum()
        val visited = searcher.candidates.toDouble() / evaluated
        println(
            "%s: nDCG@10 %.4f  Recall@100 %.4f  MRR@10 %.4f  (%d queries, %s, %d docs; mean %.2f ms, p99 %.2f ms per query at k=%d, %.0f docs scored per query)".format(
                data.name, ndcg / evaluated, recall / evaluated, mrr / evaluated, evaluated, searcher.bm25, reader.numDocs,
                total / latencies.size, latencies[(latencies.size * 99 / 100).coerceAtMost(latencies.size - 1)], hits, visited,
            ),
        )
    }
}

fun main(argv: Array<String>) {
    if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) {
        print(USAGE)
        return
    }
    val a = Args(argv.copyOfRange(1, argv.size))
    try {
        when (argv[0]) {
            "index" -> cmdIndex(a)
            "search" -> cmdSearch(a)
            "serve" -> {
                val dir = Paths.get(a.positional.firstOrNull() ?: fail("serve needs <index-dir>"))
                SearchServer(dir, bm25(a), a.opts["search"]).start(a.int("port", 8080))
            }
            "eval" -> cmdEval(a)
            "stats" -> cmdStats(a)
            "delete" -> cmdDelete(a)
            "check" -> cmdCheck(a)
            else -> fail("unknown command ${argv[0]}\n\n$USAGE")
        }
    } catch (e: QueryParser.ParseException) {
        fail("bad query: ${e.message}")
    } catch (e: java.io.IOException) {
        fail(e.message ?: e.toString())
    }
}
