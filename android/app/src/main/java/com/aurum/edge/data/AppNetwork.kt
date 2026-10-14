package com.aurum.edge.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** One routing policy for REST, downloads and WebSockets. No remote proxy list, credentials,
 * telemetry or trust-all TLS. SOCKS destinations are resolved by Tor, not the local resolver.
 * Enabling Tor is fail-closed: unavailable Tor never silently falls back to a direct route.
 */
internal class ProxyNotReady : IOException("پروکسی هنوز آماده نیست؛ مسیر مستقیم استفاده نمی‌شود")

object AppNetwork {
    data class Route(val enabled: Boolean = false, val socksPort: Int = 0, val revision: Long = 0) {
        val ready: Boolean get() = !enabled || socksPort in 1..65535
        fun proxy(): Proxy = if (!enabled) Proxy.NO_PROXY else
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort.takeIf { it in 1..65535 } ?: 1))
    }

    /** Installed by the main-thread WebView adapter; invoked before changing routes. */
    internal var beforeRouteChange: () -> Unit = {}
    private val lock = Any()
    private val mutableRoute = MutableStateFlow(Route())
    val route = mutableRoute.asStateFlow()
    private val dispatcher = Dispatcher()
    private val pool = ConnectionPool()
    private val calls = Collections.newSetFromMap(WeakHashMap<Call, Boolean>())
    private val sockets = Collections.newSetFromMap(WeakHashMap<Socket, Boolean>())

    fun configure(enabled: Boolean, socksPort: Int = 0) = synchronized(lock) {
        val port = if (enabled) socksPort.takeIf { it in 1..65535 } ?: 0 else 0
        val before = mutableRoute.value
        if (before.enabled == enabled && before.socksPort == port) return@synchronized
        beforeRouteChange()
        mutableRoute.value = Route(enabled, port, before.revision + 1)
        // Close upgraded WebSockets too: they are no longer in the idle HTTP pool.
        dispatcher.cancelAll()
        calls.forEach { it.cancel() }
        calls.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool.evictAll()
    }

    internal val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(route.value.proxy())
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(pool)
        .proxySelector(selector)
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .eventListenerFactory {
            object : EventListener() {
                private val revision = route.value.revision
                override fun connectionAcquired(call: Call, connection: Connection) {
                    synchronized(lock) {
                        if (revision != route.value.revision) {
                            call.cancel()
                            runCatching { connection.socket().close() }
                        } else sockets.add(connection.socket())
                    }
                }
            }
        }
        .addInterceptor { chain ->
            // OkHttp disables EventListener for upgraded WebSockets. Keep weak references
            // to their underlying calls too, until the WebSocket releases them.
            val before = synchronized(lock) {
                calls.add(chain.call())
                route.value
            }
            if (!before.ready) throw ProxyNotReady()
            val response = chain.proceed(chain.request())
            if (before.revision != route.value.revision) {
                response.close()
                throw IOException("مسیر اتصال تغییر کرد؛ دریافت دوباره لازم است")
            }
            response
        }
}
