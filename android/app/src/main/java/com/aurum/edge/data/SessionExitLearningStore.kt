package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SessionExitLearning
import com.aurum.edge.core.SessionExitPlanner
import com.aurum.edge.domain.SignalAction
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * حافظهٔ پایدار «یادگیری زمان خروج پیش از سشن».
 *
 * دو وظیفه دارد:
 *  ۱. نگه‌داشتن مشاهده‌های واقعی (`SessionExitLearning.Observation`) و تبدیل آن‌ها به زمان سربار
 *     هر دستهٔ دارایی.
 *  ۲. نگه‌داشتن «خروج‌های معلق»: هر پوزیشنی که پیش از پایان سشن بسته می‌شود، قیمت خروج و حجمش
 *     ذخیره می‌شود؛ وقتی بازار بازگشایی شد، اولین قیمت معتبر می‌آید و پاداش واقعی محاسبه می‌شود
 *     (نگه‌داشتن پوزیشن بهتر بود یا بستن آن).
 *
 * همهٔ دسترسی‌ها `@Synchronized` است و پرونده روی دیسک سقف اندازه دارد تا رشد بی‌پایان نکند.
 */
object SessionExitLearningStore {

    private const val FILE_NAME = "session_exit_learning_v1.json"
    private const val MAX_PENDING = 60

    @Serializable
    private data class ObservationRecord(
        val assetClassCode: String,
        val leadMinutes: Int,
        val savedUsd: Double,
        val atMs: Long,
        val symbol: String = "",
    )

    @Serializable
    private data class PendingExit(
        val tradeId: String,
        val symbol: String,
        val direction: Int,
        val positionSize: Double,
        val exitPrice: Double,
        val leadMinutes: Int,
        val atMs: Long,
        val requiredR: Double = 0.0,
    )

    @Serializable
    private data class Persisted(
        val schemaVersion: Int = 1,
        val observations: List<ObservationRecord> = emptyList(),
        val pending: List<PendingExit> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private var storageFile: File? = null
    private var state = Persisted()

    private val _estimates = MutableStateFlow<List<SessionExitLearning.Estimate>>(emptyList())
    val estimates: StateFlow<List<SessionExitLearning.Estimate>> = _estimates.asStateFlow()

    private val _lastResolvedFa = MutableStateFlow("")
    val lastResolvedFa: StateFlow<String> = _lastResolvedFa.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (storageFile != null) return
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            storageFile = file
            if (file.exists()) {
                state = json.decodeFromString<Persisted>(file.readText(Charsets.UTF_8))
            }
            refreshEstimates()
        }
    }

    /** زمان سربارِ در حال استفاده برای یک نماد (آموخته‌شده یا پیش‌فرض دسته). */
    fun leadMinutesFor(symbol: String): Int {
        val assetClass = AssetClass.of(symbol)
        if (assetClass == AssetClass.CRYPTO) return 0
        val estimate = _estimates.value.firstOrNull { it.assetClassCode == assetClass.code }
        return estimate?.leadMinutes ?: SessionExitPlanner.defaultLeadMinutes(assetClass)
    }

    fun observations(): List<SessionExitLearning.Observation> =
        state.observations.map {
            SessionExitLearning.Observation(
                assetClassCode = it.assetClassCode,
                leadMinutes = it.leadMinutes,
                savedUsd = it.savedUsd,
                atMs = it.atMs,
                symbol = it.symbol,
            )
        }

    /** تعداد خروج‌های معلقِ منتظر بازگشایی بازار. */
    fun pendingCount(): Int = state.pending.size

    /**
     * ثبت «پیش از سشن بستم» — تا بازگشایی بازار، پاداش واقعی معلوم نیست.
     */
    @Synchronized
    fun recordPreCloseExit(
        trade: PaperTrade,
        exitPrice: Double,
        leadMinutes: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!exitPrice.isFinite() || exitPrice <= 0.0) return
        val direction = when (trade.action) {
            SignalAction.BUY -> 1
            SignalAction.SELL -> -1
            else -> return
        }
        val record = PendingExit(
            tradeId = trade.id,
            symbol = trade.symbol,
            direction = direction,
            positionSize = trade.positionOz.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
            exitPrice = exitPrice,
            leadMinutes = leadMinutes,
            atMs = now,
        )
        state = state.copy(
            pending = (state.pending.filterNot { it.tradeId == record.tradeId } + record)
                .sortedByDescending { it.atMs }
                .take(MAX_PENDING),
        )
        persist()
    }

    /**
     * بازار بازگشایی شد: پاداشِ واقعیِ خروج زودتر را از تفاوت قیمت خروج و قیمت بازگشایی حساب می‌کند.
     *
     * @return متن فارسی نتیجه برای نمایش در توست، یا null اگر خروج معلقی برای این نماد نبود.
     */
    @Synchronized
    fun resolveReopen(
        symbolInput: String,
        reopenPrice: Double,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (state.pending.isEmpty()) return null
        val symbol = com.aurum.edge.core.V1Universe.normalize(symbolInput)
        val pending = state.pending.firstOrNull {
            com.aurum.edge.core.V1Universe.normalize(it.symbol) == symbol
        } ?: return null
        if (com.aurum.edge.core.MarketHours.closedFor(symbol, now)) return null
        val saved = SessionExitLearning.savedUsd(
            exitPrice = pending.exitPrice,
            reopenPrice = reopenPrice,
            direction = pending.direction,
            positionSize = pending.positionSize,
        )
        val clamped = SessionExitLearning.clampReward(saved)
        val assetClass = AssetClass.of(symbol)
        val observation = SessionExitLearning.Observation(
            assetClassCode = assetClass.code,
            leadMinutes = pending.leadMinutes,
            savedUsd = clamped,
            atMs = now,
            symbol = symbol,
        )
        val merged = SessionExitLearning.appendObservation(observations(), observation)
        state = state.copy(
            observations = merged.map {
                ObservationRecord(
                    assetClassCode = it.assetClassCode,
                    leadMinutes = it.leadMinutes,
                    savedUsd = it.savedUsd,
                    atMs = it.atMs,
                    symbol = it.symbol,
                )
            },
            pending = state.pending.filterNot { it.tradeId == pending.tradeId },
        )
        refreshEstimates()
        persist()
        val estimate = _estimates.value.firstOrNull { it.assetClassCode == assetClass.code }
        val text = String.format(
            java.util.Locale.US,
            "🧠 بازگشایی %s: اگر پوزیشن را نگه می‌داشتیم %s%,.2f$ %s می‌شد · زمان سربار آموخته‌شده: %d دقیقه",
            symbol,
            if (clamped >= 0.0) "+" else "-",
            kotlin.math.abs(clamped),
            if (clamped >= 0.0) "بهتر بود بستن" else "بهتر بود نگه‌داشتن",
            estimate?.leadMinutes ?: SessionExitPlanner.defaultLeadMinutes(assetClass),
        )
        _lastResolvedFa.value = text
        return text
    }

    private fun refreshEstimates() {
        _estimates.value = SessionExitLearning.estimateAll(observations())
    }

    private fun persist() {
        val file = storageFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(state), Charsets.UTF_8)
        }
    }

    /** پاک‌سازی کامل (برای تست یا بازنشانی از تنظیمات). */
    @Synchronized
    fun reset() {
        state = Persisted()
        _estimates.value = emptyList()
        _lastResolvedFa.value = ""
        refreshEstimates()
        persist()
    }
}
