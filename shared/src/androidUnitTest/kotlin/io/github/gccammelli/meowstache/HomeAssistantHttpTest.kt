package io.github.gccammelli.meowstache

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HomeAssistantHttpTest {
    @Test fun requestsAuthenticateAndPreserveBodies() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Triple<String, String?, String>>()
        server.createContext("/") { exchange ->
            requests += Triple(exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readBytes().decodeToString())
            val body = if (exchange.requestURI.path == "/image") byteArrayOf(0, -1, 42) else "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val backend = HaHttp("  127.0.0.1:${server.address.port}/  ", "test-token")
            assertEquals("http://127.0.0.1:${server.address.port}", backend.base)
            assertEquals("{\"ok\":true}", backend.get("/api/config"))
            assertEquals("{\"ok\":true}", backend.post("/api/services/test", "{\"value\":7}"))
            assertEquals(listOf("GET", "POST"), requests.map { it.first })
            assertTrue(requests.all { it.second == "Bearer test-token" })
            assertEquals("{\"value\":7}", requests[1].third)
        } finally { server.stop(0) }
    }

    @Test fun authorizationErrorsAndServerErrorsAreExplicit() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "server failure".toByteArray()
            exchange.sendResponseHeaders(exchange.requestURI.path.removePrefix("/").toInt(), body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val backend = HaHttp("http://127.0.0.1:${server.address.port}", "bad-token")
            for (code in listOf(401, 403)) {
                assertFailsWith<AuthException> { backend.get("/$code") }
            }
            val error = assertFailsWith<ApiException> { backend.get("/503") }
            assertTrue(error.message.orEmpty().contains("503"))
            assertTrue(error.message.orEmpty().contains("server failure"))
            assertFailsWith<ApiException> { backend.post("/500", "{}") }
        } finally { server.stop(0) }
    }
}

