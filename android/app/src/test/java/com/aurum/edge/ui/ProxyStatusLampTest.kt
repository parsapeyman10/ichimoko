package com.aurum.edge.ui

import com.aurum.edge.data.AppNetwork
import com.aurum.edge.data.EmbeddedProxy
import org.junit.Assert.*
import org.junit.Test

class ProxyStatusLampTest {
    @Test fun `only ready active Tor circuit is green not a preference or stale ONLINE label`() {
        EmbeddedProxy.Status.entries.forEach { status ->
            assertFalse(proxyLampConnected(status, AppNetwork.Route(false)))
            assertFalse(proxyLampConnected(status, AppNetwork.Route(true,0)))
            assertEquals(status == EmbeddedProxy.Status.ONLINE, proxyLampConnected(status, AppNetwork.Route(true,19050)))
        }
    }
}
