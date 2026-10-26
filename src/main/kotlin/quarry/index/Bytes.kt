package quarry.index

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A growable byte buffer with the encodings the index files use. */
class ByteWriter(initial: Int = 1024) {
    var buf = ByteArray(initial)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun byte(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
    }

    fun int(v: Int) {
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v ushr (8 * i)).toByte()
    }

    fun long(v: Long) {
        ensure(8)
        for (i in 0 until 8) buf[size++] = (v ushr (8 * i)).toByte()
    }

    /** LEB128: 7 bits per byte, high bit set on all but the last. */
    fun vint(v: Int) {
        var x = v
        ensure(5)
        while (x and 0x7F.inv() != 0) {
            buf[size++] = ((x and 0x7F) or 0x80).toByte()
            x = x ushr 7
        }
        buf[size++] = x.toByte()
    }

    fun vlong(v: Long) {
        var x = v
        ensure(10)
        while (x and 0x7FL.inv() != 0L) {
            buf[size++] = ((x and 0x7F) or 0x80).toByte()
            x = x ushr 7
        }
        buf[size++] = x.toByte()
    }

    fun string(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        vint(b.size)
        bytes(b)
    }

    /** Pack n values of `bits` bits each, little-endian bit order. */
    fun packed(values: IntArray, n: Int, bits: Int) {
        if (bits == 0) return
        val total = (n * bits + 7) / 8
        ensure(total)
        var acc = 0L
        var accBits = 0
        for (i in 0 until n) {
            acc = acc or ((values[i].toLong() and 0xFFFFFFFFL) shl accBits)
            accBits += bits
            while (accBits >= 8) {
                buf[size++] = acc.toByte()
                acc = acc ushr 8
                accBits -= 8
            }
        }
        if (accBits > 0) buf[size++] = acc.toByte()
    }

    fun reset() {
        size = 0
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Bits needed to represent v (0 for 0). */
fun bitsFor(v: Int): Int = 32 - Integer.numberOfLeadingZeros(v)

/**
 * A cursor over a read-only ByteBuffer (usually a memory-mapped segment).
 * Positions are absolute offsets into the buffer.
 */
class ByteReader(private val bb: ByteBuffer, var pos: Int = 0) {
    init {
        bb.order(ByteOrder.LITTLE_ENDIAN)
    }

    fun byte(): Int = bb.get(pos++).toInt() and 0xFF

    fun int(): Int {
        val v = bb.getInt(pos)
        pos += 4
        return v
    }

    fun long(): Long {
        val v = bb.getLong(pos)
        pos += 8
        return v
    }

    fun vint(): Int {
        var b = bb.get(pos++).toInt()
        if (b >= 0) return b
        var v = b and 0x7F
        var shift = 7
        while (true) {
            b = bb.get(pos++).toInt()
            v = v or ((b and 0x7F) shl shift)
            if (b >= 0) return v
            shift += 7
        }
    }

    fun vlong(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val b = bb.get(pos++).toInt()
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b >= 0) return v
            shift += 7
        }
    }

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        bb.get(pos, out)
        pos += n
        return out
    }

    fun string(): String {
        val n = vint()
        return String(bytes(n), Charsets.UTF_8)
    }

    /** Unpack n values of `bits` bits written by ByteWriter.packed. */
    fun packed(out: IntArray, n: Int, bits: Int, scratch: ByteArray = ByteArray((n * bits + 7) / 8)) {
        if (bits == 0) {
            out.fill(0, 0, n)
            return
        }
        // One bulk copy out of the (memory-mapped) buffer, then unpack from
        // the array: far cheaper than a bounds-checked get() per byte.
        val bytes = (n * bits + 7) / 8
        bb.get(pos, scratch, 0, bytes)
        pos += bytes
        val mask = if (bits == 32) -1L else (1L shl bits) - 1
        var acc = 0L
        var accBits = 0
        var p = 0
        for (i in 0 until n) {
            while (accBits < bits) {
                acc = acc or ((scratch[p++].toLong() and 0xFF) shl accBits)
                accBits += 8
            }
            out[i] = (acc and mask).toInt()
            acc = acc ushr bits
            accBits -= bits
        }
    }

    fun skip(n: Int) {
        pos += n
    }
}
