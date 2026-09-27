package dev.zcodemobile.shared.session

import dev.zcodemobile.protocol.Base64 as ProtoBase64

/**
 * Session-layer shims over the protocol module's cross-platform codecs.
 * Aliased so call sites read exactly as before the KMP split.
 */
internal object PBase64 {
    fun decode(text: String): ByteArray = ProtoBase64.decode(text)
}

internal object PUuid {
    fun random(): String = dev.zcodemobile.protocol.randomUuid()
}

