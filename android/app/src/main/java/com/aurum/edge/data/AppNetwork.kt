package com.aurum.edge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** Normal Android networking for REST, downloads and sockets, including an external VPN
 * when Android routes this app through it. No embedded proxy, custom DNS or TLS bypass. */
internal class NetworkUnavailable : IOException("شبکهٔ گوشی آماده نیست")

object AppNetwork {
    /** Ready means Android advertises a default Internet network, not API permission. */
    data class Route(val ready: Boolean = true, val revision: Long = 0)

    private var observer: SystemNetworkObserver? = null
    private var networkKey: Any? = null
    @Synchronized fun start(context: Context) {
        if (observer == null) observer = SystemNetworkObserver(context).also { it.start() }
    }

    fun refreshSystemNetwork(force: Boolean = false) { observer?.refresh(force) }

    private val lock = Any()
    private val mutableRoute = MutableStateFlow(Route())
    val route = mutableRoute.asStateFlow()
    private val dispatcher = Dispatcher()
    private val pool = ConnectionPool()
    private val calls = Collections.newSetFromMap(WeakHashMap<Call, Boolean>())
    private val sockets = Collections.newSetFromMap(WeakHashMap<Socket, Boolean>())

    /** A network/VPN transition invalidates same-route histories and existing sockets. */
    internal fun observeNetwork(key: Any?, ready: Boolean, force: Boolean = false) = synchronized(lock) {
        val before = mutableRoute.value
        if (!force && key == networkKey && before.ready == ready) return@synchronized
        networkKey = key
        mutableRoute.value = Route(ready, before.revision + 1)
        // Close upgraded WebSockets too: they are no longer in the idle HTTP pool.
        dispatcher.cancelAll()
        calls.forEach { it.cancel() }
        calls.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool.evictAll()
    }

    internal val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> =
            ProxySelector.getDefault()?.takeUnless { it === this }?.select(uri) ?: listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
            ProxySelector.getDefault()?.takeUnless { it === this }?.connectFailed(uri, sa, ioe)
        }
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
            if (!before.ready) throw NetworkUnavailable()
            val response = chain.proceed(chain.request())
            if (before.revision != route.value.revision) {
                response.close()
                throw IOException("مسیر اتصال تغییر کرد؛ دریافت دوباره لازم است")
            }
            response
        }
}
