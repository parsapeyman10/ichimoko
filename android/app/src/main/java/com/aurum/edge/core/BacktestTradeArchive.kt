package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * آرشیو معاملاتی یک اجرای بک‌تست + قفل «یک‌بار برای همیشه».
 *
 * ── چه مشکلی را حل می‌کند؟ ─────────────────────────────────────────────
 * ۱. کاربر می‌خواهد **در پایان هر بک‌تست** فهرست کامل معامله‌ها را در دست داشته باشد، نه فقط
 *    نمودار. اینجا هر معامله با تاریخ، جفت، جهت، قیمت ورود/خروج، دلیل خروج، کارمزد، سود خالص
 *    و ضریب R نگه داشته می‌شود و به CSV و JSON تبدیل می‌شود.
 * ۲. کاربر می‌خواهد سود بک‌تست را به عنوان سرمایهٔ واقعی «اعمال» کند — یک‌بار. پس هر آرشیو
 *    یک **شناسهٔ محتوایی** دارد که از خودِ معامله‌ها ساخته می‌شود (نه یک UUID تصادفی). دو
 *    اجرای دقیقاً یکسان روی یک فایل CSV و یک تنظیم، همان شناسه را می‌دهند و دوباره اعمال
 *    نمی‌شوند. اگر تنظیم یا داده عوض شود، شناسه هم عوض می‌شود و اعمالِ تازه مجاز است.
 *
 * ── چند تاریخ «روز» چه معنایی دارد؟ ────────────────────────────────────
 * هر معامله به **روز بسته‌شدنش (UTC)** نسبت داده می‌شود، نه روز ورود. سود/زیان وقتی واقعی
 * می‌شود که معامله بسته شود؛ نسبت‌دادن یک معاملهٔ سه‌روزه به روز ورود، «سود آن روز» را
 * دروغ می‌کند. معاملهٔ باز (بدون خروج) در مجموع روزها می‌آید ولی در هیچ روزی حساب نمی‌شود.
 *
 * این فایل هیچ‌جا به `JournalStore` دست نمی‌زند؛ فقط داده و محاسبهٔ خالص است تا در تست
 * بدون اندروید هم قابل بررسی باشد.
 */
object BacktestTradeArchive {

    /** یک روز کاری (UTC) از آرشیو. */
    data class DayBucket(
        /** تاریخ UTC به شکل `YYYY-MM-DD`. */
        val day: String,
        val trades: Int,
        val wins: Int,
        val netUsd: Double,
        /** مجموع R بدون کارمزد — برای مقایسه با «۱R» هر معامله. */
        val grossUsd: Double,
        val feesUsd: Double,
    ) {
        val winRatePct: Double get() = if (trades <= 0) 0.0 else wins * 100.0 / trades
    }

    /** نتیجهٔ کامل یک آرشیو: خود آرشیو + ابتدای پنجره + محتوای فایل‌ها. */
    data class Built(
        val archive: Archive,
        val csv: String,
        val json: String,
    )

    /**
     * ساخت آرشیو از نتیجهٔ بک‌تست.
     *
     * @param dataSetId شناسهٔ فایل CSV در انبار؛ بخشی از شناسهٔ محتوایی است، چون سود دو
     *   مجموعهٔ دادهٔ متفاوت نباید «همان اجرا» شمرده شود.
     */
    fun build(
        result: BacktestReplayEngine.Result,
        dataSetId: String,
        dataSetName: String,
        createdAt: Long = System.currentTimeMillis(),
        /**
         * کندل‌هایی که بک‌تست رویشان اجرا شد. برای «اثر انگشت داده» لازم است: اگر دو فایل
         * مختلف هیچ معامله‌ای نسازند، فهرست معامله‌هایشان هر دو خالی است و بدون این اثر انگشت
         * یک شناسه می‌گرفتند — یعنی سود صفر یکی، دومی را «تکراری» اعلام می‌کرد.
         */
        candles: List<com.aurum.edge.domain.Candle> = emptyList(),
    ): Built {
        val cfg = result.config
        val trades = result.trades
        val days = dailyBuckets(trades)
        val contentHash = contentHash(result, dataSetId, candles)

        val closed = trades.filter { !it.isOpen }
        val metrics = Metrics(
            trades = closed.size,
            wins = closed.count { it.netPnlUsd > 0.0 },
            netUsd = round2(closed.sumOf { it.netPnlUsd }),
            grossUsd = round2(closed.sumOf { it.grossPnlUsd }),
            feesUsd = round2(closed.sumOf { it.feeUsd }),
            actualTrades = closed.count { it.rMultiple > 0.0 },
        )

        val archive = Archive(
            id = contentHash,
            contentHash = contentHash,
            createdAt = createdAt,
            dataSetId = dataSetId,
            dataSetName = dataSetName,
            symbol = cfg.symbol,
            intervalLabel = cfg.triggerInterval.label,
            strategyLabel = if (cfg.runAllStrategies) "همهٔ استراتژی‌ها" else strategyTitle(trades, cfg.strategyId),
            startEquityUsd = cfg.startEquityUsd,
            endEquityUsd = round2(result.finalEquityUsd),
            riskPercent = cfg.riskPercent,
            firstTime = result.firstTime,
            lastTime = result.lastTime,
            candlesUsed = result.candlesUsed,
            evaluatedSteps = result.evaluatedSteps,
            elapsedMs = result.elapsedMs,
            metrics = metrics,
            days = days,
            rows = trades.map { row(it, cfg.symbol, cfg.triggerInterval.label) },
        )
        return Built(archive = archive, csv = toCsv(archive), json = toJson(archive))
    }

    /** تجمیع روزانه بر پایهٔ «روز بسته‌شدن» هر معامله در UTC. */
    fun dailyBuckets(trades: List<BacktestReplayEngine.Trade>): List<DayBucket> {
        val closed = trades.filter { !it.isOpen && it.exitTime != null }
        if (closed.isEmpty()) return emptyList()
        return closed
            .groupBy { dayOf(it.exitTime!!) }
            .toSortedMap()
            .map { (day, list) ->
                DayBucket(
                    day = day,
                    trades = list.size,
                    wins = list.count { it.netPnlUsd > 0.0 },
                    netUsd = round2(list.sumOf { it.netPnlUsd }),
                    grossUsd = round2(list.sumOf { it.grossPnlUsd }),
                    feesUsd = round2(list.sumOf { it.feeUsd }),
                )
            }
    }

    fun dayOf(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    fun prettyDay(day: String): String = runCatching {
        val d = LocalDate.parse(day)
        "${d.year}/%02d/%02d".format(d.monthValue, d.dayOfMonth)
    }.getOrDefault(day)

    /**
     * شناسهٔ محتوایی: از خودِ معامله‌ها ساخته می‌شود.
     *
     * چرا نه UUID؟ چون هدف «جلوگیری از اعمال تکراری» است. با UUID، دو اجرای یکسان دو شناسهٔ
     * متفاوت می‌گرفتند و کاربر می‌توانست دو بار همان سود را روی سرمایه‌اش بنشاند.
     *
     * داخل هش: جفت، تایم‌فریم، تنظیم ریسک، و برای هر معامله زمان ورود/خروج، قیمت‌ها، جهت و
     * خالص سود. تغییر هر کدام یعنی «این همان اجرا نیست».
     */
    fun contentHash(
        result: BacktestReplayEngine.Result,
        dataSetId: String,
        candles: List<com.aurum.edge.domain.Candle> = emptyList(),
    ): String {
        val cfg = result.config
        val sb = StringBuilder(512)
        sb.append(seriesFingerprint(candles)).append('|')
        sb.append(dataSetId).append('|').append(cfg.symbol).append('|')
            .append(cfg.triggerInterval.label).append('|').append(cfg.riskPercent).append('|')
            .append(cfg.startEquityUsd).append('|').append(cfg.strategyId).append('|')
            .append(cfg.runAllStrategies).append('|').append(result.firstTime).append('|')
            .append(result.lastTime).append('|').append(result.candlesUsed)
        result.trades.forEach { t ->
            sb.append(';').append(t.strategyId).append(',').append(t.entryTime).append(',')
                .append(t.exitTime ?: 0L).append(',').append(t.action.name).append(',')
                .append(round2(t.entryPrice)).append(',').append(round2(t.exitPrice ?: 0.0)).append(',')
                .append(round2(t.netPnlUsd))
        }
        return sha256Hex(sb.toString()).take(32)
    }

    /** CSV با BOM و جداکنندهٔ کاما؛ در Excel فارسی مستقیم باز می‌شود. */
    fun toCsv(archive: Archive): String {
        val sb = StringBuilder(2048)
        sb.append('\uFEFF')
        sb.append("ردیف,شمارهٔ معامله,استراتژی,نماد,تایم‌فریم,جهت,ورود (شمسی),ورود (UTC),قیمت ورود,")
            .append("حد ضرر,حد سود,خروج (UTC),قیمت خروج,دلیل خروج,کندل نگه‌داری,")
            .append("سود خالص ($),کارمزد ($),سود ناخالص ($),R,سرمایه بعد از معامله ($),برد\n")
        archive.rows.forEachIndexed { i, r ->
            sb.append(i + 1).append(',')
                .append(r.tradeNumber).append(',')
                .append(esc(r.strategyTitleFa)).append(',')
                .append(esc(r.symbol)).append(',')
                .append(r.intervalLabel).append(',')
                .append(if (r.action == SignalAction.BUY) "خرید" else "فروش").append(',')
                .append(r.entryDateFa).append(',')
                .append(isoUtc(r.entryTime)).append(',')
                .append(fmt(r.entryPrice)).append(',')
                .append(fmt(r.stopLoss)).append(',')
                .append(fmt(r.takeProfit)).append(',')
                .append(r.exitTime?.let { isoUtc(it) } ?: "باز").append(',')
                .append(r.exitPrice?.let { fmt(it) } ?: "").append(',')
                .append(esc(r.exitReasonFa)).append(',')
                .append(r.barsHeld).append(',')
                .append(fmt2(r.netPnlUsd)).append(',')
                .append(fmt2(r.feeUsd)).append(',')
                .append(fmt2(r.grossPnlUsd)).append(',')
                .append(fmt2(r.rMultiple)).append(',')
                .append(fmt2(r.equityAfterUsd)).append(',')
                .append(if (r.won) "بله" else "خیر").append('\n')
        }
        sb.append("\nخلاصه,,,,,,,,,,,,,,,,,,,,\n")
        sb.append("معامله,").append(archive.metrics.trades).append('\n')
        sb.append("برد,").append(archive.metrics.wins).append('\n')
        sb.append("سود خالص,").append(fmt2(archive.metrics.netUsd)).append('\n')
        sb.append("کارمزد,").append(fmt2(archive.metrics.feesUsd)).append('\n')
        sb.append("شناسهٔ آرشیو,").append(archive.contentHash).append('\n')
        return sb.toString()
    }

    /** JSON کامل؛ پشتیبان قابل بازخوانی و قابل مقایسه. */
    fun toJson(archive: Archive): String {
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"schema\": \"aurum.backtest.archive.v1\",\n")
        sb.append("  \"contentHash\": ").append(q(archive.contentHash)).append(",\n")
        sb.append("  \"createdAt\": ").append(archive.createdAt).append(",\n")
        sb.append("  \"dataSetId\": ").append(q(archive.dataSetId)).append(",\n")
        sb.append("  \"dataSetName\": ").append(q(archive.dataSetName)).append(",\n")
        sb.append("  \"symbol\": ").append(q(archive.symbol)).append(",\n")
        sb.append("  \"interval\": ").append(q(archive.intervalLabel)).append(",\n")
        sb.append("  \"strategy\": ").append(q(archive.strategyLabel)).append(",\n")
        sb.append("  \"startEquityUsd\": ").append(fmt2(archive.startEquityUsd)).append(",\n")
        sb.append("  \"endEquityUsd\": ").append(fmt2(archive.endEquityUsd)).append(",\n")
        sb.append("  \"riskPercent\": ").append(archive.riskPercent).append(",\n")
        sb.append("  \"candlesUsed\": ").append(archive.candlesUsed).append(",\n")
        sb.append("  \"metrics\": {\n")
        sb.append("    \"trades\": ").append(archive.metrics.trades).append(",\n")
        sb.append("    \"wins\": ").append(archive.metrics.wins).append(",\n")
        sb.append("    \"netUsd\": ").append(fmt2(archive.metrics.netUsd)).append(",\n")
        sb.append("    \"grossUsd\": ").append(fmt2(archive.metrics.grossUsd)).append(",\n")
        sb.append("    \"feesUsd\": ").append(fmt2(archive.metrics.feesUsd)).append("\n  },\n")
        sb.append("  \"days\": [\n")
        archive.days.forEachIndexed { i, d ->
            sb.append("    {\"day\": ").append(q(d.day))
                .append(", \"trades\": ").append(d.trades)
                .append(", \"wins\": ").append(d.wins)
                .append(", \"netUsd\": ").append(fmt2(d.netUsd))
                .append(", \"feesUsd\": ").append(fmt2(d.feesUsd)).append("}")
            sb.append(if (i == archive.days.lastIndex) "\n" else ",\n")
        }
        sb.append("  ],\n  \"trades\": [\n")
        archive.rows.forEachIndexed { i, r ->
            sb.append("    {")
                .append("\"n\": ").append(r.tradeNumber)
                .append(", \"strategy\": ").append(q(r.strategyTitleFa))
                .append(", \"side\": ").append(q(r.action.name))
                .append(", \"entryTime\": ").append(r.entryTime)
                .append(", \"entryPrice\": ").append(fmt(r.entryPrice))
                .append(", \"stopLoss\": ").append(fmt(r.stopLoss))
                .append(", \"takeProfit\": ").append(fmt(r.takeProfit))
                .append(", \"exitTime\": ").append(r.exitTime ?: 0L)
                .append(", \"exitPrice\": ").append(r.exitPrice?.let { fmt(it) } ?: "0")
                .append(", \"exitReason\": ").append(q(r.exitReasonFa))
                .append(", \"barsHeld\": ").append(r.barsHeld)
                .append(", \"netPnlUsd\": ").append(fmt2(r.netPnlUsd))
                .append(", \"feeUsd\": ").append(fmt2(r.feeUsd))
                .append(", \"rMultiple\": ").append(fmt2(r.rMultiple))
                .append(", \"won\": ").append(r.won)
                .append("}")
            sb.append(if (i == archive.rows.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    private fun row(
        t: BacktestReplayEngine.Trade,
        symbol: String,
        intervalLabel: String,
    ): Row = Row(
        tradeNumber = t.index,
        strategyTitleFa = t.strategyTitleFa,
        symbol = symbol,
        intervalLabel = intervalLabel,
        action = t.action,
        entryTime = t.entryTime,
        entryDateFa = dayOf(t.entryTime),
        entryPrice = t.entryPrice,
        stopLoss = t.stopLoss,
        takeProfit = t.takeProfit,
        exitTime = t.exitTime,
        exitPrice = t.exitPrice,
        exitReasonFa = t.exitReason.labelFa,
        barsHeld = t.barsHeld,
        netPnlUsd = round2(t.netPnlUsd),
        feeUsd = round2(t.feeUsd),
        grossPnlUsd = round2(t.grossPnlUsd),
        rMultiple = round2(t.rMultiple),
        equityAfterUsd = round2(t.equityAfterUsd),
        won = t.won,
    )

    private fun strategyTitle(trades: List<BacktestReplayEngine.Trade>, id: String): String =
        trades.firstOrNull { it.strategyId == id }?.strategyTitleFa
            ?: BitcoinStrategyModel.fromId(id).titleFa

    /**
     * اثر انگشت سبک داده: تعداد، زمان اولین/آخرین کندل و ۳۲ قیمت بستهٔ نمونه‌برداری‌شده.
     *
     * چرا نمونه و نه هش کامل؟ هزینه مهم است: هش ۵۰٬۰۰۰ کندل در هر اجرا صدها میلی‌ثانیه
     * می‌برد. ۳۲ نقطهٔ یکنواخت‌پخش‌شده برای این‌که دو فایل مختلف (یا همان فایل با بازهٔ
     * متفاوت) شناسهٔ یکسان نگیرند کافی است و هزینه‌اش ناچیز.
     */
    fun seriesFingerprint(candles: List<com.aurum.edge.domain.Candle>): String {
        if (candles.isEmpty()) return "empty"
        val sb = StringBuilder(256)
        sb.append(candles.size).append(':')
            .append(candles.first().time).append(':').append(candles.last().time).append(':')
        val picks = 32
        val step = (candles.size / picks).coerceAtLeast(1)
        var i = 0
        while (i < candles.size && sb.length < 900) {
            sb.append(round2(candles[i].close)).append(',')
            i += step
        }
        return sha256Hex(sb.toString()).take(16)
    }

    private fun sha256Hex(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { sb.append("%02x".format(it)) }
        return sb.toString()
    }

    private fun round2(v: Double): Double = if (v.isFinite()) Math.round(v * 100.0) / 100.0 else 0.0

    private fun fmt(v: Double): String = when {
        v >= 100.0 -> "%.2f".format(Locale.US, v)
        v >= 1.0 -> "%.4f".format(Locale.US, v)
        else -> "%.6f".format(Locale.US, v)
    }

    private fun fmt2(v: Double): String = "%.2f".format(Locale.US, v)

    private fun isoUtc(ms: Long): String = Instant.ofEpochMilli(ms).toString().replace("T", " ").take(16)

    private fun esc(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"${s.replace("\"", "\"\"")}\"" else s

    private fun q(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    data class Metrics(
        val trades: Int,
        val wins: Int,
        val netUsd: Double,
        val grossUsd: Double,
        val feesUsd: Double,
        /** تعداد معامله‌ای که واقعاً با ۱R>۰ بسته شده‌اند (برای میانگین R بدون تقسیم بر صفر). */
        val actualTrades: Int = trades,
    ) {
        val winRatePct: Double get() = if (trades <= 0) 0.0 else wins * 100.0 / trades
    }

    data class Row(
        val tradeNumber: Int,
        val strategyTitleFa: String,
        val symbol: String,
        val intervalLabel: String,
        val action: SignalAction,
        val entryTime: Long,
        val entryDateFa: String,
        val entryPrice: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val exitTime: Long?,
        val exitPrice: Double?,
        val exitReasonFa: String,
        val barsHeld: Int,
        val netPnlUsd: Double,
        val feeUsd: Double,
        val grossPnlUsd: Double,
        val rMultiple: Double,
        val equityAfterUsd: Double,
        val won: Boolean,
    )

    data class Archive(
        val id: String,
        val contentHash: String,
        val createdAt: Long,
        val dataSetId: String,
        val dataSetName: String,
        val symbol: String,
        val intervalLabel: String,
        val strategyLabel: String,
        val startEquityUsd: Double,
        val endEquityUsd: Double,
        val riskPercent: Double,
        val firstTime: Long,
        val lastTime: Long,
        val candlesUsed: Int,
        val evaluatedSteps: Int,
        val elapsedMs: Long,
        val metrics: Metrics,
        val days: List<DayBucket>,
        val rows: List<Row>,
    ) {
        val netUsd: Double get() = metrics.netUsd
        val spanDays: Int get() = days.size
        val bestDay: DayBucket? get() = days.maxByOrNull { it.netUsd }
        val worstDay: DayBucket? get() = days.minByOrNull { it.netUsd }
        val suggestedFileNameBase: String
            get() = "AurumBacktest_${symbol.replace("/", "")}_${intervalLabel}_${contentHash.take(8)}"
    }
}
