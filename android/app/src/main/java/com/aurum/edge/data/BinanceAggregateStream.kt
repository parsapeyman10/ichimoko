package com.aurum.edge.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** Raw Binance Spot market-only aggTrade stream: one active subscription per open position.
 * Callback only queues the original publication; journal IO runs on the consumer coroutine.
 * OkHttp responds to WebSocket control pings and closes on failed connection.
 */
internal class BinanceAggregateStream(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS).build(),
) {
    sealed interface Event {
        data object Connected : Event
        data object Disconnected : Event
        data class Trade(val trade: BinanceSpotClient.Aggregate) : Event
        data object Invalid : Event
    }

    fun open(symbol: String, emit: (Event) -> Unit): Closeable {
        val ticker = BinanceSpotClient.ticker(symbol)
        val request = Request.Builder()
            .url("wss://data-stream.binance.vision:443/ws/${ticker.lowercase()}@aggTrade").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { emit(Event.Connected) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 8192) { emit(Event.Invalid); return }
                try { emit(Event.Trade(BinanceSpotClient.parseAggregate(text, symbol))) }
                catch (_: Exception) { emit(Event.Invalid) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                emit(Event.Disconnected)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                emit(Event.Disconnected)
            }
        })
        return Closeable { socket.cancel() }
    }
}
