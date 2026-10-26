package quarry.index

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.zip.CRC32
import java.util.zip.Inflater

const val NO_MORE_DOCS = Int.MAX_VALUE

/** Dictionary entry of one term in one segment. */
class TermInfo(
    val docFreq: Int,
    val totalTermFreq: Long,
    val postingsAt: Long, // relative to the field's postings
    val positionsAt: Long, // relative to the field's positions
    val impactFreqs: IntArray,
    val impactNorms: IntArray,
)

class FieldReader internal constructor(
    private val seg: Segment,
    val name: String,
    private val normsAt: Long,
    internal val postingsAt: Long,
    internal val positionsAt: Long,
    private val dictAt: Long,
    blockIndexAt: Long,
    val docCount: Int,
    val sumTotalTermFreq: Long,
    val sumDocFreq: Long,
    val termCount: Int,
) {
    private val blockTerms: Array<String>
    private val blockOffsets: IntArray

    init {
        val r = seg.reader(blockIndexAt)
        val n = r.vint()
        blockTerms = Array(n) { "" }
        blockOffsets = IntArray(n)
        for (i in 0 until n) {
            blockTerms[i] = r.string()
            blockOffsets[i] = r.vint()
        }
    }

    /**
     * Norms on the heap: one byte per doc, read for every scored hit, so a
     * plain array beats a bounds-checked read from the mapping. Loaded on
     * first use (a benign race at worst loads them twice).
     */
    private var normsCache: ByteArray? = null

    fun norms(): ByteArray = normsCache ?: ByteArray(seg.maxDoc).also {
        seg.buf.get(normsAt.toInt(), it, 0, seg.maxDoc)
        normsCache = it
    }

    fun norm(doc: Int): Int = norms()[doc].toInt() and 0xFF

    /** Decode the dictionary block that would contain `term`, entry by entry. */
    private inline fun scanBlock(block: Int, visit: (String, TermInfo) -> Boolean) {
        val r = seg.reader(dictAt + blockOffsets[block])
        var postings = r.vlong()
        var positions = r.vlong()
        var prev = ""
        val count = if (block == blockTerms.size - 1) termCount - block * TERMS_PER_BLOCK else TERMS_PER_BLOCK
        for (i in 0 until count) {
            val shared = r.vint()
            val suffix = String(r.bytes(r.vint()), Charsets.UTF_8)
            val term = prev.substring(0, shared) + suffix
            val df = r.vint()
            val ttf = r.vlong() + df
            postings += r.vlong()
            positions += r.vlong()
            val ni = r.vint()
            val fs = IntArray(ni)
            val ns = IntArray(ni)
            for (k in 0 until ni) {
                fs[k] = r.vint()
                ns[k] = r.byte()
            }
            if (!visit(term, TermInfo(df, ttf, postings, positions, fs, ns))) return
            prev = term
        }
    }

    fun lookup(term: String): TermInfo? {
        if (blockTerms.isEmpty()) return null
        // The last block whose first term is <= term.
        var lo = 0
        var hi = blockTerms.size - 1
        if (term < blockTerms[0]) return null
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blockTerms[mid] <= term) lo = mid else hi = mid - 1
        }
        var found: TermInfo? = null
        scanBlock(lo) { t, info ->
            val c = t.compareTo(term)
            if (c == 0) found = info
            c < 0
        }
        return found
    }

    /** All terms in order, starting from the block containing `from`. */
    fun terms(from: String = ""): Sequence<Pair<String, TermInfo>> = sequence {
        if (blockTerms.isEmpty()) return@sequence
        var start = 0
        if (from.isNotEmpty()) {
            var lo = 0
            var hi = blockTerms.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (blockTerms[mid] <= from) lo = mid else hi = mid - 1
            }
            start = lo
        }
        for (b in start until blockTerms.size) {
            val batch = ArrayList<Pair<String, TermInfo>>(TERMS_PER_BLOCK)
            scanBlock(b) { t, info ->
                if (t >= from) batch += t to info
                true
            }
            yieldAll(batch)
        }
    }

    fun postings(info: TermInfo, positions: Boolean = false): Postings = Postings(seg, this, info, positions)
}

/** A read-only, memory-mapped segment. */
class Segment(val path: Path, val name: String, verify: Boolean = true) : Closeable {
    internal val buf: ByteBuffer
    val maxDoc: Int
    val fields: Map<String, FieldReader>
    val storedFields: List<String>
    private val chunkIndexAt: Long
    private val idEntriesAt: Long
    private val idOffsetsAt: Long
    private val idCount: Int

    /** Deleted documents (null: none). Set by the reader from the .del file. */
    var deleted: BitSet? = null

    init {
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            if (size > Int.MAX_VALUE) throw IOException("$path: segments over 2 GiB are not supported")
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size)
        }
        val len = buf.capacity()
        val magic = SegmentWriter.magic
        if (len < magic.size + 20 || (0 until magic.size).any { buf.get(it) != magic[it] }) {
            throw IOException("$path is not a quarry segment")
        }
        if (verify) {
            val crc = CRC32()
            crc.update(buf.duplicate().position(0).limit(len - 4))
            val stored = ByteReader(buf, len - 4).int()
            if (crc.value.toInt() != stored) throw IOException("$path: checksum mismatch (corrupt segment)")
        }
        val tail = ByteReader(buf, len - 4 - magic.size - 8)
        val toc = ByteReader(buf, tail.long().toInt())
        maxDoc = toc.vint()
        chunkIndexAt = toc.long()
        storedFields = List(toc.vint()) { toc.string() }
        idEntriesAt = toc.long()
        idOffsetsAt = toc.long()
        idCount = toc.vint()
        val fs = LinkedHashMap<String, FieldReader>()
        repeat(toc.vint()) {
            val fname = toc.string()
            fs[fname] = FieldReader(
                this, fname,
                normsAt = toc.long(), postingsAt = toc.long(), positionsAt = toc.long(),
                dictAt = toc.long(), blockIndexAt = toc.long(),
                docCount = toc.vint(), sumTotalTermFreq = toc.vlong(), sumDocFreq = toc.vlong(), termCount = toc.vint(),
            )
        }
        fields = fs
    }

    internal fun reader(at: Long) = ByteReader(buf, at.toInt())

    fun isLive(doc: Int): Boolean = deleted?.get(doc) != true

    fun liveCount(): Int = maxDoc - (deleted?.cardinality() ?: 0)

    /** The stored document `doc`. */
    fun document(doc: Int): Document {
        val chunk = doc / 64
        val r = reader(chunkIndexAt + chunk * 8L)
        val raw = inflate(reader(r.long()))
        val cr = ByteReader(ByteBuffer.wrap(raw))
        for (d in 0 until doc % 64) {
            cr.string()
            repeat(storedFields.size) { cr.string() }
        }
        val id = cr.string()
        val fields = LinkedHashMap<String, String>()
        for (f in storedFields) {
            val v = cr.string()
            if (v.isNotEmpty()) fields[f] = v
        }
        return Document(id, fields)
    }

    /** Every document (deleted ones too) in order, inflating each chunk once. */
    fun documents(): Sequence<Document> = sequence {
        val chunks = (maxDoc + 63) / 64
        for (c in 0 until chunks) {
            val raw = inflate(reader(reader(chunkIndexAt + c * 8L).long()))
            val cr = ByteReader(ByteBuffer.wrap(raw))
            for (d in c * 64 until minOf(maxDoc, c * 64 + 64)) {
                val id = cr.string()
                val fields = LinkedHashMap<String, String>()
                for (f in storedFields) {
                    val v = cr.string()
                    if (v.isNotEmpty()) fields[f] = v
                }
                yield(Document(id, fields))
            }
        }
    }

    private fun inflate(r: ByteReader): ByteArray {
        val size = r.vint()
        val out = ByteArray(size)
        val inflater = Inflater()
        try {
            var written = 0
            while (true) {
                val n = r.vint()
                if (n == 0) break
                inflater.setInput(r.bytes(n))
                while (!inflater.needsInput() && written < size) {
                    written += inflater.inflate(out, written, size - written)
                }
            }
        } finally {
            inflater.end()
        }
        return out
    }

    /** The external id of `doc`. */
    fun id(doc: Int): String {
        val off = ByteReader(buf, (idOffsetsAt + (idCount + doc) * 4L).toInt()).int()
        return reader(idEntriesAt + off).string()
    }

    /** The doc with external id `id` (deleted or not), or -1. */
    fun lookupId(id: String): Int {
        var lo = 0
        var hi = idCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = reader(idEntriesAt + ByteReader(buf, (idOffsetsAt + mid * 4L).toInt()).int())
            val c = r.string().compareTo(id)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return r.vint()
            }
        }
        return -1
    }

    override fun close() {
        // The mapping is released when the buffer is garbage collected.
    }
}

/**
 * Iterates one term's postings in doc order, with skipping. Positions are
 * decoded only when asked for.
 */
class Postings internal constructor(seg: Segment, field: FieldReader, info: TermInfo, private val withPositions: Boolean) {
    val docFreq = info.docFreq
    private val r = seg.reader(field.postingsAt + info.postingsAt)
    private val fullBlocks = docFreq / BLOCK
    private val lastDocs = IntArray(fullBlocks)
    private val blockAt = IntArray(fullBlocks + 1) // absolute; [fullBlocks] = tail
    private val posAt = LongArray(fullBlocks + 1)
    private val posReader = seg.reader(field.positionsAt + info.positionsAt)

    var doc = -1
        private set
    var freq = 0
        private set

    private val docs = IntArray(BLOCK)
    private val freqs = IntArray(BLOCK)
    private var allOnes = false // every freq in the decoded block is 1
    private val scratch = ByteArray(BLOCK * 4)
    private var n = 0 // decoded docs in the buffer
    private var i = -1 // index of `doc` in the buffer
    private var nextBlock = 0 // next block to decode; fullBlocks means the tail
    private var posDoc = 0 // buffer index whose positions posReader points at

    init {
        // Skip table: per full block, the gap to its last doc, its byte
        // length and the byte length of its positions.
        var last = -1
        val lens = IntArray(fullBlocks)
        val posLens = IntArray(fullBlocks)
        for (b in 0 until fullBlocks) {
            last += r.vint()
            lastDocs[b] = last
            lens[b] = r.vint()
            posLens[b] = r.vint()
        }
        var at = r.pos
        var pos = posReader.pos.toLong()
        for (b in 0 until fullBlocks) {
            blockAt[b] = at
            posAt[b] = pos
            at += lens[b]
            pos += posLens[b]
        }
        blockAt[fullBlocks] = at
        posAt[fullBlocks] = pos
    }

    private fun decode(block: Int) {
        val base = if (block == 0) -1 else lastDocs[block - 1]
        r.pos = blockAt[block]
        if (block < fullBlocks) {
            val bitsDoc = r.byte()
            val bitsFreq = r.byte()
            r.packed(docs, BLOCK, bitsDoc, scratch)
            allOnes = bitsFreq == 0
            if (!allOnes) {
                r.packed(freqs, BLOCK, bitsFreq, scratch)
                for (k in 0 until BLOCK) freqs[k]++
            }
            var d = base
            for (k in 0 until BLOCK) {
                d += docs[k] + 1
                docs[k] = d
            }
            n = BLOCK
        } else {
            var d = base
            allOnes = false
            n = docFreq - fullBlocks * BLOCK
            for (k in 0 until n) {
                val v = r.vint()
                d += (v ushr 1) + 1
                docs[k] = d
                freqs[k] = if (v and 1 != 0) 1 else r.vint()
            }
        }
        nextBlock = block + 1
        i = -1
        if (withPositions) {
            posReader.pos = posAt[block].toInt()
            posDoc = 0
        }
    }

    fun nextDoc(): Int {
        if (i + 1 >= n) {
            if (nextBlock > fullBlocks || docFreq == 0) {
                doc = NO_MORE_DOCS
                return doc
            }
            decode(nextBlock)
            if (n == 0) {
                doc = NO_MORE_DOCS
                return doc
            }
        }
        i++
        doc = docs[i]
        freq = if (allOnes) 1 else freqs[i]
        return doc
    }

    /** Move to the first doc >= target (target > current doc). */
    fun advance(target: Int): Int {
        if (doc == -1 || n == 0 || docs[n - 1] < target) {
            // Jump to the first block, from the next undecoded one, whose
            // last doc reaches the target; `fullBlocks` is the tail.
            var lo = if (doc == -1) 0 else nextBlock
            var hi = fullBlocks
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (lastDocs[mid] < target) lo = mid + 1 else hi = mid
            }
            if (lo > fullBlocks || (lo == fullBlocks && docFreq == fullBlocks * BLOCK)) {
                doc = NO_MORE_DOCS
                return doc
            }
            decode(lo)
        }
        while (nextDoc() < target) {
            // scan within the block
        }
        return doc
    }

    /** Positions of the current doc, written to out (grown as needed). */
    fun positions(out: IntArray): IntArray {
        check(withPositions) { "positions were not requested" }
        // Skip the positions of earlier docs in this block.
        while (posDoc < i) {
            repeat(if (allOnes) 1 else freqs[posDoc]) { posReader.vint() }
            posDoc++
        }
        val o = if (out.size >= freq) out else IntArray(freq)
        var p = 0
        for (k in 0 until freq) {
            p += posReader.vint()
            o[k] = p
        }
        posDoc = i + 1
        return o
    }
}
