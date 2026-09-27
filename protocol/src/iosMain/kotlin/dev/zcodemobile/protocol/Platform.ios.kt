package dev.zcodemobile.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** SHA-256 digest length in bytes (kCCDigestLength is not exported to Kotlin). */
private const val SHA256_LEN = 32

internal actual fun nowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val out = ByteArray(SHA256_LEN)
    key.usePinned { k ->
        message.usePinned { m ->
            out.usePinned { o ->
                CCHmac(
                    kCCHmacAlgSHA256.convert(),
                    k.addressOf(0), key.size.convert(),
                    m.addressOf(0), message.size.convert(),
                    o.addressOf(0),
                )
            }
        }
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(SHA256_LEN)
    bytes.usePinned { p ->
        out.usePinned { o ->
            CC_SHA256(p.addressOf(0), bytes.size.convert(), o.addressOf(0))
        }
    }
    return out
}
