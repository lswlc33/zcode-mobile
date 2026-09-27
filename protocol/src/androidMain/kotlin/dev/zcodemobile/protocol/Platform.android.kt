package dev.zcodemobile.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(message)
}

internal actual fun sha256(bytes: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
