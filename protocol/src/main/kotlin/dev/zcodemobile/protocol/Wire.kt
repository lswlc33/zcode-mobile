package dev.zcodemobile.protocol

/** Transport-facing WebSocket surface; implemented per platform (JVM / Android). */
interface WebSocketConnection {
    val isOpen: Boolean
    fun send(text: String)
    fun close(code: Int, reason: String)
}

/** Callbacks a platform WebSocket must drive. */
interface WebSocketListener {
    fun onOpen()
    fun onTextMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

/** Opens a WebSocket; inject so the protocol module stays platform-agnostic. */
fun interface WebSocketFactory {
    fun open(url: String, listener: WebSocketListener): WebSocketConnection
}

object Crc32 {
    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) 0xedb88320.toInt() xor (c ushr 1) else c ushr 1 }
            t[i] = c
        }
    }

    /** Matches `crc32WireBytes`: 8 lowercase hex chars, no prefix. */
    fun hex(bytes: ByteArray): String {
        var crc = 0xffffffff.toInt()
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xff)
            for (bit in 0 until 8) {
                crc = if (crc and 1 != 0) 0xedb88320.toInt() xor (crc ushr 1) else crc ushr 1
            }
        }
        crc = crc.inv()
        return String.format("%08x", crc.toLong() and 0xffffffffL)
    }
}

/** Channel RPC constants, mirroring `channels.shared.ts`. */
object ChannelProtocol {
    const val REQUEST_PROMISE = 100
    const val REQUEST_PROMISE_CANCEL = 101
    const val REQUEST_EVENT_LISTEN = 102
    const val REQUEST_EVENT_DISPOSE = 103

    const val RESPONSE_INITIALIZE = 200
    const val RESPONSE_PROMISE_SUCCESS = 201
    const val RESPONSE_PROMISE_ERROR = 202
    const val RESPONSE_PROMISE_ERROR_OBJ = 203
    const val RESPONSE_EVENT_FIRE = 204

    const val CHANNEL_ZCODE_AGENT = "zcode-agent"
    const val CHANNEL_ZCODE_SESSION = "zcode-session"
}
