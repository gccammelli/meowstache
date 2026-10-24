package io.github.gccammelli.meowstache

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class ApiTest {
    private class Settings : KeyValue {
        val values = mutableMapOf<String, Any>()
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getStringSet(key: String) = emptySet<String>()
        override fun putStringSet(key: String, value: Set<String>) { values[key] = value }
        override fun clear() = values.clear()
    }
    private class Secrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun clear() = values.clear()
    }
    private fun token(exp: Long = nowMs() / 1000 + 3600) =
        "header." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"mid":"owner","exp":$exp}""".encodeToByteArray()) + ".signature"

    @Test fun loginSavesTrimmedEmailAndTokensThenLogoutRemovesThem() = runTest {
        val settings = Settings(); val secrets = Secrets(); val id = token()
        HttpClient(MockEngine { request ->
            val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("USER_PASSWORD_AUTH", body.s("AuthFlow"))
            assertEquals("person@example.test", body.o("AuthParameters")?.s("USERNAME"))
            assertEquals(" password ", body.o("AuthParameters")?.s("PASSWORD"))
            assertTrue(request.headers["X-Amz-Target"]!!.endsWith("InitiateAuth"))
            respond("""{"AuthenticationResult":{"IdToken":"$id","RefreshToken":"refresh"}}""")
        }).use { http ->
            val api = Api(settings, secrets, http)
            assertFalse(api.isLoggedIn); assertEquals("", api.email)
            api.login(" person@example.test ", " password ")
            assertTrue(api.isLoggedIn); assertEquals("owner", api.userId)
            assertEquals("person@example.test", api.email)
            api.logout(); assertFalse(api.isLoggedIn); assertNull(api.userId)
            assertTrue(settings.values.isEmpty()); assertTrue(secrets.values.isEmpty())
        }
    }

    @Test fun loginFailuresGiveUsefulMessages() = runTest {
        for ((response, code, message) in listOf(
            Triple("""{"__type":"NotAuthorizedException"}""", 400, "Incorrect email or password"),
            Triple("""{"__type":"UserNotFoundException"}""", 400, "Incorrect email or password"),
            Triple("""{"message":"Try later"}""", 429, "Try later"),
            Triple("{}", 500, "Login failed (500)"),
            Triple("""{"ChallengeName":"SMS_MFA"}""", 200, "Unexpected login challenge: SMS_MFA"),
        )) HttpClient(MockEngine { respond(response, HttpStatusCode.fromValue(code)) }).use { http ->
            val secrets = Secrets()
            assertEquals(message, assertFailsWith<AuthException> { Api(Settings(), secrets, http).login("a", "b") }.message)
            assertTrue(secrets.values.isEmpty())
        }
    }

    @Test fun graphqlUsesAnUnexpiredTokenAndSerializesVariables() = runTest {
        val secrets = Secrets().apply { set("id", token()) }
        HttpClient(MockEngine { request ->
            assertEquals("Bearer ${secrets.get("id")}", request.headers["Authorization"])
            val vars = json.parseToJsonElement((request.body as TextContent).text).jsonObject.o("variables")!!
            assertEquals(JsonNull, vars["nil"]); assertEquals(5, vars.i("number"))
            assertTrue(vars.b("flag")); assertEquals("x", vars.s("text"))
            assertEquals(JsonPrimitive("kept"), vars["element"])
            respond("""{"data":{"ok":true},"errors":[{"message":"partial"}]}""")
        }).use { http ->
            val result = Api(Settings(), secrets, http).gql("https://example.test/graphql", "query", mapOf("nil" to null, "number" to 5, "flag" to true, "text" to "x", "element" to JsonPrimitive("kept")))
            assertTrue(result.b("ok"))
        }
    }

    @Test fun expiredTokenIsRefreshedBeforeTheGraphqlRequest() = runTest {
        val secrets = Secrets().apply { set("id", token(1)); set("refresh", "saved-refresh") }
        val fresh = token(); var calls = 0
        HttpClient(MockEngine { request ->
            calls++
            if (calls == 1) {
                val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("REFRESH_TOKEN_AUTH", body.s("AuthFlow"))
                assertEquals("saved-refresh", body.o("AuthParameters")?.s("REFRESH_TOKEN"))
                respond("""{"AuthenticationResult":{"IdToken":"$fresh"}}""")
            } else {
                assertEquals("Bearer $fresh", request.headers["Authorization"])
                respond("{}")
            }
        }).use { http ->
            assertTrue(Api(Settings(), secrets, http).gql("https://example.test/graphql", "query").isEmpty())
            assertEquals(fresh, secrets.get("id")); assertEquals(2, calls)
        }
    }

    @Test fun missingOrExpiredSessionsAreExplicit() = runTest {
        HttpClient(MockEngine { respond("{}") }).use { http ->
            val secrets = Secrets()
            val api = Api(Settings(), secrets, http)
            assertEquals("Not logged in", assertFailsWith<AuthException> { api.gql("https://example.test", "query") }.message)
            assertFailsWith<AuthException> { api.liveRobots {} }
            secrets.set("refresh", "old")
            assertEquals("Session expired", assertFailsWith<AuthException> { api.gql("https://example.test", "query") }.message)
        }
    }

    @Test fun graphqlAndImageErrorsAreNotAcceptedAsData() = runTest {
        for ((response, code) in listOf("{}" to 401, "upstream" to 503, """{"errors":[{"message":"one"},{"message":"two"}]}""" to 200)) {
            HttpClient(MockEngine { respond(response, HttpStatusCode.fromValue(code)) }).use { http ->
                val api = Api(Settings(), Secrets().apply { set("id", token()) }, http)
                if (code == 401) assertFailsWith<AuthException> { api.gql("https://example.test", "query") }
                else assertFailsWith<ApiException> { api.gql("https://example.test", "query") }
                if (code == 503) {
                    assertFailsWith<ApiException> { api.bytes("https://example.test/image") }
                    assertFailsWith<ApiException> { api.user() }
                }
            }
        }
    }

    @Test fun userAndBytesResponsesAreRead() = runTest {
        HttpClient(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/users/")) {
                assertTrue(request.headers["x-api-key"].orEmpty().isNotEmpty())
                respond("""{"user":{"firstName":"Mochi"}}""")
            } else respond("image")
        }).use { http ->
            val api = Api(Settings(), Secrets().apply { set("id", token()) }, http)
            assertEquals("Mochi", api.user().s("firstName"))
            assertContentEquals("image".encodeToByteArray(), api.bytes("https://example.test/image"))
        }
    }

    @Test fun malformedSavedClaimsAreRefreshedBeforeAnyAuthenticatedRequest() = runTest {
        fun claims(text: String)="header."+Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray())+".signature"
        for(saved in listOf("not-a-jwt","header.@@.signature",claims("{}"),claims("[]"),claims("""{"mid":null,"exp":"invalid"}"""))) {
            val secrets=Secrets().apply { set("id",saved); set("refresh","saved-refresh") }
            val fresh=token(); var calls=0
            HttpClient(MockEngine { request ->
                calls++
                if(request.headers["X-Amz-Target"]!=null) respond("""{"AuthenticationResult":{"IdToken":"$fresh"}}""") else {
                    assertEquals("Bearer $fresh",request.headers["Authorization"])
                    respond("""{"data":{"ok":true}}""")
                }
            }).use { http ->
                val api=Api(Settings(),secrets,http)
                assertNull(api.userId)
                assertTrue(api.gql("https://example.test/graphql","query").b("ok"))
                assertEquals("owner",api.userId); assertEquals(2,calls)
            }
        }
    }

    @Test fun missingChallengeProfileAndErrorDetailsNeverBecomeSuccessfulLoginData() = runTest {
        val secrets=Secrets().apply { set("id",token()) }
        HttpClient(MockEngine { respond("{}") }).use { http ->
            val api=Api(Settings(),secrets,http)
            assertTrue(api.user().isEmpty())
            assertFailsWith<AuthException> { api.login("person@example.test","password") }
            assertNull(secrets.get("refresh"))
        }
        for(body in listOf("""{"errors":[]}""","""{"data":{},"errors":[]}""")) {
            HttpClient(MockEngine { respond(body) }).use { http ->
                assertTrue(Api(Settings(),secrets,http).gql("https://example.test/graphql","query").isEmpty())
            }
        }
        HttpClient(MockEngine { respond("""{"errors":[{}]}""") }).use { http ->
            assertFailsWith<ApiException> { Api(Settings(),secrets,http).gql("https://example.test/graphql","query") }
        }
    }
}
