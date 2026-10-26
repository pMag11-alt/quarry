package quarry.index

/**
 * Lucene's lossy one-byte encoding of field lengths ("norms").
 *
 * Lengths below 24 are stored exactly. Larger ones keep their top four
 * significant bits, so a 1000-word document is scored as if it had 960
 * words. BM25 reads lengths back through this encoding, so quarry must use
 * it too to reproduce Lucene's scores exactly.
 */
object SmallFloat {
    private fun longToInt4(i: Long): Int {
        require(i >= 0)
        val numBits = 64 - java.lang.Long.numberOfLeadingZeros(i)
        if (numBits < 4) return i.toInt() // subnormal
        val shift = numBits - 4
        var encoded = (i ushr shift).toInt() and 0x07 // drop the implicit top bit
        encoded = encoded or ((shift + 1) shl 3)
        return encoded
    }

    private fun int4ToLong(i: Int): Long {
        val bits = (i and 0x07).toLong()
        val shift = (i ushr 3) - 1
        return if (shift == -1) bits else (bits or 0x08) shl shift
    }

    /** 255 minus the largest code an Int needs: the exactly-stored range. */
    private val NUM_FREE_VALUES = 255 - longToInt4(Int.MAX_VALUE.toLong())

    fun intToByte4(i: Int): Int {
        require(i >= 0)
        return if (i < NUM_FREE_VALUES) i else NUM_FREE_VALUES + longToInt4((i - NUM_FREE_VALUES).toLong())
    }

    fun byte4ToInt(b: Int): Int {
        val i = b and 0xFF
        return if (i < NUM_FREE_VALUES) i else (NUM_FREE_VALUES + int4ToLong(i - NUM_FREE_VALUES)).toInt()
    }

    /** The length each norm byte decodes to. */
    val LENGTH_TABLE = FloatArray(256) { byte4ToInt(it).toFloat() }
}
