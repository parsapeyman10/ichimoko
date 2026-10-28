package com.aurum.edge.data

import android.content.Context
import android.os.Environment
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PreEntry3CandleCategory
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class DailyBackupBundle(
    val schemaVersion: Int = 1,
    val dateKey: String,
    val createdAtMs: Long,
    val totalTradesCount: Int,
    val openTradesCount: Int,
    val dayTradesCount: Int,
    val cumulativeNetPnlUsd: Double,
    val dayNetPnlUsd: Double,
    val openTrades: List<PaperTrade> = emptyList(),
    val dayTrades: List<PaperTrade> = emptyList(),
)

@Serializable
data class PhoneDailyBackupMeta(
    val dateKey: String,
    val fileName: String,
    val filePath: String,
    val totalTradesCount: Int,
    val dayTradesCount: Int,
    val dayNetPnlUsd: Double,
    val updatedAtMs: Long,
    val sizeBytes: Long,
)

@Serializable
data class CandleVaultMistakeAnalysis(
    val tradeId: String,
    val symbol: String,
    val intervalLabel: String,
    val strategyId: String,
    val mistakeCode: String,
    val mistakeBadgeFa: String,
    val mistakeExplanationFa: String,
    val lessonLearnedFa: String,
    val wentToTpAfterStopOut: Boolean = false,
    val maxFavorableAfterExitR: Double = 0.0,
)

data class PhoneVaultStatusSummary(
    val totalShardedDays: Int = 0,
    val totalDailyBackups: Int = 0,
    val latestBackupDateKey: String = "—",
    val totalStoredCandlesCount: Int = 0,
    val storedSymbolIntervalsCount: Int = 0,
    val oldestCandleTimestampMs: Long = 0L,
    val newestCandleTimestampMs: Long = 0L,
    val vaultPathDisplay: String = "Download/AurumEdge/Archive/",
    val mistakeLessons: List<CandleVaultMistakeAnalysis> = emptyList(),
)

/**
 * مدیریت فوق‌پیشرفته حافظه گوشی به روش `Time-Partitioned Daily Shards + 35-Day Columnar Candle Vault`:
 * ۱. پارتیشن‌بندی معاملات بر اساس روز (`Trades_YYYY-MM-DD.json`) + فایل داغ معاملات باز (`OpenTrades_Hot.json`)
 *    برای لود آنی هزاران معامله بدون سنگین شدن رم و بدون بازنویسی کل تاریخچه در هر تیک.
 * ۲. بک‌آپ خودکار روزانه (`AurumEdge_DailyBackup_YYYY-MM-DD.json`) در حافظه گوشی (`Download/AurumEdge/Archive/DailyBackups/`).
 * ۳. خزانه ۳۵ روزه (بیش از ۱ ماه کامل) کندل‌ها (`CandleVault`) با فرمت فشرده خطی TSV برای تمام ۱۵ نماد و تایم‌فریم‌ها،
 *    همراه با موتور کالبدشکافی پس از معامله (بررسی اینکه آیا بعد از استاپ خوردن، قیمت به تارگت رفته یا روند واقعاً برگشته است).
 */
object PhoneStorageVaultManager {

    const val CANDLE_RETENTION_DAYS: Long = 35L
    private const val CANDLE_RETENTION_MS: Long = CANDLE_RETENTION_DAYS * 86_400_000L

    private val tehranZone: ZoneId = ZoneId.of("Asia/Tehran")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val tradeListSerializer = ListSerializer(PaperTrade.serializer())

    @Volatile
    private var appContextRef: Context? = null

    private val lastShardCountByDay = ConcurrentHashMap<String, Int>()
    private val lastDailyBackupWriteMsByDay = ConcurrentHashMap<String, Long>()
    private val memoryCandleVault = ConcurrentHashMap<String, List<Candle>>()
    private val lastCandleDiskFlushMs = ConcurrentHashMap<String, Long>()

    private val _vaultStatus = MutableStateFlow(PhoneVaultStatusSummary())
    val vaultStatus: StateFlow<PhoneVaultStatusSummary> = _vaultStatus.asStateFlow()

    private val _dailyBackups = MutableStateFlow<List<PhoneDailyBackupMeta>>(emptyList())
    val dailyBackups: StateFlow<List<PhoneDailyBackupMeta>> = _dailyBackups.asStateFlow()

    fun attachContext(context: Context) {
        if (appContextRef == null) {
            appContextRef = context.applicationContext
        }
    }

    fun dateKeyForMillis(ms: Long): String {
        val safeMs = if (ms > 0L) ms else System.currentTimeMillis()
        return Instant.ofEpochMilli(safeMs).atZone(tehranZone).toLocalDate().toString()
    }

    private fun resolveArchiveDirs(context: Context): List<File> {
        val list = mutableListOf<File>()
        runCatching {
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pubDownloads != null) {
                val dir = File(pubDownloads, "AurumEdge/Archive")
                if (dir.exists() || dir.mkdirs()) list += dir
            }
        }
        runCatching {
            val ext = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (ext != null) {
                val dir = File(ext, "AurumEdge/Archive")
                if (dir.exists() || dir.mkdirs()) list += dir
            }
        }
        val internal = File(context.filesDir, "phone_vault_archive").apply { mkdirs() }
        list += internal
        return list.distinctBy { it.absolutePath }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // بخش ۱: شاردینگ روزانه معاملات + بک‌آپ خودکار هر روز در حافظه گوشی
    // ══════════════════════════════════════════════════════════════════════════

    fun syncTradesToShardsAndDailyBackup(
        context: Context,
        trades: List<PaperTrade>,
        forceAllShards: Boolean = false,
    ) {
        attachContext(context)
        runCatching {
            val sorted = trades.sortedByDescending { it.openedAt }
            val openTrades = sorted.filter { it.isOpen }
            val byDay = sorted.groupBy { dateKeyForMillis(it.openedAt) }
            val todayKey = LocalDate.now(tehranZone).toString()
            val roots = resolveArchiveDirs(context)

            // ۱. ذخیره فوری معاملات باز در فایل سبک Hot
            val openEncoded = json.encodeToString(tradeListSerializer, openTrades)
            roots.forEach { root ->
                val shardDir = File(root, "DailyTradeShards").apply { mkdirs() }
                writeTextAtomic(File(shardDir, "OpenTrades_Hot.json"), openEncoded)
            }

            // ۲. ذخیره شاردهای روزانه (فقط روزهایی که تغییر کرده‌اند یا روز جاری)
            for ((dayKey, dayList) in byDay) {
                val signature = dayList.size * 31 + dayList.hashCode()
                val prevSig = lastShardCountByDay[dayKey]
                if (forceAllShards || dayKey == todayKey || prevSig != signature) {
                    val encodedDay = json.encodeToString(tradeListSerializer, dayList)
                    roots.forEach { root ->
                        val shardDir = File(root, "DailyTradeShards").apply { mkdirs() }
                        writeTextAtomic(File(shardDir, "Trades_$dayKey.json"), encodedDay)
                    }
                    lastShardCountByDay[dayKey] = signature
                }
            }

            // ۳. بک‌آپ خودکار روزانه برای امروز (و هر روزی که هنوز فایل بک‌آپ ندارد)
            val nowMs = System.currentTimeMillis()
            val lastBackupWrite = lastDailyBackupWriteMsByDay[todayKey] ?: 0L
            if (forceAllShards || nowMs - lastBackupWrite >= 15_000L || lastBackupWrite == 0L) {
                writeDailyBackupForDay(
                    roots = roots,
                    dayKey = todayKey,
                    dayTrades = byDay[todayKey].orEmpty(),
                    allTrades = sorted,
                    openTrades = openTrades,
                    nowMs = nowMs,
                )
                lastDailyBackupWriteMsByDay[todayKey] = nowMs
            }

            // اگر روزهای قبلی هم بک‌آپ ندارند، برای آن‌ها هم بک‌آپ روزانه بساز
            for ((dayKey, dayList) in byDay) {
                if (dayKey == todayKey) continue
                val primaryBackupDir = File(roots.first(), "DailyBackups").apply { mkdirs() }
                val targetFile = File(primaryBackupDir, "AurumEdge_DailyBackup_$dayKey.json")
                if (forceAllShards || !targetFile.exists()) {
                    writeDailyBackupForDay(
                        roots = roots,
                        dayKey = dayKey,
                        dayTrades = dayList,
                        allTrades = sorted,
                        openTrades = openTrades,
                        nowMs = nowMs,
                    )
                }
            }

            refreshVaultStatus(context, sorted)
        }
    }

    private fun writeDailyBackupForDay(
        roots: List<File>,
        dayKey: String,
        dayTrades: List<PaperTrade>,
        allTrades: List<PaperTrade>,
        openTrades: List<PaperTrade>,
        nowMs: Long,
    ) {
        val cumPnl = round((allTrades.filter { !it.isOpen }.sumOf { it.pnlUsd ?: 0.0 }) * 100.0) / 100.0
        val dayPnl = round((dayTrades.filter { !it.isOpen }.sumOf { it.pnlUsd ?: 0.0 }) * 100.0) / 100.0
        val bundle = DailyBackupBundle(
            schemaVersion = 1,
            dateKey = dayKey,
            createdAtMs = nowMs,
            totalTradesCount = allTrades.size,
            openTradesCount = openTrades.size,
            dayTradesCount = dayTrades.size,
            cumulativeNetPnlUsd = cumPnl,
            dayNetPnlUsd = dayPnl,
            openTrades = openTrades,
            dayTrades = dayTrades,
        )
        val encoded = json.encodeToString(DailyBackupBundle.serializer(), bundle)
        roots.forEach { root ->
            val backupDir = File(root, "DailyBackups").apply { mkdirs() }
            writeTextAtomic(File(backupDir, "AurumEdge_DailyBackup_$dayKey.json"), encoded)
        }
    }

    /**
     * خواندن فوق‌سریع و یکپارچه تمام معاملات از شاردهای روزانه و بک‌آپ‌های روزانه موجود در حافظه گوشی
     */
    fun loadAllTradesFromPhoneShards(context: Context): List<PaperTrade> {
        attachContext(context)
        val mergedById = LinkedHashMap<String, PaperTrade>()
        val roots = resolveArchiveDirs(context)

        for (root in roots) {
            val shardDir = File(root, "DailyTradeShards")
            if (shardDir.exists()) {
                val files = shardDir.listFiles { f ->
                    f.isFile && (f.name.startsWith("Trades_") || f.name == "OpenTrades_Hot.json") && f.name.endsWith(".json")
                }?.sortedByDescending { it.name }.orEmpty()
                for (f in files) {
                    val parsed = runCatching {
                        json.decodeFromString(tradeListSerializer, f.readText())
                    }.getOrNull().orEmpty()
                    for (t in parsed) {
                        val existing = mergedById[t.id]
                        if (existing == null || (!t.isOpen && existing.isOpen)) {
                            mergedById[t.id] = t
                        }
                    }
                }
            }

            val backupDir = File(root, "DailyBackups")
            if (backupDir.exists()) {
                val backupFiles = backupDir.listFiles { f ->
                    f.isFile && f.name.startsWith("AurumEdge_DailyBackup_") && f.name.endsWith(".json")
                }?.sortedByDescending { it.name }.orEmpty()
                for (bf in backupFiles) {
                    val bundle = runCatching {
                        json.decodeFromString(DailyBackupBundle.serializer(), bf.readText())
                    }.getOrNull() ?: continue
                    for (t in bundle.dayTrades + bundle.openTrades) {
                        val existing = mergedById[t.id]
                        if (existing == null || (!t.isOpen && existing.isOpen)) {
                            mergedById[t.id] = t
                        }
                    }
                }
            }
        }
        return mergedById.values.sortedByDescending { it.openedAt }
    }

    fun listDailyBackups(context: Context): List<PhoneDailyBackupMeta> {
        attachContext(context)
        val byDate = LinkedHashMap<String, PhoneDailyBackupMeta>()
        for (root in resolveArchiveDirs(context)) {
            val backupDir = File(root, "DailyBackups")
            if (!backupDir.exists()) continue
            val files = backupDir.listFiles { f ->
                f.isFile && f.name.startsWith("AurumEdge_DailyBackup_") && f.name.endsWith(".json")
            }?.sortedByDescending { it.name }.orEmpty()
            for (f in files) {
                val dateKey = f.name.removePrefix("AurumEdge_DailyBackup_").removeSuffix(".json")
                if (dateKey in byDate) continue
                val bundle = runCatching {
                    json.decodeFromString(DailyBackupBundle.serializer(), f.readText())
                }.getOrNull()
                byDate[dateKey] = PhoneDailyBackupMeta(
                    dateKey = dateKey,
                    fileName = f.name,
                    filePath = f.absolutePath,
                    totalTradesCount = bundle?.totalTradesCount ?: 0,
                    dayTradesCount = bundle?.dayTradesCount ?: 0,
                    dayNetPnlUsd = bundle?.dayNetPnlUsd ?: 0.0,
                    updatedAtMs = f.lastModified(),
                    sizeBytes = f.length(),
                )
            }
        }
        val list = byDate.values.sortedByDescending { it.dateKey }
        _dailyBackups.value = list
        return list
    }

    // ══════════════════════════════════════════════════════════════════════════
    // بخش ۲: خزانه ۳۵ روزه (۱ ماه کامل + ۵ روز بافر) کندل‌ها در حافظه گوشی
    // ══════════════════════════════════════════════════════════════════════════

    private fun maxCandlesForInterval(interval: Interval): Int = when (interval) {
        Interval.M1 -> 15_000
        Interval.M5 -> 10_080   // ۳۵ روز کامل کندل ۵ دقیقه (۳۵ × ۲۸۸)
        Interval.M15 -> 3_360   // ۳۵ روز کامل کندل ۱۵ دقیقه (۳۵ × ۹۶)
        Interval.M30 -> 1_680
        Interval.H1 -> 840      // ۳۵ روز کامل کندل ۱ ساعته (۳۵ × ۲۴)
        Interval.H4 -> 240      // ۴۰ روز کندل ۴ ساعته
        Interval.D1 -> 90
    }

    private fun candleFileName(symbol: String, interval: Interval): String {
        val safeSym = V1Universe.normalize(symbol).replace("/", "_").replace(" ", "_")
        return "${safeSym}_${interval.label}.tsv"
    }

    /**
     * بازیابی کندل‌های ذخیره‌شده ۱ ماه اخیر از حافظه گوشی برای نماد و تایم‌فریم مشخص.
     */
    fun loadPersistedCandles(
        symbolInput: String,
        interval: Interval,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Candle> {
        val symbol = V1Universe.normalize(symbolInput)
        val cacheKey = "$symbol|${interval.label}"
        memoryCandleVault[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return it }

        val ctx = appContextRef ?: return emptyList()
        val fileName = candleFileName(symbol, interval)
        val cutoff = nowMillis - CANDLE_RETENTION_MS

        for (root in resolveArchiveDirs(ctx)) {
            val file = File(File(root, "CandleVault"), fileName)
            if (!file.exists()) continue
            val parsed = parseCandlesTsv(file, cutoff)
            if (parsed.isNotEmpty()) {
                memoryCandleVault[cacheKey] = parsed
                return parsed
            }
        }
        return emptyList()
    }

    /**
     * ادغام کندل‌های جدید با تاریخچه ۱ ماه اخیر، حذف کندل‌های قدیمی‌تر از ۳۵ روز،
     * و ذخیره اتمیک در حافظه گوشی (`Download/AurumEdge/Archive/CandleVault/`).
     */
    fun appendAndPersistCandles(
        symbolInput: String,
        interval: Interval,
        incomingCandles: List<Candle>,
        nowMillis: Long = System.currentTimeMillis(),
        forceFlushToDisk: Boolean = false,
    ): List<Candle> {
        if (incomingCandles.isEmpty()) return loadPersistedCandles(symbolInput, interval, nowMillis)
        val symbol = V1Universe.normalize(symbolInput)
        val cacheKey = "$symbol|${interval.label}"
        val existing = loadPersistedCandles(symbol, interval, nowMillis)
        val cutoff = nowMillis - CANDLE_RETENTION_MS
        val maxBars = maxCandlesForInterval(interval)

        val map = java.util.TreeMap<Long, Candle>()
        for (c in existing) {
            if (c.time >= cutoff && c.open.isFinite() && c.close.isFinite()) {
                map[c.time] = c
            }
        }
        for (c in incomingCandles) {
            if (c.time >= cutoff && c.open.isFinite() && c.close.isFinite()) {
                map[c.time] = c
            }
        }
        val merged = map.values.toList().takeLast(maxBars)
        memoryCandleVault[cacheKey] = merged

        val ctx = appContextRef
        val lastFlush = lastCandleDiskFlushMs[cacheKey] ?: 0L
        val shouldFlush = forceFlushToDisk || (nowMillis - lastFlush >= 25_000L) || existing.size != merged.size
        if (ctx != null && shouldFlush && merged.isNotEmpty()) {
            lastCandleDiskFlushMs[cacheKey] = nowMillis
            runCatching {
                val encoded = encodeCandlesTsv(merged)
                val fileName = candleFileName(symbol, interval)
                for (root in resolveArchiveDirs(ctx)) {
                    val vaultDir = File(root, "CandleVault").apply { mkdirs() }
                    writeTextAtomic(File(vaultDir, fileName), encoded)
                }
            }
        }
        return merged
    }

    /**
     * کالبدشکافی معاملات ضررده با استفاده از خزانه ۱ ماهه کندل‌ها (`CandleVault`)
     * تا مشخص شود بعد از باز شدن و استاپ خوردن معامله دقیقاً چه اتفاقی در کندل‌های بعدی افتاده است.
     */
    fun analyzeLosingTradeMistakeFromVault(trade: PaperTrade): CandleVaultMistakeAnalysis? {
        if (trade.isOpen || (trade.pnlUsd ?: 0.0) >= 0.0) return null
        val candles = loadPersistedCandles(trade.symbol, trade.interval)
        if (candles.size < 15) return null

        val isBuy = trade.action == SignalAction.BUY
        val openTime = trade.openedAt
        val closeTime = trade.closedAt ?: (openTime + trade.interval.millis * 3)
        val postExitCandles = candles.filter { it.time in closeTime..(closeTime + trade.interval.millis * 24) }
        val preCandles = candles.filter { it.time <= openTime }.takeLast(30)

        val oneR = abs(trade.entry - (trade.initialStopLoss ?: trade.stopLoss)).coerceAtLeast(1e-8)
        val maxFavorableAfterExit = if (postExitCandles.isNotEmpty()) {
            if (isBuy) {
                ((postExitCandles.maxOf { it.high } - trade.entry) / oneR)
            } else {
                ((trade.entry - postExitCandles.minOf { it.low }) / oneR)
            }
        } else 0.0

        val wentToTpAfterStop = if (postExitCandles.isNotEmpty()) {
            if (isBuy) postExitCandles.any { it.high >= trade.takeProfit }
            else postExitCandles.any { it.low <= trade.takeProfit }
        } else false

        val preContext = if (trade.preEntry3Candles.isNotEmpty()) {
            trade.preEntry3CandleCategoryCode
        } else if (preCandles.isNotEmpty()) {
            IchimokuStrategyEngine.buildPreEntry3CandleContext(
                candles = preCandles,
                action = trade.action,
                activeModel = BitcoinStrategyModel.fromId(trade.strategyId),
            ).categoryCode
        } else ""

        return when {
            wentToTpAfterStop || maxFavorableAfterExit >= 1.5 -> CandleVaultMistakeAnalysis(
                tradeId = trade.id,
                symbol = trade.symbol,
                intervalLabel = trade.interval.label,
                strategyId = trade.strategyId,
                mistakeCode = "STOP_HUNT_THEN_TP",
                mistakeBadgeFa = "🎯 شکار شدو و حرکت بعدی به تارگت (+${String.format(Locale.US, "%.1f", maxFavorableAfterExit)}R)",
                mistakeExplanationFa = "بررسی کندل‌های ذخیره‌شده نشان می‌دهد جهت معامله روی ${trade.symbol} (${trade.interval.label}) کاملاً درست بوده اما شدوی نوسانی حد ضرر را لمس کرده و سپس قیمت تا +${String.format(Locale.US, "%.1f", maxFavorableAfterExit)}R در جهت تارگت حرکت کرده است.",
                lessonLearnedFa = "درس شبکه عصبی از خزانه کندل: در نماد ${trade.symbol} فاصله حد ضرر باید پشت فرکتال ۵ کندلی + 0.38×ATR قرار گیرد تا شدوی شکار نقدینگی استاپ را نزند.",
                wentToTpAfterStopOut = true,
                maxFavorableAfterExitR = round(maxFavorableAfterExit * 100.0) / 100.0,
            )

            preContext == PreEntry3CandleCategory.CCLAMP_EXTENDED_CHASE.code -> CandleVaultMistakeAnalysis(
                tradeId = trade.id,
                symbol = trade.symbol,
                intervalLabel = trade.interval.label,
                strategyId = trade.strategyId,
                mistakeCode = "CCLAMP_EXHAUSTION_ENTRY",
                mistakeBadgeFa = "⚠️ ورود در کشیدگی ۳ کندلی دور از کیجون (C-Clamp)",
                mistakeExplanationFa = "۳ کندل قبل از ورود در ${trade.symbol} (${trade.interval.label}) با شتاب زیاد از خط تعادل کیجون فاصله گرفته بودند و بازار بلافاصله برای پر کردن خلأ تعادلی برگشته است.",
                lessonLearnedFa = "درس شبکه عصبی از خزانه کندل: جلوگیری از ورود مستقیم روی کندل سوم کشیده؛ صبر برای پولبک به تنکان‌سن.",
                wentToTpAfterStopOut = false,
                maxFavorableAfterExitR = round(max(0.0, maxFavorableAfterExit) * 100.0) / 100.0,
            )

            else -> CandleVaultMistakeAnalysis(
                tradeId = trade.id,
                symbol = trade.symbol,
                intervalLabel = trade.interval.label,
                strategyId = trade.strategyId,
                mistakeCode = "TRUE_TREND_REVERSAL",
                mistakeBadgeFa = "🔄 چرخش روند و شکست کیجون پس از ورود",
                mistakeExplanationFa = "در کندل‌های پس از ورود روی ${trade.symbol} (${trade.interval.label})، قیمت با کلوز کامل پشت کیجون‌سن ساختار روند را نقض کرده است.",
                lessonLearnedFa = "درس شبکه عصبی از خزانه کندل: الزام هم‌جهتی شیب تنکان و کیجون با کلوز کندل ماشه در بالای/زیر سقف/کف ۲ کندل قبل.",
                wentToTpAfterStopOut = false,
                maxFavorableAfterExitR = round(max(0.0, maxFavorableAfterExit) * 100.0) / 100.0,
            )
        }
    }

    fun refreshVaultStatus(
        context: Context,
        allTrades: List<PaperTrade> = emptyList(),
    ): PhoneVaultStatusSummary {
        attachContext(context)
        val backups = listDailyBackups(context)
        val roots = resolveArchiveDirs(context)
        val primaryRoot = roots.firstOrNull()

        var shardDays = 0
        if (primaryRoot != null) {
            val shardDir = File(primaryRoot, "DailyTradeShards")
            shardDays = shardDir.listFiles { f ->
                f.isFile && f.name.startsWith("Trades_") && f.name.endsWith(".json")
            }?.size ?: 0
        }

        // شمارش کندل‌های ذخیره‌شده در حافظه و دیسک
        if (memoryCandleVault.isEmpty() && primaryRoot != null) {
            val vaultDir = File(primaryRoot, "CandleVault")
            val tsvFiles = vaultDir.listFiles { f -> f.isFile && f.name.endsWith(".tsv") }.orEmpty()
            val cutoff = System.currentTimeMillis() - CANDLE_RETENTION_MS
            for (f in tsvFiles) {
                val parsed = parseCandlesTsv(f, cutoff)
                if (parsed.isNotEmpty()) {
                    memoryCandleVault[f.name.removeSuffix(".tsv")] = parsed
                }
            }
        }

        var totalCandles = 0
        var oldestMs = Long.MAX_VALUE
        var newestMs = 0L
        for (bars in memoryCandleVault.values) {
            if (bars.isEmpty()) continue
            totalCandles += bars.size
            oldestMs = min(oldestMs, bars.first().time)
            newestMs = max(newestMs, bars.last().time)
        }

        val lessons = allTrades
            .filter { !it.isOpen && (it.pnlUsd ?: 0.0) < 0.0 }
            .take(12)
            .mapNotNull { analyzeLosingTradeMistakeFromVault(it) }

        val summary = PhoneVaultStatusSummary(
            totalShardedDays = max(shardDays, backups.size),
            totalDailyBackups = backups.size,
            latestBackupDateKey = backups.firstOrNull()?.dateKey ?: LocalDate.now(tehranZone).toString(),
            totalStoredCandlesCount = totalCandles,
            storedSymbolIntervalsCount = memoryCandleVault.count { it.value.isNotEmpty() },
            oldestCandleTimestampMs = if (oldestMs == Long.MAX_VALUE) 0L else oldestMs,
            newestCandleTimestampMs = newestMs,
            vaultPathDisplay = "Download/AurumEdge/Archive/ (DailyBackups + DailyTradeShards + CandleVault)",
            mistakeLessons = lessons,
        )
        _vaultStatus.value = summary
        return summary
    }

    private fun encodeCandlesTsv(candles: List<Candle>): String {
        val sb = StringBuilder(candles.size * 48)
        for (c in candles) {
            sb.append(c.time).append('\t')
                .append(c.open).append('\t')
                .append(c.high).append('\t')
                .append(c.low).append('\t')
                .append(c.close).append('\t')
                .append(if (c.closed) '1' else '0')
                .append('\n')
        }
        return sb.toString()
    }

    private fun parseCandlesTsv(file: File, cutoffMs: Long): List<Candle> {
        return runCatching {
            val result = ArrayList<Candle>(512)
            file.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parts = line.split('\t')
                    if (parts.size < 5) continue
                    val t = parts[0].toLongOrNull() ?: continue
                    if (t < cutoffMs) continue
                    val o = parts[1].toDoubleOrNull() ?: continue
                    val h = parts[2].toDoubleOrNull() ?: continue
                    val l = parts[3].toDoubleOrNull() ?: continue
                    val c = parts[4].toDoubleOrNull() ?: continue
                    val closed = parts.getOrNull(5) != "0"
                    result.add(Candle(time = t, open = o, high = h, low = l, close = c, closed = closed))
                }
            }
            result.sortedBy { it.time }
        }.getOrDefault(emptyList())
    }

    private fun writeTextAtomic(target: File, content: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }
}
