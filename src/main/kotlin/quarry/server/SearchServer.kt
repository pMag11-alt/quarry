package quarry.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import quarry.analysis.EnglishAnalyzer
import quarry.index.Document
import quarry.index.IndexReader
import quarry.index.IndexWriter
import quarry.json.Json
import quarry.search.BM25
import quarry.search.Highlighter
import quarry.search.PrefixQuery
import quarry.search.Query
import quarry.search.QueryParser
import quarry.search.Searcher
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * HTTP API over an index:
 *
 *   GET    /search?q=..&k=10&offset=0   hits with highlighted snippets
 *   GET    /docs/{id}                   one stored document
 *   POST   /docs                        add or replace documents (a JSON object, an array, or JSON lines)
 *   DELETE /docs/{id}                   delete a document
 *   GET    /stats                       index statistics
 *   GET    /                            a search page
 *
 * Searches run concurrently on a point-in-time reader; writes go through a
 * single IndexWriter and become visible when their request commits.
 */
class SearchServer(private val dir: Path, private val bm25: BM25, private val searchSpec: String?) {
    private val analyzer = EnglishAnalyzer()
    private val highlighter = Highlighter(analyzer)

    @Volatile
    private var reader: IndexReader = IndexReader.open(dir)
    private var writer: IndexWriter? = null
    private val writeLock = Any()

    private fun fields(r: IndexReader): Map<String, Float> {
        val spec = searchSpec ?: return r.fields.associateWith { 1f }
        return spec.split(',').associate {
            val parts = it.split('^', limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.toFloatOrNull() ?: 1f)
        }
    }

    fun start(port: Int) {
        val server = HttpServer.create(InetSocketAddress(port), 128)
        server.executor = Executors.newFixedThreadPool(maxOf(4, Runtime.getRuntime().availableProcessors()))
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        System.err.println("quarry: serving $dir (${reader.numDocs} documents) on http://localhost:$port")
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val method = ex.requestMethod
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            when {
                method == "OPTIONS" -> {
                    ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
                    ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
                    send(ex, 204, "", "text/plain")
                }
                path == "/" && method == "GET" -> send(ex, 200, ui, "text/html; charset=utf-8")
                path == "/search" && method == "GET" -> search(ex)
                path == "/stats" && method == "GET" -> json(ex, 200, stats())
                path == "/docs" && method == "POST" -> addDocs(ex)
                path.startsWith("/docs/") && method == "GET" -> getDoc(ex, decode(path.removePrefix("/docs/")))
                path.startsWith("/docs/") && method == "DELETE" -> deleteDoc(ex, decode(path.removePrefix("/docs/")))
                else -> json(ex, 404, mapOf("error" to "not found"))
            }
        } catch (e: QueryParser.ParseException) {
            json(ex, 400, mapOf("error" to "bad query: ${e.message}"))
        } catch (e: Json.ParseException) {
            json(ex, 400, mapOf("error" to "bad JSON: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            json(ex, 400, mapOf("error" to (e.message ?: "bad request")))
        } catch (e: Exception) {
            e.printStackTrace()
            json(ex, 500, mapOf("error" to (e.message ?: e.toString())))
        } finally {
            ex.close()
        }
    }

    private fun params(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            val kv = it.split('=', limit = 2)
            decode(kv[0]) to decode(kv.getOrElse(1) { "" })
        }

    private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)

    private fun search(ex: HttpExchange) {
        val p = params(ex)
        val text = p["q"] ?: throw IllegalArgumentException("missing q")
        val k = (p["k"]?.toIntOrNull() ?: 10).coerceIn(1, 1000)
        val offset = (p["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 10_000)
        val r = reader
        val searcher = Searcher(r, bm25)
        val query = QueryParser(analyzer, fields(r)).parse(text)
        val t0 = System.nanoTime()
        val top = searcher.search(query, offset + k)
        val took = (System.nanoTime() - t0) / 1e6
        val terms = highlighter.termsOf(query)
        val prefixes = prefixesOf(query)
        val hits = top.hits.drop(offset).map { h ->
            val doc = r.document(h.doc)
            val hl = LinkedHashMap<String, String>()
            for ((f, v) in doc.fields) {
                val snippet = highlighter.highlight(v, terms[f] ?: emptySet(), prefixes[f] ?: emptyList())
                if (snippet != null) hl[f] = snippet
            }
            mapOf("id" to doc.id, "score" to h.score, "fields" to doc.fields.mapValues { it.value.take(2000) }, "highlights" to hl)
        }
        json(
            ex, 200,
            mapOf(
                "query" to query.toString(), "took_ms" to Math.round(took * 100) / 100.0,
                "total" to top.totalHits, "total_is_lower_bound" to top.totalIsLowerBound, "hits" to hits,
            ),
        )
    }

    private fun prefixesOf(q: Query): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        fun walk(x: Query) {
            when (x) {
                is PrefixQuery -> out.getOrPut(x.field) { ArrayList() } += x.prefix
                is quarry.search.BooleanQuery -> x.clauses.forEach { walk(it.query) }
                else -> {}
            }
        }
        walk(q)
        return out
    }

    private fun stats(): Map<String, Any?> {
        val r = reader
        return mapOf(
            "documents" to r.numDocs,
            "deleted" to (r.maxDoc - r.numDocs),
            "segments" to r.segments.map { mapOf("name" to it.name, "docs" to it.maxDoc, "live" to it.liveCount()) },
            "fields" to r.fields.associateWith { f -> r.stats(f).let { mapOf("docs" to it.docCount, "tokens" to it.sumTotalTermFreq) } },
            "similarity" to bm25.toString(),
        )
    }

    private fun getDoc(ex: HttpExchange, id: String) {
        val r = reader
        val d = r.lookup(id)
        if (d < 0) json(ex, 404, mapOf("error" to "no document $id"))
        else json(ex, 200, r.document(d).let { mapOf("id" to it.id, "fields" to it.fields) })
    }

    private fun <T> write(action: (IndexWriter) -> T): T = synchronized(writeLock) {
        val w = writer ?: IndexWriter(dir).also { writer = it }
        val result = action(w)
        w.commit()
        reader = reader.reopen()
        result
    }

    private fun addDocs(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8).trim()
        val items: List<Any?> = when {
            body.startsWith("[") -> Json.parse(body) as List<*>
            body.startsWith("{") && body.contains("}\n{") -> body.lines().filter { it.isNotBlank() }.map { Json.parse(it) }
            else -> listOf(Json.parse(body))
        }
        val n = write { w ->
            for (item in items) {
                val m = item as? Map<*, *> ?: throw IllegalArgumentException("documents must be JSON objects")
                val id = (m["id"] ?: m["_id"])?.toString() ?: throw IllegalArgumentException("a document has no id")
                w.add(Document(id, w.fields.associateWith { (m[it] as? String).orEmpty() }))
            }
            items.size
        }
        json(ex, 200, mapOf("indexed" to n, "documents" to reader.numDocs))
    }

    private fun deleteDoc(ex: HttpExchange, id: String) {
        val deleted = write { it.delete(id) }
        json(ex, if (deleted) 200 else 404, mapOf("deleted" to deleted, "documents" to reader.numDocs))
    }

    private fun json(ex: HttpExchange, status: Int, body: Any?) = send(ex, status, Json.write(body), "application/json")

    private fun send(ex: HttpExchange, status: Int, body: String, type: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", type)
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private val ui: String by lazy {
        SearchServer::class.java.getResourceAsStream("/quarry/ui.html")!!.readBytes().toString(Charsets.UTF_8)
    }
}
