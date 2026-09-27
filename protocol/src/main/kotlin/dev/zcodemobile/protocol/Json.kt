package dev.zcodemobile.protocol

/**
 * Dependency-free JSON codec.
 *
 * Present so the protocol module carries no third-party runtime dependency —
 * it must compile for plain JVM (verification harness) and Android alike,
 * and Android's `org.json` has no JVM equivalent.
 *
 * Decoded model: `Map<String, Any?>`, `List<Any?>`, `String`, `Long`, `Double`,
 * `Boolean`, `null`.
 */
object Json {

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long -> sb.append(value)
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                if (d.isFinite()) sb.append(d) else sb.append("null")
            }
            is ByteArray -> writeString(sb, Base64.encode(value))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (el in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, el)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun decode(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("json: unexpected end")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) throw IllegalArgumentException("json: bad literal at $i")
            i += lit.length
        }

        private fun parseObject(): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("json: expected ':' at $i")
                i++
                map[key] = parseValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("json: unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("json: expected ',' or '}' at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("json: unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("json: expected ',' or ']' at $i")
                }
            }
        }

        private fun parseString(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("json: expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("json: bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("json: unterminated string")
        }

        private fun parseNumber(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var isDouble = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) { i++ }
                else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { isDouble = true; i++ }
                else break
            }
            val raw = s.substring(start, i)
            if (raw.isEmpty()) throw IllegalArgumentException("json: bad number at $start")
            return if (isDouble) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }
    }

    // ── typed accessors, tolerating both Long and Double from the parser ──

    @Suppress("UNCHECKED_CAST")
    fun asMap(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    fun asString(v: Any?): String? = v as? String

    fun asLong(v: Any?): Long? = (v as? Number)?.toLong()

    fun asBool(v: Any?): Boolean? = v as? Boolean
}
