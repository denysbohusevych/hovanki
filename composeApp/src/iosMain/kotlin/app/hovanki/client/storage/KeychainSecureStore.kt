@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.client.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * [SecureStore] on iOS: generic-password items in the Keychain, readable after the first unlock (a round runs with
 * the phone locked) and only on this device (not in backups or on a new phone). See docs/adr/0002-session-storage.md.
 *
 * Keychain items outlive the app, user defaults don't: on the first launch after a (re)install the leftovers of an
 * earlier installation are deleted, so a reinstalled app never tries to resume an old game.
 */
class KeychainSecureStore(private val service: String = SERVICE) : SecureStore {
    init {
        val defaults = NSUserDefaults.standardUserDefaults
        if (!defaults.boolForKey(INSTALLED_KEY)) {
            withQuery(account = null) { query -> SecItemDelete(query) }
            defaults.setBool(true, forKey = INSTALLED_KEY)
        }
    }

    override fun read(key: String): String? {
        val found = memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = withQuery(key, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) {
                SecItemCopyMatching(it, result.ptr)
            }
            if (status == errSecSuccess) CFBridgingRelease(result.value) as? NSData else null
        } ?: return null
        val bytes = ByteArray(found.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), found.bytes, found.length) }
        return bytes.decodeToString()
    }

    override fun write(key: String, value: String) {
        remove(key)
        val bytes = value.encodeToByteArray()
        if (bytes.isEmpty()) return
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        val dataRef = CFBridgingRetain(data)
        try {
            val status = withQuery(
                key,
                kSecValueData to dataRef,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ) { query -> SecItemAdd(query, null) }
            check(status == errSecSuccess) { "Keychain write failed: $status" }
        } finally {
            CFRelease(dataRef)
        }
    }

    override fun remove(key: String) {
        withQuery(key) { query -> SecItemDelete(query) }
    }

    /** Runs [block] with a query for this store's item [account] (all its items if null), plus [attributes]. */
    private inline fun <T> withQuery(
        account: String?,
        vararg attributes: Pair<CFStringRef?, CFTypeRef?>,
        block: (CFMutableDictionaryRef?) -> T,
    ): T {
        val query = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        // The dictionary retains what it holds; these +1 references are released below.
        val serviceRef = CFBridgingRetain(service)
        val accountRef = account?.let { CFBridgingRetain(it) }
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            if (accountRef != null) CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            for ((name, attribute) in attributes) CFDictionaryAddValue(query, name, attribute)
            return block(query)
        } finally {
            CFRelease(serviceRef)
            if (accountRef != null) CFRelease(accountRef)
            CFRelease(query)
        }
    }

    private companion object {
        const val SERVICE = "app.hovanki"
        const val INSTALLED_KEY = "hovanki.keychainInitialized"
    }
}
