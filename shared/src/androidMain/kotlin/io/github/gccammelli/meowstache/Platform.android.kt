package io.github.gccammelli.meowstache

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual fun deviceCountry(): String = java.util.Locale.getDefault().country

actual fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxSide)
    opts.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/** Same prefs names, token encryption and files as the Android-only versions, so updates keep the login and history. */
fun androidStorage(ctx: Context): PlatformStorage {
    val auth = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
    return PlatformStorage(
        settings = Prefs(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)),
        auth = Prefs(auth),
        secrets = KeystoreSecrets(auth),
        files = object : AppFiles {
            override fun read(name: String) = File(ctx.filesDir, name).takeIf { it.exists() }?.readText()
            override fun write(name: String, text: String) = File(ctx.filesDir, name).writeText(text)
            override fun delete(name: String) { File(ctx.filesDir, name).delete() }
        },
    )
}

private class Prefs(private val p: SharedPreferences) : KeyValue {
    override fun getString(key: String) = runCatching { p.getString(key, null) }.getOrNull()
    override fun putString(key: String, value: String?) = p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = p.edit().putBoolean(key, value).apply()
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun putInt(key: String, value: Int) = p.edit().putInt(key, value).apply()
    override fun getStringSet(key: String): Set<String> = p.getStringSet(key, emptySet())!!
    override fun putStringSet(key: String, value: Set<String>) = p.edit().putStringSet(key, value).apply()
    override fun clear() = p.edit().clear().apply()
}

/**
 * Tokens stored in the "auth" prefs, AES-GCM encrypted with a key that lives in the Android Keystore and never leaves it.
 * Tokens saved before encryption existed are re-saved encrypted on first read; if the key is gone, the token is dropped.
 */
private class KeystoreSecrets(private val p: SharedPreferences) : SecretStore {
    override fun get(key: String): String? {
        val v = p.getString(key, null) ?: return null
        if (!v.startsWith(PREFIX)) { set(key, v); return v }
        return decrypt(v) ?: run { p.edit().remove(key).apply(); null }
    }
    override fun set(key: String, value: String?) = p.edit().apply { if (value == null) remove(key) else putString(key, encrypt(value)) }.apply()
    override fun clear() = p.edit().clear().apply()

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return PREFIX + b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    }

    private fun decrypt(v: String): String? = runCatching {
        val (iv, data) = v.removePrefix(PREFIX).split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(c.doFinal(data))
    }.getOrNull()

    private companion object {
        const val ALIAS = "auth-tokens"
        const val PREFIX = "enc1:"
    }
}
