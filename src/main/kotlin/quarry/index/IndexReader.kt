package quarry.index

import java.io.Closeable
import java.io.IOException
import java.nio.file.Path

/** Collection-wide statistics of a field, summed over segments. */
data class FieldStats(val docCount: Long, val sumTotalTermFreq: Long)

/**
 * A point-in-time view of the index: the segments listed by the manifest
 * when it was opened. Later commits are not visible until reopen().
 */
class IndexReader private constructor(
    val dir: Path,
    val manifest: Manifest,
    val segments: List<Segment>,
) : Closeable {
    /** Global doc id of each segment's first document. */
    val docBases = IntArray(segments.size)
    val maxDoc: Int
    val numDocs: Int
    val fields: List<String> get() = manifest.fields

    init {
        var base = 0
        for ((i, s) in segments.withIndex()) {
            docBases[i] = base
            base += s.maxDoc
        }
        maxDoc = base
        numDocs = segments.sumOf { it.liveCount() }
    }

    fun stats(field: String): FieldStats {
        var dc = 0L
        var ttf = 0L
        for (s in segments) {
            val f = s.fields[field] ?: continue
            dc += f.docCount
            ttf += f.sumTotalTermFreq
        }
        return FieldStats(dc, ttf)
    }

    /** Documents containing the term (deleted ones too, as in Lucene). */
    fun docFreq(field: String, term: String): Long = segments.sumOf { it.fields[field]?.lookup(term)?.docFreq?.toLong() ?: 0L }

    fun segmentOf(globalDoc: Int): Int {
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (docBases[mid] <= globalDoc) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun document(globalDoc: Int): Document {
        val s = segmentOf(globalDoc)
        return segments[s].document(globalDoc - docBases[s])
    }

    fun id(globalDoc: Int): String {
        val s = segmentOf(globalDoc)
        return segments[s].id(globalDoc - docBases[s])
    }

    /** Global doc id of a live document with this external id, or -1. */
    fun lookup(id: String): Int {
        for ((i, s) in segments.withIndex()) {
            val d = s.lookupId(id)
            if (d >= 0 && s.isLive(d)) return docBases[i] + d
        }
        return -1
    }

    /** A reader on the latest commit, reusing unchanged segments. */
    fun reopen(): IndexReader {
        val m = Manifest.read(dir) ?: throw IOException("no index in $dir")
        if (m.generation == manifest.generation) return this
        return open(dir, this)
    }

    override fun close() {
        segments.forEach { it.close() }
    }

    companion object {
        fun open(dir: Path, previous: IndexReader? = null): IndexReader {
            val m = Manifest.read(dir) ?: throw IOException("no index in $dir (missing ${Manifest.FILE})")
            val old = previous?.segments?.associateBy { it.name } ?: emptyMap()
            val segs = m.segments.map { info ->
                val reuse = old[info.name]
                val seg = reuse ?: Segment(segmentFile(dir, info.name), info.name)
                if (reuse != null && reuse.deleted?.cardinality() ?: 0 == info.deleted) {
                    reuse
                } else {
                    // A segment object is shared by readers, so give the new
                    // view its own instance when deletions changed.
                    val s = if (reuse != null) Segment(segmentFile(dir, info.name), info.name, verify = false) else seg
                    s.deleted = if (info.delGen > 0) DeletesIO.read(deletesFile(dir, info.name, info.delGen)) else null
                    s
                }
            }
            return IndexReader(dir, m, segs)
        }
    }
}
