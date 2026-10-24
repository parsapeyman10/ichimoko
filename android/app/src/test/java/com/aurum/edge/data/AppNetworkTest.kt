package com.aurum.edge.data

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.ProxySelector
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppNetworkTest {
    @Before fun reset() { AppNetwork.observeNetwork("direct", true, force = true) }
    @After fun cleanup() { AppNetwork.observeNetwork("direct", true, force = true) }

    @Test fun `system proxy selector is honored without a private socks listener`() {
        val previous = ProxySelector.getDefault()
        val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("192.0.2.1", 8080))
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI) = listOf(systemProxy)
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            assertEquals(listOf(systemProxy), AppNetwork.selector.select(URI("https://example.org")))
            ProxySelector.setDefault(null)
            assertEquals(listOf(Proxy.NO_PROXY), AppNetwork.selector.select(URI("https://example.org")))
        } finally { ProxySelector.setDefault(previous) }
    }

    @Test fun `network identity change invalidates caches but repeated capability events do not`() {
        AppNetwork.observeNetwork("wifi", true)
        val first = AppNetwork.route.value.revision
        AppNetwork.observeNetwork("wifi", true)
        assertEquals(first, AppNetwork.route.value.revision)
        AppNetwork.observeNetwork("vpn", true)
        assertTrue(AppNetwork.route.value.ready)
        assertTrue(AppNetwork.route.value.revision > first)
        AppNetwork.observeNetwork(null, false)
        val error = runCatching {
            AppNetwork.builder().build().newCall(Request.Builder().url("https://example.org").build()).execute()
        }.exceptionOrNull()
        assertTrue(error is NetworkUnavailable)
    }

    @Test fun `previously built clients recover when the system network returns`() {
        MockWebServer().use { server ->
            server.start()
            val client = AppNetwork.builder().build()
            server.enqueue(MockResponse().setBody("public price"))
            val request = Request.Builder().url(server.url("/price")).build()
            client.newCall(request).execute().use { assertEquals("public price", it.body!!.string()) }
            AppNetwork.observeNetwork(null, false)
            assertTrue(runCatching { client.newCall(request).execute() }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            AppNetwork.observeNetwork("direct", true, force = true)
            server.enqueue(MockResponse().setBody("fresh price"))
            client.newCall(request).execute().use { assertEquals("fresh price", it.body!!.string()) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `switching routes also kills an already upgraded websocket`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            server.start()
            val opened = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val socket = AppNetwork.builder().build().newWebSocket(
                Request.Builder().url(server.url("/stream")).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected.countDown() }
                })
            try {
                assertTrue(opened.await(5, TimeUnit.SECONDS))
                AppNetwork.observeNetwork(null, false)
                assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            } finally { socket.cancel() }
        }
    }
}
