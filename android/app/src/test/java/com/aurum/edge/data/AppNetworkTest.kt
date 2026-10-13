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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppNetworkTest {
    @Before fun reset() { AppNetwork.configure(false) }
    @After fun cleanup() { AppNetwork.configure(false) }

    @Test fun `proxy startup and failure never offer a direct fallback`() {
        AppNetwork.configure(true)
        assertFalse(AppNetwork.route.value.ready)
        val routes = AppNetwork.selector.select(URI("https://example.org"))
        assertEquals(1, routes.size)
        assertEquals(Proxy.Type.SOCKS, routes.single().type())
        assertEquals(1, (routes.single().address() as InetSocketAddress).port)
        AppNetwork.selector.connectFailed(URI("https://example.org"), routes.single().address(), IOException())
        assertEquals(routes, AppNetwork.selector.select(URI("https://example.org")))
        val error = runCatching {
            AppNetwork.builder().build().newCall(Request.Builder().url("https://example.org").build()).execute()
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("پروکسی"))
    }

    @Test fun `a ready route uses loopback socks and off explicitly restores direct mode`() {
        AppNetwork.configure(true, 19050)
        assertTrue(AppNetwork.route.value.ready)
        val revision = AppNetwork.route.value.revision
        val proxy = AppNetwork.selector.select(URI("wss://example.org/stream")).single()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.address.hostAddress)
        assertEquals(19050, address.port)
        AppNetwork.configure(true, 19050)
        assertEquals(revision, AppNetwork.route.value.revision)
        AppNetwork.configure(false)
        assertEquals(listOf(Proxy.NO_PROXY), AppNetwork.selector.select(URI("https://example.org")))
        assertTrue(AppNetwork.route.value.revision > revision)
    }

    @Test fun `all previously built clients honor a later toggle`() {
        MockWebServer().use { server ->
            server.start()
            val client = AppNetwork.builder().build()
            server.enqueue(MockResponse().setBody("public price"))
            val request = Request.Builder().url(server.url("/price")).build()
            client.newCall(request).execute().use { assertEquals("public price", it.body!!.string()) }
            AppNetwork.configure(true)
            assertTrue(runCatching { client.newCall(request).execute() }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            AppNetwork.configure(false)
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
                AppNetwork.configure(true)
                assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            } finally { socket.cancel() }
        }
    }
}
