package dev.zcodemobile.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.kCCDigestLength
import platform.CoreCrypto.kCCHmacAlgSHA256

internal actual fun nowMillis(): Long =
    platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000

@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val out = ByteArray(kCCDigestLength)
    key.usePinned { k ->
        message.usePinned { m ->
            out.usePinned { o ->
                CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.toUInt(),
                    m.addressOf(0), message.size.toUInt(), o.addressOf(0))
            }
        }
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(kCCDigestLength)
    bytes.usePinned { p ->
        out.usePinned { o ->
            CC_SHA256(p.addressOf(0), bytes.size.toUInt(), o.addressOf(0))
        }
    }
    return out
}
