package quarry.search

import quarry.index.FieldStats
import quarry.index.SmallFloat
import kotlin.math.ln

/**
 * Okapi BM25, computed exactly as Lucene's BM25Similarity does, in float
 * arithmetic, so quarry's scores match Lucene's bit for bit:
 *
 *   idf    = ln(1 + (N - df + 0.5) / (df + 0.5))
 *   weight = boost * idf
 *   score  = weight - weight / (1 + freq / (k1 * (1 - b + b * dl / avgdl)))
 *
 * where dl is the document length after Lucene's lossy norm encoding.
 * (Lucene dropped the (k1 + 1) numerator factor; it doesn't change ranking.)
 */
class BM25(val k1: Float = 1.2f, val b: Float = 0.75f) {
    fun idf(docFreq: Long, docCount: Long): Float = ln(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5)).toFloat()

    /** 1 / (k1 * (1 - b + b * dl / avgdl)) for each of the 256 norm values. */
    fun normInverses(stats: FieldStats): FloatArray {
        val avgdl = (stats.sumTotalTermFreq / stats.docCount.toDouble()).toFloat()
        return FloatArray(256) { 1f / (k1 * ((1 - b) + b * SmallFloat.LENGTH_TABLE[it] / avgdl)) }
    }

    fun score(weight: Float, freq: Float, normInverse: Float): Float = weight - weight / (1f + freq * normInverse)

    override fun toString() = "BM25(k1=$k1, b=$b)"
}
