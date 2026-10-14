package com.aurum.edge.data

import org.junit.Assert.*
import org.junit.Test

class SnowflakeConfigTest {
    private val bridge = "snowflake 192.0.2.3:80 fingerprint=public url=https://broker.example/ fronts=front.example ice=stun:stun.example:3478"
    @Test fun `client bridge uses loopback and alternates rendezvous without exposing a relay`() {
        val normal = SnowflakeConfig.torrc(19050, listOf(bridge), false)
        assertTrue(normal.contains("UseBridges 1\n"))
        assertTrue(normal.contains("ClientTransportPlugin snowflake socks5 127.0.0.1:19050"))
        assertTrue(normal.contains("ClientOnly 1"))
        assertFalse(normal.contains("0.0.0.0"))
        assertTrue(normal.contains("Bridge $bridge"))
        val amp = SnowflakeConfig.torrc(19050, listOf(bridge), true)
        assertTrue(amp.contains("url=https://snowflake-broker.torproject.net/ fronts=www.google.com"))
        assertFalse(amp.contains("broker.example"))
    }
    @Test fun `reject invalid listener and configuration injection`() {
        assertThrows(IllegalArgumentException::class.java) { SnowflakeConfig.torrc(0, listOf(bridge), false) }
        assertThrows(IllegalArgumentException::class.java) { SnowflakeConfig.torrc(1234, listOf("$bridge\nORPort 80"), false) }
    }
    @Test fun `bootstrap diagnostics expose only numeric progress never raw Tor text`() {
        assertEquals(85, SnowflakeConfig.bootstrapPercent("NOTICE BOOTSTRAP PROGRESS=85 TAG=loading SUMMARY=private"))
        assertNull(SnowflakeConfig.bootstrapPercent("PROGRESS=999 SUMMARY=private"))
        assertNull(SnowflakeConfig.bootstrapPercent(null))
    }
}
