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
import org.jsoup.Jsoup
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
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAll(
        source: SourceDef,
        symbols: List<SymbolDef> = source.symbols,
        apiKey: String = "",
    ): SourceSnapshot = withContext(Dispatchers.IO) {
        require(SourceCatalog.find(source.id) == source) { "منبع ناشناخته است" }
        if (symbols.isEmpty()) return@withContext SourceSnapshot(source, emptyList(), online = false)
        if (source.requiresKey && apiKey.isBlank()) {
            return@withContext SourceSnapshot(source, symbols.map {
                Quote(it.code, it.label, error = "کلید خواندنی ${source.title} وارد نشده است", sourceId = source.id)
            }, online = false, error = "کلید API وارد نشده است")
        }
        val quotes = when {
            source.kind == SourceKind.HTML_CSS && source.batchTemplate != null -> {
                // TGJU's home page holds the market rows together. NEVER send nine
                // duplicate fallback requests if the page blocks us or changes format.
                val body = runCatching { fetchText(source, source.batchTemplate, apiKey) }
                symbols.map { symbol ->
                    if (body.isSuccess) parseHtmlQuote(source, symbol, body.getOrThrow())
                    else Quote(symbol.code, symbol.label,
                        error = "وب‌سایت منبع در دسترس نیست؛ دریافت تازه انجام نشد", sourceId = source.id)
                }
            }
            else -> {
                val batch = if (source.kind == SourceKind.JSON_REST) source.batchTemplate?.let { template ->
                    runCatching { fetchJson(source, template.replace("{symbols}",
                        symbols.joinToString(",") { encode(it.code) }), apiKey) }.getOrNull()
                } else null
                if (batch != null) symbols.map { parseJsonQuote(source, it, batch) }
                else coroutineScope { symbols.map { symbol -> async { fetchOne(source, symbol, apiKey) } }.awaitAll() }
            }
        }
        SourceSnapshot(source, quotes, online = quotes.any { it.price != null }, error = quotes.firstOrNull { it.price == null }?.error)
    }

    private suspend fun fetchOne(source: SourceDef, symbol: SymbolDef, apiKey: String): Quote = try {
        val url = source.urlTemplate.replace("{symbol}", encode(symbol.code))
        when (source.kind) {
            SourceKind.JSON_REST -> parseJsonQuote(source, symbol, fetchJson(source, url, apiKey))
            SourceKind.HTML_CSS -> parseHtmlQuote(source, symbol, fetchText(source, url, apiKey))
            SourceKind.TSE_TSETMC -> Quote(symbol.code, symbol.label, error = "قرارداد API و شناسه نماد TSETMC هنوز تأیید نشده است", sourceId = source.id)
        }
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

    internal fun parseHtmlQuote(source: SourceDef, symbol: SymbolDef, body: String): Quote {
        require(SourceCatalog.find(source.id) == source && symbol.code.matches(Regex("[a-z0-9_]{2,40}"))) {
            "نماد/وب‌سایت ثابت و معتبر نیست"
        }
        val selector = source.cssSelector?.replace("{symbol}", symbol.code)
            ?: return Quote(symbol.code, symbol.label, error = "سلکتور HTML تنظیم نشده است", sourceId = source.id)
        val element = Jsoup.parse(body).selectFirst(selector)
            ?: return Quote(symbol.code, symbol.label, error = "ردیف نماد در صفحه پیدا نشد", sourceId = source.id)
        // data-price belongs to the exact identified market row, NOT an advert, daily
        // high or a related coin. Fail closed if markup disappears or becomes ambiguous.
        val raw = if (source.cssAttr.isNullOrBlank()) element.text() else element.attr(source.cssAttr)
        val price = Num.parse(raw)?.times(source.scale)?.takeIf { it.isFinite() && it > 0 }
        return Quote(symbol.code, symbol.label, price = price, unit = source.unit,
            error = if (price == null) "قیمت معتبر در ردیف این نماد نبود" else null,
            sourceId = source.id, providerAt = null) // TGJU row has HH:MM, not a full date.
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
            .header("Accept", if (source.kind == SourceKind.HTML_CSS) "text/html,application/xhtml+xml" else "application/json, text/plain")
            .apply { source.headers.forEach { (key, value) -> header(key, value) } }
            .build()
        http.newCall(request).execute().use { response ->
            // Do not echo provider response bodies or URLs: a URL may contain a read-only key.
            if (!response.isSuccessful) throw IllegalStateException("خطای منبع (HTTP ${response.code})")
            val limit = if (source.kind == SourceKind.HTML_CSS) 2_000_000L else 1_048_576L
            val text = response.peekBody(limit + 1).string()
            if (text.length > limit) throw IllegalStateException("پاسخ منبع بیش از حد بزرگ است")
            if (text.isBlank()) throw IllegalStateException("پاسخ منبع خالی است")
            return text
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
