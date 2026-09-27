package dev.zcodemobile.protocol

/**
 * Platform seams for the pure-Kotlin protocol layer.
 *
 * Kept intentionally tiny: wall clock (client_ts fields), HMAC-SHA256
 * (relay auth proof) and SHA-256 (attachment checksums). Everything else
 * — UUIDs, Base64, percent-decoding, CRC32 — is implemented in common
 * code so JVM and iOS byte-for-byte agree.
 */
internal expect fun nowMillis(): Long

/** Public wall-clock accessor for clients of the protocol module. */
fun nowMillisCompat(): Long = nowMillis()

internal expect fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

internal expect fun sha256(bytes: ByteArray): ByteArray

internal fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return sb.toString()
}

private const val HEX = "0123456789abcdef"

/** RFC 4122 v4 UUID. Entropy: clock + a per-process random seed; the relay
 *  uses these only as request ids, where collision-freedom is what matters. */
fun randomUuid(): String {
    val b = ByteArray(16)
    var seed = nowMillis() xor processSeed
    for (i in b.indices) {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        seed = seed xor (seed ushr 29)
        b[i] = (seed ushr 24).toByte()
    }
    b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte() // version 4
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // variant 10xx
    val h = b.toHexLower()
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
        "${h.substring(16, 20)}-${h.substring(20, 32)}"
}

/** Mixed once per class-load so two clients started the same millisecond diverge. */
private val processSeed: Long by lazy {
    // identityHashCode of the companion-ish object gives a per-process value
    // on every platform without touching java.util.Random.
    kotlin.random.Random.nextLong()
}

/** Minimal percent-decoder (`+` means space), matching java.net.URLDecoder. */
internal fun percentDecode(raw: String): String {
    if ('%' !in raw && '+' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '+' -> { out.append(' '); i++ }
            c == '%' && i + 2 < raw.length -> {
                val hi = hexVal(raw[i + 1])
                val lo = hexVal(raw[i + 2])
                if (hi < 0 || lo < 0) { out.append(c); i++ }
                else {
                    out.append(((hi shl 4) or lo).toByte().toInt().toChar())
                    i += 3
                }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** Growable byte accumulator — the common stand-in for ByteArrayOutputStream. */
internal class ByteSink(capacity: Int = 32) {
    private var buf = ByteArray(maxOf(capacity, 8))
    private var len = 0

    val size: Int get() = len

    fun write(b: Int) {
        ensure(1)
        buf[len++] = b.toByte()
    }

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buf, len)
        len += bytes.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)

    private fun ensure(extra: Int) {
        if (len + extra <= buf.size) return
        var next = buf.size
        while (next < len + extra) next = next shl 1
        buf = buf.copyOf(next)
    }
}
