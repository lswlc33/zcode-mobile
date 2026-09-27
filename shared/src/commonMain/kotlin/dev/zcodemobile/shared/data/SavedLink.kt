package dev.zcodemobile.shared.data

import dev.zcodemobile.protocol.RemoteLink
import kotlinx.coroutines.flow.StateFlow

/**
 * Saved pairing link — the cross-platform model the home list renders.
 *
 * Persistence differs per platform (Android: EncryptedSharedPreferences,
 * iOS: Keychain); platforms implement [LinkStore] and hand it to [AppModel].
 */
data class SavedLink(
    val id: String,
    val link: RemoteLink,
    val label: String?,
    val savedAt: Long,
) {
    /** The relay link is a temporary key; surface its staleness. */
    val ageMillis: Long get() = dev.zcodemobile.protocol.nowMillisCompat() - link.timestamp

    val displayName: String
        get() = label ?: link.deviceName ?: link.deviceMid ?: link.deviceSid
}
