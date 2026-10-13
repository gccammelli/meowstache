package io.github.gccammelli.meowstache

import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Endpoints and app keys as used by pylitterbot (the library behind the Home Assistant integration).
private const val COGNITO = "https://cognito-idp.us-east-1.amazonaws.com/"
private const val CLIENT_ID = "4552ujeu3aic90nf8qn53levmn"
const val LR4_ENDPOINT = "https://lr4.iothings.site/graphql"
const val PET_ENDPOINT = "https://pet-profile.iothings.site/graphql/"
private const val V2_ENDPOINT = "https://v2.api.whisker.iothings.site"
private const val V2_KEY = "p7ndMoj61npRZP5CVz9v4Uj0bG769xy6758QRBPb"

class ApiException(message: String) : Exception(message)
class AuthException(message: String) : Exception(message)

val json = Json { ignoreUnknownKeys = true }
private val JSON_TYPE = "application/json".toMediaType()

class Api(private val prefs: SharedPreferences) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val lock = Mutex()

    val isLoggedIn get() = secret("refresh") != null
    val email get() = prefs.getString("email", "") ?: ""
    val userId: String? get() = secret("id")?.let { jwtClaims(it)["mid"]?.str() }

    suspend fun login(email: String, password: String) {
        val res = cognito(
            "USER_PASSWORD_AUTH",
            mapOf("USERNAME" to email.trim(), "PASSWORD" to password)
        )
        val auth = res["AuthenticationResult"]?.jsonObject
            ?: throw AuthException("Unexpected login challenge: ${res["ChallengeName"]?.str()}")
        prefs.edit()
            .putString("email", email.trim())
            .putString("id", TokenCrypt.encrypt(auth["IdToken"]!!.str()!!))
            .putString("refresh", TokenCrypt.encrypt(auth["RefreshToken"]!!.str()!!))
            .apply()
    }

    fun logout() = prefs.edit().clear().apply()

    /** A stored token, decrypted. Tokens saved before encryption existed are re-saved encrypted on first read. */
    private fun secret(key: String): String? {
        val v = prefs.getString(key, null) ?: return null
        if (!TokenCrypt.isEncrypted(v)) { prefs.edit().putString(key, TokenCrypt.encrypt(v)).apply(); return v }
        return TokenCrypt.decrypt(v) ?: run { prefs.edit().remove(key).apply(); null } // key gone (e.g. app data restored): log in again
    }

    private suspend fun idToken(): String = lock.withLock {
        val id = secret("id")
        val exp = id?.let { jwtClaims(it)["exp"]?.str()?.toLongOrNull() } ?: 0
        if (id != null && exp - 60 > System.currentTimeMillis() / 1000) return id
        val refresh = secret("refresh") ?: throw AuthException("Not logged in")
        val auth = cognito("REFRESH_TOKEN_AUTH", mapOf("REFRESH_TOKEN" to refresh))["AuthenticationResult"]
            ?.jsonObject ?: throw AuthException("Session expired")
        val newId = auth["IdToken"]!!.str()!!
        prefs.edit().putString("id", TokenCrypt.encrypt(newId)).apply()
        newId
    }

    private suspend fun cognito(flow: String, params: Map<String, String>): JsonObject {
        val body = buildJsonObject {
            put("AuthFlow", flow)
            put("ClientId", CLIENT_ID)
            put("AuthParameters", JsonObject(params.mapValues { JsonPrimitive(it.value) }))
        }.toString()
        val req = Request.Builder().url(COGNITO)
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            .post(body.toRequestBody("application/x-amz-json-1.1".toMediaType()))
            .build()
        val (code, text) = call(req)
        val obj = json.parseToJsonElement(text).jsonObject
        if (code != 200) {
            val type = obj["__type"]?.str().orEmpty()
            throw AuthException(
                if ("NotAuthorized" in type || "UserNotFound" in type) "Incorrect email or password"
                else obj["message"]?.str() ?: "Login failed ($code)"
            )
        }
        return obj
    }

    private suspend fun call(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { it.code to it.body.string() }
    }

    /** Runs a GraphQL operation and returns its `data` object. */
    suspend fun gql(endpoint: String, query: String, variables: Map<String, Any?> = emptyMap()): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", JsonObject(variables.mapValues { it.value.toJson() }))
        }.toString()
        val req = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer ${idToken()}")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val (code, text) = call(req)
        if (code == 401) throw AuthException("Session expired")
        if (code !in 200..299) throw ApiException("HTTP $code: ${text.take(200)}")
        val obj = json.parseToJsonElement(text).jsonObject
        val errors = obj["errors"] as? JsonArray
        val data = obj["data"] as? JsonObject
        if (data == null && !errors.isNullOrEmpty()) {
            throw ApiException(errors.joinToString { it.jsonObject["message"]?.str().orEmpty() })
        }
        return data ?: JsonObject(emptyMap())
    }

    suspend fun user(): JsonObject {
        val req = Request.Builder().url("$V2_ENDPOINT/users/$userId")
            .header("Authorization", "Bearer ${idToken()}")
            .header("x-api-key", V2_KEY)
            .build()
        val (code, text) = call(req)
        if (code !in 200..299) throw ApiException("HTTP $code")
        return json.parseToJsonElement(text).jsonObject["user"]?.jsonObject ?: JsonObject(emptyMap())
    }

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) throw ApiException("HTTP ${it.code}")
            it.body.bytes()
        }
    }
}

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

fun jwtClaims(token: String): JsonObject = runCatching {
    val payload = token.split(".")[1]
    json.parseToJsonElement(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        .jsonObject
}.getOrDefault(JsonObject(emptyMap()))

fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonObject.s(key: String): String? = this[key]?.str()
fun JsonObject.d(key: String): Double? = s(key)?.toDoubleOrNull()
fun JsonObject.i(key: String): Int? = d(key)?.toInt()
fun JsonObject.b(key: String): Boolean = s(key) == "true"
fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.a(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()

/** AES-GCM with a key that lives in the Android Keystore and never leaves it, so the tokens are useless if copied off the phone. */
private object TokenCrypt {
    private const val ALIAS = "auth-tokens"
    private const val PREFIX = "enc1:"

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

    fun isEncrypted(v: String) = v.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return PREFIX + b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    }

    fun decrypt(v: String): String? = runCatching {
        val (iv, data) = v.removePrefix(PREFIX).split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(c.doFinal(data))
    }.getOrNull()
}
