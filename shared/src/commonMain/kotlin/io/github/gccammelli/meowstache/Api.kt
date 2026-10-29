package io.github.gccammelli.meowstache

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
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

/**
 * A hard cap on requests to Whisker's servers (GitHub #4). Every request in [Api] takes a slot first, so no bug,
 * retry loop or burst of live updates can make Meowstache hammer them. A first login (a year of history) fits.
 */
class RequestGuard(private val perMinute: Int = 60, private val perHour: Int = 400, private val now: () -> Long = ::nowMs) {
    private val times = ArrayDeque<Long>()
    private val lock = Mutex()

    suspend fun take() = lock.withLock { if (!allow()) throw ApiException("Paused for a few minutes to go easy on Whisker's servers") }

    /** Takes a slot if one is free (not thread-safe on its own: [take] locks around it). */
    fun allow(): Boolean {
        val t = now()
        while (times.isNotEmpty() && t - times.first() >= 3_600_000) times.removeFirst()
        if (times.size >= perHour || times.count { t - it < 60_000 } >= perMinute) return false
        times.addLast(t)
        return true
    }
}

class Api(private val prefs: KeyValue, private val secrets: SecretStore, private val http: HttpClient = HttpClient {
        install(HttpTimeout) { connectTimeoutMillis = 20_000; requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
        install(WebSockets)
        expectSuccess = false
    }, private val realtimeEndpoint: String = "wss://lr4.iothings.site/graphql/realtime") {
    private val lock = Mutex()
    /** Every request to Whisker's servers goes through this; see [RequestGuard]. */
    val guard = RequestGuard()

    val isLoggedIn get() = secret("refresh") != null
    val email get() = prefs.getString("email") ?: ""
    val userId: String? get() = secret("id")?.let { jwtClaims(it)["mid"]?.str() }

    suspend fun login(email: String, password: String) {
        val res = cognito(
            "USER_PASSWORD_AUTH",
            mapOf("USERNAME" to email.trim(), "PASSWORD" to password)
        )
        val auth = res["AuthenticationResult"]?.jsonObject
            ?: throw AuthException("Unexpected login challenge: ${res["ChallengeName"]?.str()}")
        prefs.putString("email", email.trim())
        secrets.set("id", auth["IdToken"]!!.str()!!)
        secrets.set("refresh", auth["RefreshToken"]!!.str()!!)
    }

    fun logout() { prefs.clear(); secrets.clear() }

    private fun secret(key: String): String? = secrets.get(key)

    private suspend fun idToken(): String = lock.withLock {
        val id = secret("id")
        val exp = id?.let { jwtClaims(it)["exp"]?.str()?.toLongOrNull() } ?: 0
        if (id != null && exp - 60 > nowMs() / 1000) return id
        val refresh = secret("refresh") ?: throw AuthException("Not logged in")
        val auth = cognito("REFRESH_TOKEN_AUTH", mapOf("REFRESH_TOKEN" to refresh))["AuthenticationResult"]
            ?.jsonObject ?: throw AuthException("Session expired")
        val newId = auth["IdToken"]!!.str()!!
        secrets.set("id", newId)
        newId
    }

    private suspend fun cognito(flow: String, params: Map<String, String>): JsonObject {
        val body = buildJsonObject {
            put("AuthFlow", flow)
            put("ClientId", CLIENT_ID)
            put("AuthParameters", JsonObject(params.mapValues { JsonPrimitive(it.value) }))
        }.toString()
        guard.take()
        val (code, text) = call(http.post(COGNITO) {
            header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            contentType(ContentType.parse("application/x-amz-json-1.1"))
            setBody(body)
        })
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

    private suspend fun call(r: HttpResponse): Pair<Int, String> = r.status.value to r.bodyAsText()

    /** Runs a GraphQL operation and returns its `data` object. */
    suspend fun gql(endpoint: String, query: String, variables: Map<String, Any?> = emptyMap()): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", JsonObject(variables.mapValues { it.value.toJson() }))
        }.toString()
        val token = idToken()
        guard.take()
        val (code, text) = call(http.post(endpoint) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        })
        if (code == 401) throw AuthException("Session expired")
        if (code !in 200..299) throw ApiException("HTTP $code: ${text.take(200)}")
        val obj = json.parseToJsonElement(text).jsonObject
        val errors = obj["errors"] as? JsonArray
        val data = obj["data"] as? JsonObject
        if ((data == null || data.values.all { it is JsonNull }) && !errors.isNullOrEmpty()) {
            throw ApiException(errors.joinToString { it.jsonObject["message"]?.str().orEmpty() })
        }
        return data ?: JsonObject(emptyMap())
    }

    suspend fun user(): JsonObject {
        val token = idToken()
        guard.take()
        val (code, text) = call(http.get("$V2_ENDPOINT/users/$userId") {
            header("Authorization", "Bearer $token")
            header("x-api-key", V2_KEY)
        })
        if (code !in 200..299) throw ApiException("HTTP $code")
        return json.parseToJsonElement(text).jsonObject["user"]?.jsonObject ?: JsonObject(emptyMap())
    }

    suspend fun bytes(url: String): ByteArray {
        guard.take()
        val r = http.get(url)
        if (r.status.value !in 200..299) throw ApiException("HTTP ${r.status.value}")
        return r.readRawBytes()
    }

    /**
     * Live robot updates (GitHub #4): Whisker's realtime GraphQL subscription, the one Home Assistant's
     * Litter-Robot integration uses (pylitterbot). One connection for all the account's robots; the server only
     * sends when a robot's state changes, plus a keep-alive. [onRobots] gets each update's robots. Returns when the
     * connection ends or goes quiet for 3 minutes; the caller decides when to reconnect (never right away).
     */
    suspend fun liveRobots(onAlive: () -> Unit = {}, onRobots: suspend (List<JsonObject>) -> Unit) {
        val uid = userId ?: throw AuthException("Not logged in")
        val auth = "Bearer ${idToken()}"
        fun b64(text: String) = Base64.Default.encode(text.encodeToByteArray()).encodeURLParameter()
        val header = b64(buildJsonObject { put("Authorization", auth); put("host", "lr4.iothings.site") }.toString())
        guard.take()
        http.webSocket("$realtimeEndpoint?header=$header&payload=${b64("{}")}",
            request = { header("Sec-WebSocket-Protocol", "graphql-ws") }) {
            val query = "subscription GetLR4ByUser(\$userId: String!) { litterRobot4StateSubscriptionByUser(userId: \$userId) { robots $LR4_MODEL } }"
            send(Frame.Text(buildJsonObject {
                put("id", "meowstache-live"); put("type", "start")
                put("payload", buildJsonObject {
                    put("data", buildJsonObject { put("query", query); put("variables", buildJsonObject { put("userId", uid) }) }.toString())
                    put("extensions", buildJsonObject { put("authorization", buildJsonObject { put("Authorization", auth); put("host", "lr4.iothings.site") }) })
                })
            }.toString()))
            while (true) {
                val frame = withTimeoutOrNull(180_000) { incoming.receive() } ?: return@webSocket // quiet: reconnect later
                onAlive() // keep-alives too: the connection is healthy
                val msg = (frame as? Frame.Text)?.let { json.parseToJsonElement(it.readText()).jsonObject } ?: continue
                when (msg["type"]?.str()) {
                    "data" -> msg["payload"]?.jsonObject?.get("data")?.jsonObject?.get("litterRobot4StateSubscriptionByUser")
                        ?.jsonObject?.get("robots")?.let { r -> onRobots((r as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) }
                    "error", "connection_error" -> throw ApiException("Live updates: ${msg["payload"]}")
                    "complete" -> return@webSocket
                }
            }
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
    json.parseToJsonElement(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString())
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
