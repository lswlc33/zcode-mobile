@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.zcodemobile.shared.platform

import dev.zcodemobile.protocol.RemoteLink
import dev.zcodemobile.shared.data.LinkStore
import dev.zcodemobile.shared.data.SavedLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSMutableArray
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
 * Keychain queries are raw CFDictionaryRefs built with the C API: the cinterop
 * SecItem bindings take CFDictionaryRef / CFTypeRef, and neither Kotlin Map
 * nor NSMutableDictionary typechecks against those signatures.
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
            (arr.objectAtIndex(i.convert()) as? NSString) as? String
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

    // ── Keychain helpers (raw CFDictionary queries) ───────────────────────

    /** NSString bridges to CFStringRef; keep the pointer form for CF calls. */
    private fun cfStr(s: String): CPointer<CFStringRef>? =
        (NSString.create(string = s) as Any) as? CPointer<CFStringRef>

    private fun newMutableDict(capacity: Int): CPointer<*> =
        CFDictionaryCreateMutable(kCFAllocatorDefault, capacity.convert(), null, null) as CPointer<*>

    private fun queryBase(account: String): CPointer<*> {
        val dict = newMutableDict(3)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfStr(SERVICE))
        CFDictionaryAddValue(dict, kSecAttrAccount, cfStr(account))
        return dict
    }

    private fun keychainSet(account: String, value: String) {
        keychainDelete(account)
        val dict = newMutableDict(5)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfStr(SERVICE))
        CFDictionaryAddValue(dict, kSecAttrAccount, cfStr(account))
        CFDictionaryAddValue(dict, kSecValueData, cfStr(value))
        CFDictionaryAddValue(dict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        SecItemAdd(dict, null)
    }

    private fun keychainGet(account: String): String? = memScoped {
        val dict = queryBase(account)
        CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(dict, out.ptr)
        if (status != 0) return@memScoped null
        val data = out.value as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
    }

    private fun keychainDelete(account: String) {
        SecItemDelete(queryBase(account))
    }

    private companion object {
        const val SERVICE = "dev.zcodemobile.app"
        const val KEY_IDS = "link_accounts"
    }
}
