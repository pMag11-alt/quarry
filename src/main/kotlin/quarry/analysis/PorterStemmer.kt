package quarry.analysis

/**
 * The Porter stemming algorithm (M. F. Porter, "An algorithm for suffix
 * stripping", 1980), in the form of Porter's own reference implementation,
 * which Lucene's PorterStemFilter also follows. That version includes two
 * departures from the paper: "-bli" becomes "-ble" (not "-abli" to "-able")
 * and "-logi" becomes "-log".
 *
 * Words of one or two characters are left alone. Characters other than a-z
 * count as consonants, as in the reference code.
 */
object PorterStemmer {
    private val step2Rules = mapOf(
        'a' to listOf("ational" to "ate", "tional" to "tion"),
        'c' to listOf("enci" to "ence", "anci" to "ance"),
        'e' to listOf("izer" to "ize"),
        'l' to listOf("bli" to "ble", "alli" to "al", "entli" to "ent", "eli" to "e", "ousli" to "ous"),
        'o' to listOf("ization" to "ize", "ation" to "ate", "ator" to "ate"),
        's' to listOf("alism" to "al", "iveness" to "ive", "fulness" to "ful", "ousness" to "ous"),
        't' to listOf("aliti" to "al", "iviti" to "ive", "biliti" to "ble"),
        'g' to listOf("logi" to "log"),
    )

    private val step3Rules = mapOf(
        'e' to listOf("icate" to "ic", "ative" to "", "alize" to "al"),
        'i' to listOf("iciti" to "ic"),
        'l' to listOf("ical" to "ic", "ful" to ""),
        's' to listOf("ness" to ""),
    )

    private val step4Suffixes = mapOf(
        'a' to listOf("al"),
        'c' to listOf("ance", "ence"),
        'e' to listOf("er"),
        'i' to listOf("ic"),
        'l' to listOf("able", "ible"),
        'n' to listOf("ant", "ement", "ment", "ent"),
        's' to listOf("ism"),
        't' to listOf("ate", "iti"),
        'u' to listOf("ous"),
        'v' to listOf("ive"),
        'z' to listOf("ize"),
    )

    fun stem(word: String): String {
        if (word.length <= 2) return word
        val w = Word(word.toCharArray())
        w.step1ab()
        w.step1c()
        w.step2()
        w.step3()
        w.step4()
        w.step5()
        return String(w.b, 0, w.k + 1)
    }

    /** k is the index of the word's last character; j marks the stem end
     *  after a successful suffix match. */
    private class Word(var b: CharArray) {
        var k = b.size - 1
        var j = 0

        fun cons(i: Int): Boolean = when (b[i]) {
            'a', 'e', 'i', 'o', 'u' -> false
            'y' -> i == 0 || !cons(i - 1)
            else -> true
        }

        /** The number of vowel-consonant sequences in b[0..j]: [C](VC)^m[V]. */
        fun m(): Int {
            var n = 0
            var i = 0
            while (true) {
                if (i > j) return n
                if (!cons(i)) break
                i++
            }
            i++
            while (true) {
                while (true) {
                    if (i > j) return n
                    if (cons(i)) break
                    i++
                }
                i++
                n++
                while (true) {
                    if (i > j) return n
                    if (!cons(i)) break
                    i++
                }
                i++
            }
        }

        fun vowelInStem(): Boolean = (0..j).any { !cons(it) }

        fun doubleConsonant(i: Int): Boolean = i >= 1 && b[i] == b[i - 1] && cons(i)

        /** consonant-vowel-consonant ending at i, the last not w, x or y. */
        fun cvc(i: Int): Boolean {
            if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) return false
            val c = b[i]
            return c != 'w' && c != 'x' && c != 'y'
        }

        fun ends(s: String): Boolean {
            val len = s.length
            if (len > k + 1) return false
            for (x in 0 until len) if (b[k - len + 1 + x] != s[x]) return false
            j = k - len
            return true
        }

        /** Replace b[j+1..k] with s. */
        fun setTo(s: String) {
            val need = j + 1 + s.length
            if (need > b.size) b = b.copyOf(need + 8)
            for (x in s.indices) b[j + 1 + x] = s[x]
            k = j + s.length
        }

        fun replaceIfM(s: String) {
            if (m() > 0) setTo(s)
        }

        /** Plurals and -ed / -ing: caresses -> caress, ponies -> poni, hopping -> hop. */
        fun step1ab() {
            if (b[k] == 's') {
                when {
                    ends("sses") -> k -= 2
                    ends("ies") -> setTo("i")
                    b[k - 1] != 's' -> k--
                }
            }
            if (ends("eed")) {
                if (m() > 0) k--
            } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
                k = j
                when {
                    ends("at") -> setTo("ate")
                    ends("bl") -> setTo("ble")
                    ends("iz") -> setTo("ize")
                    doubleConsonant(k) -> {
                        val c = b[k]
                        if (c != 'l' && c != 's' && c != 'z') k--
                    }
                    else -> {
                        j = k
                        if (m() == 1 && cvc(k)) setTo("e")
                    }
                }
            }
        }

        /** Terminal y becomes i when the stem has another vowel. */
        fun step1c() {
            if (ends("y") && vowelInStem()) b[k] = 'i'
        }

        /** Double suffixes to single ones, when m > 0. */
        fun step2() {
            if (k == 0) return
            val rules = step2Rules[b[k - 1]] ?: return
            for ((suffix, repl) in rules) {
                if (ends(suffix)) {
                    replaceIfM(repl)
                    return
                }
            }
        }

        /** -ic-, -full, -ness and friends. */
        fun step3() {
            val rules = step3Rules[b[k]] ?: return
            for ((suffix, repl) in rules) {
                if (ends(suffix)) {
                    replaceIfM(repl)
                    return
                }
            }
        }

        /** Drop -ant, -ence etc. in context <c>vcvc<v>, i.e. when m > 1. */
        fun step4() {
            if (k == 0) return
            val c = b[k - 1]
            val matched = if (c == 'o') {
                // -ion only after s or t; otherwise -ou.
                (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || ends("ou")
            } else {
                step4Suffixes[c]?.any { ends(it) } ?: false
            }
            if (matched && m() > 1) k = j
        }

        /** Final -e when m > 1 (or m = 1 not after cvc), and -ll to -l when m > 1. */
        fun step5() {
            j = k
            if (b[k] == 'e') {
                val a = m()
                if (a > 1 || a == 1 && !cvc(k - 1)) k--
            }
            if (b[k] == 'l' && doubleConsonant(k) && m() > 1) k--
        }
    }
}
