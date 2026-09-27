package dev.zcodemobile.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.zcodemobile.protocol.RemoteLink
import dev.zcodemobile.shared.data.LinkStore
import dev.zcodemobile.shared.data.SavedLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [LinkStore]: EncryptedSharedPreferences (Android Keystore-backed).
 * The link's `hash` is an HMAC key for the relay; it never leaves this store
 * in logs.
 */
class AndroidLinkStore(context: Context) : LinkStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _links = MutableStateFlow(loadAll())
    override val links: StateFlow<List<SavedLink>> = _links.asStateFlow()

    override fun save(link: RemoteLink, label: String?) {
        val id = link.deviceMid ?: link.deviceSid
        prefs.edit()
            .putString(key(id), serialize(link, label))
            .apply()
        _links.value = loadAll()
    }

    override fun remove(id: String) {
        prefs.edit().remove(key(id)).apply()
        _links.value = loadAll()
    }

    private fun key(id: String) = "link.$id"

    private fun loadAll(): List<SavedLink> = prefs.all.keys
        .filter { it.startsWith("link.") }
        .mapNotNull { k -> prefs.getString(k, null)?.let(::deserialize) }
        .sortedByDescending { it.savedAt }

    private fun serialize(link: RemoteLink, label: String?): String = listOf(
        link.deviceSid,
        link.passHash,
        link.timestamp.toString(),
        link.deviceMid ?: "",
        link.deviceName ?: "",
        link.appVersion ?: "",
        label ?: "",
        System.currentTimeMillis().toString(),
    ).joinToString("\u0000")

    private fun deserialize(raw: String): SavedLink? {
        val p = raw.split('\u0000')
        if (p.size < 8) return null
        return SavedLink(
            id = p[3].ifEmpty { p[0] },
            link = RemoteLink(
                deviceSid = p[0],
                passHash = p[1],
                timestamp = p[2].toLongOrNull() ?: 0L,
                deviceMid = p[3].ifEmpty { null },
                deviceName = p[4].ifEmpty { null },
                appVersion = p[5].ifEmpty { null },
            ),
            label = p[6].ifEmpty { null },
            savedAt = p[7].toLongOrNull() ?: 0L,
        )
    }

    private companion object {
        const val FILE_NAME = "zcode_links"
    }
}
