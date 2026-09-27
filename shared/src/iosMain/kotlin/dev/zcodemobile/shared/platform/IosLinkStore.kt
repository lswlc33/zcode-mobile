package dev.zcodemobile.shared.platform

import dev.zcodemobile.protocol.RemoteLink
import dev.zcodemobile.shared.data.LinkStore
import dev.zcodemobile.shared.data.SavedLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSMutableString
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.timeIntervalSince1970
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

/**
 * iOS [LinkStore]: each link serialized to one GenericPassword Keychain item
 * (service "dev.zcodemobile.app", account "link.<id>"). The relay hash is an
 * HMAC key, so the Keychain — not NSUserDefaults — is the only acceptable
 * home. The list of stored accounts mirrors into NSUserDefaults because bulk
 * Keychain enumeration through interop is not worth the complexity.
 *
 * Keychain queries are NSMutableDictionary-based: the SecItem* cinterop
 * bindings take CFDictionaryRef, and an NSMutableDictionary IS a CFDictionary
 * (toll-free bridged) but Kotlin needs the explicit cast via the `as Any`
 * CFType route — see [cfRef].
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

        val ids = readIds().toMutableList()
        if (key(id) !in ids) {
            ids += key(id)
            writeIds(ids)
        }
        _links.value = loadAll()
    }

    override fun remove(id: String) {
        keychainDelete(account = key(id))
        writeIds(readIds().filter { it != key(id) })
        _links.value = loadAll()
    }

    private fun key(id: String) = "link.$id"

    private fun readIds(): List<String> {
        val arr = defaults.objectForKey(KEY_IDS) as? NSArray ?: return emptyList()
        return (0 until arr.count.toInt()).mapNotNull { i ->
            arr.objectAtIndex(i.toULong()) as? String
        }
    }

    private fun writeIds(ids: List<String>) {
        val arr = NSMutableArray()
        ids.forEach { arr.addObject(it) }
        defaults.setObjectForKey(arr, KEY_IDS)
    }

    private fun loadAll(): List<SavedLink> {
        return readIds().mapNotNull { acc ->
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

    // ── Keychain helpers (GenericPassword, NSMutableDictionary queries) ──

    private fun baseQuery(account: String): NSMutableDictionary {
        val q = NSMutableDictionary()
        q.setObjectForKey(NSString.create(string = kSecClassGenericPassword as String), kSecClass)
        q.setObjectForKey(NSString.create(string = SERVICE), kSecAttrService)
        q.setObjectForKey(NSString.create(string = account), kSecAttrAccount)
        return q
    }

    private fun keychainSet(account: String, value: String) {
        keychainDelete(account)
        val q = baseQuery(account)
        q.setObjectForKey(
            NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)!!,
            kSecValueData,
        )
        q.setObjectForKey(NSString.create(string = kSecAttrAccessibleAfterFirstUnlock as String), kSecAttrAccessible)
        SecItemAdd(q, null)
    }

    private fun keychainGet(account: String): String? {
        val q = baseQuery(account)
        q.setObjectForKey(NSNumberBoolean(true), kSecReturnData)
        val out = NSMutableDictionary()
        val status = SecItemCopyMatching(q, out)
        if (status != 0) return null
        val data = out.objectForKey(kSecValueData as String) as? NSData ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
    }

    private fun keychainDelete(account: String) {
        SecItemDelete(baseQuery(account))
    }

    private companion object {
        const val SERVICE = "dev.zcodemobile.app"
        const val KEY_IDS = "link_accounts"
    }
}

/** NSNumber boxing for Kotlin booleans, used for kSecReturnData. */
private fun NSNumberBoolean(value: Boolean) = platform.Foundation.NSNumber(number = value)
