package com.aurum.edge.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.CustomStrategySlot
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SavedJournalExportFile(
    val file: File,
    val name: String,
    val format: JournalExportFormat,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val modifiedAtFormatted: String,
)

enum class JournalExportFormat(
    val id: String,
    val titleFa: String,
    val subtitleFa: String,
    val extension: String,
    val mimeType: String,
) {
    HTML_TEAR_SHEET(
        id = "html",
        titleFa = "🌐 داشبورد تعاملی HTML5 (سطح TradeZella / Bloomberg)",
        subtitleFa = "گزارش گرافیکی کامل با منحنی رشد سرمایه، فیلتر زنده استراتژی‌ها و جدول کامل معاملات",
        extension = "html",
        mimeType = "text/html",
    ),
    PDF_REPORT(
        id = "pdf",
        titleFa = "📄 کارنامه رسمی چندصفحه‌ای PDF با اسکرین‌شات آرشیو تمام معاملات",
        subtitleFa = "فایل PDF کامل شامل نمودار Equity، جدول معاملات با حد ضرر ثابت و اسکرین‌شات گرافیکی چارت ایچیموکو برای تک‌تک معاملات",
        extension = "pdf",
        mimeType = "application/pdf",
    ),
    CSV_EXCEL(
        id = "csv",
        titleFa = "📊 فایل اکسل و گوگل‌شیت (CSV UTF-8 BOM)",
        subtitleFa = "۲۸ ستون تحلیلی کامل شامل فرمول R:R، کارمزد مرجع دیتا، ضریب R و زمان نگهداری",
        extension = "csv",
        mimeType = "text/csv",
    ),
    JSON_QUANT(
        id = "json",
        titleFa = "🧬 خروجی کوانت و هوش مصنوعی (Structured JSON)",
        subtitleFa = "ساختار کامل متریک‌های آماری، لیدربورد ۲۴ استراتژی (۱۵ ترکیب + ۹ کتاب درایو)، منحنی سرمایه و آرایه معاملات",
        extension = "json",
        mimeType = "application/json",
    ),
}

@Serializable
data class EquityCurvePoint(
    val stepIndex: Int,
    val timestampMs: Long,
    val dateLabel: String,
    val symbol: String,
    val strategyTitleFa: String,
    val tradePnlUsd: Double,
    val tradeRMultiple: Double,
    val cumulativePnlUsd: Double,
    val cumulativeR: Double,
    val equityUsd: Double,
    val peakEquityUsd: Double,
    val drawdownUsd: Double,
    val drawdownPct: Double,
) {
    val cumulativeEquityUsd: Double
        get() = equityUsd
}

@Serializable
data class StrategyTearSheetRow(
    val strategyId: String,
    val strategyTitleFa: String,
    val strategyBadgeFa: String,
    val totalTrades: Int,
    val closedTrades: Int,
    val openTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val expectancyUsd: Double,
    val expectancyR: Double,
    val avgPlannedRr: Double,
    val avgOptimalRr: Double,
    val avgAchievedR: Double,
    val totalFeesUsd: Double,
    val netPnlUsd: Double,
)

@Serializable
data class SymbolTearSheetRow(
    val symbol: String,
    val assetClassLabelFa: String,
    val closedTrades: Int,
    val openTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val avgR: Double,
    val totalFeesUsd: Double,
    val netPnlUsd: Double,
) {
    val labelFa: String
        get() = assetClassLabelFa
}

@Serializable
data class InstitutionalTearSheetReport(
    val schemaVersion: String = "3.9.11-institutional-tearsheet",
    val generatedAtMs: Long,
    val generatedAtFormatted: String,
    val filterTitleFa: String,
    val initialCapitalUsd: Double,
    val currentBalanceUsd: Double,
    val netPnlUsd: Double,
    val totalReturnPct: Double,
    val grossProfitUsd: Double,
    val grossLossUsd: Double,
    val totalFeesAndSpreadUsd: Double,
    val totalTradesCount: Int,
    val closedTradesCount: Int,
    val openTradesCount: Int,
    val winsCount: Int,
    val lossesCount: Int,
    val breakEvenCount: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val payoffRatio: Double,
    val expectancyUsdPerTrade: Double,
    val expectancyRPerTrade: Double,
    val sqnScore: Double,
    val sqnGradeFa: String,
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val maxDrawdownUsd: Double,
    val maxDrawdownPct: Double,
    val recoveryFactor: Double,
    val kellyOptimalPct: Double,
    val avgPlannedRr: Double,
    val avgOptimalRr: Double,
    val avgAchievedR: Double,
    val totalCumulativeR: Double,
    val avgWinUsd: Double,
    val avgLossUsd: Double,
    val bestTradeUsd: Double,
    val worstTradeUsd: Double,
    val bestTradeR: Double,
    val worstTradeR: Double,
    val maxConsecutiveWins: Int,
    val maxConsecutiveLosses: Int,
    val currentStreak: Int,
    val avgHoldDurationMinutes: Double,
    val longTradesCount: Int,
    val longWinRatePct: Double,
    val longNetPnlUsd: Double,
    val shortTradesCount: Int,
    val shortWinRatePct: Double,
    val shortNetPnlUsd: Double,
    val tf15mTradesCount: Int,
    val tf15mWinRatePct: Double,
    val tf15mProfitFactor: Double,
    val tf15mNetPnlUsd: Double,
    val tf5mTradesCount: Int,
    val tf5mWinRatePct: Double,
    val tf5mProfitFactor: Double,
    val tf5mNetPnlUsd: Double,
    val strategyLeaderboard: List<StrategyTearSheetRow>,
    val symbolLeaderboard: List<SymbolTearSheetRow>,
    val equityCurve: List<EquityCurvePoint>,
    val trades: List<PaperTrade>,
) {
    val filterScopeLabelFa: String get() = filterTitleFa
    val initialCapital: Double get() = initialCapitalUsd
    val currentEquity: Double get() = currentBalanceUsd
    val totalVenueFeesUsd: Double get() = totalFeesAndSpreadUsd
    val kellyCriterionPct: Double get() = kellyOptimalPct
    val expectancyUsd: Double get() = expectancyUsdPerTrade
    val expectancyR: Double get() = expectancyRPerTrade
    val tf15mCount: Int get() = tf15mTradesCount
    val tf15mWinRate: Double get() = tf15mWinRatePct
    val tf15mNetPnl: Double get() = tf15mNetPnlUsd
    val tf5mCount: Int get() = tf5mTradesCount
    val tf5mWinRate: Double get() = tf5mWinRatePct
    val tf5mNetPnl: Double get() = tf5mNetPnlUsd
    val longWinRate: Double get() = longWinRatePct
    val longNetPnl: Double get() = longNetPnlUsd
    val shortWinRate: Double get() = shortWinRatePct
    val shortNetPnl: Double get() = shortNetPnlUsd
}

object JournalExportEngine {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val tehranZone: ZoneId = runCatching { ZoneId.of("Asia/Tehran") }.getOrDefault(ZoneId.systemDefault())
    private val dateTimeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(tehranZone)
    private val fileStampFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US).withZone(tehranZone)

    private fun r2(v: Double): Double = if (v.isFinite()) round(v * 100.0) / 100.0 else 0.0
    private fun r3(v: Double): Double = if (v.isFinite()) round(v * 1000.0) / 1000.0 else 0.0

    fun formatTimestamp(ms: Long?): String {
        if (ms == null || ms <= 0L) return "—"
        return runCatching { dateTimeFmt.format(Instant.ofEpochMilli(ms)) }.getOrDefault("—")
    }

    fun buildInstitutionalTearSheet(
        trades: List<PaperTrade>,
        initialCapital: Double = 100_000.0,
        currentBalance: Double = 100_000.0,
        customStrategies: List<CustomStrategySlot> = emptyList(),
        filterScopeLabelFa: String = "کل ژورنال (همه ۱۵ ترکیب استراتژی و ۱۵ نماد)",
        nowEpochMs: Long = System.currentTimeMillis(),
    ): InstitutionalTearSheetReport = buildInstitutionalTearSheetInternal(
        tradesInput = trades,
        initialCapitalUsd = initialCapital,
        currentBalanceUsd = currentBalance,
        customStrategies = customStrategies,
        filterTitleFa = filterScopeLabelFa,
        nowMillis = nowEpochMs,
    )

    /**
     * محاسبه کامل کارنامه کوانت و شاخص‌های استاندارد جهانی (TradeZella / Edgewonk / QuantConnect Tear Sheet).
     */
    fun buildInstitutionalTearSheetInternal(
        tradesInput: List<PaperTrade>,
        initialCapitalUsd: Double = 100_000.0,
        currentBalanceUsd: Double = 100_000.0,
        customStrategies: List<CustomStrategySlot> = emptyList(),
        filterTitleFa: String = "کل ژورنال (همه ۱۵ ترکیب استراتژی و ۱۵ نماد)",
        nowMillis: Long = System.currentTimeMillis(),
    ): InstitutionalTearSheetReport {
        val safeInitial = if (initialCapitalUsd.isFinite() && initialCapitalUsd > 0.0) initialCapitalUsd else 100_000.0
        val sortedAll = tradesInput.sortedBy { it.openedAt }
        val openTrades = sortedAll.filter { it.isOpen }
        val closedTrades = sortedAll.filter { !it.isOpen && it.pnlUsd != null }
            .sortedBy { it.closedAt ?: it.openedAt }

        val wins = closedTrades.filter { (it.pnlUsd ?: 0.0) > 0.01 }
        val losses = closedTrades.filter { (it.pnlUsd ?: 0.0) < -0.01 }
        val breakEvens = closedTrades.filter { abs(it.pnlUsd ?: 0.0) <= 0.01 }

        val grossProfit = wins.sumOf { it.pnlUsd ?: 0.0 }
        val grossLoss = abs(losses.sumOf { it.pnlUsd ?: 0.0 })
        val netPnl = closedTrades.sumOf { it.pnlUsd ?: 0.0 }
        val totalFees = sortedAll.sumOf { it.totalRoundTripFeeUsd }

        val closedCount = closedTrades.size
        val winRate = if (closedCount > 0) (wins.size * 100.0) / closedCount else 0.0
        val profitFactor = when {
            grossLoss > 1e-6 -> grossProfit / grossLoss
            grossProfit > 0.0 -> 99.99
            else -> 0.0
        }

        val avgWinUsd = if (wins.isNotEmpty()) grossProfit / wins.size else 0.0
        val avgLossUsd = if (losses.isNotEmpty()) grossLoss / losses.size else 0.0
        val payoffRatio = if (avgLossUsd > 1e-6) avgWinUsd / avgLossUsd else 0.0

        val expectancyUsd = if (closedCount > 0) netPnl / closedCount else 0.0
        val rMultiples = closedTrades.map { it.rMultiple ?: ((it.pnlUsd ?: 0.0) / (it.fixedRiskUsd.takeIf { r -> r > 0.0 } ?: 5.0)) }
        val totalCumR = rMultiples.sum()
        val avgAchievedR = if (rMultiples.isNotEmpty()) rMultiples.average() else 0.0
        val expectancyR = avgAchievedR

        // Van Tharp SQN = sqrt(N) * (Mean(R) / StdDev(R))
        val stdDevR = if (rMultiples.size >= 2) {
            val mean = avgAchievedR
            val variance = rMultiples.sumOf { (it - mean) * (it - mean) } / (rMultiples.size - 1)
            sqrt(variance.coerceAtLeast(0.0))
        } else {
            0.0
        }
        val sqn = if (stdDevR > 1e-6 && rMultiples.size >= 2) {
            (sqrt(rMultiples.size.toDouble().coerceAtMost(100.0)) * (avgAchievedR / stdDevR)).coerceIn(-10.0, 25.0)
        } else if (closedCount == 1 && avgAchievedR > 0.0) {
            1.80
        } else {
            0.0
        }
        val sqnGradeFa = when {
            closedCount < 2 -> "در حال تکمیل نمونه آماری (${closedCount} معامله)"
            sqn >= 3.0 -> "🏆 استثنایی — گرید بانک سرمایه‌گذاری (Holy Grail / Institutional)"
            sqn >= 2.5 -> "🟢 عالی — سیستم معاملاتی بسیار قدرتمند (Excellent)"
            sqn >= 2.0 -> "🔵 خوب — دارای لبه ریاضی پایدار (Good Edge)"
            sqn >= 1.6 -> "🟡 قابل قبول — سودده استاندارد (Average)"
            else -> "🟠 نیازمند بهینه‌سازی یا نمونه بیشتر"
        }

        // Trade-level Sharpe & Sortino on R-multiples
        val sharpe = if (stdDevR > 1e-6) avgAchievedR / stdDevR else 0.0
        val downsideSquares = rMultiples.map { if (it < 0.0) it * it else 0.0 }
        val downsideDev = if (downsideSquares.isNotEmpty()) sqrt(downsideSquares.average()) else 0.0
        val sortino = if (downsideDev > 1e-6) avgAchievedR / downsideDev else if (avgAchievedR > 0.0) 5.0 else 0.0

        // Kelly Criterion % = W - (1 - W) / Payoff
        val winProb = winRate / 100.0
        val kellyPct = if (payoffRatio > 1e-6 && closedCount > 0) {
            ((winProb - ((1.0 - winProb) / payoffRatio)) * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        // Chronological Equity Curve & Max Drawdown
        val equityPoints = mutableListOf<EquityCurvePoint>()
        var runningPnl = 0.0
        var runningR = 0.0
        var peakEquity = safeInitial
        var maxDdUsd = 0.0
        var maxDdPct = 0.0

        // Point 0 (Baseline)
        equityPoints += EquityCurvePoint(
            stepIndex = 0,
            timestampMs = sortedAll.firstOrNull()?.openedAt ?: nowMillis,
            dateLabel = "شروع حساب",
            symbol = "INITIAL",
            strategyTitleFa = "سرمایه اولیه",
            tradePnlUsd = 0.0,
            tradeRMultiple = 0.0,
            cumulativePnlUsd = 0.0,
            cumulativeR = 0.0,
            equityUsd = r2(safeInitial),
            peakEquityUsd = r2(safeInitial),
            drawdownUsd = 0.0,
            drawdownPct = 0.0,
        )

        var maxWinStreak = 0
        var maxLossStreak = 0
        var curStreak = 0

        closedTrades.forEachIndexed { idx, t ->
            val pnl = t.pnlUsd ?: 0.0
            val rVal = rMultiples.getOrElse(idx) { 0.0 }
            runningPnl += pnl
            runningR += rVal
            val eq = safeInitial + runningPnl
            if (eq > peakEquity) peakEquity = eq
            val ddUsd = (peakEquity - eq).coerceAtLeast(0.0)
            val ddPct = if (peakEquity > 0.0) (ddUsd / peakEquity) * 100.0 else 0.0
            if (ddUsd > maxDdUsd) maxDdUsd = ddUsd
            if (ddPct > maxDdPct) maxDdPct = ddPct

            if (pnl > 0.01) {
                curStreak = if (curStreak >= 0) curStreak + 1 else 1
                if (curStreak > maxWinStreak) maxWinStreak = curStreak
            } else if (pnl < -0.01) {
                curStreak = if (curStreak <= 0) curStreak - 1 else -1
                if (abs(curStreak) > maxLossStreak) maxLossStreak = abs(curStreak)
            }

            equityPoints += EquityCurvePoint(
                stepIndex = idx + 1,
                timestampMs = t.closedAt ?: t.openedAt,
                dateLabel = formatTimestamp(t.closedAt ?: t.openedAt),
                symbol = t.symbol,
                strategyTitleFa = t.strategyTitleFa,
                tradePnlUsd = r2(pnl),
                tradeRMultiple = r2(rVal),
                cumulativePnlUsd = r2(runningPnl),
                cumulativeR = r2(runningR),
                equityUsd = r2(eq),
                peakEquityUsd = r2(peakEquity),
                drawdownUsd = r2(ddUsd),
                drawdownPct = r3(ddPct),
            )
        }

        val recoveryFactor = if (maxDdUsd > 1e-6) netPnl / maxDdUsd else if (netPnl > 0.0) 99.9 else 0.0
        val avgPlannedRr = if (sortedAll.isNotEmpty()) sortedAll.map { it.riskReward }.average() else 0.0
        val avgOptimalRr = if (sortedAll.isNotEmpty()) sortedAll.map { it.effectiveOptimalRr }.average() else 0.0
        val bestTradeUsd = closedTrades.maxOfOrNull { it.pnlUsd ?: 0.0 } ?: 0.0
        val worstTradeUsd = closedTrades.minOfOrNull { it.pnlUsd ?: 0.0 } ?: 0.0
        val bestTradeR = rMultiples.maxOrNull() ?: 0.0
        val worstTradeR = rMultiples.minOrNull() ?: 0.0

        val holdMinutesList = closedTrades.mapNotNull { t ->
            val closedAt = t.closedAt ?: return@mapNotNull null
            ((closedAt - t.openedAt).coerceAtLeast(1_000L)) / 60_000.0
        }
        val avgHoldMinutes = if (holdMinutesList.isNotEmpty()) holdMinutesList.average() else 0.0

        // Long vs Short breakdown
        val longClosed = closedTrades.filter { it.action == SignalAction.BUY }
        val longWins = longClosed.count { (it.pnlUsd ?: 0.0) > 0.01 }
        val longWinRate = if (longClosed.isNotEmpty()) (longWins * 100.0) / longClosed.size else 0.0
        val longNet = longClosed.sumOf { it.pnlUsd ?: 0.0 }

        val shortClosed = closedTrades.filter { it.action == SignalAction.SELL }
        val shortWins = shortClosed.count { (it.pnlUsd ?: 0.0) > 0.01 }
        val shortWinRate = if (shortClosed.isNotEmpty()) (shortWins * 100.0) / shortClosed.size else 0.0
        val shortNet = shortClosed.sumOf { it.pnlUsd ?: 0.0 }

        // 15m vs 5m Timeframe breakdown
        fun tfMetrics(tf: Interval): Triple<Int, Double, Pair<Double, Double>> {
            val tfClosed = closedTrades.filter { it.interval == tf }
            val tfW = tfClosed.filter { (it.pnlUsd ?: 0.0) > 0.01 }
            val tfL = tfClosed.filter { (it.pnlUsd ?: 0.0) < -0.01 }
            val wr = if (tfClosed.isNotEmpty()) (tfW.size * 100.0) / tfClosed.size else 0.0
            val gw = tfW.sumOf { it.pnlUsd ?: 0.0 }
            val gl = abs(tfL.sumOf { it.pnlUsd ?: 0.0 })
            val pf = if (gl > 1e-6) gw / gl else if (gw > 0.0) 99.9 else 0.0
            val net = tfClosed.sumOf { it.pnlUsd ?: 0.0 }
            return Triple(tfClosed.size, r2(wr), r2(pf) to r2(net))
        }
        val (tf15Cnt, tf15Wr, tf15PfNet) = tfMetrics(Interval.M15)
        val (tf5Cnt, tf5Wr, tf5PfNet) = tfMetrics(Interval.M5)

        // Strategy Leaderboard across all 15 built-in combinations + customs
        val strategyMeta = LinkedHashMap<String, Pair<String, String>>()
        BitcoinStrategyModel.entries.forEach { m ->
            strategyMeta[m.id] = m.titleFa to m.badgeFa
        }
        customStrategies.forEach { c ->
            strategyMeta[c.id] = c.titleFa to c.badgeFa
        }
        sortedAll.forEach { t ->
            if (t.strategyId !in strategyMeta) {
                strategyMeta[t.strategyId] = t.strategyTitleFa to t.strategyTitleFa.substringBefore('(').trim()
            }
        }

        val strategyLeaderboard = strategyMeta.entries.map { (sid, meta) ->
            val (titleFa, badgeFa) = meta
            val sAll = sortedAll.filter { it.strategyId == sid }
            val sClosed = sAll.filter { !it.isOpen && it.pnlUsd != null }
            val sOpen = sAll.count { it.isOpen }
            val sWins = sClosed.filter { (it.pnlUsd ?: 0.0) > 0.01 }
            val sLosses = sClosed.filter { (it.pnlUsd ?: 0.0) < -0.01 }
            val sGw = sWins.sumOf { it.pnlUsd ?: 0.0 }
            val sGl = abs(sLosses.sumOf { it.pnlUsd ?: 0.0 })
            val sNet = sClosed.sumOf { it.pnlUsd ?: 0.0 }
            val sPf = if (sGl > 1e-6) sGw / sGl else if (sGw > 0.0) 99.9 else 0.0
            val sWr = if (sClosed.isNotEmpty()) (sWins.size * 100.0) / sClosed.size else 0.0
            val sRList = sClosed.map { it.rMultiple ?: ((it.pnlUsd ?: 0.0) / (it.fixedRiskUsd.takeIf { r -> r > 0.0 } ?: 5.0)) }
            val sAvgR = if (sRList.isNotEmpty()) sRList.average() else 0.0
            StrategyTearSheetRow(
                strategyId = sid,
                strategyTitleFa = titleFa,
                strategyBadgeFa = badgeFa,
                totalTrades = sAll.size,
                closedTrades = sClosed.size,
                openTrades = sOpen,
                wins = sWins.size,
                losses = sLosses.size,
                winRatePct = r2(sWr),
                profitFactor = r2(sPf),
                expectancyUsd = r2(if (sClosed.isNotEmpty()) sNet / sClosed.size else 0.0),
                expectancyR = r2(sAvgR),
                avgPlannedRr = r2(if (sAll.isNotEmpty()) sAll.map { it.riskReward }.average() else 0.0),
                avgOptimalRr = r2(if (sAll.isNotEmpty()) sAll.map { it.effectiveOptimalRr }.average() else 0.0),
                avgAchievedR = r2(sAvgR),
                totalFeesUsd = r3(sAll.sumOf { it.totalRoundTripFeeUsd }),
                netPnlUsd = r2(sNet),
            )
        }.sortedWith(
            compareByDescending<StrategyTearSheetRow> { it.totalTrades > 0 }
                .thenByDescending { it.netPnlUsd }
                .thenByDescending { it.winRatePct }
        )

        // Symbol Leaderboard across all 15 symbols
        val symbolLeaderboard = V1Universe.allSymbols.map { sym ->
            val symAll = sortedAll.filter { V1Universe.normalize(it.symbol) == sym }
            val symClosed = symAll.filter { !it.isOpen && it.pnlUsd != null }
            val symOpen = symAll.count { it.isOpen }
            val symWins = symClosed.filter { (it.pnlUsd ?: 0.0) > 0.01 }
            val symLosses = symClosed.filter { (it.pnlUsd ?: 0.0) < -0.01 }
            val gw = symWins.sumOf { it.pnlUsd ?: 0.0 }
            val gl = abs(symLosses.sumOf { it.pnlUsd ?: 0.0 })
            val net = symClosed.sumOf { it.pnlUsd ?: 0.0 }
            val pf = if (gl > 1e-6) gw / gl else if (gw > 0.0) 99.9 else 0.0
            val wr = if (symClosed.isNotEmpty()) (symWins.size * 100.0) / symClosed.size else 0.0
            val rList = symClosed.map { it.rMultiple ?: ((it.pnlUsd ?: 0.0) / (it.fixedRiskUsd.takeIf { r -> r > 0.0 } ?: 5.0)) }
            val assetFa = when (AssetClass.of(sym)) {
                AssetClass.CRYPTO -> "رمزارز (Crypto)"
                AssetClass.FOREX -> "فارکس (Forex)"
                AssetClass.COMMODITY -> "فلزات گران‌بها (Metals)"
                AssetClass.STOCK -> "شاخص (Index)"
            }
            SymbolTearSheetRow(
                symbol = sym,
                assetClassLabelFa = assetFa,
                closedTrades = symClosed.size,
                openTrades = symOpen,
                wins = symWins.size,
                losses = symLosses.size,
                winRatePct = r2(wr),
                profitFactor = r2(pf),
                avgR = r2(if (rList.isNotEmpty()) rList.average() else 0.0),
                totalFeesUsd = r3(symAll.sumOf { it.totalRoundTripFeeUsd }),
                netPnlUsd = r2(net),
            )
        }.sortedWith(
            compareByDescending<SymbolTearSheetRow> { it.closedTrades + it.openTrades > 0 }
                .thenByDescending { it.netPnlUsd }
        )

        val totalReturnPct = ((currentBalanceUsd - safeInitial) / safeInitial) * 100.0

        return InstitutionalTearSheetReport(
            generatedAtMs = nowMillis,
            generatedAtFormatted = formatTimestamp(nowMillis),
            filterTitleFa = filterTitleFa,
            initialCapitalUsd = r2(safeInitial),
            currentBalanceUsd = r2(currentBalanceUsd),
            netPnlUsd = r2(netPnl),
            totalReturnPct = r3(totalReturnPct),
            grossProfitUsd = r2(grossProfit),
            grossLossUsd = r2(grossLoss),
            totalFeesAndSpreadUsd = r3(totalFees),
            totalTradesCount = sortedAll.size,
            closedTradesCount = closedCount,
            openTradesCount = openTrades.size,
            winsCount = wins.size,
            lossesCount = losses.size,
            breakEvenCount = breakEvens.size,
            winRatePct = r2(winRate),
            profitFactor = r2(profitFactor),
            payoffRatio = r2(payoffRatio),
            expectancyUsdPerTrade = r2(expectancyUsd),
            expectancyRPerTrade = r2(expectancyR),
            sqnScore = r2(sqn),
            sqnGradeFa = sqnGradeFa,
            sharpeRatio = r2(sharpe),
            sortinoRatio = r2(sortino),
            maxDrawdownUsd = r2(maxDdUsd),
            maxDrawdownPct = r3(maxDdPct),
            recoveryFactor = r2(recoveryFactor),
            kellyOptimalPct = r2(kellyPct),
            avgPlannedRr = r2(avgPlannedRr),
            avgOptimalRr = r2(avgOptimalRr),
            avgAchievedR = r2(avgAchievedR),
            totalCumulativeR = r2(totalCumR),
            avgWinUsd = r2(avgWinUsd),
            avgLossUsd = r2(avgLossUsd),
            bestTradeUsd = r2(bestTradeUsd),
            worstTradeUsd = r2(worstTradeUsd),
            bestTradeR = r2(bestTradeR),
            worstTradeR = r2(worstTradeR),
            maxConsecutiveWins = maxWinStreak,
            maxConsecutiveLosses = maxLossStreak,
            currentStreak = curStreak,
            avgHoldDurationMinutes = r2(avgHoldMinutes),
            longTradesCount = longClosed.size,
            longWinRatePct = r2(longWinRate),
            longNetPnlUsd = r2(longNet),
            shortTradesCount = shortClosed.size,
            shortWinRatePct = r2(shortWinRate),
            shortNetPnlUsd = r2(shortNet),
            tf15mTradesCount = tf15Cnt,
            tf15mWinRatePct = tf15Wr,
            tf15mProfitFactor = tf15PfNet.first,
            tf15mNetPnlUsd = tf15PfNet.second,
            tf5mTradesCount = tf5Cnt,
            tf5mWinRatePct = tf5Wr,
            tf5mProfitFactor = tf5PfNet.first,
            tf5mNetPnlUsd = tf5PfNet.second,
            strategyLeaderboard = strategyLeaderboard,
            symbolLeaderboard = symbolLeaderboard,
            equityCurve = equityPoints,
            trades = sortedAll.sortedByDescending { it.openedAt },
        )
    }

    /**
     * تولید خروجی CSV استاندارد با UTF-8 BOM (کاملاً سازگار با Microsoft Excel و Google Sheets برای متون فارسی و انگلیسی).
     */
    fun generateCsvContent(report: InstitutionalTearSheetReport): String {
        fun esc(raw: String): String {
            val cleaned = raw.replace("\r", " ").replace("\n", " ")
            return if (cleaned.contains(',') || cleaned.contains('"') || cleaned.contains(';')) {
                "\"${cleaned.replace("\"", "\"\"")}\""
            } else {
                cleaned
            }
        }

        val sb = StringBuilder()
        // UTF-8 BOM so Excel detects UTF-8 Persian & symbols automatically
        sb.append('\uFEFF')
        val headers = listOf(
            "Row",
            "TradeID",
            "Symbol",
            "AssetClass",
            "Direction",
            "Timeframe",
            "StrategyID",
            "StrategyTitleFa",
            "Status",
            "OpenedAt_Tehran",
            "ClosedAt_Tehran",
            "HoldMinutes",
            "EntryPrice",
            "StopLoss",
            "InitialStopLoss",
            "TakeProfit",
            "ExitPrice",
            "Quantity",
            "FixedRiskBudgetUsd",
            "PlannedRR",
            "OptimalRR",
            "AchievedRMultiple",
            "GrossPnlUsd",
            "VenueRoundTripFeeUsd",
            "NetPnlUsd",
            "DataSourceVenue",
            "ExitReason",
            "RRFormula",
            "Notes",
        )
        sb.append(headers.joinToString(",")).append("\r\n")

        report.trades.forEachIndexed { idx, t ->
            val holdMins = if (t.closedAt != null && t.closedAt > t.openedAt) {
                r2((t.closedAt - t.openedAt) / 60_000.0).toString()
            } else {
                ""
            }
            val achievedR = t.rMultiple ?: (t.pnlUsd?.let { it / (t.fixedRiskUsd.takeIf { r -> r > 0.0 } ?: 5.0) })
            val feeUsd = t.totalRoundTripFeeUsd
            val grossPnl = t.pnlUsd?.let { r2(it + feeUsd) }
            val spec = com.aurum.edge.core.VenueSpecs.of(t.symbol)
            val assetClass = AssetClass.of(t.symbol).name

            val row = listOf(
                (idx + 1).toString(),
                esc(t.id),
                esc(t.symbol),
                esc(assetClass),
                if (t.action == SignalAction.BUY) "LONG (BUY)" else "SHORT (SELL)",
                esc(t.interval.label),
                esc(t.strategyId),
                esc(t.strategyTitleFa),
                if (t.isOpen) "OPEN" else "CLOSED",
                esc(formatTimestamp(t.openedAt)),
                esc(formatTimestamp(t.closedAt)),
                holdMins,
                t.entry.toString(),
                t.stopLoss.toString(),
                (t.initialStopLoss ?: t.stopLoss).toString(),
                t.takeProfit.toString(),
                t.exitPrice?.toString().orEmpty(),
                t.positionOz.toString(),
                (t.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0).toString(),
                t.riskReward.toString(),
                t.effectiveOptimalRr.toString(),
                achievedR?.let { r2(it).toString() }.orEmpty(),
                grossPnl?.toString().orEmpty(),
                r3(feeUsd).toString(),
                t.pnlUsd?.let { r2(it).toString() }.orEmpty(),
                esc(spec.venue),
                esc(t.exitReason.orEmpty()),
                esc(t.buildRrFormulaSummaryFa()),
                esc(t.note),
            )
            sb.append(row.joinToString(",")).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * تولید خروجی JSON ساختاریافته کوانت برای تحلیل در پایتون، هوش مصنوعی یا نرم‌افزارهای آماری.
     */
    fun generateJsonContent(report: InstitutionalTearSheetReport): String =
        prettyJson.encodeToString(report)

    /**
     * تولید داشبورد تعاملی HTML5 در بالاترین سطح استاندارد جهانی (TradeZella / Bloomberg Quant Tear Sheet)
     * با قابلیت فیلتر و جستجوی زنده، نمودار SVG منحنی سرمایه، جدول ۱۵ استراتژی و امکان پرینت مستقیم.
     */
    fun generateInteractiveHtmlTearSheet(report: InstitutionalTearSheetReport): String {
        val points = report.equityCurve
        val minEq = points.minOfOrNull { it.equityUsd } ?: report.initialCapitalUsd
        val maxEq = points.maxOfOrNull { it.equityUsd } ?: report.initialCapitalUsd
        val eqSpan = max(1.0, maxEq - minEq)
        val svgW = 860.0
        val svgH = 220.0
        val polyPoints = if (points.size >= 2) {
            points.mapIndexed { i, p ->
                val x = (i.toDouble() / (points.size - 1).coerceAtLeast(1)) * (svgW - 40.0) + 20.0
                val y = svgH - 24.0 - ((p.equityUsd - minEq) / eqSpan) * (svgH - 48.0)
                String.format(Locale.US, "%.1f,%.1f", x, y)
            }.joinToString(" ")
        } else {
            "20.0,110.0 840.0,110.0"
        }

        val strategyRowsHtml = report.strategyLeaderboard.joinToString("\n") { s ->
            val pnlColor = if (s.netPnlUsd >= 0.0) "#089981" else "#f23645"
            val sign = if (s.netPnlUsd >= 0.0) "+" else "-"
            """<tr>
                <td style="font-weight:700;color:#f3c65f;">${s.strategyTitleFa}</td>
                <td>${s.totalTrades} (${s.closedTrades} بسته / ${s.openTrades} باز)</td>
                <td>${s.wins}W / ${s.losses}L</td>
                <td style="color:#38bdf8;font-weight:700;">${String.format(Locale.US, "%.1f%%", s.winRatePct)}</td>
                <td>${String.format(Locale.US, "%.2f", s.profitFactor)}</td>
                <td>1:${String.format(Locale.US, "%.2f", s.avgOptimalRr)}</td>
                <td>${String.format(Locale.US, "%+.2fR", s.avgAchievedR)}</td>
                <td>$${String.format(Locale.US, "%.3f", s.totalFeesUsd)}</td>
                <td style="font-weight:700;color:$pnlColor;">$sign$${String.format(Locale.US, "%,.2f", abs(s.netPnlUsd))}</td>
            </tr>"""
        }

        val symbolRowsHtml = report.symbolLeaderboard.filter { it.closedTrades + it.openTrades > 0 }.joinToString("\n") { sym ->
            val pnlColor = if (sym.netPnlUsd >= 0.0) "#089981" else "#f23645"
            val sign = if (sym.netPnlUsd >= 0.0) "+" else "-"
            """<tr>
                <td style="font-weight:700;color:#f3c65f;">${sym.symbol}</td>
                <td>${sym.assetClassLabelFa}</td>
                <td>${sym.closedTrades} بسته / ${sym.openTrades} باز</td>
                <td>${sym.wins}W / ${sym.losses}L</td>
                <td>${String.format(Locale.US, "%.1f%%", sym.winRatePct)}</td>
                <td>${String.format(Locale.US, "%.2f", sym.profitFactor)}</td>
                <td>${String.format(Locale.US, "%+.2fR", sym.avgR)}</td>
                <td>$${String.format(Locale.US, "%.3f", sym.totalFeesUsd)}</td>
                <td style="font-weight:700;color:$pnlColor;">$sign$${String.format(Locale.US, "%,.2f", abs(sym.netPnlUsd))}</td>
            </tr>"""
        }

        val tradeRowsHtml = report.trades.mapIndexed { idx, t ->
            val pnl = t.pnlUsd ?: 0.0
            val pnlColor = when {
                t.isOpen -> "#38bdf8"
                pnl >= 0.0 -> "#089981"
                else -> "#f23645"
            }
            val outcomeTag = when {
                t.isOpen -> "OPEN"
                pnl > 0.01 -> "WIN"
                pnl < -0.01 -> "LOSS"
                else -> "BE"
            }
            val achievedR = t.rMultiple ?: (t.pnlUsd?.let { it / (t.fixedRiskUsd.takeIf { r -> r > 0.0 } ?: 5.0) } ?: 0.0)
            val holdStr = if (t.closedAt != null && t.closedAt > t.openedAt) {
                String.format(Locale.US, "%.1f دقیقه", (t.closedAt - t.openedAt) / 60_000.0)
            } else {
                "در جریان"
            }
            val pnlStr = if (t.isOpen) "باز (شناور)" else {
                val s = if (pnl >= 0.0) "+" else "-"
                "$s$${String.format(Locale.US, "%.2f", abs(pnl))} (${String.format(Locale.US, "%+.2fR", achievedR)})"
            }
            """<tr class="trade-row" data-sym="${t.symbol}" data-tf="${t.interval.label}" data-outcome="$outcomeTag" data-strat="${t.strategyTitleFa}">
                <td>${idx + 1}</td>
                <td style="font-weight:700;color:#f3c65f;">${t.symbol}</td>
                <td style="color:${if (t.action == SignalAction.BUY) "#089981" else "#f23645"};font-weight:700;">${if (t.action == SignalAction.BUY) "BUY (LONG)" else "SELL (SHORT)"}</td>
                <td><span class="badge">${t.interval.label}</span></td>
                <td>${t.strategyTitleFa}</td>
                <td dir="ltr">${t.entry}</td>
                <td dir="ltr">${t.stopLoss}</td>
                <td dir="ltr">${t.takeProfit}</td>
                <td dir="ltr">${t.exitPrice ?: "—"}</td>
                <td>1:${String.format(Locale.US, "%.2f", t.riskReward)} (مناسب: 1:${String.format(Locale.US, "%.2f", t.effectiveOptimalRr)})</td>
                <td>$${String.format(Locale.US, "%.3f", t.totalRoundTripFeeUsd)}</td>
                <td style="font-weight:700;color:$pnlColor;" dir="ltr">$pnlStr</td>
                <td dir="ltr">${formatTimestamp(t.openedAt)}</td>
                <td>$holdStr</td>
                <td>${t.exitReason ?: t.note}</td>
            </tr>"""
        }.joinToString("\n")

        val netColor = if (report.netPnlUsd >= 0.0) "#089981" else "#f23645"
        val netSign = if (report.netPnlUsd >= 0.0) "+" else "-"

        return """<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Aurum Edge — Institutional Quant Tear Sheet (${report.generatedAtFormatted})</title>
<style>
  :root { --bg:#090c10; --card:#121721; --card2:#171e2b; --line:#263042; --gold:#f3c65f; --cyan:#38bdf8; --green:#089981; --red:#f23645; --text:#f3f4f6; --muted:#9ca3af; }
  * { box-sizing:border-box; margin:0; padding:0; }
  body { background:var(--bg); color:var(--text); font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Tahoma,sans-serif; padding:16px; line-height:1.55; }
  .header { background:linear-gradient(135deg,#161f2e,#0d121b); border:1px solid rgba(243,198,95,0.4); border-radius:16px; padding:18px; margin-bottom:16px; display:flex; flex-wrap:wrap; justify-content:space-between; align-items:center; gap:12px; }
  .title { font-size:20px; font-weight:800; color:var(--gold); }
  .subtitle { font-size:12px; color:var(--muted); margin-top:4px; }
  .kpi-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(155px,1fr)); gap:10px; margin-bottom:16px; }
  .kpi { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:12px; }
  .kpi-label { font-size:11px; color:var(--muted); }
  .kpi-val { font-size:17px; font-weight:800; margin-top:4px; direction:ltr; text-align:right; }
  .section { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:15px; margin-bottom:16px; overflow-x:auto; }
  .section-title { font-size:15px; font-weight:700; color:var(--gold); margin-bottom:10px; display:flex; justify-content:space-between; align-items:center; }
  table { width:100%; border-collapse:collapse; font-size:12px; min-width:720px; }
  th, td { padding:9px 10px; border-bottom:1px solid var(--line); text-align:right; }
  th { background:var(--card2); color:var(--cyan); font-weight:700; position:sticky; top:0; }
  tr:hover { background:rgba(56,189,248,0.06); }
  .badge { background:#1e293b; color:var(--cyan); padding:2px 7px; border-radius:6px; font-weight:700; font-size:11px; }
  .filter-bar { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:12px; align-items:center; }
  input, select, button { background:var(--card2); color:var(--text); border:1px solid var(--line); border-radius:8px; padding:7px 11px; font-size:12px; }
  button { background:var(--gold); color:#000; font-weight:700; cursor:pointer; border:none; }
  @media print { body { background:#fff; color:#000; } .no-print { display:none; } }
</style>
</head>
<body>
<div class="header">
  <div>
    <div class="title">📊 کارنامه کوانت و ژورنال معاملاتی Aurum Edge (Institutional Tear Sheet)</div>
    <div class="subtitle">بازه/فیلتر: ${report.filterTitleFa} · زمان تولید گزارش: ${report.generatedAtFormatted} · گرید سیستم (Van Tharp SQN): ${report.sqnGradeFa}</div>
  </div>
  <div class="no-print" style="display:flex;gap:8px;">
    <button onclick="window.print()">🖨️ چاپ / ذخیره PDF</button>
  </div>
</div>

<div class="kpi-grid">
  <div class="kpi"><div class="kpi-label">موجودی اولیه حساب</div><div class="kpi-val" style="color:var(--text);">$${String.format(Locale.US, "%,.2f", report.initialCapitalUsd)}</div></div>
  <div class="kpi"><div class="kpi-label">موجودی فعلی حساب</div><div class="kpi-val" style="color:var(--gold);">$${String.format(Locale.US, "%,.2f", report.currentBalanceUsd)}</div></div>
  <div class="kpi"><div class="kpi-label">سود/زیان خالص کل (Net PnL)</div><div class="kpi-val" style="color:$netColor;">$netSign$${String.format(Locale.US, "%,.2f", abs(report.netPnlUsd))} (${String.format(Locale.US, "%+.2f%%", report.totalReturnPct)})</div></div>
  <div class="kpi"><div class="kpi-label">مجموع ضریب R کسب‌شده</div><div class="kpi-val" style="color:$netColor;">${String.format(Locale.US, "%+.2f R", report.totalCumulativeR)}</div></div>
  <div class="kpi"><div class="kpi-label">نرخ برد (Win Rate)</div><div class="kpi-val" style="color:var(--gold);">${String.format(Locale.US, "%.1f%%", report.winRatePct)} (${report.winsCount}W / ${report.lossesCount}L)</div></div>
  <div class="kpi"><div class="kpi-label">پرافیت فاکتور (Profit Factor)</div><div class="kpi-val" style="color:var(--cyan);">${String.format(Locale.US, "%.2f", report.profitFactor)}</div></div>
  <div class="kpi"><div class="kpi-label">امید ریاضی هر معامله (Expectancy)</div><div class="kpi-val" style="color:$netColor;">$${String.format(Locale.US, "%+.2f", report.expectancyUsdPerTrade)} (${String.format(Locale.US, "%+.2fR", report.expectancyRPerTrade)})</div></div>
  <div class="kpi"><div class="kpi-label">امتیاز کیفیت سیستم (SQN)</div><div class="kpi-val" style="color:var(--gold);">${String.format(Locale.US, "%.2f", report.sqnScore)}</div></div>
  <div class="kpi"><div class="kpi-label">نسبت شارپ / سورتینو (Sharpe / Sortino)</div><div class="kpi-val" style="color:var(--cyan);">${String.format(Locale.US, "%.2f", report.sharpeRatio)} / ${String.format(Locale.US, "%.2f", report.sortinoRatio)}</div></div>
  <div class="kpi"><div class="kpi-label">حداکثر افت سرمایه (Max Drawdown)</div><div class="kpi-val" style="color:var(--red);">-$${String.format(Locale.US, "%.2f", report.maxDrawdownUsd)} (${String.format(Locale.US, "%.2f%%", report.maxDrawdownPct)})</div></div>
  <div class="kpi"><div class="kpi-label">ضریب بازیابی (Recovery Factor)</div><div class="kpi-val" style="color:var(--green);">${String.format(Locale.US, "%.2f", report.recoveryFactor)}</div></div>
  <div class="kpi"><div class="kpi-label">نسبت میانگین برد به باخت (Payoff)</div><div class="kpi-val" style="color:var(--gold);">${String.format(Locale.US, "%.2f", report.payoffRatio)} ($${String.format(Locale.US, "%.2f", report.avgWinUsd)} / -$${String.format(Locale.US, "%.2f", report.avgLossUsd)})</div></div>
  <div class="kpi"><div class="kpi-label">میانگین عدد مناسب R:R</div><div class="kpi-val" style="color:var(--cyan);">1:${String.format(Locale.US, "%.2f", report.avgOptimalRr)}</div></div>
  <div class="kpi"><div class="kpi-label">فرمول کلی بهینه (Kelly %)</div><div class="kpi-val" style="color:var(--green);">${String.format(Locale.US, "%.1f%%", report.kellyOptimalPct)}</div></div>
  <div class="kpi"><div class="kpi-label">عملکرد تایم 15m در برابر 5m</div><div class="kpi-val" style="color:var(--text);">15m: $${String.format(Locale.US, "%+.1f", report.tf15mNetPnlUsd)} (${String.format(Locale.US, "%.0f%%", report.tf15mWinRatePct)}) | 5m: $${String.format(Locale.US, "%+.1f", report.tf5mNetPnlUsd)} (${String.format(Locale.US, "%.0f%%", report.tf5mWinRatePct)})</div></div>
  <div class="kpi"><div class="kpi-label">کل کارمزد و اسپرد مرجع دیتا</div><div class="kpi-val" style="color:var(--muted);">$${String.format(Locale.US, "%.3f", report.totalFeesAndSpreadUsd)}</div></div>
</div>

<div class="section">
  <div class="section-title">
    <span>📈 منحنی رشد سرمایه (Equity Curve — از $${String.format(Locale.US, "%,.2f", minEq)} تا $${String.format(Locale.US, "%,.2f", maxEq)})</span>
    <span style="font-size:12px;color:var(--cyan);">بهترین معامله: +$${String.format(Locale.US, "%.2f", report.bestTradeUsd)} · بدترین معامله: -$${String.format(Locale.US, "%.2f", abs(report.worstTradeUsd))} · میانگین زمان معامله: ${String.format(Locale.US, "%.1f", report.avgHoldDurationMinutes)} دقیقه</span>
  </div>
  <svg viewBox="0 0 860 220" style="width:100%;height:200px;background:#0c111a;border-radius:10px;border:1px solid #1f2937;">
    <line x1="20" y1="110" x2="840" y2="110" stroke="#1e293b" stroke-dasharray="5,5" stroke-width="1"/>
    <polyline fill="none" stroke="$netColor" stroke-width="3" points="$polyPoints"/>
  </svg>
</div>

<div class="section">
  <div class="section-title"><span>🏆 رتبه‌بندی کامل ۱۵ ترکیب استراتژی ایچیموکو (Strategy Combination Leaderboard)</span></div>
  <table>
    <thead>
      <tr>
        <th>نام استراتژی / ترکیب</th>
        <th>تعداد معاملات</th>
        <th>برد / باخت</th>
        <th>نرخ برد</th>
        <th>پرافیت فاکتور</th>
        <th>میانگین عدد مناسب R:R</th>
        <th>میانگین R</th>
        <th>کارمزد کل</th>
        <th>سود/زیان خالص</th>
      </tr>
    </thead>
    <tbody>
      $strategyRowsHtml
    </tbody>
  </table>
</div>

<div class="section">
  <div class="section-title"><span>🌍 تفکیک عملکرد بر اساس نماد (طلا، نقره، کریپتو، فارکس و داوجونز)</span></div>
  <table>
    <thead>
      <tr>
        <th>نماد</th>
        <th>دسته بازار</th>
        <th>وضعیت معاملات</th>
        <th>برد / باخت</th>
        <th>نرخ برد</th>
        <th>پرافیت فاکتور</th>
        <th>میانگین R</th>
        <th>کارمزد مرجع دیتا</th>
        <th>سود/زیان خالص</th>
      </tr>
    </thead>
    <tbody>
      $symbolRowsHtml
    </tbody>
  </table>
</div>

<div class="section">
  <div class="section-title"><span>📜 جدول ممیزی تک‌تک معاملات (Trade-by-Trade Institutional Audit Log)</span></div>
  <div class="filter-bar no-print">
    <input type="text" id="qInput" placeholder="🔍 جستجوی نماد، استراتژی یا یادداشت..." oninput="filterRows()">
    <select id="tfSelect" onchange="filterRows()">
      <option value="ALL">همه تایم‌فریم‌ها (15m + 5m)</option>
      <option value="15m">تایم‌فریم 15m</option>
      <option value="5m">تایم‌فریم 5m</option>
    </select>
    <select id="outSelect" onchange="filterRows()">
      <option value="ALL">همه نتایج (برد، باخت، باز)</option>
      <option value="WIN">فقط بردها (WIN)</option>
      <option value="LOSS">فقط باخت‌ها (LOSS)</option>
      <option value="OPEN">فقط معاملات باز (OPEN)</option>
    </select>
  </div>
  <table id="tradesTable">
    <thead>
      <tr>
        <th>#</th>
        <th>نماد</th>
        <th>جهت</th>
        <th>تایم‌فریم</th>
        <th>استراتژی</th>
        <th>ورود (Entry)</th>
        <th>حد ضرر (SL)</th>
        <th>حد سود (TP)</th>
        <th>خروج</th>
        <th>R:R و عدد مناسب</th>
        <th>کارمزد</th>
        <th>نتیجه دلاری و R</th>
        <th>زمان باز شدن</th>
        <th>مدت زمان</th>
        <th>دلیل خروج / جزئیات</th>
      </tr>
    </thead>
    <tbody>
      $tradeRowsHtml
    </tbody>
  </table>
</div>

<script>
function filterRows() {
  const q = (document.getElementById('qInput').value || '').toLowerCase();
  const tf = document.getElementById('tfSelect').value;
  const out = document.getElementById('outSelect').value;
  document.querySelectorAll('.trade-row').forEach(row => {
    const text = row.innerText.toLowerCase();
    const rTf = row.getAttribute('data-tf');
    const rOut = row.getAttribute('data-outcome');
    const okQ = !q || text.includes(q);
    const okTf = tf === 'ALL' || rTf === tf;
    const okOut = out === 'ALL' || rOut === out;
    row.style.display = (okQ && okTf && okOut) ? '' : 'none';
  });
}
</script>
</body>
</html>"""
    }

    /**
     * تولید فایل PDF رسمی چندصفحه‌ای با گرافیک برداری (PdfDocument) شامل کارنامه کوانت، منحنی سرمایه،
     * جدول کامل معاملات با حد ضرر ثابت و صفحات اسکرین‌شات آرشیو تصویری ایچیموکو برای تمام معاملات.
     */
    fun writePdfTearSheet(
        report: InstitutionalTearSheetReport,
        outputFile: File,
        context: Context? = null,
    ) {
        if (context != null && report.trades.isNotEmpty()) {
            runCatching {
                TradeSnapshotArchiveManager.ensureAllTradesHaveArchiveSnapshots(context, report.trades)
            }
        }
        val doc = PdfDocument()
        try {
            val pageW = 842 // A4 Landscape width in points
            val pageH = 595 // A4 Landscape height in points

            val bgPaint = Paint().apply { color = Color.rgb(11, 15, 23) }
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 27, 39) }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(55, 70, 95)
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(243, 198, 95)
                textSize = 16f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(160, 174, 192)
                textSize = 10f
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(156, 163, 175)
                textSize = 9.5f
            }
            val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(230, 235, 242)
                textSize = 9f
            }
            val headerCellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(56, 189, 248)
                textSize = 9.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }

            // ── Page 1: Executive Quant Summary + Equity Curve + Strategy Leaderboard ──
            val page1Info = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
            val page1 = doc.startPage(page1Info)
            val c1: Canvas = page1.canvas
            c1.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)

            // Header Banner
            c1.drawRoundRect(RectF(24f, 20f, pageW - 24f, 68f), 10f, 10f, cardPaint)
            c1.drawText("AURUM EDGE — INSTITUTIONAL QUANT TEAR SHEET & VISUAL SNAPSHOT ARCHIVE", 38f, 42f, titlePaint)
            c1.drawText(
                "Generated: ${report.generatedAtFormatted}  |  Balance: $${String.format(Locale.US, "%,.2f", report.currentBalanceUsd)}  |  Policy: Fixed Non-Stepped SL & Locked Initial R:R",
                38f,
                58f,
                subPaint,
            )

            // 12 KPI Boxes (2 rows x 6 columns)
            val kpis = listOf(
                "Net PnL ($)" to String.format(Locale.US, "%+.2f$ (%+.2f%%)", report.netPnlUsd, report.totalReturnPct),
                "Win Rate" to String.format(Locale.US, "%.1f%% (%dW/%dL)", report.winRatePct, report.winsCount, report.lossesCount),
                "Profit Factor" to String.format(Locale.US, "%.2f", report.profitFactor),
                "Expectancy / Trade" to String.format(Locale.US, "%+.2f$ (%+.2fR)", report.expectancyUsdPerTrade, report.expectancyRPerTrade),
                "SQN / Sharpe / Sortino" to String.format(Locale.US, "%.2f / %.2f / %.2f", report.sqnScore, report.sharpeRatio, report.sortinoRatio),
                "Max Drawdown" to String.format(Locale.US, "-$%.2f (%.2f%%)", report.maxDrawdownUsd, report.maxDrawdownPct),
                "Total Cumulative R" to String.format(Locale.US, "%+.2f R", report.totalCumulativeR),
                "Payoff Ratio" to String.format(Locale.US, "%.2f ($%.2f/-$%.2f)", report.payoffRatio, report.avgWinUsd, report.avgLossUsd),
                "Avg Optimal R:R" to String.format(Locale.US, "1:%.2f", report.avgOptimalRr),
                "15m vs 5m Net PnL" to String.format(Locale.US, "15m: %+.1f$ | 5m: %+.1f$", report.tf15mNetPnlUsd, report.tf5mNetPnlUsd),
                "Long vs Short Net" to String.format(Locale.US, "L: %+.1f$ | S: %+.1f$", report.longNetPnlUsd, report.shortNetPnlUsd),
                "Venue Fees Paid" to String.format(Locale.US, "$%.3f (%d trades)", report.totalFeesAndSpreadUsd, report.totalTradesCount),
            )
            val boxW = (pageW - 48f - 50f) / 6f
            val boxH = 44f
            kpis.forEachIndexed { i, (lbl, v) ->
                val row = i / 6
                val col = i % 6
                val left = 24f + col * (boxW + 10f)
                val top = 78f + row * (boxH + 8f)
                val rect = RectF(left, top, left + boxW, top + boxH)
                c1.drawRoundRect(rect, 8f, 8f, cardPaint)
                c1.drawRoundRect(rect, 8f, 8f, borderPaint)
                c1.drawText(lbl, left + 8f, top + 16f, labelPaint)
                c1.drawText(v, left + 8f, top + 34f, valPaint)
            }

            // Equity Curve Vector Chart
            val chartRect = RectF(24f, 184f, pageW - 24f, 310f)
            c1.drawRoundRect(chartRect, 8f, 8f, cardPaint)
            c1.drawRoundRect(chartRect, 8f, 8f, borderPaint)
            c1.drawText("Equity Curve ($)", 36f, 202f, headerCellPaint)
            val pts = report.equityCurve
            if (pts.size >= 2) {
                val minEq = pts.minOf { it.equityUsd }
                val maxEq = pts.maxOf { it.equityUsd }
                val span = max(1.0, maxEq - minEq)
                val path = Path()
                pts.forEachIndexed { idx, p ->
                    val px = 38f + (idx.toFloat() / (pts.size - 1).coerceAtLeast(1)) * (chartRect.width() - 28f)
                    val py = chartRect.bottom - 14f - (((p.equityUsd - minEq) / span).toFloat() * (chartRect.height() - 38f))
                    if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (report.netPnlUsd >= 0.0) Color.rgb(8, 153, 129) else Color.rgb(242, 54, 69)
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                }
                c1.drawPath(path, linePaint)
            }

            // Top Strategy Combinations Summary on Page 1
            c1.drawText("Strategy Combination Performance Leaderboard (Top 10 Active):", 24f, 332f, titlePaint)
            var sy = 352f
            c1.drawText("Strategy ID / Title", 28f, sy, headerCellPaint)
            c1.drawText("Trades (W/L)", 290f, sy, headerCellPaint)
            c1.drawText("Win Rate", 390f, sy, headerCellPaint)
            c1.drawText("Profit Factor", 470f, sy, headerCellPaint)
            c1.drawText("Optimal R:R", 560f, sy, headerCellPaint)
            c1.drawText("Avg R", 645f, sy, headerCellPaint)
            c1.drawText("Net PnL ($)", 725f, sy, headerCellPaint)
            sy += 16f
            report.strategyLeaderboard.take(12).forEach { s ->
                c1.drawText(s.strategyId.take(34), 28f, sy, cellPaint)
                c1.drawText("${s.closedTrades} (${s.wins}W/${s.losses}L)", 290f, sy, cellPaint)
                c1.drawText(String.format(Locale.US, "%.1f%%", s.winRatePct), 390f, sy, cellPaint)
                c1.drawText(String.format(Locale.US, "%.2f", s.profitFactor), 470f, sy, cellPaint)
                c1.drawText(String.format(Locale.US, "1:%.2f", s.avgOptimalRr), 560f, sy, cellPaint)
                c1.drawText(String.format(Locale.US, "%+.2fR", s.avgAchievedR), 645f, sy, cellPaint)
                c1.drawText(String.format(Locale.US, "%+.2f$", s.netPnlUsd), 725f, sy, cellPaint)
                sy += 16f
            }
            doc.finishPage(page1)

            // ── Subsequent Pages: Trade-by-Trade Audit Log (24 trades per page) ──
            val chunks = report.trades.chunked(24).ifEmpty { listOf(emptyList()) }
            var currentPageNumber = 2
            chunks.forEachIndexed { pageIdx, batch ->
                val pInfo = PdfDocument.PageInfo.Builder(pageW, pageH, currentPageNumber++).create()
                val page = doc.startPage(pInfo)
                val c = page.canvas
                c.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)
                c.drawText("Trade-by-Trade Institutional Audit Log (Fixed Non-Stepped SL) — Table ${pageIdx + 1} of ${chunks.size}", 24f, 32f, titlePaint)

                var y = 56f
                c.drawText("#", 24f, y, headerCellPaint)
                c.drawText("Symbol", 52f, y, headerCellPaint)
                c.drawText("Side / TF", 125f, y, headerCellPaint)
                c.drawText("Strategy ID", 195f, y, headerCellPaint)
                c.drawText("Entry", 345f, y, headerCellPaint)
                c.drawText("Fixed SL", 415f, y, headerCellPaint)
                c.drawText("TP", 485f, y, headerCellPaint)
                c.drawText("Exit", 555f, y, headerCellPaint)
                c.drawText("Init R:R", 625f, y, headerCellPaint)
                c.drawText("Fee ($)", 680f, y, headerCellPaint)
                c.drawText("Net PnL ($)", 738f, y, headerCellPaint)
                y += 18f

                batch.forEach { t ->
                    val side = if (t.action == SignalAction.BUY) "BUY" else "SELL"
                    val fixedSl = t.initialStopLoss ?: t.stopLoss
                    val pnlText = if (t.isOpen) "OPEN" else String.format(Locale.US, "%+.2f$", t.pnlUsd ?: 0.0)
                    c.drawText("#${t.displayTradeNumber}", 24f, y, cellPaint)
                    c.drawText(t.symbol, 52f, y, cellPaint)
                    c.drawText("$side ${t.interval.label}", 125f, y, cellPaint)
                    c.drawText(t.strategyId.take(22), 195f, y, cellPaint)
                    c.drawText("${t.entry}", 345f, y, cellPaint)
                    c.drawText("$fixedSl", 415f, y, cellPaint)
                    c.drawText("${t.takeProfit}", 485f, y, cellPaint)
                    c.drawText(t.exitPrice?.toString() ?: "—", 555f, y, cellPaint)
                    c.drawText(String.format(Locale.US, "1:%.2f", t.riskReward), 625f, y, cellPaint)
                    c.drawText(String.format(Locale.US, "$%.3f", t.totalRoundTripFeeUsd), 680f, y, cellPaint)
                    c.drawText(pnlText, 738f, y, cellPaint)
                    y += 19f
                }
                doc.finishPage(page)
            }

            // ── Visual Archive Screenshot Pages: Ichimoku Chart Snapshot for Every Trade ──
            val sortedTradesForSnapshots = report.trades.sortedBy { it.displayTradeNumber }
            val tradeBasedSnapshots = sortedTradesForSnapshots.flatMap { t ->
                val existing = TradeSnapshotArchiveManager.recordsForTrade(t.id)
                if (existing.isNotEmpty()) {
                    existing
                } else {
                    buildList {
                        add(TradeSnapshotArchiveManager.buildSyntheticSnapshotRecordForTrade(t, isClosePhase = false))
                        if (!t.isOpen) {
                            add(TradeSnapshotArchiveManager.buildSyntheticSnapshotRecordForTrade(t, isClosePhase = true))
                        }
                    }
                }
            }
            val snapshotRecords = if (tradeBasedSnapshots.isNotEmpty()) {
                tradeBasedSnapshots
            } else {
                TradeSnapshotArchiveManager.records.value.sortedBy { it.displayTradeNumber }
            }
            val snapChunks = snapshotRecords.chunked(2)
            snapChunks.forEachIndexed { idx, pair ->
                val pInfo = PdfDocument.PageInfo.Builder(pageW, pageH, currentPageNumber++).create()
                val page = doc.startPage(pInfo)
                val c = page.canvas
                val bitmapsToRecycle = mutableListOf<android.graphics.Bitmap>()
                c.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)
                c.drawText(
                    "Visual Ichimoku Archive Screenshots — Sheet ${idx + 1} of ${snapChunks.size} (${snapshotRecords.size} Total Snapshots)",
                    24f,
                    28f,
                    titlePaint,
                )
                pair.forEachIndexed { slotIdx, rec ->
                    val slotLeft = if (slotIdx == 0) 20f else 426f
                    val slotRight = if (slotIdx == 0) 416f else 822f
                    val cardRect = RectF(slotLeft, 40f, slotRight, pageH - 18f)
                    c.drawRoundRect(cardRect, 10f, 10f, cardPaint)
                    c.drawRoundRect(cardRect, 10f, 10f, borderPaint)

                    val phaseTag = if (rec.isCloseSnapshot) "CLOSE" else "OPEN"
                    val pnlTag = if (rec.isCloseSnapshot && rec.realizedPnlUsd != null) {
                        String.format(Locale.US, " | PnL: %+.2f$ (%+.2fR)", rec.realizedPnlUsd, rec.realizedRMultiple ?: 0.0)
                    } else {
                        String.format(Locale.US, " | Risk: -$%.2f -> Target: +$%.2f", rec.fixedRiskUsd, rec.expectedProfitUsd)
                    }
                    c.drawText(
                        "Trade #${rec.displayTradeNumber} [$phaseTag] · ${rec.symbol} (${rec.timeframeLabel}) · ${rec.action}",
                        slotLeft + 10f,
                        58f,
                        headerCellPaint,
                    )
                    c.drawText(
                        "Entry: ${rec.entryPrice} | Fixed SL: ${rec.stopLossPrice} | TP: ${rec.takeProfitPrice} | R:R 1:${String.format(Locale.US, "%.2f", rec.riskReward.coerceIn(1.5, 3.0))}$pnlTag",
                        slotLeft + 10f,
                        73f,
                        cellPaint,
                    )
                    c.drawText(
                        "Strategy: ${rec.strategyTitleFa} (${rec.strategyId})",
                        slotLeft + 10f,
                        87f,
                        subPaint,
                    )

                    val bmp = runCatching {
                        TradeSnapshotArchiveManager.loadOrRenderSnapshotBitmap(rec)
                    }.getOrNull()
                    if (bmp != null && !bmp.isRecycled) {
                        val dstRect = RectF(slotLeft + 8f, 96f, slotRight - 8f, pageH - 26f)
                        c.drawBitmap(bmp, null, dstRect, null)
                        bitmapsToRecycle += bmp
                    }
                }
                doc.finishPage(page)
                bitmapsToRecycle.forEach { b -> runCatching { b.recycle() } }
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                doc.writeTo(out)
            }
        } catch (_: Throwable) {
            writePortablePdfFallback(report, outputFile)
        } finally {
            runCatching { doc.close() }
        }
    }

    private fun writePortablePdfFallback(
        report: InstitutionalTearSheetReport,
        outputFile: File,
    ) {
        outputFile.parentFile?.mkdirs()
        val lines = mutableListOf<String>()
        lines += "AURUM EDGE v3.9.24 - INSTITUTIONAL TEAR SHEET & VISUAL ARCHIVE PDF"
        lines += "Generated: ${report.generatedAtFormatted} | Policy: Fixed Non-Stepped SL | Preserved Initial R:R"
        lines += String.format(
            Locale.US,
            "Balance: $%,.2f | Net PnL: %s$%,.2f | Win Rate: %.1f%% (%dW/%dL) | PF: %.2f | SQN: %.2f",
            report.currentBalanceUsd,
            if (report.netPnlUsd >= 0) "+" else "-",
            abs(report.netPnlUsd),
            report.winRatePct,
            report.winsCount,
            report.lossesCount,
            report.profitFactor,
            report.sqnScore,
        )
        lines += "----------------------------------------------------------------------------------------"
        val tradeIds = report.trades.map { it.id }.toSet()
        val snapshots = TradeSnapshotArchiveManager.records.value.filter { it.tradeId in tradeIds }
        report.trades.forEachIndexed { idx, t ->
            val fixedSl = t.initialStopLoss ?: t.stopLoss
            val openSnap = snapshots.firstOrNull { it.tradeId == t.id && !it.isCloseSnapshot }
            val closeSnap = snapshots.firstOrNull { it.tradeId == t.id && it.isCloseSnapshot }
            lines += String.format(
                Locale.US,
                "#%d | %s (%s) | %s | Entry: %.2f | Fixed SL: %.2f | TP: %.2f | Init R:R: 1:%.2f | PnL: %s | Archive: OPEN=%s CLOSE=%s",
                t.tradeNumber.takeIf { it > 0 } ?: (idx + 1),
                t.symbol,
                t.interval.label,
                t.action.name,
                t.entry,
                fixedSl,
                t.takeProfit,
                t.riskReward,
                t.pnlUsd?.let { String.format(Locale.US, "%s$%.2f", if (it >= 0) "+" else "-", abs(it)) } ?: "OPEN",
                openSnap?.localImagePath?.substringAfterLast('/') ?: "GENERATED",
                closeSnap?.localImagePath?.substringAfterLast('/') ?: if (t.isOpen) "PENDING" else "GENERATED",
            )
        }
        val streamContent = buildString {
            append("BT\n/F1 10 Tf\n36 550 Td\n14 TL\n")
            lines.take(36).forEach { line ->
                val escaped = line
                    .replace("\\", "\\\\")
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                append("($escaped) Tj T*\n")
            }
            append("ET\n")
        }
        val pdfText = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
            append("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n")
            append("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj\n")
            append("4 0 obj << /Length ${streamContent.toByteArray(Charsets.US_ASCII).size} >>\nstream\n")
            append(streamContent)
            append("endstream\nendobj\n")
            append("5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n")
            append("trailer << /Root 1 0 R >>\n%%EOF\n")
        }
        outputFile.writeText(pdfText, Charsets.US_ASCII)
    }

    fun suggestedFileName(
        report: InstitutionalTearSheetReport,
        format: JournalExportFormat,
    ): String {
        val stamp = fileStampFmt.format(Instant.ofEpochMilli(report.generatedAtMs))
        return "AurumEdge_Journal_${format.id.uppercase(Locale.ROOT)}_$stamp.${format.extension}"
    }

    /**
     * ذخیره فایل خروجی در دایرکتوری `filesDir/exports/` و بازگرداندن مسیر آن برای اشتراک‌گذاری، مشاهده یا ذخیره.
     */
    fun exportToFile(
        context: Context,
        report: InstitutionalTearSheetReport,
        format: JournalExportFormat,
    ): File {
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val fileName = suggestedFileName(report, format)
        val outFile = File(exportDir, fileName)
        when (format) {
            JournalExportFormat.HTML_TEAR_SHEET -> {
                outFile.writeText(generateInteractiveHtmlTearSheet(report), Charsets.UTF_8)
            }
            JournalExportFormat.PDF_REPORT -> {
                writePdfTearSheet(report, outFile, context)
            }
            JournalExportFormat.CSV_EXCEL -> {
                outFile.writeText(generateCsvContent(report), Charsets.UTF_8)
            }
            JournalExportFormat.JSON_QUANT -> {
                outFile.writeText(generateJsonContent(report), Charsets.UTF_8)
            }
        }
        return outFile
    }

    /**
     * ذخیره تضمین‌شده بایت‌های فایل در حافظه گوشی (`Downloads/AurumEdge` با فال‌بک خودکار به `Documents/AurumEdge` و `ExternalFilesDir`).
     */
    fun saveBytesToPhoneStorage(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val tryDownloads = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AurumEdge")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("URI null")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: error("OutputStream null")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/AurumEdge/$fileName"
            }.getOrNull()
            if (tryDownloads != null) return tryDownloads

            val tryDocs = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/AurumEdge")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: error("URI null")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: error("OutputStream null")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Documents/AurumEdge/$fileName"
            }.getOrNull()
            if (tryDocs != null) return tryDocs
        }

        return runCatching {
            @Suppress("DEPRECATION")
            val pubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AurumEdge",
            )
            val targetDir = if (pubDir.exists() || pubDir.mkdirs()) {
                pubDir
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AurumEdge").apply { mkdirs() }
            }
            val dest = File(targetDir, fileName)
            dest.writeBytes(bytes)
            dest.absolutePath
        }.getOrElse {
            val fallbackDir = File(context.filesDir, "exports").apply { mkdirs() }
            val dest = File(fallbackDir, fileName)
            dest.writeBytes(bytes)
            dest.absolutePath
        }
    }

    /**
     * ذخیره فوری و مستقیم در پوشه `Downloads/AurumEdge` حافظه گوشی بدون نیاز به مجوز دستی در اندروید ۱۰ به بالا.
     */
    fun saveDirectToDownloads(
        context: Context,
        report: InstitutionalTearSheetReport,
        format: JournalExportFormat,
    ): Result<String> = runCatching {
        val localFile = exportToFile(context, report, format)
        val bytes = localFile.readBytes()
        saveBytesToPhoneStorage(context, localFile.name, format.mimeType, bytes)
    }

    /**
     * تولید و ذخیره مستقیم فایل PDF اختصاصی «آرشیو اسکرین‌شات تمام معاملات (باز و بسته)» با کیفیت بالا و حجم بهینه.
     */
    fun exportArchiveScreenshotsPdf(
        context: Context,
        trades: List<PaperTrade>,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<Pair<File, String>> = runCatching {
        if (trades.isNotEmpty()) {
            runCatching {
                TradeSnapshotArchiveManager.ensureAllTradesHaveArchiveSnapshots(
                    context = context,
                    trades = trades,
                    maxTrades = 120,
                    saveToPhoneGallery = false,
                )
            }
        }
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val stamp = fileStampFmt.format(Instant.ofEpochMilli(nowMs))
        val outFile = File(exportDir, "AurumEdge_Archive_Screenshots_$stamp.pdf")

        val allArchiveRecs = TradeSnapshotArchiveManager.records.value.sortedBy { it.displayTradeNumber }
        val tradeFallbackRecs = trades.sortedBy { it.displayTradeNumber }.flatMap { t ->
            val existing = allArchiveRecs.filter { it.tradeId == t.id }
            if (existing.isNotEmpty()) {
                existing
            } else {
                buildList {
                    add(TradeSnapshotArchiveManager.buildSyntheticSnapshotRecordForTrade(t, isClosePhase = false))
                    if (!t.isOpen) {
                        add(TradeSnapshotArchiveManager.buildSyntheticSnapshotRecordForTrade(t, isClosePhase = true))
                    }
                }
            }
        }
        val mergedSnapshots = (tradeFallbackRecs + allArchiveRecs)
            .distinctBy { it.snapshotId }
            .sortedWith(compareBy<ArchivedTradeSnapshotRecord> { it.displayTradeNumber }.thenBy { it.isCloseSnapshot })

        val doc = PdfDocument()
        try {
            val pageW = 842
            val pageH = 595
            val bgPaint = Paint().apply { color = Color.rgb(11, 15, 23) }
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 25, 38) }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(243, 198, 95)
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(243, 198, 95)
                textSize = 15f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(56, 189, 248)
                textSize = 10.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(235, 240, 248)
                textSize = 9.5f
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(160, 174, 192)
                textSize = 8.8f
            }

            if (mergedSnapshots.isEmpty()) {
                val pInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                val page = doc.startPage(pInfo)
                val c = page.canvas
                c.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)
                c.drawRoundRect(RectF(28f, 28f, pageW - 28f, 120f), 12f, 12f, cardPaint)
                c.drawText("AURUM EDGE — TRADE SNAPSHOT ARCHIVE PDF REPORT", 44f, 60f, titlePaint)
                c.drawText("Generated: ${formatTimestamp(nowMs)} | No archived trade snapshots yet — open a trade to capture its Ichimoku snapshot.", 44f, 88f, textPaint)
                doc.finishPage(page)
            } else {
                val chunks = mergedSnapshots.chunked(2)
                chunks.forEachIndexed { pageIdx, pair ->
                    val pInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageIdx + 1).create()
                    val page = doc.startPage(pInfo)
                    val c = page.canvas
                    val bitmapsOnPage = mutableListOf<android.graphics.Bitmap>()
                    c.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)
                    c.drawText(
                        "AURUM EDGE — ARCHIVE SCREENSHOTS PDF (${mergedSnapshots.size} Snapshots) · Page ${pageIdx + 1} of ${chunks.size} · Generated: ${formatTimestamp(nowMs)}",
                        24f,
                        26f,
                        titlePaint,
                    )
                    pair.forEachIndexed { slotIdx, rec ->
                        val slotLeft = if (slotIdx == 0) 20f else 426f
                        val slotRight = if (slotIdx == 0) 416f else 822f
                        val cardRect = RectF(slotLeft, 36f, slotRight, pageH - 16f)
                        c.drawRoundRect(cardRect, 10f, 10f, cardPaint)
                        c.drawRoundRect(cardRect, 10f, 10f, borderPaint)

                        val phaseTag = if (rec.isCloseSnapshot) "CLOSED" else "OPENED"
                        val clampedRr = rec.riskReward.coerceIn(1.50, 3.00)
                        val pnlStr = if (rec.isCloseSnapshot && rec.realizedPnlUsd != null) {
                            String.format(Locale.US, " | Net PnL: %+.2f$ (%+.2fR)", rec.realizedPnlUsd, (rec.realizedRMultiple ?: 0.0).coerceIn(-1.5, 3.0))
                        } else {
                            String.format(Locale.US, " | Fixed Risk: -$%.2f | Target: +$%.2f", rec.fixedRiskUsd, rec.expectedProfitUsd)
                        }
                        c.drawText(
                            "Trade #${rec.displayTradeNumber} [$phaseTag] · ${rec.symbol} (${rec.timeframeLabel}) · ${rec.action}",
                            slotLeft + 10f,
                            54f,
                            headerPaint,
                        )
                        c.drawText(
                            "Entry: ${rec.entryPrice} | Structural SL: ${rec.stopLossPrice} | TP: ${rec.takeProfitPrice} | R:R 1:${String.format(Locale.US, "%.2f", clampedRr)}$pnlStr",
                            slotLeft + 10f,
                            69f,
                            textPaint,
                        )
                        c.drawText(
                            "Strategy: ${rec.strategyTitleFa} (${rec.strategyId}) | Pre-3C: ${rec.preEntry3CandleCategoryFa}",
                            slotLeft + 10f,
                            83f,
                            subPaint,
                        )

                        val bmp = runCatching {
                            TradeSnapshotArchiveManager.loadOrRenderSnapshotBitmap(rec)
                        }.getOrNull()
                        if (bmp != null && !bmp.isRecycled) {
                            val dstRect = RectF(slotLeft + 8f, 92f, slotRight - 8f, pageH - 24f)
                            c.drawBitmap(bmp, null, dstRect, null)
                            bitmapsOnPage += bmp
                        }
                    }
                    doc.finishPage(page)
                    bitmapsOnPage.forEach { b -> runCatching { b.recycle() } }
                }
            }

            FileOutputStream(outFile).use { out -> doc.writeTo(out) }
        } catch (_: Throwable) {
            val dummyReport = buildInstitutionalTearSheet(trades, nowMs = nowMs)
            writePortablePdfFallback(dummyReport, outFile)
        } finally {
            runCatching { doc.close() }
        }

        val savedPath = saveBytesToPhoneStorage(
            context = context,
            fileName = outFile.name,
            mimeType = JournalExportFormat.PDF_REPORT.mimeType,
            bytes = outFile.readBytes(),
        )
        outFile to savedPath
    }

    /**
     * تولید فایل PDF جامع «تمام دیتاهای هوش مصنوعی، شبکه عصبی خودآموز و رگرسیون ماتریسی پارامترها»
     * (`AurumEdge_AI_Neural_Full_Report_<stamp>.pdf`) و ذخیره مستقیم در حافظه گوشی (`Downloads/AurumEdge`).
     */
    fun exportFullAiAndNeuralReportPdf(
        context: Context,
        neuralSummary: NeuralLearningStateSummary,
        lastAiVerdict: AiTradeVerdict?,
        trades: List<PaperTrade>,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<Pair<File, String>> = runCatching {
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val stamp = fileStampFmt.format(Instant.ofEpochMilli(nowMs))
        val outFile = File(exportDir, "AurumEdge_AI_Neural_Full_Report_$stamp.pdf")
        writeAiNeuralPdfDocument(
            neuralSummary = neuralSummary,
            lastAiVerdict = lastAiVerdict,
            trades = trades,
            outputFile = outFile,
            nowMs = nowMs,
        )
        val savedPath = saveBytesToPhoneStorage(
            context = context,
            fileName = outFile.name,
            mimeType = JournalExportFormat.PDF_REPORT.mimeType,
            bytes = outFile.readBytes(),
        )
        outFile to savedPath
    }

    fun writeAiNeuralPdfDocument(
        neuralSummary: NeuralLearningStateSummary,
        lastAiVerdict: AiTradeVerdict?,
        trades: List<PaperTrade>,
        outputFile: File,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val doc = PdfDocument()
        try {
            val pageW = 842
            val pageH = 595
            val bgPaint = Paint().apply { color = Color.rgb(10, 14, 22) }
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 25, 38) }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(65, 85, 118)
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val goldTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(243, 198, 95)
                textSize = 15.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val cyanHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(56, 189, 248)
                textSize = 10f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(232, 238, 246)
                textSize = 9f
            }
            val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(156, 168, 186)
                textSize = 8.5f
            }
            val reg = neuralSummary.autoTunedParameters.latestRegressionReport

            // ── Page 1: Executive Neural Brain Summary + Parameter Regression Matrix + Feature Slopes ──
            val p1 = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
            val c1 = p1.canvas
            c1.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)
            c1.drawRoundRect(RectF(22f, 18f, pageW - 22f, 68f), 10f, 10f, cardPaint)
            c1.drawText("AURUM EDGE — FULL AI & SELF-LEARNING NEURAL NETWORK + PARAMETER REGRESSION REPORT", 36f, 40f, goldTitlePaint)
            c1.drawText(
                "Generated: ${formatTimestamp(nowMs)} | Arch: ${neuralSummary.architectureNameFa} | Gen #${neuralSummary.autoTunedParameters.tuningGeneration} | Learned Trades: ${neuralSummary.totalLearnedTrades} / ${neuralSummary.warmupTargetTrades} | R:R Locked: [1:1.50 .. 1:3.00]",
                36f,
                57f,
                mutedPaint,
            )

            // Regression KPI Row
            val kpiRect = RectF(22f, 76f, pageW - 22f, 128f)
            c1.drawRoundRect(kpiRect, 8f, 8f, cardPaint)
            c1.drawRoundRect(kpiRect, 8f, 8f, borderPaint)
            c1.drawText(
                "Regression Method: ${reg.regressionMethodFa}   |   R² = ${String.format(Locale.US, "%.2f", reg.rSquared)} (Adj R² = ${String.format(Locale.US, "%.2f", reg.adjustedRSquared)})   |   Walk-Forward RMSE = ${String.format(Locale.US, "%.2f", reg.walkForwardRmse)}   |   Expected Lift: +${reg.expectedWinRateLiftPct}% WR, +${reg.expectedRLiftPerTrade}R/trade",
                34f,
                96f,
                cyanHeaderPaint,
            )
            c1.drawText(
                "${reg.optimalStopLossCorridorFa}   |   ${reg.optimalRrCorridorFa}",
                34f,
                115f,
                cellPaint,
            )

            // 10 Regressed Structural Parameters Table
            c1.drawText("1. World-Class Parameter Regression Table (Ridge L2 + Huber Robust IRLS + Empirical Bayes):", 24f, 148f, goldTitlePaint)
            var y1 = 166f
            c1.drawText("Parameter Key / Title", 26f, y1, cyanHeaderPaint)
            c1.drawText("Prior", 295f, y1, cyanHeaderPaint)
            c1.drawText("Regressed", 350f, y1, cyanHeaderPaint)
            c1.drawText("Slope β", 425f, y1, cyanHeaderPaint)
            c1.drawText("t-Stat", 485f, y1, cyanHeaderPaint)
            c1.drawText("95% Confidence Corridor", 540f, y1, cyanHeaderPaint)
            c1.drawText("Unit", 700f, y1, cyanHeaderPaint)
            y1 += 15f
            reg.parameterRows.forEach { row ->
                c1.drawText("${row.paramKey} (${row.paramTitleFa.take(28)})", 26f, y1, cellPaint)
                c1.drawText(String.format(Locale.US, "%.2f", row.priorValue), 295f, y1, cellPaint)
                c1.drawText(String.format(Locale.US, "%.2f", row.regressedValue), 350f, y1, cyanHeaderPaint)
                c1.drawText(String.format(Locale.US, "%+.2f", row.slopeBeta), 425f, y1, cellPaint)
                c1.drawText(String.format(Locale.US, "%+.2f", row.tStat), 485f, y1, cellPaint)
                c1.drawText(row.confidenceIntervalLabel, 540f, y1, cellPaint)
                c1.drawText(row.unitLabel, 700f, y1, mutedPaint)
                y1 += 15f
            }

            // 16 Neural Features Attention & Regression Slopes (2 columns x 8 rows)
            c1.drawText("2. 16-Feature Self-Attention Weights & Huber-Ridge Regression Slopes:", 24f, 336f, goldTitlePaint)
            val slopes = reg.featureSlopes
            slopes.forEachIndexed { idx, fs ->
                val col = idx / 8
                val rowIdx = idx % 8
                val xBase = if (col == 0) 26f else 432f
                val fy = 356f + rowIdx * 26f
                c1.drawRoundRect(RectF(xBase, fy - 12f, xBase + 388f, fy + 10f), 5f, 5f, cardPaint)
                c1.drawText(
                    "#${idx + 1} ${fs.featureTitleFa.take(26)} | β_R=${String.format(Locale.US, "%+.3f", fs.huberBetaR)} | β_Win=${String.format(Locale.US, "%+.3f", fs.ridgeBetaWin)} | Imp=${String.format(Locale.US, "%.1f%%", fs.importancePct)} | t=${String.format(Locale.US, "%+.1f", fs.tStat)}",
                    xBase + 8f,
                    fy + 2f,
                    cellPaint,
                )
            }
            doc.finishPage(p1)

            // ── Page 2: All 16 Per-Strategy Neural Tuning Profiles + Live 15-Symbol Market Intelligence ──
            val p2 = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 2).create())
            val c2 = p2.canvas
            c2.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)
            c2.drawText("3. Per-Strategy Neural Tuning Profiles (All 16 Strategies — R:R Locked in [1:1.50 .. 1:3.00]):", 24f, 30f, goldTitlePaint)

            var y2 = 50f
            c2.drawText("Strategy ID", 26f, y2, cyanHeaderPaint)
            c2.drawText("Gen", 230f, y2, cyanHeaderPaint)
            c2.drawText("Min R:R", 275f, y2, cyanHeaderPaint)
            c2.drawText("SL Buffer", 340f, y2, cyanHeaderPaint)
            c2.drawText("Max Kijun", 415f, y2, cyanHeaderPaint)
            c2.drawText("Min ER14", 490f, y2, cyanHeaderPaint)
            c2.drawText("Max Wick", 560f, y2, cyanHeaderPaint)
            c2.drawText("Sniper Dist", 630f, y2, cyanHeaderPaint)
            c2.drawText("Neural Gate", 710f, y2, cyanHeaderPaint)
            y2 += 15f

            val profiles = neuralSummary.autoTunedParameters.perStrategyProfiles.values.toList()
            profiles.take(16).forEach { prof ->
                c2.drawText(prof.strategyId.take(28), 26f, y2, cellPaint)
                c2.drawText("#${prof.tuningGeneration}", 230f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "1:%.2f", prof.minRiskReward.coerceIn(1.5, 3.0)), 275f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "%.2f ATR", prof.slBufferAtr), 340f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "%.2f ATR", prof.maxKijunExtensionAtr), 415f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "%.0f%%", prof.minEfficiencyRatio * 100.0), 490f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "%.0f%%", prof.maxAdverseWickRatio * 100.0), 560f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "%.2f ATR", prof.minSniperDistanceAtr), 630f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "%.1f%%", prof.neuralWinProbGatePct), 710f, y2, cyanHeaderPaint)
                y2 += 14.5f
            }

            // Live 15-Symbol Market Intelligence Table
            y2 += 12f
            c2.drawText("4. Live 15-Symbol Market Intelligence & Causal Event Attribution:", 24f, y2, goldTitlePaint)
            y2 += 18f
            c2.drawText("Symbol", 26f, y2, cyanHeaderPaint)
            c2.drawText("TF", 105f, y2, cyanHeaderPaint)
            c2.drawText("Confidence", 155f, y2, cyanHeaderPaint)
            c2.drawText("News Bias", 230f, y2, cyanHeaderPaint)
            c2.drawText("Regime / Trend State", 310f, y2, cyanHeaderPaint)
            c2.drawText("Primary Root Cause & Tactical Action", 480f, y2, cyanHeaderPaint)
            y2 += 14f
            neuralSummary.liveMarketAttributions.take(15).forEach { m ->
                c2.drawText(m.symbol, 26f, y2, cellPaint)
                c2.drawText(m.timeframeLabel, 105f, y2, cellPaint)
                c2.drawText(String.format(Locale.US, "%.1f%%", m.causalConfidencePct), 155f, y2, cyanHeaderPaint)
                c2.drawText(String.format(Locale.US, "%+.2f", m.newsSentimentScore), 230f, y2, cellPaint)
                c2.drawText(m.marketRegimeStateFa.take(24), 310f, y2, cellPaint)
                c2.drawText("${m.primaryCauseBadgeFa.take(22)} | ${m.recommendedTacticalActionFa.take(28)}", 480f, y2, mutedPaint)
                y2 += 14f
            }
            doc.finishPage(p2)

            // ── Page 3: AI Gate Yes/No Audit Decisions & Recent Learned Trade Lessons ──
            val p3 = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 3).create())
            val c3 = p3.canvas
            c3.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)
            c3.drawText("5. AvalAI Single-Question Yes/No Gate Decision & Experience Replay Trade Lessons:", 24f, 30f, goldTitlePaint)

            var y3 = 52f
            c3.drawText("AvalAI Single-Question Yes/No Filter Status:", 26f, y3, cyanHeaderPaint)
            y3 += 16f
            if (lastAiVerdict == null) {
                c3.drawText("AvalAI Key Active & Ready (Single Question: Should I trade right now with these conditions or not? -> Yes/No).", 26f, y3, mutedPaint)
                y3 += 18f
            } else {
                val dec = if (lastAiVerdict.shouldEnter) "YES (APPROVED)" else "NO (BLOCKED)"
                c3.drawText(
                    "${formatTimestamp(lastAiVerdict.checkedAt)} | ${lastAiVerdict.symbol} | ${lastAiVerdict.action} | $dec | Summary: ${lastAiVerdict.summaryFa.take(48)}",
                    26f,
                    y3,
                    cellPaint,
                )
                y3 += 18f
            }

            y3 += 10f
            c3.drawText("Structural Stop-Loss Diagnostics & Regression Insights:", 26f, y3, cyanHeaderPaint)
            y3 += 16f
            reg.regressionDiagnosticNotesFa.forEach { note ->
                c3.drawText("• ${note.take(115)}", 26f, y3, cellPaint)
                y3 += 16f
            }
            y3 += 8f
            c3.drawText("Recent Learned Trade Lessons (Experience Replay Buffer):", 26f, y3, cyanHeaderPaint)
            y3 += 16f
            neuralSummary.recentLessons.take(12).forEach { lesson ->
                val outTag = if (lesson.won) "WIN" else "LOSS"
                c3.drawText(
                    "${formatTimestamp(lesson.timestampMs)} | ${lesson.symbol} (${lesson.timeframeLabel}) | $outTag (${String.format(Locale.US, "%+.2fR", lesson.achievedR.coerceIn(-1.5, 3.0))}) | ${lesson.lessonLearnedFa.take(65)}",
                    26f,
                    y3,
                    cellPaint,
                )
                y3 += 15f
            }
            doc.finishPage(p3)

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out -> doc.writeTo(out) }
        } catch (_: Throwable) {
            writePortableAiNeuralPdfFallback(neuralSummary, lastAiVerdict, trades, outputFile, nowMs)
        } finally {
            runCatching { doc.close() }
        }
    }

    private fun writePortableAiNeuralPdfFallback(
        neuralSummary: NeuralLearningStateSummary,
        lastAiVerdict: AiTradeVerdict?,
        trades: List<PaperTrade>,
        outputFile: File,
        nowMs: Long,
    ) {
        outputFile.parentFile?.mkdirs()
        val reg = neuralSummary.autoTunedParameters.latestRegressionReport
        val lines = mutableListOf<String>()
        lines += "AURUM EDGE - FULL AI & NEURAL NETWORK + PARAMETER REGRESSION PDF REPORT"
        lines += "Generated: ${formatTimestamp(nowMs)} | Arch: ${neuralSummary.architectureNameFa} | Gen #${neuralSummary.autoTunedParameters.tuningGeneration}"
        lines += "Learned Trades: ${neuralSummary.totalLearnedTrades} / ${neuralSummary.warmupTargetTrades} | Accuracy: ${neuralSummary.runningAccuracyPct}% | Avg R: ${neuralSummary.tradeForensics.expectancyPerTradeR}"
        lines += "Regression: ${reg.regressionMethodFa} | R2=${reg.rSquared} | AdjR2=${reg.adjustedRSquared} | RMSE=${reg.walkForwardRmse}"
        lines += "Optimal Stop-Loss Corridor: [0.80 ATR .. 2.10 ATR] behind 5-Bar Fractal + Kijun26 + Sweep Shield"
        lines += "Locked Risk:Reward Corridor: [1:1.50 .. 1:3.00] (Strictly >= 1.50 and <= 3.00)"
        lines += "----------------------------------------------------------------------------------------"
        reg.parameterRows.forEach { r ->
            lines += String.format(
                Locale.US,
                "PARAM %-26s | Prior: %6.2f | Regressed: %6.2f | Beta: %+5.2f | t=%+5.2f | CI: %s",
                r.paramKey,
                r.priorValue,
                r.regressedValue,
                r.slopeBeta,
                r.tStat,
                r.confidenceIntervalLabel,
            )
        }
        lines += "----------------------------------------------------------------------------------------"
        reg.featureSlopes.take(16).forEach { fs ->
            lines += String.format(
                Locale.US,
                "FEAT #%02d | Beta_R: %+6.3f | Beta_Win: %+6.3f | Importance: %5.1f%% | t-Stat: %+5.2f",
                fs.featureIndex + 1,
                fs.huberBetaR,
                fs.ridgeBetaWin,
                fs.importancePct,
                fs.tStat,
            )
        }
        lines += "Live Symbols Attributed: ${neuralSummary.liveMarketAttributions.size} | Last AI Verdict: ${lastAiVerdict?.shouldEnter ?: "READY"} | Total Journal Trades: ${trades.size}"
        val streamContent = buildString {
            append("BT\n/F1 9 Tf\n32 555 Td\n13 TL\n")
            lines.take(40).forEach { line ->
                val escaped = line
                    .replace("\\", "\\\\")
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                append("($escaped) Tj T*\n")
            }
            append("ET\n")
        }
        val pdfText = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
            append("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n")
            append("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj\n")
            append("4 0 obj << /Length ${streamContent.toByteArray(Charsets.US_ASCII).size} >>\nstream\n")
            append(streamContent)
            append("endstream\nendobj\n")
            append("5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n")
            append("trailer << /Root 1 0 R >>\n%%EOF\n")
        }
        outputFile.writeText(pdfText, Charsets.US_ASCII)
    }

    /**
     * ذخیره مستقیم فایل در پوشه انتخاب‌شده توسط کاربر از طریق پنجره انتخاب پوشه سیستم‌عامل (`Save As...` / SAF).
     */
    fun writeExportToUri(
        context: Context,
        uri: Uri,
        report: InstitutionalTearSheetReport,
        format: JournalExportFormat,
    ): Result<String> = runCatching {
        val localFile = exportToFile(context, report, format)
        val bytes = localFile.readBytes()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: error("امکان نوشتن در مسیر انتخابی وجود ندارد")
        localFile.name
    }

    /**
     * فهرست تمام فایل‌های خروجی و بکاپ ذخیره‌شده در داخل اپ برای مشاهده، اشتراک‌گذاری یا بازگردانی فوری.
     */
    fun listSavedExportFiles(context: Context): List<SavedJournalExportFile> {
        val exportDir = File(context.filesDir, "exports")
        if (!exportDir.exists()) return emptyList()
        return exportDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f ->
                val ext = f.extension.lowercase(Locale.ROOT)
                val fmt = JournalExportFormat.entries.firstOrNull { it.extension == ext } ?: return@mapNotNull null
                SavedJournalExportFile(
                    file = f,
                    name = f.name,
                    format = fmt,
                    sizeBytes = f.length(),
                    modifiedAtMs = f.lastModified(),
                    modifiedAtFormatted = formatTimestamp(f.lastModified()),
                )
            }
            .orEmpty()
    }

    /**
     * پارس و بازگردانی (Import / Restore) معاملات از فایل بکاپ JSON (چه فایل کامل Tear-Sheet و چه آرایه PaperTrade) یا فایل CSV اکسل.
     */
    fun parseBackupContent(rawText: String): Result<List<PaperTrade>> = runCatching {
        val trimmed = rawText.removePrefix("\uFEFF").trim()
        require(trimmed.isNotEmpty()) { "فایل یا متن بکاپ خالی است" }

        if (trimmed.startsWith("{")) {
            val report = prettyJson.decodeFromString(InstitutionalTearSheetReport.serializer(), trimmed)
            return@runCatching report.trades
        }
        if (trimmed.startsWith("[")) {
            val list = prettyJson.decodeFromString(ListSerializer(PaperTrade.serializer()), trimmed)
            return@runCatching list
        }
        // Parse CSV backup (29 columns)
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.size >= 2) { "فرمت فایل بکاپ معتبر نیست (باید JSON یا CSV استاندارد Aurum Edge باشد)" }
        val dataLines = lines.drop(1)
        val trades = dataLines.mapNotNull { line ->
            val cols = splitCsvLine(line)
            if (cols.size < 18) return@mapNotNull null
            val tradeId = cols.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sym = V1Universe.normalize(cols.getOrNull(2).orEmpty())
            val dirRaw = cols.getOrNull(4).orEmpty().uppercase(Locale.ROOT)
            val action = if (dirRaw.contains("SELL") || dirRaw.contains("SHORT")) SignalAction.SELL else SignalAction.BUY
            val tfRaw = cols.getOrNull(5).orEmpty()
            val interval = if (tfRaw.contains("15")) Interval.M15 else Interval.M5
            val stratId = cols.getOrNull(6)?.takeIf { it.isNotBlank() } ?: BitcoinStrategyModel.BASE_ICHIMOKU.id
            val stratTitle = cols.getOrNull(7)?.takeIf { it.isNotBlank() } ?: BitcoinStrategyModel.fromId(stratId).titleFa
            val statusRaw = cols.getOrNull(8).orEmpty().uppercase(Locale.ROOT)
            val isOpen = statusRaw == "OPEN"
            val holdMins = cols.getOrNull(11)?.toDoubleOrNull() ?: 15.0
            val entry = cols.getOrNull(12)?.toDoubleOrNull() ?: return@mapNotNull null
            val sl = cols.getOrNull(13)?.toDoubleOrNull() ?: return@mapNotNull null
            val initSl = cols.getOrNull(14)?.toDoubleOrNull() ?: sl
            val tp = cols.getOrNull(15)?.toDoubleOrNull() ?: return@mapNotNull null
            val exitP = cols.getOrNull(16)?.toDoubleOrNull()
            val qty = cols.getOrNull(17)?.toDoubleOrNull() ?: 1.0
            val fixedRisk = cols.getOrNull(18)?.toDoubleOrNull() ?: 5.0
            val plannedRr = cols.getOrNull(19)?.toDoubleOrNull() ?: 1.5
            val optRr = cols.getOrNull(20)?.toDoubleOrNull() ?: plannedRr
            val netPnl = cols.getOrNull(24)?.toDoubleOrNull()
            val exitReason = cols.getOrNull(26)?.takeIf { it.isNotBlank() }
            val note = cols.getOrNull(28).orEmpty()
            val now = System.currentTimeMillis()
            PaperTrade(
                id = tradeId,
                symbol = sym,
                interval = interval,
                action = action,
                entry = entry,
                stopLoss = sl,
                takeProfit = tp,
                confidence = 90.0,
                riskReward = plannedRr,
                openedAt = now - (holdMins * 60_000.0).toLong(),
                closedAt = if (isOpen) null else now,
                exitPrice = if (isOpen) null else exitP,
                exitReason = if (isOpen) null else exitReason,
                pnlUsd = if (isOpen) null else netPnl,
                positionOz = qty,
                note = note,
                initialStopLoss = initSl,
                strategyId = stratId,
                strategyTitleFa = stratTitle,
                fixedRiskUsd = fixedRisk,
                optimalRiskReward = optRr,
            )
        }
        require(trades.isNotEmpty()) { "هیچ معامله معتبری در فایل بکاپ یافت نشد" }
        trades
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    fun readAndParseBackupUri(context: Context, uri: Uri): Result<List<PaperTrade>> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("امکان خواندن فایل انتخابی وجود ندارد")
        parseBackupContent(text).getOrThrow()
    }

    /**
     * اشتراک‌گذاری مستقیم فایل خروجی ژورنال از طریق Android Share Sheet (تلگرام، واتساپ، ایمیل، گوگل‌درایو و غیره).
     */
    fun shareExportedFile(
        context: Context,
        file: File,
        format: JournalExportFormat,
    ): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Aurum Edge — ${format.titleFa}")
            putExtra(Intent.EXTRA_TEXT, "گزارش تحلیلی و ممیزی ژورنال معاملاتی Aurum Edge (${file.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "ارسال و ذخیره خروجی ژورنال (${format.extension.uppercase(Locale.ROOT)})").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * باز کردن مستقیم فایل خروجی در مرورگر، PDF خوان یا اکسل گوشی.
     */
    fun openExportedFileExternally(
        context: Context,
        file: File,
        format: JournalExportFormat,
    ): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, format.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(viewIntent)
    }

    /**
     * کپی خلاصه اجرایی کارنامه کوانت در کلیپ‌بورد گوشی.
     */
    fun copyExecutiveSummaryToClipboard(context: Context, report: InstitutionalTearSheetReport) {
        val summary = buildString {
            appendLine("📊 کارنامه کوانت Aurum Edge (${report.generatedAtFormatted})")
            appendLine("• موجودی فعلی: $${String.format(Locale.US, "%,.2f", report.currentBalanceUsd)} | سود خالص: $${String.format(Locale.US, "%+.2f", report.netPnlUsd)} (${String.format(Locale.US, "%+.2fR", report.totalCumulativeR)})")
            appendLine("• معاملات بسته: ${report.closedTradesCount} (${report.winsCount} برد / ${report.lossesCount} باخت) | نرخ برد: ${String.format(Locale.US, "%.1f%%", report.winRatePct)}")
            appendLine("• پرافیت فاکتور: ${String.format(Locale.US, "%.2f", report.profitFactor)} | امید ریاضی: $${String.format(Locale.US, "%+.2f", report.expectancyUsdPerTrade)} (${String.format(Locale.US, "%+.2fR", report.expectancyRPerTrade)})")
            appendLine("• امتیاز کیفیت سیستم (SQN): ${String.format(Locale.US, "%.2f", report.sqnScore)} (${report.sqnGradeFa})")
            appendLine("• شارپ: ${String.format(Locale.US, "%.2f", report.sharpeRatio)} | سورتینو: ${String.format(Locale.US, "%.2f", report.sortinoRatio)} | حداکثر افت سرمایه: -$${String.format(Locale.US, "%.2f", report.maxDrawdownUsd)}")
            appendLine("• تایم‌فریم 15m: $${String.format(Locale.US, "%+.2f", report.tf15mNetPnlUsd)} (${String.format(Locale.US, "%.1f%%", report.tf15mWinRatePct)}) | تایم‌فریم 5m: $${String.format(Locale.US, "%+.2f", report.tf5mNetPnlUsd)} (${String.format(Locale.US, "%.1f%%", report.tf5mWinRatePct)})")
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("AurumEdge TearSheet", summary))
    }
}
