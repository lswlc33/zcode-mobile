package dev.zcodemobile.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCDigestLength
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun nowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val out = ByteArray(kCCDigestLength.toInt())
    memScopedPin(key, message, out) { k, m, o ->
        CCHmac(
            kCCHmacAlgSHA256.convert(),
            k, key.size.convert(),
            m, message.size.convert(),
            o,
        )
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(kCCDigestLength.toInt())
    bytes.usePinned { p ->
        out.usePinned { o ->
            CC_SHA256(p.addressOf(0), bytes.size.convert(), o.addressOf(0))
        }
    }
    return out
}

/**
 * `CCHmac` wants raw pointers for all three buffers at once; pinned scopes
 * must stay open for the whole call, so nest them via this helper.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <T> memScopedPin(
    a: ByteArray, b: ByteArray, c: ByteArray,
    block: (kotlinx.cinterop.Pinned<ByteArray>, kotlinx.cinterop.Pinned<ByteArray>, kotlinx.cinterop.Pinned<ByteArray>) -> T,
): T {
    a.usePinned { pa ->
        b.usePinned { pb ->
            c.usePinned { pc ->
                return block(pa, pb, pc)
            }
        }
    }
}
