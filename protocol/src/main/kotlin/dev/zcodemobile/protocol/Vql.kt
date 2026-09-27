package dev.zcodemobile.protocol

/**
 * Minimal VQL (variable-length quantity) codec.
 *
 * Mirrors `packages/rpc/src/serialization.ts` from zai-org/ZCode.
 * Wire shape: `[1 byte type tag][VQL length where applicable][data]`.
 * Seven bits per byte, high bit = continuation.
 */
object Vql {

    const val UNDEFINED = 0
    const val STRING = 1
    const val BUFFER = 2
    const val VS_BUFFER = 3
    const val ARRAY = 4
    const val OBJECT = 5
    const val INT = 6

    /** A byte sink that accumulates without copying on every write. */
    class Writer {
        private val out = ByteSink()

        fun write(bytes: ByteArray) {
            out.write(bytes)
        }

        fun writeByte(b: Int) {
            out.write(b)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** A cursor over a byte array; reads are clamped, matching VSBuffer.slice. */
    class Reader(private val buf: ByteArray) {
        var pos = 0
            private set

        fun read(n: Int): ByteArray {
            val end = minOf(pos + n, buf.size)
            val slice = buf.copyOfRange(pos, end)
            pos = end
            return slice
        }

        fun readByte(): Int {
            if (pos >= buf.size) throw IllegalStateException("vql: truncated buffer")
            return buf[pos++].toInt() and 0xff
        }
    }

    fun writeInt(w: Writer, value: Int) {
        if (value == 0) {
            w.writeByte(0)
            return
        }
        val scratch = ArrayList<Int>(5)
        var v = value
        while (v != 0) {
            scratch.add(v and 0x7f)
            v = v ushr 7
        }
        for (i in 0 until scratch.size - 1) scratch[i] = scratch[i] or 0x80
        for (b in scratch) w.writeByte(b)
    }

    fun readInt(r: Reader): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = r.readByte()
            value = value or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
    }

    /**
     * Encode a value. Supported types: null, String, ByteArray, List<*>,
     * Int/Long/Double/Boolean, and anything else via the JSON object fallback.
     */
    fun serialize(w: Writer, data: Any?) {
        when (data) {
            null -> w.writeByte(UNDEFINED)
            is String -> {
                val bytes = data.toByteArray(Charsets.UTF_8)
                w.writeByte(STRING); writeInt(w, bytes.size); w.write(bytes)
            }
            is ByteArray -> {
                w.writeByte(BUFFER); writeInt(w, data.size); w.write(data)
            }
            is List<*> -> {
                w.writeByte(ARRAY); writeInt(w, data.size)
                for (el in data) serialize(w, el)
            }
            is Int -> {
                w.writeByte(INT); writeInt(w, data)
            }
            is Boolean -> {
                // Booleans ride the JSON object fallback, as in the reference impl.
                val bytes = data.toString().toByteArray(Charsets.UTF_8)
                w.writeByte(OBJECT); writeInt(w, bytes.size); w.write(bytes)
            }
            else -> {
                val bytes = Json.encode(data).toByteArray(Charsets.UTF_8)
                w.writeByte(OBJECT); writeInt(w, bytes.size); w.write(bytes)
            }
        }
    }

    fun deserialize(r: Reader): Any? {
        return when (val type = r.readByte()) {
            UNDEFINED -> null
            STRING -> String(r.read(readInt(r)), Charsets.UTF_8)
            BUFFER -> r.read(readInt(r))
            VS_BUFFER -> r.read(readInt(r))
            ARRAY -> {
                val n = readInt(r)
                val list = ArrayList<Any?>(n)
                repeat(n) { list.add(deserialize(r)) }
                list
            }
            OBJECT -> Json.decode(String(r.read(readInt(r)), Charsets.UTF_8))
            INT -> readInt(r)
            else -> throw IllegalStateException("vql: unknown type tag $type")
        }
    }

    /** Channel messages are always `serialize(header) + serialize(body)`. */
    fun encodeMessage(header: Any?, body: Any?): ByteArray {
        val w = Writer()
        serialize(w, header)
        serialize(w, body)
        return w.toByteArray()
    }

    fun decodeMessage(buf: ByteArray): Pair<Any?, Any?> {
        val r = Reader(buf)
        return deserialize(r) to deserialize(r)
    }
}
