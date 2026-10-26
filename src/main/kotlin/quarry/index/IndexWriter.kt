package quarry.index

import quarry.analysis.Analyzer
import quarry.analysis.EnglishAnalyzer
import quarry.json.Json
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.PriorityQueue
import java.util.zip.CRC32

/** A segment as listed in the manifest. */
data class SegmentInfo(val name: String, val maxDoc: Int, val delGen: Int, val deleted: Int)

/** The commit point: which segments make up the index. */
data class Manifest(val generation: Long, val fields: List<String>, val segments: List<SegmentInfo>, val counter: Int) {
    fun toJson(): String = Json.write(
        mapOf(
            "format" to "quarry-1",
            "generation" to generation,
            "fields" to fields,
            "counter" to counter,
            "segments" to segments.map { mapOf("name" to it.name, "maxDoc" to it.maxDoc, "delGen" to it.delGen, "deleted" to it.deleted) },
        ),
    )

    companion object {
        const val FILE = "segments.json"

        fun read(dir: Path): Manifest? {
            val f = dir.resolve(FILE)
            if (!Files.exists(f)) return null
            val j = Json.parseObject(Files.readString(f))
            require(j["format"] == "quarry-1") { "$f: unknown index format ${j["format"]}" }
            @Suppress("UNCHECKED_CAST")
            return Manifest(
                generation = (j["generation"] as Long),
                fields = (j["fields"] as List<String>),
                counter = (j["counter"] as Long).toInt(),
                segments = (j["segments"] as List<Map<String, Any?>>).map {
                    SegmentInfo(it["name"] as String, (it["maxDoc"] as Long).toInt(), (it["delGen"] as Long).toInt(), (it["deleted"] as Long).toInt())
                },
            )
        }
    }
}

internal fun segmentFile(dir: Path, name: String): Path = dir.resolve("$name.seg")
internal fun deletesFile(dir: Path, name: String, gen: Int): Path = dir.resolve("${name}_$gen.del")

/** Deleted-docs bitsets: 8 bytes length, the words, then a CRC32. */
internal object DeletesIO {
    fun write(path: Path, bits: BitSet) {
        val words = bits.toLongArray()
        val bb = ByteBuffer.allocate(8 + words.size * 8 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(words.size.toLong())
        for (w in words) bb.putLong(w)
        val crc = CRC32()
        crc.update(bb.array(), 0, bb.position())
        bb.putInt(crc.value.toInt())
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
            bb.flip()
            while (bb.hasRemaining()) ch.write(bb)
            ch.force(true)
        }
    }

    fun read(path: Path): BitSet {
        val bytes = Files.readAllBytes(path)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.getLong().toInt()
        val words = LongArray(n) { bb.getLong() }
        val crc = CRC32()
        crc.update(bytes, 0, 8 + n * 8)
        if (crc.value.toInt() != bb.getInt()) throw IOException("$path: checksum mismatch")
        return BitSet.valueOf(words)
    }
}

/**
 * Adds, updates and deletes documents. Documents are buffered in memory and
 * flushed as immutable segments; commit() makes everything durable and
 * visible to newly opened readers by atomically replacing the manifest.
 * One writer per index (enforced with a lock file).
 */
class IndexWriter(
    private val dir: Path,
    fields: List<String>? = null,
    private val analyzer: Analyzer = EnglishAnalyzer(),
    private val maxBufferedDocs: Int = 100_000,
    private val mergeFactor: Int = 10,
) : Closeable {
    private val lockChannel: FileChannel
    private val lock: FileLock
    private var manifest: Manifest
    val fields: List<String>

    private val segments = ArrayList<SegmentInfo>()
    private val readers = HashMap<String, Segment>()
    private val pendingDeletes = HashMap<String, BitSet>()

    private var bufferDocs = ArrayList<Document>()
    private var bufferIds = HashMap<String, Int>()
    private var bufferDeleted = BitSet()
    private var buffers = HashMap<String, FieldBuffer>()

    init {
        Files.createDirectories(dir)
        lockChannel = FileChannel.open(dir.resolve("write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try {
            lockChannel.tryLock()
        } catch (e: java.nio.channels.OverlappingFileLockException) {
            null // held by another writer in this JVM
        } ?: run {
            lockChannel.close()
            throw IOException("$dir is locked by another writer")
        }
        val existing = Manifest.read(dir)
        this.fields = existing?.fields ?: (fields ?: throw IllegalArgumentException("a new index needs its field names"))
        if (fields != null && existing != null && fields != existing.fields) {
            throw IllegalArgumentException("index has fields ${existing.fields}, not $fields")
        }
        manifest = existing ?: Manifest(0, this.fields, emptyList(), 0)
        segments += manifest.segments
        resetBuffer()
    }

    private fun resetBuffer() {
        bufferDocs = ArrayList()
        bufferIds = HashMap()
        bufferDeleted = BitSet()
        buffers = HashMap()
        for (f in fields) buffers[f] = FieldBuffer()
    }

    private fun reader(info: SegmentInfo): Segment = readers.getOrPut(info.name) {
        Segment(segmentFile(dir, info.name), info.name, verify = false).also { seg ->
            if (info.delGen > 0) seg.deleted = DeletesIO.read(deletesFile(dir, info.name, info.delGen))
        }
    }

    private fun isDeleted(info: SegmentInfo, doc: Int): Boolean =
        reader(info).deleted?.get(doc) == true || pendingDeletes[info.name]?.get(doc) == true

    /** Add a document, replacing any existing one with the same id. */
    fun add(doc: Document) {
        delete(doc.id)
        addNew(doc)
    }

    /** Add a document known not to exist yet (bulk loading: skips the id lookup). */
    fun addNew(doc: Document) {
        val local = bufferDocs.size
        bufferDocs += doc
        bufferIds[doc.id] = local
        for (f in fields) {
            val text = doc.fields[f]
            if (!text.isNullOrEmpty()) buffers.getValue(f).addDoc(local, text, analyzer)
        }
        if (bufferDocs.size >= maxBufferedDocs) flush()
    }

    /** Delete the document with this id. Returns whether one existed. */
    fun delete(id: String): Boolean {
        var found = false
        bufferIds.remove(id)?.let {
            bufferDeleted.set(it)
            found = true
        }
        for (info in segments) {
            val d = reader(info).lookupId(id)
            if (d >= 0 && !isDeleted(info, d)) {
                pendingDeletes.getOrPut(info.name) { BitSet() }.set(d)
                found = true
            }
        }
        return found
    }

    val bufferedDocs: Int get() = bufferDocs.size

    /** Write buffered documents as a new segment (not yet committed). */
    fun flush() {
        if (bufferDocs.isEmpty()) return
        val name = "s${manifest.counter}"
        manifest = manifest.copy(counter = manifest.counter + 1)
        val docs = bufferDocs
        val maxDoc = docs.size
        val w = SegmentWriter(segmentFile(dir, name), maxDoc)
        w.writeIds(w.writeStored(docs.iterator(), fields))
        for (f in fields) {
            val b = buffers.getValue(f)
            val norms = b.norms.copyOf(maxOf(maxDoc, b.norms.size))
            w.writeField(f, norms, b.terms.keys.sorted().asSequence().map { it to b.terms.getValue(it) })
        }
        w.finish()
        val deleted = bufferDeleted.cardinality()
        var info = SegmentInfo(name, maxDoc, 0, deleted)
        if (deleted > 0) {
            DeletesIO.write(deletesFile(dir, name, 1), bufferDeleted)
            info = info.copy(delGen = 1)
        }
        segments += info
        resetBuffer()
    }

    /** Flush, persist deletes, merge if the policy says so, and publish a new manifest. */
    fun commit() {
        flush()
        applyDeletes()
        maybeMerge()
        publish()
    }

    private fun applyDeletes() {
        for ((name, bits) in pendingDeletes) {
            val idx = segments.indexOfFirst { it.name == name }
            if (idx < 0) continue
            val info = segments[idx]
            val all = (reader(info).deleted?.clone() as BitSet?) ?: BitSet()
            all.or(bits)
            val gen = info.delGen + 1
            DeletesIO.write(deletesFile(dir, name, gen), all)
            segments[idx] = info.copy(delGen = gen, deleted = all.cardinality())
            readers.remove(name)
        }
        pendingDeletes.clear()
    }

    private fun publish() {
        manifest = manifest.copy(generation = manifest.generation + 1, segments = segments.toList())
        val tmp = dir.resolve("segments.json.tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            ch.write(ByteBuffer.wrap(manifest.toJson().toByteArray()))
            ch.force(true)
        }
        Files.move(tmp, dir.resolve(Manifest.FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        removeUnreferencedFiles()
    }

    private fun removeUnreferencedFiles() {
        val keep = HashSet<String>()
        for (s in segments) {
            keep += "${s.name}.seg"
            if (s.delGen > 0) keep += "${s.name}_${s.delGen}.del"
        }
        Files.list(dir).use { files ->
            for (f in files) {
                val n = f.fileName.toString()
                if ((n.endsWith(".seg") || n.endsWith(".del")) && n !in keep) Files.deleteIfExists(f)
            }
        }
    }

    /** Tiered merging: merge the `mergeFactor` smallest segments while there are too many,
     *  and rewrite segments that are mostly deletions. */
    private fun maybeMerge() {
        while (segments.size >= mergeFactor) {
            val smallest = segments.sortedBy { it.maxDoc - it.deleted }.take(mergeFactor)
            merge(smallest)
        }
        for (s in segments.toList()) {
            if (s.maxDoc > 0 && s.deleted * 10 > s.maxDoc * 4) merge(listOf(s))
        }
    }

    /** Merge everything down to at most `maxSegments` segments, then commit. */
    fun forceMerge(maxSegments: Int = 1) {
        flush()
        applyDeletes()
        while (segments.size > maxSegments) {
            val n = minOf(segments.size - maxSegments + 1, segments.size)
            merge(segments.sortedBy { it.maxDoc - it.deleted }.take(n))
        }
        if (maxSegments == 1 && segments.size == 1 && segments[0].deleted > 0) merge(segments.toList())
        publish()
    }

    /** Rewrite `infos` as one segment without their deleted documents. */
    private fun merge(infos: List<SegmentInfo>) {
        val order = segments.filter { it in infos } // keep doc order stable
        val segs = order.map { reader(it) }
        val docMaps = segs.map { seg ->
            IntArray(seg.maxDoc)
        }
        var next = 0
        for ((k, seg) in segs.withIndex()) {
            for (d in 0 until seg.maxDoc) docMaps[k][d] = if (seg.isLive(d)) next++ else -1
        }
        val maxDoc = next
        val name = "s${manifest.counter}"
        manifest = manifest.copy(counter = manifest.counter + 1)
        val liveDocs = ArrayList<Pair<Int, Int>>(maxDoc)
        for ((k, seg) in segs.withIndex()) for (d in 0 until seg.maxDoc) if (docMaps[k][d] >= 0) liveDocs += k to d
        val w = SegmentWriter(segmentFile(dir, name), maxDoc)
        val stored = segs.asSequence().flatMapIndexed { k, seg ->
            seg.documents().filterIndexed { d, _ -> docMaps[k][d] >= 0 }
        }
        w.writeIds(w.writeStored(stored.iterator(), fields))
        for (f in fields) {
            val norms = ByteArray(maxOf(maxDoc, 1))
            for ((i, kd) in liveDocs.withIndex()) {
                val fr = segs[kd.first].fields[f] ?: continue
                norms[i] = fr.norm(kd.second).toByte()
            }
            w.writeField(f, norms, mergedTerms(segs, docMaps, f))
        }
        w.finish()
        // The merged segment takes the place of the first merged one.
        val at = segments.indexOf(order.first())
        segments.removeAll(infos.toSet())
        for (i in infos) readers.remove(i.name)
        segments.add(minOf(at, segments.size), SegmentInfo(name, maxDoc, 0, 0))
    }

    /** k-way merge of the segments' term dictionaries for one field. */
    private fun mergedTerms(segs: List<Segment>, docMaps: List<IntArray>, field: String): Sequence<Pair<String, TermPostings>> = sequence {
        class Cursor(val k: Int, val it: Iterator<Pair<String, TermInfo>>) {
            var cur: Pair<String, TermInfo>? = if (it.hasNext()) it.next() else null
            fun advance() {
                cur = if (it.hasNext()) it.next() else null
            }
        }
        val pq = PriorityQueue<Cursor>(compareBy<Cursor>({ it.cur!!.first }, { it.k }))
        for ((k, seg) in segs.withIndex()) {
            val fr = seg.fields[field] ?: continue
            val c = Cursor(k, fr.terms().iterator())
            if (c.cur != null) pq += c
        }
        var buf = IntArray(16)
        while (pq.isNotEmpty()) {
            val term = pq.peek().cur!!.first
            val out = TermPostings()
            val same = ArrayList<Cursor>()
            while (pq.isNotEmpty() && pq.peek().cur!!.first == term) same += pq.poll()
            same.sortBy { it.k }
            for (c in same) {
                val fr = segs[c.k].fields.getValue(field)
                val p = fr.postings(c.cur!!.second, positions = true)
                while (p.nextDoc() != NO_MORE_DOCS) {
                    val nd = docMaps[c.k][p.doc]
                    if (nd < 0) continue
                    out.docs.add(nd)
                    out.freqs.add(p.freq)
                    buf = p.positions(buf)
                    for (i in 0 until p.freq) out.positions.add(buf[i])
                }
                c.advance()
                if (c.cur != null) pq += c
            }
            if (out.docs.size > 0) yield(term to out)
        }
    }

    override fun close() {
        if (bufferDocs.isNotEmpty() || pendingDeletes.isNotEmpty()) commit()
        lock.release()
        lockChannel.close()
    }
}
