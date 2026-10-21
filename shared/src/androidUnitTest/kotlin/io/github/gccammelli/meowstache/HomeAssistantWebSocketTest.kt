package io.github.gccammelli.meowstache

import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.concurrent.thread
import kotlin.test.*

/** A loopback-only WebSocket peer: exercises the real Ktor client without a Home Assistant. */
private class HaSocket(private val authenticate: Boolean = true, private val reject: Boolean = false) : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val url = "http://localhost:${listener.localPort}"
    val received = mutableListOf<JsonObject>()
    private var failure: Throwable? = null
    private val worker = thread(isDaemon = true) {
        try {
            listener.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    check(byte >= 0)
                    headers.append(byte.toChar())
                }
                val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                output.flush()
                fun send(text: String) {
                    val bytes = text.toByteArray()
                    output.write(0x81)
                    if (bytes.size < 126) output.write(bytes.size) else { output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255) }
                    output.write(bytes); output.flush()
                }
                fun next(): JsonObject {
                    check(input.read() and 15 == 1)
                    val flags = input.read()
                    var size = flags and 127
                    if (size == 126) size = (input.read() shl 8) + input.read()
                    val mask = if (flags and 128 != 0) input.readNBytes(4) else byteArrayOf(0, 0, 0, 0)
                    val bytes = input.readNBytes(size)
                    bytes.indices.forEach { i -> bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte() }
                    return json.parseToJsonElement(bytes.decodeToString()).jsonObject.also { received += it }
                }
                send("{\"type\":\"auth_required\"}")
                next()
                send(if (authenticate) "{\"type\":\"auth_ok\"}" else "{\"type\":\"auth_invalid\"}")
                if (authenticate) {
                    repeat(if (reject) 1 else 2) {
                        val c = next()
                        val id = c.getValue("id")
                        if (reject) send("{\"id\":$id,\"success\":false,\"error\":{\"message\":\"operation denied\"}}")
                        else {
                            send("{\"id\":999,\"success\":true,\"result\":\"unrelated\"}")
                            send("{\"id\":$id,\"success\":true,\"result\":{\"command\":${c.getValue("type")}}}")
                        }
                    }
                }
                output.write(byteArrayOf(0x88.toByte(), 0)); output.flush()
            }
        } catch (e: Throwable) { failure = e }
    }
    override fun close() {
        listener.close(); worker.join(6000)
        check(!worker.isAlive) { "WebSocket fixture failed to finish" }
        failure?.let { throw AssertionError("WebSocket fixture failed", it) }
    }
}

class HomeAssistantWebSocketTest {
    @Test fun authenticatesAndMatchesEachReplyToItsCommandId() = runTest {
        HaSocket().use { server ->
            val backend = HaHttp(server.url, "test-token")
            val result = backend.ws(listOf("config/device_registry/list", "config/entity_registry/list").map { buildJsonObject { put("type", it) } })
            assertEquals(listOf("config/device_registry/list", "config/entity_registry/list"), result.map { it!!.jsonObject.s("command") })
            assertEquals("test-token", server.received.first().s("access_token"))
            assertEquals(listOf("1", "2"), server.received.drop(1).map { it.s("id") })
        }
    }
    @Test fun invalidTokenFailsBeforeAnyRegistryCommand() = runTest {
        HaSocket(authenticate = false).use { server ->
            assertFailsWith<AuthException> { HaHttp(server.url, "bad-token").ws(listOf(buildJsonObject { put("type", "config/device_registry/list") })) }
            assertEquals(1, server.received.size)
        }
    }
    @Test fun refusedCommandReportsTheHomeAssistantError() = runTest {
        HaSocket(reject = true).use { server ->
            val error = assertFailsWith<ApiException> { HaHttp(server.url, "token").ws(listOf(buildJsonObject { put("type", "config/device_registry/list") })) }
            assertEquals("operation denied", error.message)
        }
    }
}

