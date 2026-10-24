package io.github.gccammelli.meowstache

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A local server speaking the actual subscription wire protocol; no cloud credentials or network. */
@RunWith(RobolectricTestRunner::class)
class LiveApiTest {
    private class Peer(private val messages: List<String>,private val binary: Boolean=false,private val connections: Int=1) : AutoCloseable {
        private val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val endpoint="ws://localhost:${server.localPort}/graphql/realtime"
        var request: JsonObject?=null
        var accepted=0
            private set
        var handshake=""
        private var failure: Throwable?=null
        private val worker=thread(isDaemon=true) {
            try {
                repeat(connections) { server.accept().use { socket ->
                    accepted++
                    socket.soTimeout=5000
                    val input=socket.getInputStream(); val output=socket.getOutputStream()
                    val headers=StringBuilder()
                    while(!headers.endsWith("\r\n\r\n")) {
                        val next=input.read(); check(next>=0); headers.append(next.toChar())
                    }
                    handshake=headers.toString()
                    val key=handshake.lines().first { it.startsWith("Sec-WebSocket-Key:",true) }.substringAfter(':').trim()
                    val accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
                        "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: graphql-ws\r\n\r\n").toByteArray()); output.flush()
                    check(input.read() and 15==1)
                    val flags=input.read(); var size=flags and 127
                    if(size==126) size=(input.read() shl 8)+input.read()
                    val mask=if(flags and 128!=0) input.readNBytes(4) else byteArrayOf(0,0,0,0)
                    val bytes=input.readNBytes(size)
                    bytes.indices.forEach { i -> bytes[i]=(bytes[i].toInt() xor mask[i%4].toInt()).toByte() }
                    request=json.parseToJsonElement(bytes.decodeToString()).jsonObject
                    fun frame(opcode: Int,text: String) {
                        val payload=text.toByteArray(); output.write(opcode)
                        if(payload.size<126) output.write(payload.size) else { output.write(126); output.write(payload.size shr 8); output.write(payload.size and 255) }
                        output.write(payload); output.flush()
                    }
                    if(binary) frame(0x82,"binary")
                    messages.forEach { frame(0x81,it) }
                    output.write(byteArrayOf(0x88.toByte(),0)); output.flush()
                } }
            } catch(e: Throwable) { failure=e }
        }
        override fun close() {
            server.close(); worker.join(6000)
            check(!worker.isAlive) { "Subscription fixture did not finish" }
            failure?.let { throw AssertionError("Subscription fixture failed",it) }
        }
    }
    private fun api(http: HttpClient,endpoint: String): Api {
        val id="header."+Base64.getUrlEncoder().withoutPadding().encodeToString("""{"mid":"test-owner","exp":4102444800}""".toByteArray())+".signature"
        val values=object : KeyValue {
            override fun getString(key: String): String?=null
            override fun putString(key: String,value: String?)=Unit
            override fun getBoolean(key: String,default: Boolean)=default
            override fun putBoolean(key: String,value: Boolean)=Unit
            override fun getInt(key: String,default: Int)=default
            override fun putInt(key: String,value: Int)=Unit
            override fun getStringSet(key: String)=emptySet<String>()
            override fun putStringSet(key: String,value: Set<String>)=Unit
            override fun clear()=Unit
        }
        val secrets=object : SecretStore {
            override fun get(key: String)=if(key=="id") id else null
            override fun set(key: String,value: String?)=Unit
            override fun clear()=Unit
        }
        return Api(values,secrets,http,endpoint)
    }

    @Test fun subscriptionAuthenticatesAndSeparatesKeepAlivesFromRobotUpdates() = runTest {
        val updates=mutableListOf<List<JsonObject>>(); var alive=0
        Peer(listOf("""{"type":"ka"}""","""{"type":"data","payload":{}}""",
            """{"type":"data"}""","""{"type":"data","payload":{"data":{}}}""",
            """{"type":"data","payload":{"data":{"litterRobot4StateSubscriptionByUser":{}}}}""",
            """{"type":"data","payload":{"data":{"litterRobot4StateSubscriptionByUser":{"robots":[null,42,{"serial":"robot","isOnline":true}]}}}}""",
            """{"type":"data","payload":{"data":{"litterRobot4StateSubscriptionByUser":{"robots":{}}}}}""",
            """{"type":"complete"}"""),binary=true).use { peer ->
            HttpClient { install(WebSockets) }.use { http ->
                withContext(Dispatchers.IO) { withTimeout(5000) { api(http,peer.endpoint).liveRobots({ alive++ }) { updates+=it } } }
            }
            assertEquals(9,alive); assertEquals(2,updates.size)
            assertEquals("robot",updates.first().single().s("serial")); assertTrue(updates.last().isEmpty())
            assertTrue(peer.handshake.contains("Sec-WebSocket-Protocol: graphql-ws",true))
            assertEquals("start",peer.request!!.s("type"))
            val payload=peer.request!!.o("payload")!!
            assertTrue(payload.s("data")!!.contains("test-owner"))
            assertTrue(payload.o("extensions")!!.o("authorization")!!.s("Authorization")!!.startsWith("Bearer header."))
        }
    }

    @Test fun subscriptionErrorsAreExplicitForBothServerErrorTypes() = runTest {
        for(type in listOf("error","connection_error")) {
            Peer(listOf("""{"type":"$type","payload":"denied"}""")).use { peer ->
                HttpClient { install(WebSockets) }.use { http ->
                    val error=assertFailsWith<ApiException> { withContext(Dispatchers.IO) { withTimeout(5000) { api(http,peer.endpoint).liveRobots {} } } }
                    assertTrue(error.message!!.contains("denied"))
                }
            }
        }
    }

    @Test fun liveLoopPropagatesCancellationAfterAMeaningfulRobotChange() = runTest {
        fun update(status: String)="""{"type":"data","payload":{"data":{"litterRobot4StateSubscriptionByUser":{"robots":[{"serial":"robot","robotStatus":"$status"}]}}}}"""
        Peer(listOf(update("ready"),"""{"type":"ka"}""",update("cycling"))).use { peer ->
            HttpClient { install(WebSockets) }.use { http ->
                var changes=0; var alive=0
                val canceled=assertFailsWith<CancellationException> { withContext(Dispatchers.IO) { withTimeout(5000) {
                    liveLoop(api(http,peer.endpoint),{ alive++ },{ changes++; throw CancellationException("Stopped watching") })
                } } }
                assertEquals("Stopped watching",canceled.message)
                assertEquals(1,changes); assertEquals(3,alive)
            }
        }
    }

    @Test fun liveLoopReconnectsAfterAServerErrorAndStillAllowsCancellation() = runTest {
        Peer(listOf("""{"type":"ka"}""","""{"type":"error","payload":"offline"}"""),connections=2).use { peer ->
            HttpClient { install(WebSockets) }.use { http ->
                var alive=0
                val canceled=assertFailsWith<CancellationException> { withContext(Dispatchers.IO) { withTimeout(5000) {
                    liveLoop(api(http,peer.endpoint),{ if(++alive==3) throw CancellationException("Stopped after reconnect") },
                        { fail("Keep-alives and errors are not robot changes") },Backoff(minMs=1,maxMs=2))
                } } }
                assertEquals("Stopped after reconnect",canceled.message)
                assertEquals(3,alive); assertEquals(2,peer.accepted)
            }
        }
    }
}
