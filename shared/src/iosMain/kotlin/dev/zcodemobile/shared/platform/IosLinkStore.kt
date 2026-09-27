package dev.zcodemobile.shared.platform

import dev.zcodemobile.protocol.RemoteLink
import dev.zcodemobile.shared.data.LinkStore
import dev.zcodemobile.shared.data.SavedLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Foundation.NSDataReadOptionsMapped
import platform.Foundation.NSDataReadingUncached
import platform.Foundation.dataWithContentsOfFile

/**
 * iOS [LinkStore]: each link serialized to one GenericPassword Keychain item
 * (service "dev.zcodemobile.app", account "link.<id>"). The relay hash is an
 * HMAC key, so the Keychain — not NSUserDefaults — is the only acceptable
 * home. The index of stored ids mirrors into NSUserDefaults because Keychain
 * enumeration is not practical through interop.
 */
class IosLinkStore : LinkStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    private val _links = MutableStateFlow(loadAll())
    override val links: StateFlow<List<SavedLink>> = _links.asStateFlow()

    override fun save(link: RemoteLink, label: String?) {
        val id = link.deviceMid ?: link.deviceSid
        val serialized = listOf(
            link.deviceSid,
            link.passHash,
            link.timestamp.toString(),
            link.deviceMid ?: "",
            link.deviceName ?: "",
            link.appVersion ?: "",
            label ?: "",
            (NSDate().timeIntervalSince1970 * 1000).toLong().toString(),
        ).joinToString("\u0000")

        keychainSet(account = key(id), value = serialized)

        val ids = (defaults.stringArrayForKey(KEY_IDS) ?: emptyList()).toMutableList()
        if (key(id) !in ids) {
            ids += key(id)
            defaults.setObject(ids, KEY_IDS)
        }
        _links.value = loadAll()
    }

    override fun remove(id: String) {
        keychainDelete(account = key(id))
        val ids = (defaults.stringArrayForKey(KEY_IDS) ?: emptyList()).filter { it != key(id) }
        defaults.setObject(ids, KEY_IDS)
        _links.value = loadAll()
    }

    private fun key(id: String) = "link.$id"

    private fun loadAll(): List<SavedLink> {
        val accounts = defaults.stringArrayForKey(KEY_IDS) ?: return emptyList()
        return accounts.mapNotNull { acc ->
            keychainGet(acc)?.let(::deserialize)?.copy(id = acc.removePrefix("link."))
        }.sortedByDescending { it.savedAt }
    }

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

    // ── Keychain helpers (GenericPassword) ────────────────────────────────

    private fun baseQuery(account: String) = mapOf<Any?, Any?>(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to account,
    )

    private fun keychainSet(account: String, value: String) {
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val addQuery = baseQuery(account) + mapOf<Any?, Any?>(
            kSecValueData to data,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
        )
        // Replace-or-add: delete first, then add.
        SecItemDelete(baseQuery(account))
        SecItemAdd(addQuery, null)
    }

    private fun keychainGet(account: String): String? {
        val query = baseQuery(account) + mapOf<Any?, Any?>(
            kSecReturnData to true,
        )
        val result = kotlinx.cinterop.alloc<kotlinx.cinterop.CPointerVar<platform.CoreFoundation.CFDataRef>>()
        val status = SecItemCopyMatching(query, kotlinx.cinterop.alloc<platform.CoreFoundation.CFTypeRefVar>().ptr)
        if (status != 0) return null
        // SecItemCopyMatching writes a CFDataRef into the result pointer.
        val data = result.value ?: return null
        val nsdata = data as? NSData ?: return null
        return NSString.create(data = nsdata, encoding = NSUTF8StringEncoding) as? String
    }

    private fun keychainDelete(account: String) {
        SecItemDelete(baseQuery(account))
    }

    private companion object {
        const val SERVICE = "dev.zcodemobile.app"
        const val KEY_IDS = "link_accounts"
    }
}
