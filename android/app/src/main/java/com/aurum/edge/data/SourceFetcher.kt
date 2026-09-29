package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Read-only provider fetcher. No custom URLs or trading credentials are accepted by the app. */
class SourceFetcher(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false) // never forward an API key to a redirect target
        .build(),
    private val retryDelay: (Long) -> Unit = Thread::sleep,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAll(
        source: SourceDef,
        symbols: List<SymbolDef> = source.symbols,
        apiKey: String = "",
    ): SourceSnapshot = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        require(SourceCatalog.find(source.id) == source) { "منبع ناشناخته است" }
        if (symbols.isEmpty()) return@withContext SourceSnapshot(
            source, emptyList(), fetchedAt = System.currentTimeMillis(), online = false,
            latencyMs = (System.nanoTime() - startedAt) / 1_000_000L,
        )
        if (source.requiresKey && apiKey.isBlank()) {
            return@withContext SourceSnapshot(
                source,
                symbols.map {
                    Quote(it.code, it.label, error = "کلید خواندنی ${source.title} وارد نشده است", sourceId = source.id)
                },
                fetchedAt = System.currentTimeMillis(),
                online = false,
                error = "کلید API وارد نشده است",
                latencyMs = (System.nanoTime() - startedAt) / 1_000_000L,
            )
        }
        val quotes = if (source.batchTemplate != null) {
            // Batch only requests that share both a provider AND a read-only key. A key is
            // never sent to another provider. Never fan a failed/429 batch out into
            // repeated individual GETs.
            val batch = runCatching { fetchJson(source, source.batchTemplate.replace("{symbols}",
                symbols.joinToString(",") { encode(it.code) }), apiKey) }.getOrNull()
            if (batch != null) symbols.map { parseJsonQuote(source, it, batch) }
            else symbols.map { Quote(it.code, it.label,
                error = "پاسخ گروهی منبع در دسترس نیست؛ کش با زمان اصلی باقی می‌ماند",
                sourceId = source.id) }
        } else coroutineScope { symbols.map { symbol -> async { fetchOne(source, symbol, apiKey) } }.awaitAll() }
        SourceSnapshot(
            source = source,
            quotes = quotes,
            fetchedAt = System.currentTimeMillis(),
            online = quotes.any { it.price != null },
            error = quotes.firstOrNull { it.price == null }?.error,
            latencyMs = (System.nanoTime() - startedAt) / 1_000_000L,
        )
    }

    private suspend fun fetchOne(source: SourceDef, symbol: SymbolDef, apiKey: String): Quote = try {
        val url = source.urlTemplate.replace("{symbol}", encode(symbol.code))
        parseJsonQuote(source, symbol, fetchJson(source, url, apiKey))
    } catch (error: Exception) {
        Quote(symbol.code, symbol.label, error = (error.message ?: "خطای دریافت داده").take(100), sourceId = source.id)
    }

    internal fun parseJsonQuote(source: SourceDef, symbol: SymbolDef, root: JsonElement): Quote {
        val raw = JsonPath.number(root, source.pricePath, symbol.code)?.takeIf { it > 0 }
        val price = raw?.times(source.scale)?.takeIf { it.isFinite() && it > 0 }
        val rawChange = JsonPath.number(root, source.changePath, symbol.code)
        val change = when (source.changeMode) {
            ChangeMode.PERCENT -> rawChange
            ChangeMode.ABSOLUTE -> if (raw != null && rawChange != null && raw - rawChange != 0.0) rawChange / (raw - rawChange) * 100 else null
            ChangeMode.PREV_CLOSE -> if (raw != null && rawChange != null && rawChange > 0) (raw - rawChange) / rawChange * 100 else null
            ChangeMode.NONE -> null
        }?.takeIf { it.isFinite() }
        val providerAt = when (source.timestampMode) {
            SourceTime.UNIX_SECONDS -> JsonPath.number(root, source.timestampPath, symbol.code)?.toLong()?.times(1000L)
            SourceTime.UTC_DATETIME -> (JsonPath.first(root, source.timestampPath, symbol.code) as? JsonPrimitive)
                ?.contentOrNull?.let { TwelveDataClient.parseTime(it) }
            SourceTime.NONE -> null
        }?.takeIf { it > 0L }
        val code = (root as? JsonObject)?.get("code")?.toString()?.trim('"')
        val error = when {
            price != null -> null
            code == "429" -> "سهمیه منبع به پایان رسید (429)"
            code == "401" || code == "403" -> "کلید منبع پذیرفته نشد ($code)"
            else -> "قیمت در پاسخ منبع پیدا نشد"
        }
        return Quote(
            code = symbol.code, label = symbol.label, price = price, changePct = change,
            volume = JsonPath.number(root, source.volumePath, symbol.code)?.times(source.scale)?.takeIf { it.isFinite() },
            unit = source.unit, error = error, sourceId = source.id, providerAt = providerAt,
            spark = JsonPath.numbers(root, source.sparkPath, symbol.code).takeLast(240).map { it * source.scale }.filter { it.isFinite() },
        )
    }

    private fun fetchJson(source: SourceDef, url: String, apiKey: String): JsonElement = json.parseToJsonElement(fetchText(source, url, apiKey))

    private fun fetchText(source: SourceDef, template: String, apiKey: String): String {
        val url = template.replace("{api_key}", encode(apiKey))
        val expectedHost = source.urlTemplate.replace("{symbol}", "test").replace("{api_key}", "test").toHttpUrl().host
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.host == expectedHost) { "آدرس منبع معتبر نیست" }
        val request = Request.Builder().url(parsed)
            .header("User-Agent", "Trading/1.0 (Android; public-data-client)")
            .header("Accept-Language", "fa,en;q=0.8")
            .header("Accept", "application/json, text/plain")
            .apply { source.headers.forEach { (key, value) -> header(key, value) } }
            .build()
        repeat(3) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    // Never retry HTTP 4xx/429 or a changed provider schema.
                    // Do not echo provider bodies or URLs (which may contain a read-only key).
                    if (!response.isSuccessful) throw IllegalStateException("خطای منبع (HTTP ${response.code})")
                    val limit = 1_048_576L
                    val text = response.peekBody(limit + 1).string()
                    if (text.length > limit) throw IllegalStateException("پاسخ منبع بیش از حد بزرگ است")
                    if (text.isBlank()) throw IllegalStateException("پاسخ منبع خالی است")
                    return text
                }
            } catch (_: IOException) {
                if (attempt == 2) throw IllegalStateException("اتصال منبع پس از ۳ تلاش کوتاه برقرار نشد")
                retryDelay(500L * (attempt + 1))
            }
        }
        error("پاسخ منبع دریافت نشد")
    }

    /** Yahoo FX tickers carry "=" (EURUSD=X); "=" is a legal path/query char and must stay literal. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%3D", "=")
}
