package io.github.gccammelli.meowstache

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
}
