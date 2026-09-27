package dev.zcodemobile.shared.data

import dev.zcodemobile.protocol.RemoteLink
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform persistence seam for pairing links.
 *
 * The link's `hash` is an HMAC key that authenticates this device to the
 * relay — anyone holding it can drive the paired desktop — so every platform
 * implementation must use its OS credential storage (Android Keystore,
 * iOS Keychain) and never log the value.
 */
interface LinkStore {
    val links: StateFlow<List<SavedLink>>

    fun save(link: RemoteLink, label: String?)
    fun remove(id: String)
    fun get(id: String): SavedLink? = links.value.firstOrNull { it.id == id }
}
