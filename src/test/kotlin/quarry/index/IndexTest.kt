package quarry.index

import quarry.analysis.EnglishAnalyzer
import quarry.search.BM25
import quarry.search.Searcher
import quarry.search.TermQuery
import quarry.search.bagOfWords
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexTest {
    private val dir = Files.createTempDirectory("quarry-index")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `varints and bit packing round trip`() {
        val w = ByteWriter(4)
        val rnd = Random(1)
        val ints = IntArray(1000) { if (it % 3 == 0) rnd.nextInt() else rnd.nextInt(0, 1 shl rnd.nextInt(0, 31)) }
        val longs = LongArray(300) { rnd.nextLong() }
        ints.forEach { w.vint(it) }
        longs.forEach { w.vlong(it) }
        for (bits in 0..32) {
            val vals = IntArray(BLOCK) { if (bits == 0) 0 else rnd.nextInt().ushr(32 - bits).let { v -> if (bits == 32) rnd.nextInt() else v } }
            w.packed(vals, BLOCK, bits)
        }
        w.string("héllo wörld 😀")
        val r = ByteReader(java.nio.ByteBuffer.wrap(w.toByteArray()))
        val rnd2 = Random(1)
        IntArray(1000) { if (it % 3 == 0) rnd2.nextInt() else rnd2.nextInt(0, 1 shl rnd2.nextInt(0, 31)) }.forEach { assertEquals(it, r.vint()) }
        LongArray(300) { rnd2.nextLong() }.forEach { assertEquals(it, r.vlong()) }
        val out = IntArray(BLOCK)
        for (bits in 0..32) {
            val vals = IntArray(BLOCK) { if (bits == 0) 0 else rnd2.nextInt().ushr(32 - bits).let { v -> if (bits == 32) rnd2.nextInt() else v } }
            r.packed(out, BLOCK, bits)
            assertContentEquals(vals, out, "bits=$bits")
        }
        assertEquals("héllo wörld 😀", r.string())
    }

    @Test
    fun `postings iterate and skip exactly like a sorted list`() {
        // One term in a controlled set of docs, long enough for many blocks.
        val rnd = Random(3)
        val docsWithTerm = (0 until 5000).filter { rnd.nextInt(3) == 0 }.toSet()
        IndexWriter(dir, listOf("f"), maxBufferedDocs = 1_000_000).use { w ->
            for (i in 0 until 5000) {
                val reps = 1 + i % 4
                val text = if (i in docsWithTerm) List(reps) { "target" }.joinToString(" ") + " filler" else "filler other"
                w.addNew(Document("$i", mapOf("f" to text)))
            }
            w.commit()
        }
        IndexReader.open(dir).use { r ->
            val seg = r.segments.single()
            val fr = seg.fields.getValue("f")
            val info = assertNotNull(fr.lookup("target"))
            assertEquals(docsWithTerm.size, info.docFreq)
            val expected = docsWithTerm.sorted()
            // Full iteration with frequencies and positions.
            val p = fr.postings(info, positions = true)
            var buf = IntArray(4)
            for (d in expected) {
                assertEquals(d, p.nextDoc())
                assertEquals(1 + d % 4, p.freq)
                buf = p.positions(buf)
                for (k in 0 until p.freq) assertEquals(k, buf[k])
            }
            assertEquals(NO_MORE_DOCS, p.nextDoc())
            // Random advance() targets against a binary search.
            repeat(200) {
                val q = fr.postings(info, positions = rnd.nextBoolean())
                var cur = -1
                while (true) {
                    val target = cur + 1 + rnd.nextInt(0, 400)
                    val want = expected.firstOrNull { it >= target } ?: NO_MORE_DOCS
                    cur = q.advance(target)
                    assertEquals(want, cur, "advance($target)")
                    if (cur == NO_MORE_DOCS) break
                    if (rnd.nextBoolean()) {
                        val nxt = expected.firstOrNull { it > cur } ?: NO_MORE_DOCS
                        cur = q.nextDoc()
                        assertEquals(nxt, cur)
                        if (cur == NO_MORE_DOCS) break
                    }
                }
            }
            assertNull(fr.lookup("absent"))
            assertEquals(listOf("filler", "other", "target"), fr.terms().map { it.first }.toList())
        }
    }

    @Test
    fun `add update delete commit reopen and merge`() {
        IndexWriter(dir, listOf("title", "text"), maxBufferedDocs = 10, mergeFactor = 1000).use { w ->
            for (i in 0 until 95) w.add(Document("doc$i", mapOf("title" to "Title $i", "text" to "alpha beta gamma number$i")))
            w.commit()
        }
        IndexReader.open(dir).use { r ->
            assertEquals(95, r.numDocs)
            assertEquals(10, r.segments.size)
            val d = r.document(r.lookup("doc42"))
            assertEquals(mapOf("title" to "Title 42", "text" to "alpha beta gamma number42"), d.fields)
            assertEquals("doc42", r.id(r.lookup("doc42")))
        }
        val r1 = IndexReader.open(dir)
        IndexWriter(dir).use { w ->
            w.add(Document("doc42", mapOf("title" to "Replaced", "text" to "delta")))
            assertTrue(w.delete("doc7"))
            assertFalse(w.delete("nope"))
            w.add(Document("new", mapOf("text" to "alpha")))
            w.commit()
        }
        // The old reader still sees its snapshot; a reopened one sees the change.
        assertEquals(95, r1.numDocs)
        val r2 = r1.reopen()
        assertEquals(95, r2.numDocs) // -doc7 -old doc42 +doc42 +new
        assertEquals(-1, r2.lookup("doc7"))
        assertEquals("Replaced", r2.document(r2.lookup("doc42")).fields["title"])
        val s = Searcher(r2, BM25())
        assertEquals(94L, s.count(TermQuery("text", "alpha")))
        assertEquals(1L, s.count(TermQuery("text", "delta")))
        val before = s.search(bagOfWords("text", EnglishAnalyzer().terms("alpha number5")), 20).hits.map { r2.id(it.doc) to it.score }

        IndexWriter(dir).use { it.forceMerge(1) }
        val r3 = r2.reopen()
        assertEquals(1, r3.segments.size)
        assertEquals(95, r3.maxDoc) // deletions are gone after the merge
        assertEquals(95, r3.numDocs)
        val after = Searcher(r3, BM25()).search(bagOfWords("text", EnglishAnalyzer().terms("alpha number5")), 20).hits.map { r3.id(it.doc) }
        // Merging drops deleted docs from the stats, so scores can shift a
        // little, but the matching set is the same.
        assertEquals(before.map { it.first }.toSet(), after.toSet())
        listOf(r1, r2, r3).forEach { it.close() }
        // Only files of the live commit remain.
        val files = Files.list(dir).use { st -> st.map { it.fileName.toString() }.toList() }.toSet()
        val segs = files.filter { it.endsWith(".seg") }
        assertEquals(1, segs.size)
        assertEquals(setOf("segments.json", "write.lock") + segs, files)
    }

    @Test
    fun `only one writer at a time`() {
        IndexWriter(dir, listOf("f")).use {
            assertFailsWith<java.io.IOException> { IndexWriter(dir, listOf("f")) }
        }
    }

    @Test
    fun `corruption is detected`() {
        IndexWriter(dir, listOf("f")).use { w ->
            w.add(Document("a", mapOf("f" to "some text here")))
            w.commit()
        }
        val seg = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".seg") }.findFirst().get() }
        Files.newByteChannel(seg, StandardOpenOption.WRITE).use { ch ->
            ch.position(20)
            ch.write(java.nio.ByteBuffer.wrap(byteArrayOf(0x55)))
        }
        val e = assertFailsWith<java.io.IOException> { IndexReader.open(dir) }
        assertTrue("checksum" in e.message!!)
    }

    @Test
    fun `stats count only documents with the field`() {
        IndexWriter(dir, listOf("a", "b")).use { w ->
            w.add(Document("1", mapOf("a" to "one two three")))
            w.add(Document("2", mapOf("a" to "four", "b" to "five six")))
            w.add(Document("3", mapOf("a" to "the of and"))) // only stop words: no terms
            w.commit()
        }
        IndexReader.open(dir).use { r ->
            assertEquals(FieldStats(2, 4), r.stats("a"))
            assertEquals(FieldStats(1, 2), r.stats("b"))
        }
    }
}
