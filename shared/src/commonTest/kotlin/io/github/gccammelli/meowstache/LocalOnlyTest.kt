package io.github.gccammelli.meowstache

import io.ktor.client.request.get
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalOnlyTest {
    @Test fun homeNetworkAddressesAreLocal() {
        for (h in listOf("10.20.30.40", "10.20.0.5", "172.20.1.1", "127.0.0.1", "100.101.102.103", "homeassistant.local",
                "ha.home.arpa", "localhost", "[fe80::1]", "fd12::3")) assertTrue(isLocalHost(h), h)
    }

    @Test fun internetAddressesAreNot() {
        for (h in listOf("8.8.8.8", "172.32.0.1", "example.duckdns.org", "ha.example.com", "2001:db8::1", "10.20.30",
                "999.1.1.1", "local.example.com", "homeassistant", "nas.lan", "ha.internal")) assertFalse(isLocalHost(h), h)
    }
    @Test fun privateAddressBoundariesDoNotAuthorizeNeighboringPublicRanges() {
        val cases=listOf(
            listOf(192,167,1,1) to false,listOf(192,168,0,1) to true,listOf(192,169,1,1) to false,
            listOf(172,15,255,255) to false,listOf(172,16,0,1) to true,listOf(172,31,255,255) to true,listOf(172,32,0,1) to false,
            listOf(169,253,1,1) to false,listOf(169,254,1,1) to true,listOf(169,255,1,1) to false,
            listOf(100,63,255,255) to false,listOf(100,64,0,1) to true,listOf(100,127,255,255) to true,listOf(100,128,0,1) to false
        )
        for((parts,local) in cases) {
            val address=parts.joinToString(".")
            kotlin.test.assertEquals(local,isLocalHost(address),address)
        }
        for(host in listOf("::1","fc00::1","[FD00::1]","LOCALHOST.","HA.HOME.ARPA.")) assertTrue(isLocalHost(host),host)
        for(host in listOf("1.2.3.4.5","1.2.3.-1","1.2.3.256","0:0:0:0:0:0:0:2",".local.example","home.arpa.example")) assertFalse(isLocalHost(host),host)
    }

    @Test fun cleartextRequestsAndRedirectsCannotReachPublicHosts() = kotlinx.coroutines.test.runTest {
        val hosts=mutableListOf<String>()
        io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            hosts+=request.url.host
            if(request.url.encodedPath=="/redirect") respond("",io.ktor.http.HttpStatusCode.Found,
                io.ktor.http.headersOf("Location","http://public.example/")) else respond("ok")
        }).localOnlyCleartext().use { http ->
            for(url in listOf("http://public.example/","ws://public.example/"))
                assertTrue(kotlin.test.assertFailsWith<ApiException> { http.get(url) }.message!!.startsWith("Use https://"))
            assertTrue(hosts.isEmpty())
            http.get("https://public.example/"); http.get("http://localhost/")
            kotlin.test.assertEquals(listOf("public.example","localhost"),hosts)
            kotlin.test.assertFailsWith<ApiException> { http.get("http://localhost/redirect") }
            kotlin.test.assertEquals(listOf("public.example","localhost","localhost"),hosts)
        }
    }

}
