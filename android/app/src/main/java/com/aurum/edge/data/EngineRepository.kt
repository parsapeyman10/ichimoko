package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Talks to the user's own backend.
 *
 * This is strictly an ADDITION. The phone's on-device engine remains the default and is
 * untouched; nothing here can modify a local signal, a local paper trade or the journal.
 * If the server is unreachable the app simply reports that and carries on as before —
 * there is no silent fallback that invents a signal, which would be the worst possible
 * failure mode for a trading screen.
 *
 * The autopilot controlled from here is the BACKEND's paper trader. It does not place
 * broker orders: the server's real-order endpoint is fail-closed and returns 503.
 */
class EngineRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)       // a trading endpoint must never be redirected elsewhere
        .followSslRedirects(false)
        .build()

    private val mutex = Mutex()

    private val _state = MutableStateFlow(EngineUiState())
    val state: StateFlow<EngineUiState> = _state.asStateFlow()

    /** Human-readable, Persian, and never a stack trace. */
    private fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "آدرس سرور پیدا نشد — نشانی را بررسی کنید"
        is SocketTimeoutException -> "سرور پاسخ نداد (تایم‌اوت)"
        is SSLException -> "اتصال امن برقرار نشد (گواهی HTTPS)"
        else -> "ارتباط با سرور برقرار نشد: ${error::class.simpleName}"
    }

    private suspend fun fetch(base: String, path: String, params: List<Pair<String, String>>,
                             post: Boolean = false): Result<JsonObject> = withContext(Dispatchers.IO) {
        val url = EngineApi.url(base, path, params)
            ?: return@withContext Result.failure<JsonObject>(IllegalArgumentException(
                "نشانی سرور معتبر نیست. باید https باشد و بدون مسیر اضافه (مثال: https://my-host.example)."))
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (post) builder.post(ByteArray(0).toRequestBody(null, 0, 0))
        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                if (!response.isSuccessful) {
                    val detail = (parsed?.get("detail") as? JsonPrimitive)?.contentOrNull
                    throw IllegalStateException(detail ?: "سرور خطا داد (HTTP ${response.code})")
                }
                parsed ?: throw IllegalStateException("پاسخ سرور JSON معتبر نبود")
            }
        }.recoverCatching { error ->
            throw IllegalStateException(
                if (error is IllegalStateException || error is IllegalArgumentException) error.message.orEmpty()
                else describe(error)
            )
        }
    }

    /** Verify the server is reachable and speaks our contract before anything else. */
    suspend fun checkConnection(base: String) = mutex.withLock {
        _state.value = _state.value.copy(connecting = true, error = null)
        val result = fetch(base, "health", emptyList())
        _state.value = result.fold(
            onSuccess = { _state.value.copy(connecting = false, connected = true, error = null) },
            onFailure = { _state.value.copy(connecting = false, connected = false, error = it.message) },
        )
    }

    suspend fun loadInstruments(base: String, kind: String, query: String) = mutex.withLock {
        _state.value = _state.value.copy(loadingInstruments = true)
        val result = fetch(base, "instruments", listOf(
            "kind" to kind, "q" to query, "limit" to "200",
        ))
        _state.value = result.fold(
            onSuccess = {
                _state.value.copy(
                    loadingInstruments = false, connected = true,
                    instruments = EngineApi.parseInstruments(it), error = null,
                )
            },
            onFailure = { _state.value.copy(loadingInstruments = false, error = it.message) },
        )
    }

    suspend fun loadSignal(base: String, symbol: String, timeframe: String, balance: Double) = mutex.withLock {
        _state.value = _state.value.copy(loadingSignal = true, error = null)
        val result = fetch(base, "scalp/signal", listOf(
            "symbol" to symbol,
            "timeframe" to timeframe,
            "equity" to balance.toString(),
        ))
        _state.value = result.fold(
            onSuccess = { payload ->
                val parsed = EngineApi.parseScalp(payload)
                if (parsed == null) _state.value.copy(loadingSignal = false, error = "پاسخ سرور قابل خواندن نبود")
                else _state.value.copy(loadingSignal = false, connected = true, scalp = parsed, error = null)
            },
            onFailure = { _state.value.copy(loadingSignal = false, error = it.message) },
        )
    }

    suspend fun loadAutopilot(base: String) = mutex.withLock {
        _state.value = _state.value.copy(loadingAutopilot = true)
        val result = fetch(base, "autopilot/state", emptyList())
        _state.value = result.fold(
            onSuccess = { payload ->
                val parsed = EngineApi.parseAutopilot(payload)
                if (parsed == null) _state.value.copy(loadingAutopilot = false, error = "پاسخ اتوپایلوت قابل خواندن نبود")
                else _state.value.copy(loadingAutopilot = false, connected = true, autopilot = parsed, error = null)
            },
            onFailure = { _state.value.copy(loadingAutopilot = false, error = it.message) },
        )
    }

    /** start / stop / cycle. The reply is ignored; the refreshed state is the truth. */
    suspend fun controlAutopilot(base: String, action: String) {
        val path = when (action) {
            "start" -> "autopilot/start"
            "stop" -> "autopilot/stop"
            "cycle" -> "autopilot/cycle"
            else -> return
        }
        mutex.withLock {
            _state.value = _state.value.copy(loadingAutopilot = true, error = null)
            val result = fetch(base, path, emptyList(), post = true)
            if (result.isFailure) {
                _state.value = _state.value.copy(
                    loadingAutopilot = false,
                    error = result.exceptionOrNull()?.message,
                )
                return
            }
        }
        loadAutopilot(base)
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }
}

data class EngineUiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val loadingInstruments: Boolean = false,
    val loadingSignal: Boolean = false,
    val loadingAutopilot: Boolean = false,
    val instruments: List<EngineInstrument> = emptyList(),
    val scalp: EngineScalp? = null,
    val autopilot: EngineAutopilot? = null,
    val error: String? = null,
)
