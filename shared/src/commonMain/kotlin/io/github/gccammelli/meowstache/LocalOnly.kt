package io.github.gccammelli.meowstache

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin

/**
 * Plain http:// and ws:// are allowed only to addresses on the home network: anywhere else the Home Assistant token
 * would cross the internet unencrypted. The apps allow cleartext traffic because a Home Assistant on the LAN usually
 * has no certificate; this is what keeps that to the LAN.
 */
fun isLocalHost(host: String): Boolean {
    val h = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
    // Only names that can't be registered publicly: mDNS (.local) and the home-network zone. Others (a bare name, .lan,
    // a router's own domain) could be answered by any DNS server, so they need https or the IP address.
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".home.arpa")) return true
    if (':' in h) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
    val ip = h.split('.').map { it.toIntOrNull() ?: return false }.takeIf { it.size == 4 && it.all { n -> n in 0..255 } } ?: return false
    return ip[0] == 10 || ip[0] == 127 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] in 16..31 ||
        ip[0] == 169 && ip[1] == 254 || ip[0] == 100 && ip[1] in 64..127 // the last: carrier-grade NAT, e.g. Tailscale
}

/** Refuses requests from this client that would send data unencrypted beyond the home network. */
fun HttpClient.localOnlyCleartext(): HttpClient = apply {
    plugin(HttpSend).intercept { request ->
        val scheme = request.url.protocol.name.lowercase()
        if ((scheme == "http" || scheme == "ws") && !isLocalHost(request.url.host))
            throw ApiException("Use https:// for ${request.url.host}: plain http only works on your home network")
        execute(request)
    }
}
