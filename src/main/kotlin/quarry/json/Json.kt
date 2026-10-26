package quarry.json

/**
 * A small JSON parser and writer (RFC 8259). Values are plain Kotlin types:
 * null, Boolean, Double/Long, String, List<Any?> and Map<String, Any?>
 * (insertion-ordered).
 */
object Json {
    class ParseException(message: String, val offset: Int) : RuntimeException("$message at offset $offset")

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw ParseException("trailing characters", p.i)
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ParseException("expected an object", 0)

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short -> sb.append(v)
            // Floats print their shortest form ("0.1", not 0.100000001...).
            is Float -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Double -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Number -> number(v.toDouble(), sb)
            is String -> string(v, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(x, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(v.asList(), sb)
            is FloatArray -> write(v.asList(), sb)
            is IntArray -> write(v.asList(), sb)
            else -> string(v.toString(), sb)
        }
    }

    private fun number(d: Double, sb: StringBuilder) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.rint(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong())
            else -> sb.append(d)
        }
    }

    private fun string(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw ParseException(msg, i)

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 512) fail("nesting too deep")
            if (i >= s.length) fail("unexpected end of input")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (s[i] == '-' || s[i].isDigit()) num() else fail("unexpected character '${s[i]}'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected a string key")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> {
                        i--
                        fail("expected ',' or '}'")
                    }
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val a = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return a
            }
            while (true) {
                ws()
                a += value(depth + 1)
                ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return a
                    else -> {
                        i--
                        fail("expected ',' or ']'")
                    }
                }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("short \\u escape")
                                val cp = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                i += 4
                                sb.append(cp.toChar()) // surrogate pairs arrive as two escapes
                            }
                            else -> fail("bad escape")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') i++ else if (s[i].isDigit()) while (i < s.length && s[i].isDigit()) i++ else fail("bad number")
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                if (i >= s.length || !s[i].isDigit()) fail("digit expected")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) fail("digit expected")
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (isDouble) text.toDouble() else text.toLongOrNull() ?: text.toDouble()
        }
    }
}
