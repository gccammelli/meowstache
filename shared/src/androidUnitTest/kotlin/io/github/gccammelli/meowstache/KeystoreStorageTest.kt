package io.github.gccammelli.meowstache

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

/** Simulates only the Android key service; production storage still uses real JVM AES-GCM. */
private object TestKeys {
    val entries = mutableMapOf<String, SecretKey>()
}

class TestKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?) = TestKeys.entries[alias]
    override fun engineContainsAlias(alias: String) = TestKeys.entries.containsKey(alias)
    override fun engineAliases() = Collections.enumeration(TestKeys.entries.keys)
    override fun engineSize() = TestKeys.entries.size
    override fun engineIsKeyEntry(alias: String) = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineGetCreationDate(alias: String): Date? = null
    override fun engineDeleteEntry(alias: String) { TestKeys.entries.remove(alias) }
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = error("Not exportable")
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) = error("Not used")
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = error("Not used")
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = error("Not used")
}

class TestKeyGenerator : KeyGeneratorSpi() {
    private lateinit var spec: KeyGenParameterSpec
    override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
        spec = params as KeyGenParameterSpec
        assertEquals(256, spec.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertContentEquals(arrayOf("GCM"), spec.blockModes)
        assertContentEquals(arrayOf("NoPadding"), spec.encryptionPaddings)
    }
    override fun engineInit(random: SecureRandom?) = error("A key specification is required")
    override fun engineInit(keysize: Int, random: SecureRandom?) = error("A key specification is required")
    override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES", "SunJCE").apply { init(spec.keySize) }.generateKey().also { TestKeys.entries[spec.keystoreAlias] = it }
}

@RunWith(RobolectricTestRunner::class)
class KeystoreStorageTest {
    @Test fun tokensAreEncryptedRoundTripAndAreDiscardedWhenTheKeyIsLost() {
        val provider = object : Provider("AndroidKeyStore", 1.0, "Test-only Android key boundary") {
            init {
                put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
                put("KeyGenerator.AES", TestKeyGenerator::class.java.name)
            }
        }
        assertEquals(-1, Security.getProviders().indexOfFirst { it.name == provider.name })
        Security.addProvider(provider)
        TestKeys.entries.clear()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            val storage = androidStorage(context)
            storage.secrets.set("session", "test-secret")
            val encrypted = prefs.getString("session", null)!!
            assertTrue(encrypted.startsWith("enc1:")); assertFalse(encrypted.contains("test-secret"))
            assertEquals("test-secret", storage.secrets.get("session"))
            val key = TestKeys.entries.values.single()
            storage.secrets.set("session", "test-secret")
            assertSame(key, TestKeys.entries.values.single())
            assertNotEquals(encrypted, prefs.getString("session", null))
            assertEquals("test-secret", androidStorage(context).secrets.get("session"))
            prefs.edit().putString("legacy", "legacy-secret").commit()
            assertEquals("legacy-secret", storage.secrets.get("legacy"))
            assertTrue(prefs.getString("legacy", null)!!.startsWith("enc1:"))
            TestKeys.entries.clear()
            assertNull(storage.secrets.get("session")); assertFalse(prefs.contains("session"))
            storage.secrets.set("session", "replacement")
            assertEquals("replacement", storage.secrets.get("session"))
            storage.secrets.set("session", null)
            assertNull(storage.secrets.get("session")); assertFalse(prefs.contains("session"))
            storage.secrets.clear()
            assertTrue(prefs.all.isEmpty())
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("old-setting", 42).commit()
            assertNull(storage.settings.getString("old-setting"))
        } finally {
            TestKeys.entries.clear()
            Security.removeProvider(provider.name)
        }
    }
}
