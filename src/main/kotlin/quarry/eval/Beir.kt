package quarry.eval

import quarry.index.Document
import quarry.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln

/**
 * A BEIR dataset directory: corpus.jsonl, queries.jsonl and qrels/<split>.tsv
 * (https://github.com/beir-cellar/beir).
 */
class BeirDataset(val dir: Path) {
    val name: String = dir.fileName.toString()

    /** Documents with one "contents" field: title and text joined, as Pyserini's flat BEIR indexes do. */
    fun corpus(): Sequence<Document> = sequence {
        Files.newBufferedReader(dir.resolve("corpus.jsonl")).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val j = Json.parseObject(line)
                val title = (j["title"] as? String).orEmpty().trim()
                val text = (j["text"] as? String).orEmpty().trim()
                val contents = if (title.isEmpty()) text else if (text.isEmpty()) title else "$title $text"
                yield(Document(j["_id"].toString(), mapOf("contents" to contents)))
            }
        }
    }

    fun queries(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Files.newBufferedReader(dir.resolve("queries.jsonl")).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val j = Json.parseObject(line)
                out[j["_id"].toString()] = j["text"].toString()
            }
        }
        return out
    }

    /** query id -> (doc id -> relevance grade). */
    fun qrels(split: String = "test"): Map<String, Map<String, Int>> {
        val out = LinkedHashMap<String, MutableMap<String, Int>>()
        Files.newBufferedReader(dir.resolve("qrels").resolve("$split.tsv")).useLines { lines ->
            for ((n, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                val p = line.split('\t')
                if (n == 0 && p[0] == "query-id") continue
                out.getOrPut(p[0]) { LinkedHashMap() }[p[1]] = p[2].trim().toDouble().toInt()
            }
        }
        return out
    }
}

/** IR metrics with trec_eval's conventions (which BEIR uses through pytrec_eval). */
object Metrics {
    /**
     * trec_eval ranks by score descending and breaks ties by document id in
     * descending string order, ignoring the run's own order.
     */
    fun trecOrder(run: List<Pair<String, Float>>): List<String> =
        run.sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenByDescending { it.first }).map { it.first }

    /** nDCG@k with linear gains (trec_eval's ndcg_cut). */
    fun ndcg(ranked: List<String>, rels: Map<String, Int>, k: Int): Double {
        var dcg = 0.0
        for ((i, d) in ranked.take(k).withIndex()) {
            val g = rels[d] ?: 0
            if (g > 0) dcg += g / log2(i + 2.0)
        }
        val ideal = rels.values.filter { it > 0 }.sortedDescending().take(k)
        var idcg = 0.0
        for ((i, g) in ideal.withIndex()) idcg += g / log2(i + 2.0)
        return if (idcg == 0.0) 0.0 else dcg / idcg
    }

    fun recall(ranked: List<String>, rels: Map<String, Int>, k: Int): Double {
        val relevant = rels.count { it.value > 0 }
        if (relevant == 0) return 0.0
        return ranked.take(k).count { (rels[it] ?: 0) > 0 }.toDouble() / relevant
    }

    fun mrr(ranked: List<String>, rels: Map<String, Int>, k: Int): Double {
        for ((i, d) in ranked.take(k).withIndex()) if ((rels[d] ?: 0) > 0) return 1.0 / (i + 1)
        return 0.0
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
