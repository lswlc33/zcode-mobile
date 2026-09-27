package dev.zcodemobile.protocol

/**
 * Dependency-free Base64 (RFC 4648) for the protocol layer, replacing
 * java.util.Base64 so the same bytes come out of JVM and iOS alike.
 */
object Base64 {

    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val REVERSE = IntArray(128) { -1 }.also { r ->
        for (i in ALPHA.indices) r[ALPHA[i].code] = i
    }

    /** Standard alphabet with padding, as `Base64.getEncoder()`. */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size / 3 + 1) * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            emitGroup(sb, bytes, i, 3)
            i += 3
        }
        when (bytes.size - i) {
            1 -> emitGroup(sb, bytes, i, 1)
            2 -> emitGroup(sb, bytes, i, 2)
        }
        return sb.toString()
    }

    private fun emitGroup(sb: StringBuilder, b: ByteArray, off: Int, count: Int) {
        val v = ((b[off].toInt() and 0xff) shl 16) or
            ((if (count > 1) b[off + 1].toInt() and 0xff else 0) shl 8) or
            (if (count > 2) b[off + 2].toInt() and 0xff else 0)
        sb.append(ALPHA[(v ushr 18) and 0x3f])
        sb.append(ALPHA[(v ushr 12) and 0x3f])
        sb.append(if (count > 1) ALPHA[(v ushr 6) and 0x3f] else '=')
        sb.append(if (count > 2) ALPHA[v and 0x3f] else '=')
    }

    /** URL-safe alphabet, no padding, as `Base64.getUrlEncoder().withoutPadding()`. */
    fun encodeUrlNoPad(bytes: ByteArray): String {
        val std = encode(bytes)
        return buildString(std.length) {
            for (c in std) {
                when (c) {
                    '+' -> append('-')
                    '/' -> append('_')
                    '=' -> {}
                    else -> append(c)
                }
            }
        }
    }

    /** Accepts both standard and URL-safe alphabets, with or without padding. */
    fun decode(text: String): ByteArray {
        val out = ByteArray(text.length / 4 * 3 + 3)
        var len = 0
        var acc = 0
        var bits = 0
        for (c in text) {
            if (c == '=' || c == '\n' || c == '\r') continue
            val v = if (c == '-') 62
            else if (c == '_') 63
            else if (c.code < 128) REVERSE[c.code]
            else -1
            if (v < 0) throw IllegalArgumentException("base64: bad character '$c'")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[len++] = ((acc ushr bits) and 0xff).toByte()
            }
        }
        return out.copyOf(len)
    }
}
