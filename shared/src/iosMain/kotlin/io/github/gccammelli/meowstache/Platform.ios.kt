@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.gccammelli.meowstache

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
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
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
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

actual fun deviceCountry(): String = NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String ?: ""

actual fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching {
    val img = Image.makeFromEncoded(bytes)
    val scale = maxSide.toFloat() / maxOf(img.width, img.height)
    if (scale >= 1f) img.toComposeImageBitmap() else {
        val w = (img.width * scale).toInt(); val h = (img.height * scale).toInt()
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(img, Rect.makeWH(w.toFloat(), h.toFloat()))
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
}.getOrNull()

fun iosStorage() = PlatformStorage(
    settings = Defaults("settings"),
    auth = Defaults("auth"),
    secrets = Keychain,
    files = object : AppFiles {
        private val dir: String by lazy {
            val d = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
            NSFileManager.defaultManager.createDirectoryAtPath(d, true, null, null)
            d
        }
        override fun read(name: String) = NSString.stringWithContentsOfFile("$dir/$name", NSUTF8StringEncoding, null)
        override fun write(name: String, text: String) { NSString.create(string = text).writeToFile("$dir/$name", true, NSUTF8StringEncoding, null) }
        override fun delete(name: String) { NSFileManager.defaultManager.removeItemAtPath("$dir/$name", null) }
    },
)

/** NSUserDefaults with a per-store key prefix ("settings.", "auth."). */
private class Defaults(private val prefix: String) : KeyValue {
    private val d = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "$prefix.$key"
    override fun getString(key: String) = d.stringForKey(k(key))
    override fun putString(key: String, value: String?) = if (value == null) d.removeObjectForKey(k(key)) else d.setObject(value, k(key))
    override fun getBoolean(key: String, default: Boolean) = if (d.objectForKey(k(key)) == null) default else d.boolForKey(k(key))
    override fun putBoolean(key: String, value: Boolean) = d.setBool(value, k(key))
    override fun getInt(key: String, default: Int) = if (d.objectForKey(k(key)) == null) default else d.integerForKey(k(key)).toInt()
    override fun putInt(key: String, value: Int) = d.setInteger(value.toLong(), k(key))
    override fun getStringSet(key: String) = d.arrayForKey(k(key))?.filterIsInstance<String>()?.toSet() ?: emptySet()
    override fun putStringSet(key: String, value: Set<String>) = d.setObject(value.toList(), k(key))
    override fun clear() = d.dictionaryRepresentation().keys.filterIsInstance<String>().filter { it.startsWith("$prefix.") }.forEach { d.removeObjectForKey(it) }
}

/** Login tokens as Keychain generic passwords, readable only on this device after first unlock (never synced or backed up). */
private object Keychain : SecretStore {
    private const val SERVICE = "meowstache"

    /** A Keychain query; [retained] collects objects to release once the call is done. */
    private fun query(account: String?, retained: MutableList<CFTypeRef?>, vararg extra: Pair<CFStringRef?, CFTypeRef?>): CFMutableDictionaryRef? {
        fun cf(s: String) = CFBridgingRetain(NSString.create(string = s)).also { retained += it }
        val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, cf(SERVICE))
        if (account != null) CFDictionaryAddValue(q, kSecAttrAccount, cf(account))
        extra.forEach { (k, v) -> CFDictionaryAddValue(q, k, v) }
        return q
    }

    private inline fun <T> withQuery(account: String?, vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val retained = mutableListOf<CFTypeRef?>()
        val q = query(account, retained, *extra)
        try { return block(q) } finally { CFRelease(q); retained.forEach { CFRelease(it) } }
    }

    override fun get(key: String): String? = memScoped {
        val out = alloc<CFTypeRefVar>()
        val status = withQuery(key, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { SecItemCopyMatching(it, out.ptr) }
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(out.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun set(key: String, value: String?) {
        withQuery(key) { SecItemDelete(it) }
        if (value == null) return
        val data = CFBridgingRetain(NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding))
        try {
            withQuery(key, kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { SecItemAdd(it, null) }
        } finally { CFRelease(data) }
    }

    override fun clear() { withQuery(null) { SecItemDelete(it) } }
}
