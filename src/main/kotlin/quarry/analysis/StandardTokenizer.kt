package quarry.analysis

import quarry.analysis.Unicode12.ALETTER
import quarry.analysis.Unicode12.DOUBLE_QUOTE
import quarry.analysis.Unicode12.EMOJI
import quarry.analysis.Unicode12.EMOJI_MODIFIER
import quarry.analysis.Unicode12.EMOJI_MODIFIER_BASE
import quarry.analysis.Unicode12.EXTEND
import quarry.analysis.Unicode12.EXTENDED_PICTOGRAPHIC
import quarry.analysis.Unicode12.EXTEND_NUM_LET
import quarry.analysis.Unicode12.FORMAT
import quarry.analysis.Unicode12.HAN
import quarry.analysis.Unicode12.HEBREW
import quarry.analysis.Unicode12.HIRAGANA
import quarry.analysis.Unicode12.KATAKANA
import quarry.analysis.Unicode12.MID_LETTER
import quarry.analysis.Unicode12.MID_NUM
import quarry.analysis.Unicode12.MID_NUM_LET
import quarry.analysis.Unicode12.NUMERIC
import quarry.analysis.Unicode12.REGIONAL_INDICATOR
import quarry.analysis.Unicode12.SINGLE_QUOTE
import quarry.analysis.Unicode12.SOUTHEAST_ASIAN
import quarry.analysis.Unicode12.ZWJ

/**
 * Splits text into words the way Lucene's StandardTokenizer does.
 *
 * Lucene's tokenizer is a JFlex grammar over Unicode 12.1 properties
 * implementing the UAX #29 word-break rules: at each position the longest
 * match among its rules (emoji sequences, numbers, words, Southeast Asian
 * runs, single ideographs, single hiragana) becomes a token, and anything
 * else is skipped one character at a time. Here the word and number rules
 * are a hand-written automaton over "units" (a base character followed by
 * any Extend/Format/ZWJ characters), which gives the same spans.
 */
class StandardTokenizer(private val text: String, private val maxTokenLength: Int = 255) {
    private var pos = 0

    /** Start (inclusive) and end (exclusive) of the current token, in chars. */
    var start = 0
        private set
    var end = 0
        private set

    fun next(): Boolean {
        val n = text.length
        while (pos < n) {
            val limit = minOf(n, pos + maxTokenLength)
            var best = matchWord(pos, limit)
            best = maxOf(best, matchEmoji(pos, limit))
            best = maxOf(best, matchRun(pos, limit))
            if (best > pos) {
                start = pos
                end = best
                pos = best
                return true
            }
            pos += Character.charCount(text.codePointAt(pos))
        }
        return false
    }

    private fun cpAt(i: Int) = text.codePointAt(i)

    private fun isExt(cp: Int): Boolean {
        val wb = Unicode12.wordBreak(cp)
        return wb == EXTEND || wb == FORMAT || wb == ZWJ
    }

    /** Skip Extend/Format/ZWJ characters starting at p. */
    private fun skipExt(p: Int, limit: Int): Int {
        var i = p
        while (i < limit) {
            val cp = cpAt(i)
            if (!isExt(cp)) break
            val next = i + Character.charCount(cp)
            if (next > limit) break
            i = next
        }
        return i
    }

    /** The emoji grammar's variant: no presentation selectors, optionally no ZWJ. */
    private fun skipExtForEmoji(p: Int, limit: Int, allowZwj: Boolean): Int {
        var i = p
        while (i < limit) {
            val cp = cpAt(i)
            if (cp == 0xFE0E || cp == 0xFE0F || !isExt(cp)) break
            if (!allowZwj && Unicode12.wordBreak(cp) == ZWJ) break
            i += Character.charCount(cp)
        }
        return minOf(i, limit)
    }

    // ---- words and numbers -------------------------------------------------

    private companion object {
        const val FAIL = -1
        const val START = 0
        const val LEAD = 1 // leading ExtendNumLet, nothing else yet
        const val LET = 2 // after a letter (accepting)
        const val LMID = 3 // letter + MidLetter, needs a letter
        const val HSQ = 4 // Hebrew letter + single quote (accepting)
        const val HDQ = 5 // Hebrew letter + double quote, needs a Hebrew letter
        const val NUM = 6 // after a digit (accepting)
        const val NMID = 7 // digit + MidNum, needs a digit
        const val SEP = 8 // ExtendNumLet after a segment (accepting)
        const val KAT = 9 // after katakana (accepting)
        const val KSEP = 10 // ExtendNumLet after katakana (accepting)
        const val HDQL = 11 // end of a Hebrew HL " HL group (accepting; no mid char may follow)

        fun accepting(state: Int) = state == LET || state == HSQ || state == NUM || state == SEP ||
            state == KAT || state == KSEP || state == HDQL

        fun isLetter(wb: Int) = wb == ALETTER || wb == HEBREW
        fun midLetter(wb: Int) = wb == MID_LETTER || wb == MID_NUM_LET || wb == SINGLE_QUOTE
        fun midNum(wb: Int) = wb == MID_NUM || wb == MID_NUM_LET || wb == SINGLE_QUOTE

        fun step(state: Int, wb: Int, hebrew: Boolean): Int = when (state) {
            START, LEAD -> when {
                isLetter(wb) -> LET
                wb == NUMERIC -> NUM
                wb == KATAKANA -> KAT
                wb == EXTEND_NUM_LET -> LEAD
                else -> FAIL
            }
            LET -> when {
                isLetter(wb) -> LET
                wb == NUMERIC -> NUM
                wb == EXTEND_NUM_LET -> SEP
                wb == SINGLE_QUOTE && hebrew -> HSQ
                midLetter(wb) -> LMID
                wb == DOUBLE_QUOTE && hebrew -> HDQ
                else -> FAIL
            }
            LMID -> if (isLetter(wb)) LET else FAIL
            HSQ, HDQL -> when {
                isLetter(wb) -> LET
                wb == NUMERIC -> NUM
                wb == EXTEND_NUM_LET -> SEP
                else -> FAIL
            }
            HDQ -> if (wb == HEBREW) HDQL else FAIL
            NUM -> when {
                wb == NUMERIC -> NUM
                isLetter(wb) -> LET
                wb == EXTEND_NUM_LET -> SEP
                midNum(wb) -> NMID
                else -> FAIL
            }
            NMID -> if (wb == NUMERIC) NUM else FAIL
            SEP -> when {
                wb == EXTEND_NUM_LET -> SEP
                isLetter(wb) -> LET
                wb == NUMERIC -> NUM
                wb == KATAKANA -> KAT
                else -> FAIL
            }
            KAT -> when (wb) {
                KATAKANA -> KAT
                EXTEND_NUM_LET -> KSEP
                else -> FAIL
            }
            KSEP -> when {
                wb == EXTEND_NUM_LET -> KSEP
                wb == KATAKANA -> KAT
                isLetter(wb) -> LET
                wb == NUMERIC -> NUM
                else -> FAIL
            }
            else -> FAIL
        }
    }

    /** End of the longest word/number token at p, or p if none. */
    private fun matchWord(p0: Int, limit: Int): Int {
        var state = START
        var hebrew = false // the last letter is Hebrew and may take a quote
        var p = p0
        var accept = p0
        while (p < limit) {
            val cp = cpAt(p)
            val wb = Unicode12.wordBreak(cp)
            val unitEnd = skipExt(p + Character.charCount(cp), limit)
            if (p + Character.charCount(cp) > limit) break
            val prev = state
            state = step(state, wb, hebrew)
            if (state == FAIL) break
            // A Hebrew letter can take a quote (HL SQ, HL DQ HL) only when it
            // starts a new branch of the grammar, not right after a mid-word
            // character or as the end of an HL DQ HL group.
            if (isLetter(wb)) hebrew = wb == HEBREW && prev != LMID && prev != HDQ
            p = unitEnd
            if (accepting(state)) accept = p
        }
        return accept
    }

    // ---- Southeast Asian runs, single ideographs and hiragana ---------------

    private fun matchRun(p0: Int, limit: Int): Int {
        val cp = cpAt(p0)
        val cls = Unicode12.classOf(cp)
        val first = p0 + Character.charCount(cp)
        if (first > limit) return p0
        return when {
            cls and SOUTHEAST_ASIAN != 0 -> {
                var p = skipExt(first, limit)
                while (p < limit) {
                    val c = cpAt(p)
                    if (Unicode12.classOf(c) and SOUTHEAST_ASIAN == 0 || p + Character.charCount(c) > limit) break
                    p = skipExt(p + Character.charCount(c), limit)
                }
                p
            }
            cls and (HAN or HIRAGANA) != 0 -> skipExt(first, limit)
            else -> p0
        }
    }

    // ---- emoji ---------------------------------------------------------------

    private fun emojiChar(cp: Int): Boolean {
        val e = Unicode12.emojiOf(cp)
        if (e and EXTENDED_PICTOGRAPHIC != 0) return true
        if (e and EMOJI == 0) return false
        // Emoji minus regional indicators, keycap bases, "accidental" emoji
        // and modifiers.
        val rkam = Unicode12.wordBreak(cp) == REGIONAL_INDICATOR || cp in '0'.code..'9'.code || cp == '#'.code ||
            cp == '*'.code || cp == 0xA9 || cp == 0xAE || cp == 0x2122 || cp == 0x3030 || cp == 0x303D ||
            e and EMOJI_MODIFIER != 0
        return !rkam
    }

    /**
     * Every position an Extend/Format/ZWJ run starting at p can stop at
     * (the grammar's `ExtFmtZwjSansPresSel*` may match any prefix).
     */
    private fun extStops(p: Int, limit: Int, out: MutableList<Int>) {
        out += p
        val last = skipExtForEmoji(p, limit, allowZwj = true)
        var i = p
        while (i < last) {
            i += Character.charCount(cpAt(i))
            out += i
        }
    }

    /**
     * All end positions of one emoji element starting at p:
     *   ZWJ* EmojiChar Ext* FE0F?  |  (ZWJ* ModifierBase Ext*)? Modifier Ext*
     */
    private fun elementEnds(p0: Int, limit: Int): List<Int> {
        var p = p0
        while (p < limit && Unicode12.wordBreak(cpAt(p)) == ZWJ) p++
        if (p >= limit) return emptyList()
        val cp = cpAt(p)
        val e = Unicode12.emojiOf(cp)
        val afterBase = p + Character.charCount(cp)
        if (afterBase > limit) return emptyList()
        val ends = ArrayList<Int>()
        val stops = ArrayList<Int>()
        extStops(afterBase, limit, stops)
        if (emojiChar(cp)) {
            for (x in stops) {
                ends += x
                if (x < limit && cpAt(x) == 0xFE0F) ends += x + 1
            }
        }
        // A lone modifier takes no leading ZWJ: the grammar's ZWJ* sits
        // inside the optional modifier-base group.
        if (e and EMOJI_MODIFIER != 0 && p == p0) ends += stops
        if (e and EMOJI_MODIFIER_BASE != 0) {
            for (x in stops) {
                if (x >= limit) continue
                val m = cpAt(x)
                if (Unicode12.emojiOf(m) and EMOJI_MODIFIER == 0) continue
                val after = x + Character.charCount(m)
                if (after <= limit) extStops(after, limit, ends)
            }
        }
        return ends
    }

    /**
     * Longest `Element ( (ZWJ Element)* | TagSpec+ TagTerm )` starting at p,
     * or -1. Tag sequences (subdivision flags) only follow the first element.
     */
    private fun emojiSequence(p: Int, limit: Int, first: Boolean = true): Int {
        var best = -1
        for (e in elementEnds(p, limit)) {
            best = maxOf(best, e)
            if (e < limit && Unicode12.wordBreak(cpAt(e)) == ZWJ) {
                best = maxOf(best, emojiSequence(e + 1, limit, first = false))
            }
            if (first) {
                var t = e
                while (t < limit && cpAt(t) in 0xE0020..0xE007E) t += 2
                if (t > e && t < limit && cpAt(t) == 0xE007F && t + 2 <= limit) best = maxOf(best, t + 2)
            }
        }
        return best
    }

    private fun matchEmoji(p0: Int, limit: Int): Int {
        val cp = cpAt(p0)
        // Flags: two regional indicators.
        if (Unicode12.wordBreak(cp) == REGIONAL_INDICATOR) {
            val second = skipExt(p0 + Character.charCount(cp), limit)
            if (second < limit && Unicode12.wordBreak(cpAt(second)) == REGIONAL_INDICATOR) {
                val after = second + Character.charCount(cpAt(second))
                if (after <= limit) return skipExt(after, limit)
            }
            return p0
        }
        // Keycaps: [0-9#*] Ext* FE0F? U+20E3 Ext*.
        if (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) {
            var best = p0
            val stops = ArrayList<Int>()
            extStops(p0 + 1, limit, stops)
            for (x in stops) {
                var y = x
                if (y < limit && cpAt(y) == 0xFE0F) {
                    if (y + 1 < limit && cpAt(y + 1) == 0x20E3) best = maxOf(best, skipExtForEmoji(y + 2, limit, true))
                }
                if (y < limit && cpAt(y) == 0x20E3) {
                    y++
                    best = maxOf(best, skipExtForEmoji(y, limit, true))
                }
            }
            return best
        }
        return maxOf(p0, emojiSequence(p0, limit))
    }
}
