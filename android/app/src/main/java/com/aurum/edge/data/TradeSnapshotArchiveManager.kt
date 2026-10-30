package com.aurum.edge.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aurum.edge.core.compressArchiveCandles
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.PaperConditionRecord
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ArchivedCandlePoint(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
)

@Serializable
data class ArchivedTradeSnapshotRecord(
    val tradeId: String,
    val tradeNumber: Int = 0,
    val snapshotPhase: String = "OPEN", // "OPEN" or "CLOSE"
    val snapshotTimestampMs: Long = 0L,
    val symbol: String,
    val timeframeLabel: String,
    val action: String,
    val strategyId: String,
    val strategyTitleFa: String,
    val strategyBadgeFa: String,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val riskReward: Double,
    val optimalRiskReward: Double,
    val fixedRiskUsd: Double,
    val expectedProfitUsd: Double,
    val confidence: Double,
    val openedAtMs: Long,
    val exitPrice: Double? = null,
    val exitAtMs: Long? = null,
    val exitReasonFa: String = "",
    val realizedPnlUsd: Double? = null,
    val realizedRMultiple: Double? = null,
    val closeConditionCategoryFa: String = "",
    val rrFormulaFa: String = "",
    val slBasisFa: String = "",
    val tpBasisFa: String = "",
    val tenkan: Double = 0.0,
    val kijun: Double = 0.0,
    val currentSpanA: Double = 0.0,
    val currentSpanB: Double = 0.0,
    val futureSpanA: Double = 0.0,
    val futureSpanB: Double = 0.0,
    val atr14: Double = 0.0,
    val rsi14: Double = 50.0,
    val rsi7: Double = 50.0,
    val ema21: Double = 0.0,
    val ema34: Double = 0.0,
    val adx14: Double = 25.0,
    val conditionsSummary: List<PaperConditionRecord> = emptyList(),
    val recentCandles: List<ArchivedCandlePoint> = emptyList(),
    val localImagePath: String = "",
    val phoneGalleryUri: String = "",
    val imageSizeBytes: Long = 0L,
    val fullPaperTrade: PaperTrade? = null,
    val candleSourceNote: String = "",
    val indicatorsAvailable: Boolean = false,
) {
    val displayTradeNumber: Int
        get() = when {
            tradeNumber > 0 -> tradeNumber
            fullPaperTrade != null && fullPaperTrade.tradeNumber > 0 -> fullPaperTrade.tradeNumber
            else -> fullPaperTrade?.displayTradeNumber ?: (kotlin.math.abs(tradeId.hashCode()) % 9000 + 1000)
        }

    val isCloseSnapshot: Boolean
        get() = snapshotPhase.equals("CLOSE", ignoreCase = true)

    val effectiveTimestampMs: Long
        get() = if (snapshotTimestampMs > 0L) snapshotTimestampMs else (exitAtMs ?: openedAtMs)

    val phaseBadgeFa: String
        get() = if (isCloseSnapshot) {
            "🏁 لحظه بسته شدن (${closeConditionCategoryFa.ifBlank { "CLOSE" }})"
        } else {
            "📸 لحظه باز شدن (OPEN)"
        }
}

/**
 * موتور آرشیو تصویری معاملات و ذخیره‌سازی دوگانه (حافظه داخلی اپ + گالری/دانلودهای گوشی)
 * همراه با تزریق متادیتای خودخوان (`Embedded Self-Readable JSON Payload`) در دل تصویر کم‌حجم WebP:
 * - از هر معامله بازشده یک اسکرین‌شات گرافیکی دقیق شامل کندل‌ها، ابر ایچیموکو، ابر آینده ۲۶ کندلی،
 *   تنکان/کیجون و باکس پوزیشن TradingView (`Entry`, `Stop-Loss`, `Take-Profit`, `R:R`) به همراه ۱۶ شرط ایچیموکو می‌سازد.
 * - با فرمت `WebP` باکیفیت و بسیار کم‌حجم (`~35KB..65KB`) هم در آرشیو اپ و هم در حافظه گوشی (`Pictures/AurumEdge` و `Download/AurumEdge/Archive`) ذخیره می‌کند.
 * - دیتای کامل معامله و شرایط ایچیموکو را هم در انتهای خود فایل تصویر و هم در فایل JSON آرشیو گوشی ذخیره می‌کند تا اپ بتواند بعداً از روی عکس‌ها یا فایل‌های گوشی کل دیتای خود را بازخوانی کند.
 */
object TradeSnapshotArchiveManager {

    private const val ARCHIVE_DIR_NAME = "trade_snapshots_v1"
    private const val INDEX_FILE_NAME = "trade_snapshots_index_v1.json"
    private const val EMBED_START_MARKER = "\n/*AURUM_SNAPSHOT_V1_START*/"
    private const val EMBED_END_MARKER = "/*AURUM_SNAPSHOT_V1_END*/"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _records = MutableStateFlow<List<ArchivedTradeSnapshotRecord>>(emptyList())
    val records: StateFlow<List<ArchivedTradeSnapshotRecord>> = _records.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        runCatching {
            val loaded = loadIndexFromDisk(context)
            _records.value = loaded.sortedByDescending { it.effectiveTimestampMs }
        }
    }

    fun recordForTrade(tradeId: String): ArchivedTradeSnapshotRecord? =
        _records.value.firstOrNull { it.tradeId == tradeId && it.isCloseSnapshot }
            ?: _records.value.firstOrNull { it.tradeId == tradeId }

    fun recordsForTrade(tradeId: String): List<ArchivedTradeSnapshotRecord> =
        _records.value.filter { it.tradeId == tradeId }.sortedBy { if (it.isCloseSnapshot) 1 else 0 }

    /**
     * تضمین ثبت و ساخت اسکرین‌شات آرشیو تصویری (`OPEN` و در صورت بسته بودن `CLOSE`) برای معاملات موجود در ژورنال
     * بدون قفل طولانی‌مدت MediaStore و با ذخیره یکپارچه ایندکس در پایان حلقه.
     */
    @Synchronized
    fun ensureAllTradesHaveArchiveSnapshots(
        context: Context,
        trades: List<PaperTrade>,
        maxTrades: Int = 120,
        saveToPhoneGallery: Boolean = false,
    ): List<ArchivedTradeSnapshotRecord> {
        if (_records.value.isEmpty()) {
            init(context)
        }
        if (trades.isEmpty()) return _records.value
        val dir = File(context.filesDir, ARCHIVE_DIR_NAME).apply { mkdirs() }
        val currentMap = _records.value.associateBy { "${it.tradeId}_${it.snapshotPhase}" }.toMutableMap()
        var modified = false

        val targetTrades = trades.sortedByDescending { it.openedAt }.take(maxTrades).reversed()
        for (trade in targetTrades) {
            val openKey = "${trade.id}_OPEN"
            val existingOpen = currentMap[openKey]
            if (existingOpen == null || existingOpen.localImagePath.isBlank() || !File(existingOpen.localImagePath).exists()) {
                runCatching {
                    val rec = buildSyntheticSnapshotRecordForTrade(trade, isClosePhase = false)
                    val bmp = renderTradeIchimokuSnapshotBitmap(rec)
                    val webpBytes = compressBitmapToCompactWebp(bmp, quality = 78)
                    bmp.recycle()
                    val embedded = attachEmbeddedJsonToImageBytes(webpBytes, rec)
                    val safeSym = trade.symbol.replace("/", "_").replace(" ", "_")
                    val fileName = "AurumTrade_No${rec.displayTradeNumber}_OPEN_${safeSym}_${trade.interval.label}_${trade.openedAt}.webp"
                    val localFile = File(dir, fileName)
                    localFile.writeBytes(embedded)
                    val galleryUri = if (saveToPhoneGallery) {
                        saveImageAndSelfReadableDataToPhoneStorage(context, fileName, embedded, rec)
                    } else ""
                    currentMap[openKey] = rec.copy(
                        localImagePath = localFile.absolutePath,
                        phoneGalleryUri = galleryUri,
                        imageSizeBytes = embedded.size.toLong(),
                    )
                    modified = true
                }
            }
            if (!trade.isOpen) {
                val closeKey = "${trade.id}_CLOSE"
                val existingClose = currentMap[closeKey]
                if (existingClose == null || existingClose.localImagePath.isBlank() || !File(existingClose.localImagePath).exists()) {
                    runCatching {
                        val rec = buildSyntheticSnapshotRecordForTrade(trade, isClosePhase = true)
                        val bmp = renderTradeIchimokuSnapshotBitmap(rec)
                        val webpBytes = compressBitmapToCompactWebp(bmp, quality = 78)
                        bmp.recycle()
                        val embedded = attachEmbeddedJsonToImageBytes(webpBytes, rec)
                        val safeSym = trade.symbol.replace("/", "_").replace(" ", "_")
                        val closeAt = trade.closedAt ?: (trade.openedAt + 900_000L)
                        val fileName = "AurumTrade_No${rec.displayTradeNumber}_CLOSE_${safeSym}_${trade.interval.label}_$closeAt.webp"
                        val localFile = File(dir, fileName)
                        localFile.writeBytes(embedded)
                        val galleryUri = if (saveToPhoneGallery) {
                            saveImageAndSelfReadableDataToPhoneStorage(context, fileName, embedded, rec)
                        } else ""
                        currentMap[closeKey] = rec.copy(
                            localImagePath = localFile.absolutePath,
                            phoneGalleryUri = galleryUri,
                            imageSizeBytes = embedded.size.toLong(),
                        )
                        modified = true
                    }
                }
            }
        }
        if (modified) {
            val updated = currentMap.values.sortedByDescending { it.effectiveTimestampMs }
            _records.value = updated
            saveIndexToDisk(context, updated)
        }
        return _records.value
    }

    fun buildSyntheticSnapshotRecordForTrade(
        trade: PaperTrade,
        isClosePhase: Boolean = !trade.isOpen,
    ): ArchivedTradeSnapshotRecord {
        val fixedSl = trade.initialStopLoss ?: trade.stopLoss
        val fixedRisk = trade.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0
        val expectedWin = round2(fixedRisk * trade.riskReward)
        val closeAt = trade.closedAt ?: (trade.openedAt + 900_000L)
        val exitP = trade.exitPrice ?: trade.takeProfit
        val closeCatFa = if (isClosePhase) classifyCloseConditionFa(trade) else ""
        val realizedPnl = trade.pnlUsd ?: 0.0
        val realizedR = trade.rMultiple ?: (realizedPnl / fixedRisk.coerceAtLeast(0.5))
        return ArchivedTradeSnapshotRecord(
            tradeId = trade.id,
            tradeNumber = trade.displayTradeNumber,
            snapshotPhase = if (isClosePhase) "CLOSE" else "OPEN",
            snapshotTimestampMs = if (isClosePhase) closeAt else trade.openedAt,
            symbol = trade.symbol,
            timeframeLabel = trade.interval.label,
            action = trade.action.name,
            strategyId = trade.strategyId,
            strategyTitleFa = trade.strategyTitleFa,
            strategyBadgeFa = trade.strategyTitleFa.take(24),
            entryPrice = trade.entry,
            stopLossPrice = fixedSl,
            takeProfitPrice = trade.takeProfit,
            riskReward = trade.riskReward,
            optimalRiskReward = trade.effectiveOptimalRr,
            fixedRiskUsd = fixedRisk,
            expectedProfitUsd = expectedWin,
            confidence = trade.confidence,
            openedAtMs = trade.openedAt,
            exitPrice = if (isClosePhase) trade.exitPrice else null,
            exitAtMs = if (isClosePhase) closeAt else null,
            exitReasonFa = if (isClosePhase) (trade.exitReason ?: closeCatFa) else "",
            realizedPnlUsd = if (isClosePhase) round2(realizedPnl) else null,
            realizedRMultiple = if (isClosePhase) round2(realizedR) else null,
            closeConditionCategoryFa = closeCatFa,
            rrFormulaFa = trade.buildRrFormulaSummaryFa(),
            slBasisFa = "سنگر ساختاری ثابت (غیرپله‌ای)",
            tpBasisFa = "موج هوسودا (R:R اولیه ثابت)",
            tenkan = trade.entry,
            kijun = fixedSl,
            currentSpanA = trade.entry,
            currentSpanB = fixedSl,
            futureSpanA = trade.takeProfit,
            futureSpanB = trade.entry,
            atr14 = abs(trade.entry - fixedSl) * 0.5,
            conditionsSummary = trade.entryConditions,
            fullPaperTrade = trade,
            candleSourceNote = "تصویر بازسازی‌شده؛ کندل و اندیکاتور ثبت نشده",
            indicatorsAvailable = false,
        )
    }

    fun loadOrRenderSnapshotBitmap(record: ArchivedTradeSnapshotRecord): Bitmap {
        val raw = if (record.localImagePath.isNotBlank()) {
            val f = File(record.localImagePath)
            if (f.exists()) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            } else null
        } else null
        val source = raw ?: renderTradeIchimokuSnapshotBitmap(record)
        if (source.width <= 800 && source.height <= 560) return source
        val scaled = runCatching {
            Bitmap.createScaledBitmap(source, 760, 520, true)
        }.getOrElse { source }
        if (scaled !== source) {
            runCatching { source.recycle() }
        }
        return scaled
    }

    private fun classifyCloseConditionFa(trade: PaperTrade): String {
        val reason = trade.exitReason.orEmpty()
        val pnl = trade.pnlUsd ?: 0.0
        return when {
            reason.contains("حد سود", ignoreCase = true) || reason.contains("TP", ignoreCase = true) ->
                "حد سود (TP)"
            reason.contains("متحرک", ignoreCase = true) || reason.contains("قفل سود", ignoreCase = true) ||
                reason.contains("Trailing", ignoreCase = true) || (reason.contains("SL", ignoreCase = true) && pnl > 0.0) ->
                "قفل سود / استاپ متحرک"
            reason.contains("روند", ignoreCase = true) && pnl > 0.0 ->
                "خروج هوشمند در سود (ضعف روند)"
            reason.contains("قطعی", ignoreCase = true) || reason.contains("Backfill", ignoreCase = true) ->
                if (pnl >= 0.0) "TP بازیابی آفلاین" else "SL بازیابی آفلاین"
            reason.contains("دستی", ignoreCase = true) || reason.contains("لحظه", ignoreCase = true) ->
                if (pnl >= 0.0) "بستن دستی در سود" else "بستن دستی در ضرر"
            pnl > 0.0 -> "بسته شدن در سود"
            else -> "برخورد به حد ضرر (SL)"
        }
    }

    /**
     * ثبت و ساخت فوری اسکرین‌شات ایچیموکو به محض باز شدن معامله + نوشتن شماره معامله روی چارت + ذخیره در آرشیو و حافظه گوشی.
     */
    @Synchronized
    fun captureAndArchiveTradeOpen(
        context: Context,
        trade: PaperTrade,
        report: StrategyEvaluationReport?,
        triggerCandles: List<Candle>,
    ): ArchivedTradeSnapshotRecord? {
        return runCatching {
            val effectiveCandles = triggerCandles.ifEmpty {
                LiveQuoteFetcher.peekCachedCandles(trade.symbol, trade.interval).ifEmpty {
                    PhoneStorageVaultManager.loadPersistedCandles(trade.symbol, trade.interval, trade.openedAt)
                }
            }
            val trigSnap = report?.triggerSnapshot
                ?: IchimokuStrategyEngine.computeSnapshot(effectiveCandles)
            val closedCandles = effectiveCandles
                .filter { it.high.isFinite() && it.low.isFinite() && it.close > 0.0 }
                .sortedBy { it.time }
                .takeLast(52)

            val fixedRisk = trade.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0
            val expectedWin = round2(fixedRisk * trade.riskReward)
            val rrBreakdown = report?.rrBreakdown
            val tNum = trade.displayTradeNumber

            val baseRecord = ArchivedTradeSnapshotRecord(
                tradeId = trade.id,
                tradeNumber = tNum,
                snapshotPhase = "OPEN",
                snapshotTimestampMs = trade.openedAt,
                symbol = trade.symbol,
                timeframeLabel = trade.interval.label,
                action = trade.action.name,
                strategyId = trade.strategyId,
                strategyTitleFa = trade.strategyTitleFa,
                strategyBadgeFa = report?.strategyBadgeFa ?: trade.strategyTitleFa.take(24),
                entryPrice = trade.entry,
                stopLossPrice = trade.stopLoss,
                takeProfitPrice = trade.takeProfit,
                riskReward = trade.riskReward,
                optimalRiskReward = trade.effectiveOptimalRr,
                fixedRiskUsd = fixedRisk,
                expectedProfitUsd = expectedWin,
                confidence = trade.confidence,
                openedAtMs = trade.openedAt,
                rrFormulaFa = rrBreakdown?.formulaTextFa ?: trade.buildRrFormulaSummaryFa(),
                slBasisFa = rrBreakdown?.slBasisFa ?: "سنگر ساختاری کیجون/کومو",
                tpBasisFa = rrBreakdown?.tpBasisFa ?: "موج هوسودا",
                tenkan = trigSnap?.tenkan ?: trade.entry,
                kijun = trigSnap?.kijun ?: trade.stopLoss,
                currentSpanA = trigSnap?.currentSpanA ?: trade.entry,
                currentSpanB = trigSnap?.currentSpanB ?: trade.stopLoss,
                futureSpanA = trigSnap?.futureSpanA ?: trade.takeProfit,
                futureSpanB = trigSnap?.futureSpanB ?: trade.entry,
                atr14 = trigSnap?.atr14 ?: (abs(trade.entry - trade.stopLoss) * 0.5),
                rsi14 = trigSnap?.rsi14 ?: 55.0,
                rsi7 = trigSnap?.rsi7 ?: 55.0,
                ema21 = trigSnap?.ema21 ?: trade.entry,
                ema34 = trigSnap?.ema34 ?: trade.entry,
                adx14 = trigSnap?.adx14 ?: 25.0,
                conditionsSummary = trade.entryConditions,
                indicatorsAvailable = trigSnap != null,
                candleSourceNote = if (closedCandles.isEmpty()) "کندل ثبت‌شده موجود نیست" else "کندل‌های ثبت‌شده در زمان ورود",
                recentCandles = closedCandles.map {
                    ArchivedCandlePoint(
                        t = it.time,
                        o = roundPrice(it.open),
                        h = roundPrice(it.high),
                        l = roundPrice(it.low),
                        c = roundPrice(it.close),
                    )
                },
                fullPaperTrade = trade,
            )

            // ۱. رندر تصویر گرافیکی باکیفیت از چارت ایچیموکو + شماره معامله + باکس پوزیشن SL/TP + شروط معامله
            val bitmap = renderTradeIchimokuSnapshotBitmap(baseRecord, closedCandles)
            val webpBytes = compressBitmapToCompactWebp(bitmap, quality = 80)
            bitmap.recycle()

            // ۲. تزریق متادیتای خودخوان JSON در انتهای بایت‌های تصویر (Self-Readable Image)
            val embeddedBytes = attachEmbeddedJsonToImageBytes(webpBytes, baseRecord)

            // ۳. ذخیره در پوشه داخلی آرشیو اپ
            val dir = File(context.filesDir, ARCHIVE_DIR_NAME).apply { mkdirs() }
            val safeSym = trade.symbol.replace("/", "_").replace(" ", "_")
            val fileName = "AurumTrade_No${tNum}_OPEN_${safeSym}_${trade.interval.label}_${trade.openedAt}.webp"
            val localFile = File(dir, fileName)
            localFile.writeBytes(embeddedBytes)

            // ۴. ذخیره خودکار در حافظه گوشی (گالری Pictures/AurumEdge و پوشه Download/AurumEdge/Archive)
            val galleryUri = saveImageAndSelfReadableDataToPhoneStorage(
                context = context,
                fileName = fileName,
                imageBytesWithEmbeddedMeta = embeddedBytes,
                record = baseRecord,
            )

            val finalRecord = baseRecord.copy(
                localImagePath = localFile.absolutePath,
                phoneGalleryUri = galleryUri,
                imageSizeBytes = embeddedBytes.size.toLong(),
            )

            val updated = (_records.value.filterNot { it.tradeId == trade.id && !it.isCloseSnapshot } + finalRecord)
                .sortedByDescending { it.effectiveTimestampMs }
            _records.value = updated
            saveIndexToDisk(context, updated)
            finalRecord
        }.getOrNull()
    }

    /**
     * ثبت و ساخت فوری اسکرین‌شات ایچیموکو در لحظه بسته شدن معامله با هر شرایطی (TP، SL، قفل سود متحرک، ضعف روند، دستی یا بازیابی آفلاین)
     * همراه با درج شماره معامله (`معامله #N`)، خط قیمت خروج و نتیجه قطعی روی تصویر.
     */
    @Synchronized
    fun captureAndArchiveTradeClose(
        context: Context,
        closedTrade: PaperTrade,
        closeCandles: List<Candle> = emptyList(),
    ): ArchivedTradeSnapshotRecord? {
        return runCatching {
            if (_records.value.isEmpty()) {
                init(context)
            }
            val openSnap = _records.value.firstOrNull { it.tradeId == closedTrade.id && !it.isCloseSnapshot }
            val closeAt = closedTrade.closedAt ?: System.currentTimeMillis()
            val exitP = closedTrade.exitPrice ?: closedTrade.takeProfit

            // Combine the preserved entry context with the vault and live data. Never invent
            // an exit candle or rewrite a market candle to match the execution price.
            val entryCandles = openSnap?.recentCandles.orEmpty().map {
                Candle(time = it.t, open = it.o, high = it.h, low = it.l, close = it.c)
            }
            val vaultCandles = PhoneStorageVaultManager.loadPersistedCandles(
                closedTrade.symbol, closedTrade.interval, closeAt,
            )
            val liveCandles = closeCandles.ifEmpty {
                LiveQuoteFetcher.peekCachedCandles(closedTrade.symbol, closedTrade.interval)
            }
            val periodMs = closedTrade.interval.minutes * 60_000L
            val rawClosedCandles = (entryCandles + vaultCandles + liveCandles)
                .filter { it.time <= closeAt && it.time >= closedTrade.openedAt - 52 * periodMs }
                .filter { it.open.isFinite() && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.close > 0.0 && it.high >= it.low }
                .associateBy { it.time }.values.sortedBy { it.time }
            val missingEntry = rawClosedCandles.none { it.time <= closedTrade.openedAt && it.time + periodMs > closedTrade.openedAt }
            val missingExit = rawClosedCandles.none { it.time <= closeAt && it.time + periodMs > closeAt }
            val hasGaps = rawClosedCandles.zipWithNext().any { (a, b) -> b.time - a.time > periodMs * 2 }
            val sourceNote = if (missingEntry || missingExit || hasGaps) "داده کندل ناقص است؛ فاصله‌ها بازسازی نشده‌اند" else "کندل‌های ثبت‌شده؛ نمایش تجمیعی OHLC"
            val compactCandles = compressArchiveCandles(rawClosedCandles, 160)

            val trigSnap = if (!missingExit && !hasGaps) IchimokuStrategyEngine.computeSnapshot(rawClosedCandles) else null
            val fixedRisk = closedTrade.fixedRiskUsd.takeIf { it > 0.0 } ?: 5.0
            val expectedWin = round2(fixedRisk * closedTrade.riskReward)
            val realizedPnl = closedTrade.pnlUsd ?: 0.0
            val realizedR = closedTrade.rMultiple ?: (realizedPnl / fixedRisk.coerceAtLeast(0.5))
            val tNum = closedTrade.displayTradeNumber
            val closeCatFa = classifyCloseConditionFa(closedTrade)

            val closeRecord = ArchivedTradeSnapshotRecord(
                tradeId = closedTrade.id,
                tradeNumber = tNum,
                snapshotPhase = "CLOSE",
                snapshotTimestampMs = closeAt,
                symbol = closedTrade.symbol,
                timeframeLabel = closedTrade.interval.label,
                action = closedTrade.action.name,
                strategyId = closedTrade.strategyId,
                strategyTitleFa = closedTrade.strategyTitleFa,
                strategyBadgeFa = openSnap?.strategyBadgeFa ?: closedTrade.strategyTitleFa.take(24),
                entryPrice = closedTrade.entry,
                stopLossPrice = closedTrade.stopLoss,
                takeProfitPrice = closedTrade.takeProfit,
                riskReward = closedTrade.riskReward,
                optimalRiskReward = closedTrade.effectiveOptimalRr,
                fixedRiskUsd = fixedRisk,
                expectedProfitUsd = expectedWin,
                confidence = closedTrade.confidence,
                openedAtMs = closedTrade.openedAt,
                exitPrice = closedTrade.exitPrice,
                exitAtMs = closeAt,
                exitReasonFa = closedTrade.exitReason ?: closeCatFa,
                realizedPnlUsd = round2(realizedPnl),
                realizedRMultiple = round2(realizedR),
                closeConditionCategoryFa = closeCatFa,
                rrFormulaFa = openSnap?.rrFormulaFa ?: closedTrade.buildRrFormulaSummaryFa(),
                slBasisFa = openSnap?.slBasisFa ?: "سنگر ساختاری کیجون/کومو",
                tpBasisFa = openSnap?.tpBasisFa ?: "موج هوسودا",
                tenkan = trigSnap?.tenkan ?: openSnap?.tenkan ?: exitP,
                kijun = trigSnap?.kijun ?: openSnap?.kijun ?: closedTrade.stopLoss,
                currentSpanA = trigSnap?.currentSpanA ?: openSnap?.currentSpanA ?: closedTrade.entry,
                currentSpanB = trigSnap?.currentSpanB ?: openSnap?.currentSpanB ?: closedTrade.stopLoss,
                futureSpanA = trigSnap?.futureSpanA ?: openSnap?.futureSpanA ?: closedTrade.takeProfit,
                futureSpanB = trigSnap?.futureSpanB ?: openSnap?.futureSpanB ?: closedTrade.entry,
                atr14 = trigSnap?.atr14 ?: openSnap?.atr14 ?: (abs(closedTrade.entry - closedTrade.stopLoss) * 0.5),
                rsi14 = trigSnap?.rsi14 ?: openSnap?.rsi14 ?: 50.0,
                rsi7 = trigSnap?.rsi7 ?: openSnap?.rsi7 ?: 50.0,
                ema21 = trigSnap?.ema21 ?: openSnap?.ema21 ?: exitP,
                ema34 = trigSnap?.ema34 ?: openSnap?.ema34 ?: exitP,
                adx14 = trigSnap?.adx14 ?: openSnap?.adx14 ?: 25.0,
                conditionsSummary = closedTrade.entryConditions.ifEmpty { openSnap?.conditionsSummary.orEmpty() },
                candleSourceNote = sourceNote,
                indicatorsAvailable = trigSnap != null,
                recentCandles = compactCandles.map {
                    ArchivedCandlePoint(
                        t = it.time,
                        o = roundPrice(it.open),
                        h = roundPrice(it.high),
                        l = roundPrice(it.low),
                        c = roundPrice(it.close),
                    )
                },
                fullPaperTrade = closedTrade,
            )

            val bitmap = renderTradeIchimokuSnapshotBitmap(closeRecord, compactCandles)
            val webpBytes = compressBitmapToCompactWebp(bitmap, quality = 80)
            bitmap.recycle()

            val embeddedBytes = attachEmbeddedJsonToImageBytes(webpBytes, closeRecord)

            val dir = File(context.filesDir, ARCHIVE_DIR_NAME).apply { mkdirs() }
            val safeSym = closedTrade.symbol.replace("/", "_").replace(" ", "_")
            val fileName = "AurumTrade_No${tNum}_CLOSE_${safeSym}_${closedTrade.interval.label}_${closeAt}.webp"
            val localFile = File(dir, fileName)
            localFile.writeBytes(embeddedBytes)

            val galleryUri = saveImageAndSelfReadableDataToPhoneStorage(
                context = context,
                fileName = fileName,
                imageBytesWithEmbeddedMeta = embeddedBytes,
                record = closeRecord,
            )

            val finalCloseRecord = closeRecord.copy(
                localImagePath = localFile.absolutePath,
                phoneGalleryUri = galleryUri,
                imageSizeBytes = embeddedBytes.size.toLong(),
            )

            val updated = (_records.value.filterNot { it.tradeId == closedTrade.id && it.isCloseSnapshot } + finalCloseRecord)
                .sortedByDescending { it.effectiveTimestampMs }
            _records.value = updated
            saveIndexToDisk(context, updated)
            finalCloseRecord
        }.getOrNull()
    }

    /**
     * رندر گرافیکی باکیفیت و کم‌حجم اسکرین‌شات معامله ایچیموکو (کندل‌ها + ابر کومو + ابر آینده ۲۶ کندلی +
     * تنکان و کیجون + باکس پوزیشن Long/Short با نواحی سبز حد سود و قرمز حد ضرر + جدول شروط ورود).
     */
    fun renderTradeIchimokuSnapshotBitmap(
        record: ArchivedTradeSnapshotRecord,
        rawCandles: List<Candle> = emptyList(),
    ): Bitmap {
        val width = 1200
        val height = 820
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // پس‌زمینه سرمه‌ای تیره سازمانی
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.parseColor("#0A0F1D"),
                Color.parseColor("#070B14"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val isBuy = record.action.equals("BUY", ignoreCase = true)
        val sideColor = if (isBuy) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
        val goldColor = Color.parseColor("#F5C451")
        val cyanColor = Color.parseColor("#38BDF8")
        val mutedText = Color.parseColor("#94A3B8")
        val whiteText = Color.parseColor("#F8FAFC")

        // ── ۱. نوار هدر بالای تصویر ────────────────────────────────────────────
        val headerRect = RectF(18f, 16f, width - 18f, 114f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111A2E")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = sideColor
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        canvas.drawRoundRect(headerRect, 16f, 16f, cardPaint)
        canvas.drawRoundRect(headerRect, 16f, 16f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = whiteText
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = goldColor
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mutedText
            textSize = 15.5f
        }

        val sideLabel = if (isBuy) "BUY / LONG" else "SELL / SHORT"
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(record.effectiveTimestampMs))
        val tNum = record.displayTradeNumber
        val phaseTitle = if (record.isCloseSnapshot) {
            "🏁 بسته شدن (${record.closeConditionCategoryFa.ifBlank { "CLOSE" }})"
        } else {
            "📸 باز شدن (OPEN)"
        }
        canvas.drawText(
            "معامله #$tNum  ·  $phaseTitle  ·  ${record.symbol} [${record.timeframeLabel}]  ·  $sideLabel  ·  R:R 1:${String.format(Locale.US, "%.2f", record.riskReward)}",
            36f,
            54f,
            titlePaint,
        )
        val pnlSummaryText = if (record.isCloseSnapshot && record.realizedPnlUsd != null) {
            val pnl = record.realizedPnlUsd
            val rMult = record.realizedRMultiple ?: 0.0
            val sign = if (pnl >= 0.0) "+" else "-"
            "نتیجه نهایی: ${sign}$${String.format(Locale.US, "%.2f", abs(pnl))} (${sign}${String.format(Locale.US, "%.2f", abs(rMult))}R)  |  خروج: ${fmtPrice(record.exitPrice ?: record.entryPrice)}  |  علت: ${record.exitReasonFa.take(36)}"
        } else {
            "ریسک: -$${String.format(Locale.US, "%.2f", record.fixedRiskUsd)}  ←  سود هدف: +$${String.format(Locale.US, "%.2f", record.expectedProfitUsd)} (Optimal 1:${String.format(Locale.US, "%.2f", record.optimalRiskReward)})"
        }
        canvas.drawText(
            "استراتژی: ${record.strategyTitleFa}  |  زمان: $dateStr  |  $pnlSummaryText",
            36f,
            90f,
            subPaint,
        )

        // بج بزرگ و خوانای شماره معامله در گوشه راست هدر
        val numBadgeRect = RectF(width - 250f, 26f, width - 32f, 102f)
        val numBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (record.isCloseSnapshot) {
                if ((record.realizedPnlUsd ?: 0.0) >= 0.0) Color.parseColor("#065F46") else Color.parseColor("#7F1D1D")
            } else {
                Color.parseColor("#1E3A8A")
            }
            style = Paint.Style.FILL
        }
        val numBadgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = goldColor
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
        }
        canvas.drawRoundRect(numBadgeRect, 14f, 14f, numBadgePaint)
        canvas.drawRoundRect(numBadgeRect, 14f, 14f, numBadgeBorder)
        val numBigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = goldColor
            textSize = 27f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val numSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = whiteText
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("معامله #$tNum", numBadgeRect.centerX(), 62f, numBigPaint)
        canvas.drawText(if (record.isCloseSnapshot) "بسته شدن (CLOSE)" else "باز شدن (OPEN)", numBadgeRect.centerX(), 88f, numSubPaint)

        // ── ۲. ناحیه اصلی چارت ایچیموکو + باکس پوزیشن SL/TP ────────────────────
        val chartLeft = 24f
        val chartTop = 128f
        val chartRight = width - 220f
        val chartBottom = 612f
        val chartRect = RectF(chartLeft, chartTop, chartRight, chartBottom)

        val chartBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0B1324")
        }
        val chartBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }
        canvas.drawRoundRect(chartRect, 14f, 14f, chartBgPaint)
        canvas.drawRoundRect(chartRect, 14f, 14f, chartBorderPaint)

        // Only draw recorded candles; missing history is never synthesized.
        val candlesToDraw: List<Candle> = when {
            rawCandles.isNotEmpty() -> if (record.isCloseSnapshot) rawCandles else rawCandles.takeLast(46)
            record.recentCandles.isNotEmpty() -> (if (record.isCloseSnapshot) record.recentCandles else record.recentCandles.takeLast(46)).map {
                Candle(time = it.t, open = it.o, high = it.h, low = it.l, close = it.c, volume = 1.0, closed = true)
            }
            else -> emptyList()
        }

        val displayCandles = if (record.isCloseSnapshot) compressArchiveCandles(candlesToDraw, 80) else candlesToDraw
        val series = IchimokuStrategyEngine.computeChartSeries(displayCandles)
        val bars = if (record.isCloseSnapshot) series.bars else series.bars.takeLast(40)
        val futureKumo = series.futureKumo.take(26)

        // محاسبه بازه قیمتی کل چارت (شامل کندل‌ها، کومو، Entry، SL، TP و Exit)
        var minPrice = minOf(record.entryPrice, record.stopLossPrice, record.takeProfitPrice)
        var maxPrice = maxOf(record.entryPrice, record.stopLossPrice, record.takeProfitPrice)
        record.exitPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { ex ->
            minPrice = minOf(minPrice, ex)
            maxPrice = maxOf(maxPrice, ex)
        }
        if (!record.isCloseSnapshot) bars.forEach { b ->
            minPrice = minOf(minPrice, b.candle.low, b.currentSpanA, b.currentSpanB, b.tenkan, b.kijun)
            maxPrice = maxOf(maxPrice, b.candle.high, b.currentSpanA, b.currentSpanB, b.tenkan, b.kijun)
        }
        if (!record.isCloseSnapshot) futureKumo.forEach { fk ->
            minPrice = minOf(minPrice, fk.spanA, fk.spanB)
            maxPrice = maxOf(maxPrice, fk.spanA, fk.spanB)
        }
        val span = (maxPrice - minPrice).coerceAtLeast(abs(record.entryPrice) * 0.004)
        val paddedMin = minPrice - span * 0.10
        val paddedMax = maxPrice + span * 0.10
        val totalSpan = (paddedMax - paddedMin).coerceAtLeast(1e-8)

        fun priceToY(p: Double): Float {
            val ratio = ((p - paddedMin) / totalSpan).coerceIn(0.0, 1.0)
            return (chartBottom - 16f - ratio * (chartRect.height() - 32f)).toFloat()
        }

        // خطوط شبکه افقی و برچسب قیمت در سمت راست
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#162238")
            strokeWidth = 1f
        }
        for (g in 0..5) {
            val p = paddedMin + (totalSpan * g / 5.0)
            val y = priceToY(p)
            canvas.drawLine(chartLeft + 8f, y, chartRight - 8f, y, gridPaint)
            canvas.drawText(fmtPrice(p), chartRight + 10f, y + 5f, smallPaint)
        }

        val totalSlots = (bars.size + futureKumo.size.coerceAtLeast(18)).coerceAtLeast(30)
        val usableWidth = chartRect.width() - 32f
        val slotWidth = usableWidth / totalSlots.toFloat()
        fun slotToX(slotIdx: Int): Float = chartLeft + 16f + slotIdx * slotWidth

        // ── ۲.الف: رسم ابر کوموی جاری و ابر آینده ۲۶ کندلی (+1..+26) ──────────
        data class KumoPoint(val slot: Int, val ssa: Double, val ssb: Double)
        val allKumoPoints = mutableListOf<KumoPoint>()
        bars.forEachIndexed { idx, b ->
            allKumoPoints += KumoPoint(idx, b.currentSpanA, b.currentSpanB)
        }
        futureKumo.forEachIndexed { fIdx, fk ->
            allKumoPoints += KumoPoint(bars.size + fIdx, fk.spanA, fk.spanB)
        }

        val bullCloudFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(52, 16, 185, 129)
            style = Paint.Style.FILL
        }
        val bearCloudFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(52, 239, 68, 68)
            style = Paint.Style.FILL
        }
        for (i in 0 until (allKumoPoints.size - 1)) {
            val p1 = allKumoPoints[i]
            val p2 = allKumoPoints[i + 1]
            val path = Path().apply {
                moveTo(slotToX(p1.slot), priceToY(p1.ssa))
                lineTo(slotToX(p2.slot), priceToY(p2.ssa))
                lineTo(slotToX(p2.slot), priceToY(p2.ssb))
                lineTo(slotToX(p1.slot), priceToY(p1.ssb))
                close()
            }
            val isBullKumo = (p1.ssa + p2.ssa) >= (p1.ssb + p2.ssb)
            canvas.drawPath(path, if (isBullKumo) bullCloudFill else bearCloudFill)
        }

        // خط عمودی مرز کندل فعلی و شروع ابر آینده ۲۶ کندلی
        val nowX = slotToX((bars.size - 1).coerceAtLeast(0))
        val futureDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 245, 196, 81)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }
        canvas.drawLine(nowX, chartTop + 10f, nowX, chartBottom - 10f, futureDividerPaint)
        canvas.drawText("Future Kumo (+26)", nowX + 8f, chartTop + 26f, smallPaint.apply { color = goldColor })

        // ── ۲.ب: رسم باکس پوزیشن TradingView (ناحیه سبز TP و ناحیه قرمز SL) ──
        val entryIndex = bars.indexOfLast { it.candle.time <= record.openedAtMs }.coerceAtLeast(0)
        val boxLeft = (if (record.isCloseSnapshot) slotToX(entryIndex) else nowX - slotWidth * 2f).coerceAtLeast(chartLeft + 20f)
        val boxRight = (chartRight - 14f).coerceAtLeast(boxLeft + 80f)
        val entryY = priceToY(record.entryPrice)
        val tpY = priceToY(record.takeProfitPrice)
        val slY = priceToY(record.stopLossPrice)

        // مستطیل سبز حد سود (Take-Profit Zone)
        val tpRect = RectF(boxLeft, min(entryY, tpY), boxRight, max(entryY, tpY))
        val tpZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(68, 16, 185, 129)
            style = Paint.Style.FILL
        }
        val tpStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
        }
        canvas.drawRect(tpRect, tpZonePaint)
        canvas.drawLine(boxLeft, tpY, boxRight, tpY, tpStrokePaint)

        // مستطیل قرمز حد ضرر (Stop-Loss Zone)
        val slRect = RectF(boxLeft, min(entryY, slY), boxRight, max(entryY, slY))
        val slZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, 239, 68, 68)
            style = Paint.Style.FILL
        }
        val slStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
        }
        canvas.drawRect(slRect, slZonePaint)
        canvas.drawLine(boxLeft, slY, boxRight, slY, slStrokePaint)

        // خط طلایی نقطه ورود (Entry Line)
        val entryLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = goldColor
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
        }
        canvas.drawLine(boxLeft, entryY, boxRight, entryY, entryLinePaint)

        // ── ۲.ج: رسم کندل‌ها و خطوط Tenkan-sen و Kijun-sen ────────────────────
        val bullCandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            strokeWidth = 2f
        }
        val bearCandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EF4444")
            strokeWidth = 2f
        }
        val tenkanPath = Path()
        val kijunPath = Path()

        bars.forEachIndexed { idx, b ->
            val cx = slotToX(idx)
            val c = b.candle
            val isBull = c.close >= c.open
            val p = if (isBull) bullCandlePaint else bearCandlePaint
            val highY = priceToY(c.high)
            val lowY = priceToY(c.low)
            val openY = priceToY(c.open)
            val closeY = priceToY(c.close)
            canvas.drawLine(cx, highY, cx, lowY, p)
            val bodyTop = min(openY, closeY)
            val bodyBot = max(openY, closeY).coerceAtLeast(bodyTop + 2.5f)
            val halfW = (slotWidth * 0.34f).coerceIn(2.5f, 7.5f)
            canvas.drawRect(cx - halfW, bodyTop, cx + halfW, bodyBot, p)

            val ty = priceToY(b.tenkan)
            val ky = priceToY(b.kijun)
            if (idx == 0) {
                tenkanPath.moveTo(cx, ty)
                kijunPath.moveTo(cx, ky)
            } else {
                tenkanPath.lineTo(cx, ty)
                kijunPath.lineTo(cx, ky)
            }
        }

        val tenkanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyanColor
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        val kijunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
        }
        canvas.drawPath(tenkanPath, tenkanPaint)
        canvas.drawPath(kijunPath, kijunPaint)

        // برچسب‌های قیمت Entry / TP / SL در سمت راست چارت
        fun drawPriceTag(y: Float, label: String, bgColor: Int) {
            val safeY = y.coerceIn(chartTop + 18f, chartBottom - 18f)
            val tagRect = RectF(chartRight + 6f, safeY - 15f, width - 18f, safeY + 15f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 14.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawRoundRect(tagRect, 8f, 8f, p)
            canvas.drawText(label, tagRect.left + 8f, safeY + 5f, tp)
        }

        drawPriceTag(tpY, "TP: ${fmtPrice(record.takeProfitPrice)}", Color.parseColor("#059669"))
        drawPriceTag(entryY, "IN: ${fmtPrice(record.entryPrice)}", Color.parseColor("#D97706"))
        drawPriceTag(slY, "SL: ${fmtPrice(record.stopLossPrice)}", Color.parseColor("#DC2626"))

        // اگر اسکرین‌شات مربوط به لحظه بسته شدن معامله (CLOSE) است، خط قیمت خروج و برچسب OUT را هم رسم کن
        record.exitPrice?.takeIf { record.isCloseSnapshot && it.isFinite() && it > 0.0 }?.let { exPrice ->
            val exitY = priceToY(exPrice)
            val isWinClose = (record.realizedPnlUsd ?: 0.0) >= 0.0
            val exitColor = if (isWinClose) Color.parseColor("#22D3EE") else Color.parseColor("#FB7185")
            val exitLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = exitColor
                style = Paint.Style.STROKE
                strokeWidth = 3.0f
                pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
            }
            canvas.drawLine(boxLeft, exitY, boxRight, exitY, exitLinePaint)
            canvas.drawCircle(nowX, exitY, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = exitColor })
            drawPriceTag(exitY, "OUT: ${fmtPrice(exPrice)}", if (isWinClose) Color.parseColor("#0E7490") else Color.parseColor("#BE123C"))
        }

        if (record.isCloseSnapshot) {
            canvas.drawText("IN", slotToX(entryIndex), chartBottom - 8f, smallPaint.apply { color = goldColor })
            canvas.drawText("OUT", nowX - 25f, chartBottom - 8f, smallPaint.apply { color = cyanColor })
            canvas.drawText("Aggregated OHLC / approximate indicators; gaps possible; prices clipped", chartLeft + 12f, chartBottom - 28f, smallPaint)
            canvas.drawText(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(record.openedAtMs)), boxLeft, chartBottom - 45f, smallPaint)
            record.exitAtMs?.let { canvas.drawText(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it)), (nowX - 130f).coerceAtLeast(boxLeft), chartBottom - 60f, smallPaint) }
        }
        if (bars.isEmpty()) canvas.drawText("NO RECORDED CANDLES", chartLeft + 30f, chartTop + 95f, smallPaint)

        // بج واترمارک شماره معامله مستقیماً روی بوم چارت (گوشه بالا چپ چارت)
        val chartBadgeRect = RectF(chartLeft + 14f, chartTop + 14f, chartLeft + 340f, chartTop + 58f)
        val chartBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 15, 23, 42)
            style = Paint.Style.FILL
        }
        val chartBadgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = goldColor
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
        }
        canvas.drawRoundRect(chartBadgeRect, 10f, 10f, chartBadgeBg)
        canvas.drawRoundRect(chartBadgeRect, 10f, 10f, chartBadgeStroke)
        val chartBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = goldColor
            textSize = 18.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val phaseShort = if (record.isCloseSnapshot) "🏁 CLOSE (${record.closeConditionCategoryFa.take(16)})" else "📸 OPEN (ورود)"
        canvas.drawText("معامله #$tNum  ·  $phaseShort", chartBadgeRect.left + 14f, chartBadgeRect.top + 29f, chartBadgeText)

        // ── ۳. پنل پایین: شروط ۱۶گانه ایچیموکو و اندیکاتورها در لحظه باز شدن ───
        val footerRect = RectF(18f, 626f, width - 18f, height - 16f)
        canvas.drawRoundRect(footerRect, 14f, 14f, cardPaint)
        canvas.drawRoundRect(footerRect, 14f, 14f, chartBorderPaint)

        val footerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = whiteText
            textSize = 15.5f
        }
        val footerAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyanColor
            textSize = 15.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        canvas.drawText(
            "📊 ${if (record.indicatorsAvailable) "اندیکاتورهای مرحله" else "اندیکاتور ناموجود/تخمینی"}:  Tenkan=${fmtPrice(record.tenkan)}  |  Kijun=${fmtPrice(record.kijun)}  |  SpanA=${fmtPrice(record.currentSpanA)}  |  SpanB=${fmtPrice(record.currentSpanB)}  |  ATR14=${fmtPrice(record.atr14)}  |  RSI14=${String.format(Locale.US, "%.1f", record.rsi14)}  |  ADX14=${String.format(Locale.US, "%.1f", record.adx14)}",
            34f,
            658f,
            footerAccentPaint,
        )
        val pre3Badge = record.fullPaperTrade?.preEntry3CandleCategoryFa?.takeIf { it.isNotBlank() } ?: "ثبت نشده"
        canvas.drawText(
            "📐 ${record.rrFormulaFa}   |   🕯️ ۳ کندل قبل: $pre3Badge   |   🛡️ مبنای SL: ${record.slBasisFa.take(40)}",
            34f,
            690f,
            footerTextPaint,
        )

        val conds = record.conditionsSummary
        if (conds.isNotEmpty()) {
            val row1 = conds.take(6).joinToString("  ·  ") { c ->
                val icon = if (c.status.equals("CONFIRMED", true) || c.status.contains("OK", true)) "✅" else "⚠️"
                "$icon ${c.name.take(20)}"
            }
            val row2 = conds.drop(6).take(6).joinToString("  ·  ") { c ->
                val icon = if (c.status.equals("CONFIRMED", true) || c.status.contains("OK", true)) "✅" else "⚠️"
                "$icon ${c.name.take(20)}"
            }
            val row3 = conds.drop(12).take(4).joinToString("  ·  ") { c ->
                val icon = if (c.status.equals("CONFIRMED", true) || c.status.contains("OK", true)) "✅" else "⚠️"
                "$icon ${c.name.take(24)}"
            }
            canvas.drawText(row1, 34f, 724f, smallPaint.apply { color = Color.parseColor("#CBD5E1") })
            if (row2.isNotBlank()) canvas.drawText(row2, 34f, 754f, smallPaint)
            if (row3.isNotBlank()) canvas.drawText(row3, 34f, 784f, smallPaint)
        } else {
            canvas.drawText(
                "اطلاعات شروط ثبت نشده است",
                34f,
                735f,
                smallPaint.apply { color = Color.parseColor("#10B981") },
            )
        }

        return bmp
    }

    private fun compressBitmapToCompactWebp(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality.coerceIn(50, 92), out)
        return out.toByteArray()
    }

    /**
     * چسباندن متادیتای کامل JSON معامله و کندل‌ها در انتهای فایل تصویر WebP
     * تا هر زمان کاربر فایل تصویر را از گوشی انتخاب کرد، خود اپ بتواند دیتای کامل معامله را از دل عکس بخواند!
     */
    fun attachEmbeddedJsonToImageBytes(
        webpBytes: ByteArray,
        record: ArchivedTradeSnapshotRecord,
    ): ByteArray {
        val payloadJson = json.encodeToString(record)
        val trailer = (EMBED_START_MARKER + payloadJson + EMBED_END_MARKER).toByteArray(Charsets.UTF_8)
        val combined = ByteArray(webpBytes.size + trailer.size)
        System.arraycopy(webpBytes, 0, combined, 0, webpBytes.size)
        System.arraycopy(trailer, 0, combined, webpBytes.size, trailer.size)
        return combined
    }

    /**
     * استخراج دیتای کامل معامله و شرایط ایچیموکو از بایت‌های یک فایل تصویر آرشیو (`.webp`) یا فایل `.json`.
     */
    fun extractEmbeddedSnapshotFromBytes(bytes: ByteArray): ArchivedTradeSnapshotRecord? {
        return runCatching {
            val text = String(bytes, Charsets.UTF_8)
            val startIdx = text.lastIndexOf(EMBED_START_MARKER)
            val endIdx = text.lastIndexOf(EMBED_END_MARKER)
            if (startIdx >= 0 && endIdx > startIdx) {
                val jsonSlice = text.substring(startIdx + EMBED_START_MARKER.length, endIdx).trim()
                return@runCatching json.decodeFromString(ArchivedTradeSnapshotRecord.serializer(), jsonSlice)
            }
            val trimmed = text.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return@runCatching json.decodeFromString(ArchivedTradeSnapshotRecord.serializer(), trimmed)
            }
            null
        }.getOrNull()
    }

    fun loadBitmapForRecord(record: ArchivedTradeSnapshotRecord): Bitmap? {
        return runCatching {
            if (record.localImagePath.isNotBlank()) {
                val f = File(record.localImagePath)
                if (f.exists()) {
                    val bytes = f.readBytes()
                    val markerBytes = EMBED_START_MARKER.toByteArray(Charsets.UTF_8)
                    val cleanLen = indexOfSubArray(bytes, markerBytes).takeIf { it > 0 } ?: bytes.size
                    return@runCatching BitmapFactory.decodeByteArray(bytes, 0, cleanLen)
                }
            }
            renderTradeIchimokuSnapshotBitmap(record)
        }.getOrNull()
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in haystack.indices.reversed()) {
            if (i + needle.size > haystack.size) continue
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * ذخیره خودکار تصویر کم‌حجم WebP در گالری گوشی (`Pictures/AurumEdge/TradeArchive`)
     * و ذخیره نسخه خودخوان در `Download/AurumEdge/Archive` تا بعداً اپ بتواند دیتای خود را از حافظه گوشی بخواند.
     */
    private fun saveImageAndSelfReadableDataToPhoneStorage(
        context: Context,
        fileName: String,
        imageBytesWithEmbeddedMeta: ByteArray,
        record: ArchivedTradeSnapshotRecord,
    ): String {
        var savedGalleryUri = ""
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val imgValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AurumEdge/TradeArchive")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imgValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(imageBytesWithEmbeddedMeta) }
                    imgValues.clear()
                    imgValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, imgValues, null, null)
                    savedGalleryUri = uri.toString()
                }

                // همچنین ذخیره فایل در Download/AurumEdge/Archive برای بازخوانی خودکار بعدی
                val dlValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/webp")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AurumEdge/Archive")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val dlUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                if (dlUri != null) {
                    resolver.openOutputStream(dlUri)?.use { it.write(imageBytesWithEmbeddedMeta) }
                    dlValues.clear()
                    dlValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(dlUri, dlValues, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val picDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "AurumEdge/TradeArchive",
                ).apply { mkdirs() }
                val outFile = File(picDir, fileName)
                outFile.writeBytes(imageBytesWithEmbeddedMeta)
                savedGalleryUri = Uri.fromFile(outFile).toString()
            }
        }
        return savedGalleryUri
    }

    /**
     * بازخوانی خودکار تمام عکس‌ها و فایل‌های آرشیو ذخیره‌شده در حافظه گوشی (`Pictures/AurumEdge/TradeArchive` و `Download/AurumEdge/Archive`)
     * یا از یک URI انتخابی کاربر، و بازگردانی کامل رکوردهای آرشیو و معاملات به داخل اپ!
     */
    fun scanAndRestoreFromPhoneStorage(
        context: Context,
        journalStore: JournalStore? = null,
    ): Int {
        var restoredCount = 0
        val restoredTrades = mutableListOf<PaperTrade>()
        synchronized(this) {
            val currentMap = _records.value.associateBy { "${it.tradeId}_${it.snapshotPhase}" }.toMutableMap()
            val dir = File(context.filesDir, ARCHIVE_DIR_NAME).apply { mkdirs() }

            fun ingestBytes(bytes: ByteArray) {
                val rec = extractEmbeddedSnapshotFromBytes(bytes) ?: return
                val key = "${rec.tradeId}_${rec.snapshotPhase}"
                val localFile = File(dir, "AurumTrade_No${rec.displayTradeNumber}_${rec.snapshotPhase}_${rec.symbol.replace("/", "_")}_${rec.timeframeLabel}_${rec.effectiveTimestampMs}.webp")
                if (!localFile.exists()) {
                    runCatching { localFile.writeBytes(bytes) }
                }
                val updatedRec = rec.copy(
                    localImagePath = localFile.absolutePath,
                    imageSizeBytes = bytes.size.toLong(),
                )
                if (key !in currentMap) {
                    restoredCount++
                }
                currentMap[key] = updatedRec
                updatedRec.fullPaperTrade?.let { trade ->
                    restoredTrades += trade
                }
            }

            // ۱. اسکن MediaStore.Images و MediaStore.Downloads در اندروید ۱۰+
            runCatching {
                val resolver = context.contentResolver
                val collections = mutableListOf<Uri>(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    collections += MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                for (collection in collections) {
                    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                    val selectionArgs = arrayOf("AurumTrade_%")
                    resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val itemUri = android.content.ContentUris.withAppendedId(collection, id)
                            runCatching {
                                resolver.openInputStream(itemUri)?.use { stream ->
                                    ingestBytes(stream.readBytes())
                                }
                            }
                        }
                    }
                }
            }

            // ۲. اسکن پوشه داخلی اپ
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith("AurumTrade_")) {
                        ingestBytes(f.readBytes())
                    }
                }
            }

            val merged = currentMap.values.sortedByDescending { it.openedAtMs }.take(500)
            _records.value = merged
            saveIndexToDisk(context, merged)
        }
        restoredTrades.forEach { trade ->
            runCatching { journalStore?.restoreArchivedTradeIfMissing(trade) }
        }
        return restoredCount
    }

    /**
     * بازخوانی از یک فایل تصویر `.webp` یا `.json` انتخاب‌شده توسط کاربر از فایل‌منیجر گوشی.
     */
    fun importSnapshotFromUri(
        context: Context,
        uri: Uri,
        journalStore: JournalStore? = null,
    ): ArchivedTradeSnapshotRecord? {
        return runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val rec = extractEmbeddedSnapshotFromBytes(bytes) ?: return null
            val updatedRec = synchronized(this) {
                val dir = File(context.filesDir, ARCHIVE_DIR_NAME).apply { mkdirs() }
                val localFile = File(dir, "AurumTrade_No${rec.displayTradeNumber}_${rec.snapshotPhase}_${rec.symbol.replace("/", "_")}_${rec.timeframeLabel}_${rec.effectiveTimestampMs}.webp")
                localFile.writeBytes(bytes)
                val nextRec = rec.copy(
                    localImagePath = localFile.absolutePath,
                    imageSizeBytes = bytes.size.toLong(),
                )
                val merged = (_records.value.filterNot { it.tradeId == rec.tradeId && it.snapshotPhase == rec.snapshotPhase } + nextRec)
                    .sortedByDescending { it.effectiveTimestampMs }
                _records.value = merged
                saveIndexToDisk(context, merged)
                nextRec
            }
            updatedRec.fullPaperTrade?.let { trade ->
                runCatching { journalStore?.restoreArchivedTradeIfMissing(trade) }
            }
            updatedRec
        }.getOrNull()
    }

    private fun loadIndexFromDisk(context: Context): List<ArchivedTradeSnapshotRecord> {
        val file = File(context.filesDir, INDEX_FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ArchivedTradeSnapshotRecord>>(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun saveIndexToDisk(context: Context, list: List<ArchivedTradeSnapshotRecord>) {
        runCatching {
            val file = File(context.filesDir, INDEX_FILE_NAME)
            file.writeText(json.encodeToString(list), Charsets.UTF_8)
        }
    }

    private fun fmtPrice(v: Double): String {
        val a = abs(v)
        val fmt = when {
            a >= 500.0 -> "%.2f"
            a >= 10.0 -> "%.3f"
            else -> "%.5f"
        }
        return String.format(Locale.US, fmt, v)
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
    private fun roundPrice(v: Double): Double = kotlin.math.round(v * 100_000.0) / 100_000.0
}
