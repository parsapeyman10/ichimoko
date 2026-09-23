package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Fetches public data without inventing values. Failed symbols remain explicit failures. */
class SourceFetcher(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAll(source: SourceDef, symbols: List<SymbolDef> = source.symbols): SourceSnapshot = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) return@withContext SourceSnapshot(source, emptyList())
        val batch = source.batchTemplate?.let { template ->
            runCatching { fetchJson(source, template.replace("{symbols}", symbols.joinToString(",") { encode(it.code) })) }
                .getOrNull()
        }
        val quotes = if (batch != null && source.kind == SourceKind.JSON_REST) {
            symbols.map { parseJsonQuote(source, it, batch) }
        } else {
            coroutineScope {
                symbols.map { symbol -> async { fetchOne(source, symbol) } }.awaitAll()
            }
        }
        SourceSnapshot(source, quotes, online = quotes.any { it.price != null }, error = quotes.firstOrNull { it.price == null }?.error)
    }

    private suspend fun fetchOne(source: SourceDef, symbol: SymbolDef): Quote = try {
        when (source.kind) {
            SourceKind.JSON_REST -> parseJsonQuote(source, symbol, fetchJson(source, source.urlTemplate.replace("{symbol}", encode(symbol.code))))
            SourceKind.HTML_CSS -> parseHtmlQuote(source, symbol, fetchText(source, source.urlTemplate.replace("{symbol}", encode(symbol.code))))
            SourceKind.TSE_TSETMC -> Quote(symbol.code, symbol.label, error = "TSETMC adapter هنوز به منبع متصل نشده است", sourceId = source.id)
        }
    } catch (error: Exception) {
        Quote(symbol.code, symbol.label, error = shortError(error), stale = false, sourceId = source.id)
    }

    private fun parseJsonQuote(source: SourceDef, symbol: SymbolDef, root: JsonElement): Quote {
        val raw = JsonPath.number(root, source.pricePath, symbol.code)
        val price = raw?.times(source.scale)
        val rawChange = JsonPath.number(root, source.changePath, symbol.code)
        val change = when (source.changeMode) {
            ChangeMode.PERCENT -> rawChange
            ChangeMode.ABSOLUTE -> if (raw != null && raw != rawChange) rawChange?.div(raw - (rawChange ?: 0.0))?.times(100.0) else null
            ChangeMode.PREV_CLOSE -> if (raw != null && rawChange != null && rawChange != 0.0) (raw - rawChange) / rawChange * 100.0 else null
            ChangeMode.NONE -> null
        }
        return Quote(symbol.code, symbol.label, price, change, JsonPath.number(root, source.volumePath, symbol.code)?.times(source.scale), source.unit, if (price == null) "قیمت در پاسخ پیدا نشد" else null, sourceId = source.id, spark = JsonPath.numbers(root, source.sparkPath, symbol.code).takeLast(240).map { it * source.scale })
    }

    private fun parseHtmlQuote(source: SourceDef, symbol: SymbolDef, body: String): Quote {
        val selector = source.cssSelector ?: return Quote(symbol.code, symbol.label, error = "سلکتور HTML تنظیم نشده است", sourceId = source.id)
        val element = Jsoup.parse(body).select(selector).first()
            ?: return Quote(symbol.code, symbol.label, error = "سلکتور در صفحه پیدا نشد", sourceId = source.id)
        val raw = if (source.cssAttr.isNullOrBlank()) element.text() else element.attr(source.cssAttr)
        val price = Num.parse(raw)?.times(source.scale)
        return Quote(symbol.code, symbol.label, price = price, unit = source.unit, error = if (price == null) "مقدار صفحه عددی نبود" else null, sourceId = source.id)
    }

    private fun fetchJson(source: SourceDef, url: String): JsonElement = json.parseToJsonElement(fetchText(source, url))

    private fun fetchText(source: SourceDef, url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Trading/1.0 (Android; public-data-client)")
            .header("Accept-Language", "fa,en;q=0.8")
            .header("Accept", if (source.kind == SourceKind.HTML_CSS) "text/html,application/xhtml+xml,*/*" else "application/json, text/plain, */*")
            .apply { source.headers.forEach { (key, value) -> header(key, value) } }
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} — ${body.take(60)}")
            if (body.isBlank()) throw IllegalStateException("پاسخ خالی")
            return body
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun shortError(error: Exception): String = (error.message ?: "خطای دریافت داده").take(80)
}
