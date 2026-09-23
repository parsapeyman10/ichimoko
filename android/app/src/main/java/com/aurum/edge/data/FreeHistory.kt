package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Fixed, read-only research sources. A rate FIX is not an OHLC candle or a live trading quote. */
enum class FreeHistoryKind { ECB_RATE, GOLD_MONTHLY, TWELVE_DAILY }

data class FreeHistoryChoice(
    val id: String,
    val title: String,
    val code: String,
    val kind: FreeHistoryKind,
    val sourceTitle: String,
    val sourcePage: String,
)

object FreeHistoryCatalog {
    val choices = listOf(
        FreeHistoryChoice("eur-usd", "EUR/USD · نرخ مرجع روزانه", "EUR/USD", FreeHistoryKind.ECB_RATE,
            "ECB از Frankfurter · بدون کلید", "https://frankfurter.dev/providers/ecb/"),
        FreeHistoryChoice("eur-gbp", "EUR/GBP · نرخ مرجع روزانه", "EUR/GBP", FreeHistoryKind.ECB_RATE,
            "ECB از Frankfurter · بدون کلید", "https://frankfurter.dev/providers/ecb/"),
        FreeHistoryChoice("gold-monthly", "طلا USD/انس · میانگین ماهانه", "GOLD/USD", FreeHistoryKind.GOLD_MONTHLY,
            "World Bank Pink Sheet از DataHub · بدون کلید", "https://datahub.io/core/gold-prices"),
        FreeHistoryChoice("gold-spot", "XAU/USD · اسپات روزانه (مشروط)", "XAU/USD", FreeHistoryKind.TWELVE_DAILY,
            "Twelve Data · دسترسی Commodities ممکن است پولی باشد", "https://twelvedata.com/docs/market-data/time-series"),
        FreeHistoryChoice("aapl-stock", "AAPL · سهم آمریکا روزانه", "AAPL", FreeHistoryKind.TWELVE_DAILY,
            "Twelve Data · کلید رایگان داده‌خوانی", "https://twelvedata.com/docs/market-data/time-series"),
        FreeHistoryChoice("msft-stock", "MSFT · سهم آمریکا روزانه", "MSFT", FreeHistoryKind.TWELVE_DAILY,
            "Twelve Data · کلید رایگان داده‌خوانی", "https://twelvedata.com/docs/market-data/time-series"),
    )

    fun find(id: String): FreeHistoryChoice? = choices.firstOrNull { it.id == id }
}

data class DailyOhlc(val date: LocalDate, val open: Double, val high: Double, val low: Double,
                     val close: Double, val volume: Double?)
data class DailyReference(val date: LocalDate, val rate: Double)
data class MonthlyGold(val date: LocalDate, val usdPerTroyOunce: Double)

sealed interface FreeHistoryResult {
    val choice: FreeHistoryChoice
    val sourceUrl: String // NEVER includes a provider key
    val fetchedAt: Long

    data class Ohlc(
        override val choice: FreeHistoryChoice, val rows: List<DailyOhlc>,
        override val sourceUrl: String, override val fetchedAt: Long,
    ) : FreeHistoryResult

    data class Rates(
        override val choice: FreeHistoryChoice, val rows: List<DailyReference>,
        override val sourceUrl: String, override val fetchedAt: Long,
    ) : FreeHistoryResult

    data class GoldMonthly(
        override val choice: FreeHistoryChoice, val rows: List<MonthlyGold>,
        override val sourceUrl: String, override val fetchedAt: Long,
    ) : FreeHistoryResult
}

sealed interface FreeHistoryState {
    data object Idle : FreeHistoryState
    data class Loading(val title: String) : FreeHistoryState
    data class Done(val result: FreeHistoryResult) : FreeHistoryState
    data class Failed(val message: String) : FreeHistoryState
}

/** Explicit provider adapters: no search/scraping, arbitrary URLs, redirects, broker prices or fake rows. */
class FreeHistoryDownloader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun download(id: String, twelveKey: String): FreeHistoryResult {
        val choice = FreeHistoryCatalog.find(id) ?: error("منبع تاریخی ناشناخته است")
        val today = LocalDate.now(ZoneOffset.UTC)
        return when (choice.kind) {
            FreeHistoryKind.ECB_RATE -> {
                val quote = choice.code.substringAfter('/')
                val from = today.minusDays(370)
                val until = today.minusDays(1)
                val url = "https://api.frankfurter.dev/v2/providers/ecb/rates?from=$from&to=$until&base=EUR&quotes=$quote"
                val raw = fetch(url)
                FreeHistoryResult.Rates(choice, parseEcb(raw, choice, from, today), url, System.currentTimeMillis())
            }
            FreeHistoryKind.GOLD_MONTHLY -> {
                val url = "https://raw.githubusercontent.com/datasets/gold-prices/main/data/monthly-processed.csv"
                val raw = fetch(url, "text/csv")
                FreeHistoryResult.GoldMonthly(choice, parseGoldMonthly(raw, today), url, System.currentTimeMillis())
            }
            FreeHistoryKind.TWELVE_DAILY -> {
                require(twelveKey.isNotBlank()) { "برای سهم/طلای اسپات، کلید داده‌خوانی Twelve Data را در تنظیمات وارد کنید (طلای روزانه شاید دسترسی پولی بخواهد)" }
                val url = "https://api.twelvedata.com/time_series?symbol=${URLEncoder.encode(choice.code, "UTF-8")}" +
                    "&interval=1day&outputsize=365&order=ASC&timezone=UTC&apikey=${URLEncoder.encode(twelveKey.trim(), "UTF-8")}"
                val raw = fetch(url)
                FreeHistoryResult.Ohlc(choice, parseTwelveDaily(raw, choice, today), choice.sourcePage, System.currentTimeMillis())
            }
        }
    }

    private suspend fun fetch(url: String, accept: String = "application/json"): String = withContext(Dispatchers.IO) {
        // The URL is constructed only from hard-coded symbols and fixed HTTPS hosts above.
        val response = http.newCall(Request.Builder().url(url).header("Accept", accept).build()).execute()
        response.use {
            if (!it.isSuccessful) error("دریافت داده از ناشر انجام نشد (HTTP ${it.code})")
            val body = it.peekBody(800_001L).string()
            require(body.length <= 800_000 && body.isNotBlank()) { "پاسخ ناشر خالی یا بزرگ‌تر از سقف است" }
            body
        }
    }

    internal fun parseEcb(raw: String, choice: FreeHistoryChoice, from: LocalDate, today: LocalDate): List<DailyReference> {
        require(choice.kind == FreeHistoryKind.ECB_RATE && choice.code.startsWith("EUR/")) { "جفت ECB نامعتبر است" }
        val rows = json.parseToJsonElement(raw) as? JsonArray ?: error("پاسخ نرخ مرجع ECB نامعتبر است")
        require(rows.size in 20..400) { "دادهٔ نرخ مرجع ECB کافی نیست" }
        val until = today.minusDays(1)
        val quote = choice.code.substringAfter('/')
        val parsed = rows.mapNotNull { item ->
            val row = item as? JsonObject ?: error("ردیف نرخ مرجع نامعتبر است")
            require(row.text("base") == "EUR" && row.text("quote") == quote) { "هویت جفت ECB مغایرت دارد" }
            val date = parseDate(row.text("date"))
            val rate = row.text("rate")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                ?: error("نرخ ECB نامعتبر است")
            // Frankfurter sometimes includes the last known rate before `from` on a non-business start date.
            if (date < from) null else {
                require(date <= until) { "دادهٔ آینده در پاسخ ECB وجود دارد" }
                DailyReference(date, rate)
            }
        }.sortedBy { it.date }
        require(parsed.size >= 20 && parsed.distinctBy { it.date }.size == parsed.size &&
            parsed.last().date >= today.minusDays(10)) { "نرخ‌های ECB تکراری، ناقص یا قدیمی‌اند" }
        return parsed
    }

    internal fun parseGoldMonthly(raw: String, today: LocalDate): List<MonthlyGold> {
        val lines = raw.trimEnd().lineSequence().map { it.trimEnd('\r') }.toList()
        require(lines.size in 25..2400 && lines.first() == "Date,Price") { "CSV ماهانهٔ طلا از منبع معتبر نیست" }
        val currentMonth = today.withDayOfMonth(1)
        val parsed = lines.drop(1).mapNotNull { line ->
            val cols = line.trimEnd('\r').split(',')
            require(cols.size == 2) { "ردیف طلای ماهانه دو ستون Date,Price ندارد" }
            val date = parseDate(cols[0])
            require(date.dayOfMonth == 1) { "تاریخ طلای ماهانه معتبر نیست" }
            val price = cols[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                ?: error("میانگین قیمت ماهانهٔ طلا معتبر نیست")
            require(!date.isAfter(currentMonth)) { "تاریخ آینده در تاریخچهٔ طلا وجود دارد" }
            // Before 1960 this dataset repeats annual observations as if they were monthly.
            if (date.isBefore(LocalDate.of(1960, 1, 1)) || date.isEqual(currentMonth)) null
            else MonthlyGold(date, price)
        }.sortedBy { it.date }
        require(parsed.size >= 24 && parsed.distinctBy { it.date }.size == parsed.size &&
            !parsed.last().date.isBefore(currentMonth.minusMonths(3))) {
            "تاریخچهٔ طلا قدیمی، تکراری یا ناقص است"
        }
        return parsed
    }

    internal fun parseTwelveDaily(raw: String, choice: FreeHistoryChoice, today: LocalDate): List<DailyOhlc> {
        require(choice.kind == FreeHistoryKind.TWELVE_DAILY) { "نوع منبع اشتباه است" }
        val root = json.parseToJsonElement(raw) as? JsonObject ?: error("پاسخ کندل تاریخی نامعتبر است")
        if (root.text("status") == "error" || root["code"] != null) {
            error("Twelve Data کلید/سطح دسترسی یا سهمیهٔ این نماد را نپذیرفت؛ طلای روزانه ممکن است دسترسی Commodities بخواهد")
        }
        val meta = root["meta"] as? JsonObject ?: error("هویت منبع کندل مشخص نیست")
        require(meta.text("symbol") == choice.code && meta.text("interval") == "1day" &&
            meta.text("currency") == "USD") { "نماد/واحد/بازهٔ زمانی پاسخ با درخواست یکسان نیست" }
        val values = root["values"] as? JsonArray ?: error("کندل روزانه موجود نیست")
        require(values.size in 30..365) { "تعداد کندل روزانه قابل اتکا نیست" }
        val parsed = values.mapNotNull { value ->
            val bar = value as? JsonObject ?: error("کندل نامعتبر است")
            val date = parseDate(bar.text("datetime"))
            // An intraday/unfinished 'daily' bar is never returned as a completed EOD observation.
            if (date >= today) null else {
                require(date >= today.minusDays(800)) { "تاریخ کندل خارج از پنجرهٔ انتخابی است" }
                val open = bar.positive("open")
                val high = bar.positive("high")
                val low = bar.positive("low")
                val close = bar.positive("close")
                require(low <= minOf(open, close) && high >= maxOf(open, close) && high >= low) {
                    "OHLC کندل ناقص یا نامعتبر است"
                }
                val rawVolume = bar.text("volume")
                val volume = rawVolume?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                require(rawVolume == null || volume != null) { "حجم کندل معتبر نیست" }
                if (choice.code in setOf("AAPL", "MSFT")) require(volume != null) { "حجم سهم گزارش نشده است" }
                DailyOhlc(date, open, high, low, close, volume)
            }
        }.sortedBy { it.date }
        require(parsed.size >= 30 && parsed.distinctBy { it.date }.size == parsed.size &&
            parsed.last().date >= today.minusDays(10)) { "کندل‌های روزانه کافی، تازه یا یکتا نیستند" }
        return parsed
    }

    companion object {
        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.positive(key: String): Double = text(key)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: error("قیمت $key معتبر نیست")
        private fun parseDate(raw: String?): LocalDate {
            require(raw != null && Regex("\\d{4}-\\d{2}-\\d{2}").matches(raw)) { "تاریخ روزانه ناشر نامعتبر است" }
            return LocalDate.parse(raw)
        }

        /** CSV is exported locally by the user; reference rate never masquerades as OHLC. */
        fun csv(result: FreeHistoryResult): String = when (result) {
            is FreeHistoryResult.Ohlc -> buildString {
                append("date,symbol,open,high,low,close,volume,source\n")
                result.rows.forEach { bar ->
                    append("${bar.date},${result.choice.code},${bar.open},${bar.high},${bar.low},${bar.close},${bar.volume ?: ""},Twelve Data\n")
                }
            }
            is FreeHistoryResult.Rates -> buildString {
                append("date,base,quote,reference_rate,source\n")
                result.rows.forEach { rate ->
                    append("${rate.date},EUR,${result.choice.code.substringAfter('/')},${rate.rate},ECB (Frankfurter)\n")
                }
            }
            is FreeHistoryResult.GoldMonthly -> buildString {
                append("month,usd_per_troy_ounce,source\n")
                result.rows.forEach { row ->
                    append("${row.date},${row.usdPerTroyOunce},World Bank via DataHub (monthly average)\n")
                }
            }
        }
    }
}
