package quarry.index

import quarry.analysis.Analyzer
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A document: an external id plus named text fields (all indexed and stored). */
data class Document(val id: String, val fields: Map<String, String>)

/** A growable IntArray. */
class IntList(capacity: Int = 4) {
    var a = IntArray(capacity)
    var size = 0

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]

    fun last() = a[size - 1]
}

const val BLOCK = 128
const val TERMS_PER_BLOCK = 32
private val MAGIC = "QRYSEG01".toByteArray()

/** One term's postings while a segment is being built or merged. */
class TermPostings {
    val docs = IntList()
    val freqs = IntList()
    val positions = IntList() // `freq` positions per doc, concatenated
}

/** In-memory inverted index of one field for the documents buffered so far. */
class FieldBuffer {
    val terms = HashMap<String, TermPostings>()
    var norms = ByteArray(64)
    var docCount = 0
    var sumTotalTermFreq = 0L

    fun setNorm(doc: Int, norm: Int) {
        if (doc >= norms.size) norms = norms.copyOf(maxOf(norms.size * 2, doc + 1))
        norms[doc] = norm.toByte()
    }

    fun addDoc(doc: Int, text: String, analyzer: Analyzer) {
        val perDoc = HashMap<String, IntList>()
        var length = 0
        analyzer.analyze(text) { term, position, _, _ ->
            perDoc.getOrPut(term) { IntList(2) }.add(position)
            length++
        }
        if (length == 0) return
        for ((term, positions) in perDoc) {
            val p = terms.getOrPut(term) { TermPostings() }
            p.docs.add(doc)
            p.freqs.add(positions.size)
            for (i in 0 until positions.size) p.positions.add(positions[i])
        }
        setNorm(doc, SmallFloat.intToByte4(length))
        docCount++
        sumTotalTermFreq += length
    }
}

/**
 * Writes a segment file. Layout (all little-endian, offsets absolute):
 *
 *   magic "QRYSEG01"
 *   stored fields: Deflate-compressed chunks of up to 64 documents
 *   id table: (id, doc) sorted by id, plus a fixed-width offset array
 *   for each field: norms (1 byte/doc), postings, positions, term dictionary
 *     (blocks of 32 prefix-compressed terms) and its block index
 *   table of contents, its offset (8 bytes), magic, CRC32 of all before it
 *
 * Postings per term: a skip table, then blocks of 128 documents (doc gaps
 * and freq-1 bit-packed at the block's maximum width), then the remaining
 * documents as varints. Each dictionary entry also stores the term's
 * impacts: the (freq, norm) pairs no other posting dominates, from which a
 * query computes the term's exact maximum BM25 contribution.
 */
class SegmentWriter(private val path: Path, private val maxDoc: Int) {
    private val crc = CRC32()
    private val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    private val raw = BufferedOutputStream(Channels.newOutputStream(channel), 1 shl 16)
    private var pos = 0L
    private val toc = ByteWriter()
    private var fieldCount = 0
    private val fieldToc = ByteWriter()

    private val out = object : OutputStream() {
        override fun write(b: Int) {
            raw.write(b)
            crc.update(b)
            pos++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            raw.write(b, off, len)
            crc.update(b, off, len)
            pos += len
        }
    }

    init {
        out.write(MAGIC)
    }

    private fun emit(w: ByteWriter): Long {
        val at = pos
        out.write(w.buf, 0, w.size)
        return at
    }

    /** Stored fields for docs 0 until maxDoc, in order. Returns their ids. */
    fun writeStored(docs: Iterator<Document>, fieldNames: List<String>): Array<String> {
        val ids = Array(maxDoc) { "" }
        val chunkOffsets = LongArray((maxDoc + 63) / 64)
        val chunk = ByteWriter(1 shl 16)
        val deflater = Deflater(Deflater.BEST_SPEED)
        var compressed = ByteArray(1 shl 16)
        var c = 0
        var doc = 0
        while (doc < maxDoc) {
            chunk.reset()
            val end = minOf(maxDoc, doc + 64)
            for (d in doc until end) {
                val document = docs.next()
                ids[d] = document.id
                chunk.string(document.id)
                for (f in fieldNames) chunk.string(document.fields[f] ?: "")
            }
            deflater.reset()
            deflater.setInput(chunk.buf, 0, chunk.size)
            deflater.finish()
            val w = ByteWriter(chunk.size / 2 + 64)
            w.vint(chunk.size)
            while (!deflater.finished()) {
                if (compressed.size < chunk.size + 64) compressed = ByteArray(chunk.size + 64)
                val n = deflater.deflate(compressed)
                w.vint(n)
                w.bytes(compressed, 0, n)
            }
            w.vint(0)
            chunkOffsets[c++] = emit(w)
            doc = end
        }
        deflater.end()
        val idx = ByteWriter(chunkOffsets.size * 8 + 8)
        for (o in chunkOffsets) idx.long(o)
        val idxAt = emit(idx)
        toc.long(idxAt)
        toc.vint(fieldNames.size)
        for (f in fieldNames) toc.string(f)
        return ids
    }

    /**
     * The id table, for updates and deletes by id: entries sorted by id, an
     * offset array in id order (binary search) and one in doc order
     * (doc -> id without touching the stored fields).
     */
    fun writeIds(ids: Array<String>) {
        val order = ids.indices.sortedWith { a, b -> ids[a].compareTo(ids[b]) }
        val entries = ByteWriter(ids.size * 16)
        val byId = IntArray(ids.size)
        val byDoc = IntArray(ids.size)
        for ((i, d) in order.withIndex()) {
            byId[i] = entries.size
            byDoc[d] = entries.size
            entries.string(ids[d])
            entries.vint(d)
        }
        val entriesAt = emit(entries)
        val offs = ByteWriter(ids.size * 8)
        for (o in byId) offs.int(o)
        for (o in byDoc) offs.int(o)
        val offsAt = emit(offs)
        toc.long(entriesAt)
        toc.long(offsAt)
        toc.vint(ids.size)
    }

    /**
     * One field. `terms` must be in sorted order; postings use segment doc
     * ids (ascending).
     */
    fun writeField(name: String, norms: ByteArray, terms: Sequence<Pair<String, TermPostings>>) {
        // Docs with at least one indexed term have a non-zero norm.
        var docCount = 0
        for (d in 0 until maxDoc) if (norms[d].toInt() != 0) docCount++
        var sumTotalTermFreq = 0L
        val normsAt = emit(ByteWriter(maxDoc).also { it.bytes(norms, 0, maxDoc) })
        val postings = ByteWriter(1 shl 16)
        val positions = ByteWriter(1 shl 16)
        val dict = ByteWriter(1 shl 16)
        val blockIndex = ArrayList<Pair<String, Int>>()
        var termCount = 0
        var sumDocFreq = 0L
        var prevTerm = ""
        val docBuf = IntArray(BLOCK)
        val freqBuf = IntArray(BLOCK)
        val blockBytes = ByteWriter(BLOCK * 8)
        val skip = ByteWriter(256)
        var postingsBase = 0L
        var positionsBase = 0L

        for ((term, p) in terms) {
            val n = p.docs.size
            val postingsStart = postings.size.toLong()
            val positionsStart = positions.size.toLong()
            // Full blocks with a skip table in front.
            val fullBlocks = n / BLOCK
            skip.reset()
            blockBytes.reset()
            var prevLast = -1
            var posI = 0
            val blocks = ByteWriter(fullBlocks * BLOCK * 2 + 16)
            for (b in 0 until fullBlocks) {
                var prev = prevLast
                var maxGap = 0
                var maxFreq = 0
                for (i in 0 until BLOCK) {
                    val d = p.docs[b * BLOCK + i]
                    docBuf[i] = d - prev - 1
                    prev = d
                    freqBuf[i] = p.freqs[b * BLOCK + i] - 1
                    maxGap = maxGap or docBuf[i]
                    maxFreq = maxFreq or freqBuf[i]
                }
                val posBefore = positions.size
                for (i in 0 until BLOCK) {
                    val f = p.freqs[b * BLOCK + i]
                    var last = 0
                    for (k in 0 until f) {
                        val ps = p.positions[posI++]
                        positions.vint(ps - last)
                        last = ps
                    }
                }
                val start = blocks.size
                blocks.byte(bitsFor(maxGap))
                blocks.byte(bitsFor(maxFreq))
                blocks.packed(docBuf, BLOCK, bitsFor(maxGap))
                blocks.packed(freqBuf, BLOCK, bitsFor(maxFreq))
                skip.vint(prev - prevLast)
                skip.vint(blocks.size - start)
                skip.vint(positions.size - posBefore)
                prevLast = prev
            }
            postings.bytes(skip.buf, 0, skip.size)
            postings.bytes(blocks.buf, 0, blocks.size)
            // Tail: gap << 1 | (freq == 1).
            var prev = prevLast
            for (i in fullBlocks * BLOCK until n) {
                val d = p.docs[i]
                val f = p.freqs[i]
                val gap = d - prev - 1
                prev = d
                if (f == 1) postings.vint((gap shl 1) or 1) else {
                    postings.vint(gap shl 1)
                    postings.vint(f)
                }
                var last = 0
                for (k in 0 until f) {
                    val ps = p.positions[posI++]
                    positions.vint(ps - last)
                    last = ps
                }
            }

            // Dictionary entry.
            if (termCount % TERMS_PER_BLOCK == 0) {
                blockIndex += term to dict.size
                dict.vlong(postingsStart)
                dict.vlong(positionsStart)
                postingsBase = postingsStart
                positionsBase = positionsStart
                prevTerm = ""
            }
            var shared = 0
            val maxShared = minOf(prevTerm.length, term.length)
            while (shared < maxShared && prevTerm[shared] == term[shared]) shared++
            // Never split a surrogate pair.
            if (shared > 0 && Character.isHighSurrogate(term[shared - 1])) shared--
            val suffix = term.substring(shared).toByteArray(Charsets.UTF_8)
            dict.vint(shared)
            dict.vint(suffix.size)
            dict.bytes(suffix)
            var ttf = 0L
            for (i in 0 until n) ttf += p.freqs[i]
            sumTotalTermFreq += ttf
            dict.vint(n)
            dict.vlong(ttf - n)
            dict.vlong(postingsStart - postingsBase)
            dict.vlong(positionsStart - positionsBase)
            writeImpacts(dict, p, norms, 0, n)
            postingsBase = postingsStart
            positionsBase = positionsStart
            prevTerm = term
            termCount++
            sumDocFreq += n
        }

        val postingsAt = emit(postings)
        val positionsAt = emit(positions)
        val dictAt = emit(dict)
        val bi = ByteWriter(blockIndex.size * 16 + 8)
        bi.vint(blockIndex.size)
        for ((t, off) in blockIndex) {
            bi.string(t)
            bi.vint(off)
        }
        val blockIndexAt = emit(bi)

        fieldCount++
        fieldToc.string(name)
        fieldToc.long(normsAt)
        fieldToc.long(postingsAt)
        fieldToc.long(positionsAt)
        fieldToc.long(dictAt)
        fieldToc.long(blockIndexAt)
        fieldToc.vint(docCount)
        fieldToc.vlong(sumTotalTermFreq)
        fieldToc.vlong(sumDocFreq)
        fieldToc.vint(termCount)
    }

    /**
     * The Pareto front of (freq, norm) over postings [from, until): higher
     * freq and shorter docs score higher, so the BM25 maximum over those
     * postings is the maximum over these few pairs.
     */
    private fun writeImpacts(out: ByteWriter, p: TermPostings, norms: ByteArray, from: Int, until: Int) {
        // Best (smallest) norm seen for each freq, then keep the pairs whose
        // norm beats every pair with a higher freq.
        val best = HashMap<Int, Int>()
        for (i in from until until) {
            val f = p.freqs[i]
            val nb = norms[p.docs[i]].toInt() and 0xFF
            val cur = best[f]
            if (cur == null || nb < cur) best[f] = nb
        }
        val freqs = best.keys.sortedDescending()
        val front = ArrayList<Pair<Int, Int>>()
        var minNorm = Int.MAX_VALUE
        for (f in freqs) {
            val nb = best.getValue(f)
            if (nb < minNorm) {
                front += f to nb
                minNorm = nb
            }
        }
        out.vint(front.size)
        for ((f, nb) in front) {
            out.vint(f)
            out.byte(nb)
        }
    }

    /** Write the table of contents and checksum; the file is complete after this. */
    fun finish() {
        val tocAt = pos
        val w = ByteWriter(toc.size + fieldToc.size + 16)
        w.vint(maxDoc)
        w.bytes(toc.buf, 0, toc.size)
        w.vint(fieldCount)
        w.bytes(fieldToc.buf, 0, fieldToc.size)
        emit(w)
        val tail = ByteWriter(16)
        tail.long(tocAt)
        tail.bytes(MAGIC)
        emit(tail)
        val sum = crc.value.toInt()
        val c = ByteWriter(4)
        c.int(sum)
        raw.write(c.buf, 0, 4)
        raw.flush()
        channel.force(true) // durable before any manifest refers to it
        channel.close()
    }

    companion object {
        val magic: ByteArray get() = MAGIC.copyOf()
    }
}
