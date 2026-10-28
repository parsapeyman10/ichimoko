package com.aurum.edge.data

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.CustomStrategySlot
import com.aurum.edge.core.SignalProfile
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Interval
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local settings. The Twelve Data key is a read-only market-data credential
 * (no trading rights), stored in app-private storage only and never logged.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE)

    // Retired in 1.3.55: an old enabled flag must not strand an upgraded install
    // waiting for a daemon which is no longer bundled. No other preferences are cleared.
    init {
        prefs.edit().remove("embedded_tor_enabled").apply()
        migrateAutoDownload() // owner-requested: in-app updating is on unless switched off
    }

    /**
     * In-app updating became default-on in 2.0.2. A stored `false` from an older build cannot be
     * told apart from "never chosen", so the preference is enabled once, recorded as migrated, and
     * left alone afterwards. Turning the switch off after this migration sticks.
     */
    private fun migrateAutoDownload() {
        if (prefs.getBoolean(KEY_AUTO_DOWNLOAD_MIGRATED, false)) return
        prefs.edit()
            .putBoolean(KEY_AUTO_DOWNLOAD_MIGRATED, true)
            .putBoolean(KEY_AUTO_DOWNLOAD_UPDATES, true)
            .apply()
    }

    // Declared BEFORE the first read(): read() parses the per-symbol key map, and a property
    // initialised later would still be null on that first call.
    private var symbolKeyCache: Pair<String, Map<String, String>>? = null

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Dedicated Twelve Data keys, one per catalog symbol. The free plan meters every key
     * separately, so a symbol with its own key is not starved by the other thirteen.
     * Anything unreadable (corrupt JSON, unknown symbol, whitespace) is dropped rather than
     * guessed: a bad entry silently means "use the global key" and never a made-up credential.
     */
    private fun readSymbolKeys(): Map<String, String> {
        val raw = prefs.getString(KEY_TD_KEYS, null).orEmpty()
        symbolKeyCache?.let { (cachedRaw, cached) -> if (cachedRaw == raw) return cached }
        val map: Map<String, String> = if (raw.isBlank()) emptyMap() else
            runCatching { KEY_JSON.decodeFromString(symbolKeySerializer, raw) }
                .getOrElse { emptyMap<String, String>() }
                .entries.mapNotNull { entry ->
                    val symbol = V1Universe.normalize(entry.key)
                    val key = entry.value.trim()
                    if (!V1Universe.valid(symbol) || key.isEmpty() || key.any { it.isWhitespace() }) null
                    else symbol to key
                }.toMap()
        symbolKeyCache = raw to map
        return map
    }

    /** Persist one dedicated key (blank removes it) as a single verified disk write. */
    @Synchronized
    fun saveSymbolKey(symbolInput: String, keyInput: String): Boolean {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return false
        val key = keyInput.trim()
        if (key.any { it.isWhitespace() }) return false
        val next = readSymbolKeys().toMutableMap()
        if (key.isBlank()) next.remove(symbol) else next[symbol] = key
        val encoded = KEY_JSON.encodeToString(symbolKeySerializer, next)
        if (!prefs.edit().putString(KEY_TD_KEYS, encoded).commit()) return false
        symbolKeyCache = null
        if (readSymbolKeys() != next) return false
        _settings.value = read()
        return true
    }

    /** The dedicated key for one symbol, or blank when it falls back to the global key. */
    fun symbolKey(symbol: String): String = readSymbolKeys()[V1Universe.normalize(symbol)].orEmpty()

    fun read(): AppSettings {
        // Legacy profile preferences remain readable for existing on-device records, but
        // they are not V1 controls and are not displayed as editable signal filters.
        // Four scored layers and absolute locks are fixed by the current contract.
        val profile = SignalProfile.BASE
        val storedApiKey = prefs.getString(KEY_API, null)?.trim()
        val storedNewsAiKey = prefs.getString(KEY_NEWS_AI_KEY, null)?.trim().orEmpty()
        val storedNewsAiUrl = prefs.getString(KEY_NEWS_AI_URL, null)?.trim().orEmpty()
        val storedNewsAiModel = prefs.getString(KEY_NEWS_AI_MODEL, null)?.trim().orEmpty()
        val storedStrategy = com.aurum.edge.core.StrategyKind.fromId(
            prefs.getString(KEY_ACTIVE_STRATEGY, "ICHIMOKU"))
            ?: com.aurum.edge.core.StrategyKind.ICHIMOKU_PRICE_ACTION
        // Retired v1_watchlist is intentionally ignored. It cannot narrow the app catalog.
        // AI/news fields are retired: stored values stay readable for old installs but nothing
        // in the app consumes them anymore (kept only so AppSettings keeps its stored shape).
        val aiUrl = storedNewsAiUrl.ifBlank { com.aurum.edge.data.AvalAiAdvisor.DEFAULT_BASE_URL }
        val aiFormat = prefs.getString(KEY_NEWS_AI_FORMAT, "AUTO").orEmpty().ifBlank { "AUTO" }
        val aiModel = storedNewsAiModel.ifBlank { com.aurum.edge.data.AvalAiAdvisor.DEFAULT_MODEL }
        val avalAiEnabled = prefs.getBoolean(KEY_AVAL_AI_ENABLED, false)
        val storedBtcStrategyId = prefs.getString(KEY_BTC_STRATEGY_ID, BitcoinStrategyModel.BASE_ICHIMOKU.id)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BitcoinStrategyModel.BASE_ICHIMOKU.id
        val storedBaseConfig = prefs.getString(KEY_BASE_STRATEGY_CONFIG_JSON, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { KEY_JSON.decodeFromString(BaseStrategyConfig.serializer(), raw) }.getOrNull() }
            ?: BaseStrategyConfig()
        val storedCustomStrategies = prefs.getString(KEY_CUSTOM_STRATEGIES_JSON, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { KEY_JSON.decodeFromString(customStrategiesSerializer, raw) }.getOrNull() }
            ?: emptyList()
        val validStrategyId = if (
            BitcoinStrategyModel.entries.any { it.id == storedBtcStrategyId } ||
            storedCustomStrategies.any { it.id == storedBtcStrategyId }
        ) {
            storedBtcStrategyId
        } else {
            BitcoinStrategyModel.BASE_ICHIMOKU.id
        }
        val categoryStrategies = AssetClass.entries.associateWith { category ->
            CategoryStrategy.fromStored(prefs.getString("v1_strategy_${category.name}", null))
        }
        val defaultAllIds = (BitcoinStrategyModel.entries.map { it.id } + storedCustomStrategies.map { it.id }).toSet()
        val storedEnabledIdsRaw = prefs.getString(KEY_ENABLED_STRATEGY_IDS, null)?.trim().orEmpty()
        val runAllSim = prefs.getBoolean(KEY_RUN_ALL_STRATEGIES, true)
        val rawParsedEnabledIds = if (storedEnabledIdsRaw.isBlank()) {
            defaultAllIds
        } else {
            storedEnabledIdsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().ifEmpty { defaultAllIds }
        }
        val storedEnabledIds = if (runAllSim && rawParsedEnabledIds.size >= 15) {
            rawParsedEnabledIds + BitcoinStrategyModel.drivePdfStrategyIds
        } else {
            rawParsedEnabledIds
        }
        return AppSettings(
        apiKey = storedApiKey?.takeIf { it.isNotBlank() }
            ?: com.aurum.edge.BuildConfig.DEFAULT_TD_API_KEY.trim(),
        twelveDataKeys = readSymbolKeys(),
        // Symbol can be any instrument in the 15-symbol catalog (metals, Dow Jones, Ichimoku forex, top-5 crypto)
        symbol = V1Universe.normalize(prefs.getString(KEY_SYMBOL, null) ?: "XAU/USD")
            .takeIf(V1Universe::valid) ?: "XAU/USD",
        interval = Interval.M5,
        riskPercent = prefs.getFloat(KEY_RISK, 0.5f).toDouble().takeIf { it.isFinite() }?.coerceIn(0.1, 10.0) ?: 0.5,
        accountBalance = prefs.getFloat(KEY_BALANCE, 100_000f).toDouble(),
        initialCapital = prefs.getFloat(KEY_INITIAL_CAPITAL, prefs.getFloat(KEY_BALANCE, 100_000f)).toDouble()
            .takeIf { it.isFinite() && it >= 10.0 } ?: 100_000.0,
        targetCapital = prefs.getFloat(KEY_TARGET_CAPITAL, 100_000f).toDouble()
            .takeIf { it.isFinite() && it >= 100.0 } ?: 100_000.0,
        useFixedDollarRisk = prefs.getBoolean(KEY_USE_FIXED_DOLLAR_RISK, true),
        fixedRiskUsd = prefs.getFloat(KEY_FIXED_RISK_USD, 5.0f).toDouble()
            .takeIf { it.isFinite() && it >= 0.5 } ?: 5.0,
        dailyLossLimitPercent = prefs.getFloat(KEY_DAILY_LOSS_LIMIT, 100.0f).toDouble()
            .takeIf { it.isFinite() }?.coerceIn(0.5, 100.0) ?: 100.0,
        maxDrawdownLimitPercent = prefs.getFloat(KEY_MAX_DRAWDOWN_LIMIT, 100.0f).toDouble()
            .takeIf { it.isFinite() }?.coerceIn(1.0, 100.0) ?: 100.0,
        minConfidence = prefs.getFloat(KEY_MIN_CONF, 85f).toDouble().takeIf { it.isFinite() }?.coerceIn(60.0, 95.0) ?: 85.0,
        spreadPrice = prefs.getFloat(KEY_SPREAD, 0.12f).toDouble(),
        commissionPerOz = prefs.getFloat(KEY_COMMISSION, 0.035f).toDouble(),
        backgroundMonitor = prefs.getBoolean(KEY_MONITOR, true),
        notifyOnSignal = prefs.getBoolean(KEY_NOTIFY, true),
        alertSoundUri = prefs.getString(KEY_ALERT_SOUND_URI, "").orEmpty(),
        alertSoundName = prefs.getString(KEY_ALERT_SOUND_NAME, "").orEmpty(),
        alarmVolumePercent = prefs.getInt(KEY_ALARM_VOLUME_PCT, 90).coerceIn(0, 100),
        alarmTonePreset = prefs.getString(KEY_ALARM_TONE_PRESET, "CUSTOM_ONLY").orEmpty().ifBlank { "CUSTOM_ONLY" },
        alarmVibrationEnabled = prefs.getBoolean(KEY_ALARM_VIBRATION_ENABLED, true),
        entryAlarmSoundUri = prefs.getString(KEY_ENTRY_ALARM_SOUND_URI, "").orEmpty(),
        entryAlarmSoundName = prefs.getString(KEY_ENTRY_ALARM_SOUND_NAME, "Ding-Dong").orEmpty().ifBlank { "Ding-Dong" },
        tpAlarmSoundUri = prefs.getString(KEY_TP_ALARM_SOUND_URI, "").orEmpty(),
        tpAlarmSoundName = prefs.getString(KEY_TP_ALARM_SOUND_NAME, "Red Dwarf").orEmpty().ifBlank { "Red Dwarf" },
        slAlarmSoundUri = prefs.getString(KEY_SL_ALARM_SOUND_URI, "").orEmpty(),
        slAlarmSoundName = prefs.getString(KEY_SL_ALARM_SOUND_NAME, "Latch").orEmpty().ifBlank { "Latch" },
        maxLeverageCap = prefs.getInt(KEY_MAX_LEVERAGE_CAP, 10).coerceIn(1, 10),
        newsBaseUrl = prefs.getString(KEY_NEWS_URL, "").orEmpty().trim(),
        pauseOnNews = prefs.getBoolean(KEY_NEWS_PAUSE, false),
        autoPaperTrading = prefs.getBoolean(KEY_AUTO_PAPER, false),
        autoDownloadUpdates = prefs.getBoolean(KEY_AUTO_DOWNLOAD_UPDATES, true),
        avalAiEnabled = avalAiEnabled,
        newsAiApiKey = storedNewsAiKey,
        newsAiBaseUrl = aiUrl,
        newsAiModel = aiModel,
        newsAiFormat = aiFormat,
        signalProfile = profile,
        activeStrategy = storedStrategy,
        selectedBitcoinStrategyId = validStrategyId,
        runAllStrategiesSimultaneously = prefs.getBoolean(KEY_RUN_ALL_STRATEGIES, true),
        enabledStrategyIds = storedEnabledIds,
        baseStrategyConfig = storedBaseConfig,
        customStrategies = storedCustomStrategies,
        categoryStrategies = categoryStrategies,
    )
    }

    /**
     * Persist the optional market key and symbol in ONE disk transaction. A successful commit,
     * not merely an in-memory SharedPreferences.apply(), is required before reconnecting.
     * Empty input preserves an existing/default key; if no key exists it explicitly enters the
     * keyless public-feed mode instead of blocking the Yahoo/Swissquote connection.
     */
    @Synchronized
    fun saveMarketCredentials(keyInput: String, symbolInput: String): Boolean {
        val existing = read().apiKey.trim()
        val key = keyInput.trim().ifBlank { existing }
        if (key.any { it.isWhitespace() }) return false
        val symbol = V1Universe.normalize(symbolInput).ifBlank { "XAU/USD" }
        val valid = V1Universe.valid(symbol)
        if (!valid) return false
        val saved = prefs.edit().putString(KEY_API, key).putString(KEY_SYMBOL, symbol).commit()
        if (saved && prefs.getString(KEY_API, null).orEmpty() == key && prefs.getString(KEY_SYMBOL, null) == symbol) {
            _settings.value = read()
            return true
        }
        // Do not report success or restart the feed on a failed disk write.
        return false
    }

    /**
     * Switch ONLY the chart/signal symbol, without touching the stored key.
     * Allowed set is the instrument universe (gold, commodities, forex, crypto).
     */
    @Synchronized
    fun saveChartSymbol(symbolInput: String): Boolean {
        val symbol = V1Universe.normalize(symbolInput)
        val valid = V1Universe.valid(symbol)
        if (!valid) return false
        val saved = prefs.edit().putString(KEY_SYMBOL, symbol).commit()
        if (saved && prefs.getString(KEY_SYMBOL, null) == symbol) {
            _settings.value = read()
            return true
        }
        return false
    }

    /** Market-only archive on the user's own HTTPS host. Never send journal/account data.
     * The bearer token stays in app-private preferences, not in AppSettings/Compose state.
     */
    fun tickArchiveConfig(): Pair<String, String>? {
        val url = prefs.getString(KEY_TICK_ARCHIVE_URL, null)?.trim().orEmpty()
        val token = prefs.getString(KEY_TICK_ARCHIVE_TOKEN, null).orEmpty()
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null || parsed.encodedPath != "/" || token.length < 32)
            return null
        return url.trimEnd('/') to token
    }

    fun tickArchiveUrl(): String = prefs.getString(KEY_TICK_ARCHIVE_URL, null).orEmpty()
    fun hasTickArchive(): Boolean = tickArchiveConfig() != null

    @Synchronized
    fun saveTickArchive(urlInput: String, tokenInput: String): Boolean {
        val url = urlInput.trim().trimEnd('/')
        if (url.isBlank()) return prefs.edit().remove(KEY_TICK_ARCHIVE_URL)
            .remove(KEY_TICK_ARCHIVE_TOKEN).commit()
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null || parsed.encodedPath != "/") return false
        val token = tokenInput.trim().ifEmpty { prefs.getString(KEY_TICK_ARCHIVE_TOKEN, null).orEmpty() }
        if (token.length < 32 || token.any { it.isWhitespace() }) return false
        return prefs.edit().putString(KEY_TICK_ARCHIVE_URL, url)
            .putString(KEY_TICK_ARCHIVE_TOKEN, token).commit()
    }

    /**
     * Real-time continuous balance adjustment upon settled paper trade (profit or loss).
     * Ensures position sizing and compounding dynamically track actual account equity.
     */
    /** Stable UTC-day paper equity. Manual balance increases cannot enlarge today's loss cap.
     * Must be called before every balance mutation and again by the journal's final entry guard.
     */
    @Synchronized
    fun dayOpeningEquity(now: Long = System.currentTimeMillis()): Double {
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        if (prefs.getString("v1_risk_day", null) == day) {
            return prefs.getFloat("v1_risk_open_equity", 0f).toDouble()
        }
        val opening = read().accountBalance
        check(opening.isFinite() && opening > 0.0 && prefs.edit()
            .putString("v1_risk_day", day).putFloat("v1_risk_open_equity", opening.toFloat()).commit()) {
            "موجودی آغاز روز برای قفل ریسک ثبت نشد"
        }
        return opening
    }

    @Synchronized
    fun adjustBalance(deltaUsd: Double) {
        if (!deltaUsd.isFinite() || deltaUsd == 0.0) return
        dayOpeningEquity()
        val current = read()
        val updated = kotlin.math.round((current.accountBalance + deltaUsd) * 100.0) / 100.0
        val finalBalance = maxOf(10.0, updated)
        update { it.copy(accountBalance = finalBalance) }
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val proposed = transform(_settings.value)
        if (proposed.accountBalance != _settings.value.accountBalance) dayOpeningEquity()
        val next = proposed.copy(
            symbol = V1Universe.normalize(proposed.symbol).takeIf(V1Universe::valid) ?: "XAU/USD",
            interval = Interval.M5,
            riskPercent = proposed.riskPercent.takeIf { it.isFinite() }?.coerceIn(0.1, 10.0) ?: 0.5,
            initialCapital = proposed.initialCapital.takeIf { it.isFinite() && it >= 10.0 } ?: proposed.accountBalance.coerceAtLeast(10.0),
            targetCapital = proposed.targetCapital.takeIf { it.isFinite() && it >= 100.0 } ?: 100_000.0,
            fixedRiskUsd = proposed.fixedRiskUsd.takeIf { it.isFinite() && it >= 0.5 } ?: 5.0,
            dailyLossLimitPercent = proposed.dailyLossLimitPercent.takeIf { it.isFinite() }?.coerceIn(0.5, 25.0) ?: 3.0,
            maxDrawdownLimitPercent = proposed.maxDrawdownLimitPercent.takeIf { it.isFinite() }?.coerceIn(1.0, 50.0) ?: 10.0,
            minConfidence = proposed.minConfidence.takeIf { it.isFinite() }?.coerceIn(60.0, 95.0) ?: 85.0,
        )
        prefs.edit()
            .putString(KEY_API, next.apiKey.trim())
            .putString(KEY_SYMBOL, next.symbol.trim().ifBlank { "XAU/USD" })
            .putString(KEY_INTERVAL, next.interval.label)
            .putFloat(KEY_RISK, next.riskPercent.toFloat())
            .putFloat(KEY_BALANCE, next.accountBalance.toFloat())
            .putFloat(KEY_INITIAL_CAPITAL, next.initialCapital.toFloat())
            .putFloat(KEY_TARGET_CAPITAL, next.targetCapital.toFloat())
            .putBoolean(KEY_USE_FIXED_DOLLAR_RISK, next.useFixedDollarRisk)
            .putFloat(KEY_FIXED_RISK_USD, next.fixedRiskUsd.toFloat())
            .putFloat(KEY_DAILY_LOSS_LIMIT, next.dailyLossLimitPercent.toFloat())
            .putFloat(KEY_MAX_DRAWDOWN_LIMIT, next.maxDrawdownLimitPercent.toFloat())
            .putFloat(KEY_MIN_CONF, next.minConfidence.toFloat())
            .putFloat(KEY_SPREAD, next.spreadPrice.toFloat())
            .putFloat(KEY_COMMISSION, next.commissionPerOz.toFloat())
            .putBoolean(KEY_MONITOR, next.backgroundMonitor)
            .putBoolean(KEY_NOTIFY, next.notifyOnSignal)
            .putString(KEY_ALERT_SOUND_URI, next.alertSoundUri)
            .putString(KEY_ALERT_SOUND_NAME, next.alertSoundName)
            .putInt(KEY_ALARM_VOLUME_PCT, next.alarmVolumePercent.coerceIn(0, 100))
            .putString(KEY_ALARM_TONE_PRESET, next.alarmTonePreset.ifBlank { "CUSTOM_ONLY" })
            .putBoolean(KEY_ALARM_VIBRATION_ENABLED, next.alarmVibrationEnabled)
            .putString(KEY_ENTRY_ALARM_SOUND_URI, next.entryAlarmSoundUri)
            .putString(KEY_ENTRY_ALARM_SOUND_NAME, next.entryAlarmSoundName.ifBlank { "Ding-Dong" })
            .putString(KEY_TP_ALARM_SOUND_URI, next.tpAlarmSoundUri)
            .putString(KEY_TP_ALARM_SOUND_NAME, next.tpAlarmSoundName.ifBlank { "Red Dwarf" })
            .putString(KEY_SL_ALARM_SOUND_URI, next.slAlarmSoundUri)
            .putString(KEY_SL_ALARM_SOUND_NAME, next.slAlarmSoundName.ifBlank { "Latch" })
            .putInt(KEY_MAX_LEVERAGE_CAP, next.maxLeverageCap.coerceIn(1, 10))
            .putString(KEY_NEWS_URL, next.newsBaseUrl.trim())
            .putString(KEY_SIGNAL_PROFILE, next.signalProfile.persistName())
            .putBoolean(KEY_SIGNAL_MOMENTUM_VOLUME, next.signalProfile.momentumVolume)
            .putBoolean(KEY_SIGNAL_FLAT_SPAN_B, next.signalProfile.flatSpanB)
            .putBoolean(KEY_SIGNAL_RANGE_CHOP, next.signalProfile.rangeChopFilter)
            .putBoolean(KEY_SIGNAL_HIGHER_TIMEFRAME, next.signalProfile.higherTimeframeFilter)
            .putBoolean(KEY_SIGNAL_FAKE_BREAKOUT, next.signalProfile.fakeBreakoutFilter)
            .putBoolean(KEY_SIGNAL_DYNAMIC_SPREAD, next.signalProfile.dynamicSpreadFilter)
            .putBoolean(KEY_SIGNAL_RISKY_TIMING, next.signalProfile.riskyTimingFilter)
            .putBoolean(KEY_SIGNAL_STRUCTURE_RISK, next.signalProfile.structureRiskFilter)
            .putBoolean(KEY_SIGNAL_COOLDOWN, next.signalProfile.cooldownFilter)
            .putBoolean(KEY_SIGNAL_CHIKOU, next.signalProfile.chikouConfirmation)
            .putString(KEY_ACTIVE_STRATEGY, next.activeStrategy.id)
            .putString(KEY_BTC_STRATEGY_ID, next.selectedBitcoinStrategyId)
            .putBoolean(KEY_RUN_ALL_STRATEGIES, next.runAllStrategiesSimultaneously)
            .putString(KEY_ENABLED_STRATEGY_IDS, next.enabledStrategyIds.joinToString(","))
            .putString(KEY_BASE_STRATEGY_CONFIG_JSON, KEY_JSON.encodeToString(BaseStrategyConfig.serializer(), next.baseStrategyConfig))
            .putString(KEY_CUSTOM_STRATEGIES_JSON, KEY_JSON.encodeToString(customStrategiesSerializer, next.customStrategies))
            .apply {
                AssetClass.entries.forEach { category ->
                    putString("v1_strategy_${category.name}",
                        (next.categoryStrategies[category] ?: CategoryStrategy.HYBRID).name)
                }
            }
            .putBoolean(KEY_NEWS_PAUSE, next.pauseOnNews)
            .putBoolean(KEY_AUTO_PAPER, next.autoPaperTrading)
            .putBoolean(KEY_AUTO_DOWNLOAD_UPDATES, next.autoDownloadUpdates)
            .putBoolean(KEY_AVAL_AI_ENABLED, next.avalAiEnabled)
            .putString(KEY_NEWS_AI_KEY, next.newsAiApiKey.trim())
            .putString(KEY_NEWS_AI_URL, next.newsAiBaseUrl.trim().ifBlank { com.aurum.edge.data.AvalAiAdvisor.DEFAULT_BASE_URL })
            .putString(KEY_NEWS_AI_MODEL, next.newsAiModel.trim().ifBlank { com.aurum.edge.data.AvalAiAdvisor.DEFAULT_MODEL })
            .apply()
        _settings.value = next
    }

    fun setLastSync(symbol: String, interval: Interval = Interval.M5, at: Long = System.currentTimeMillis()) {
        val norm = V1Universe.normalize(symbol)
        prefs.edit().putLong("last_sync_${norm}_${interval.label}", at).apply()
    }

    fun getLastSync(symbol: String, interval: Interval = Interval.M5): Long? {
        val norm = V1Universe.normalize(symbol)
        val ts = prefs.getLong("last_sync_${norm}_${interval.label}", 0L)
        return ts.takeIf { it > 0L }
    }

    /**
     * مهاجرت یک‌باره موجودی حساب به ۱۰۰,۰۰۰ دلار و تنظیم کلید پیش‌فرض AvalAI در زمان اجرای برنامه روی دستگاه کاربر
     */
    fun ensureBalance100kMigrated() {
        val cur = read()
        val needsBalanceUpgrade = !prefs.getBoolean(KEY_BALANCE_100K_MIGRATED_V3903, false) && cur.accountBalance < 50_000.0
        val needsKeyPreload = cur.newsAiApiKey.isBlank()
        val all24StrategyIds = (BitcoinStrategyModel.entries.map { it.id } + cur.customStrategies.map { it.id }).toSet()
        val needsAll15StrategiesMigration = !prefs.getBoolean(KEY_ALL_15_COMBOS_MIGRATED_V3908, false)
        val needsCleanAlarmMigration = !prefs.getBoolean(KEY_CLEAN_ALARM_V3915, false)
        if (!prefs.getBoolean(KEY_BALANCE_100K_MIGRATED_V3903, false)) {
            prefs.edit().putBoolean(KEY_BALANCE_100K_MIGRATED_V3903, true).apply()
        }
        if (needsAll15StrategiesMigration) {
            prefs.edit().putBoolean(KEY_ALL_15_COMBOS_MIGRATED_V3908, true).apply()
        }
        if (needsCleanAlarmMigration) {
            prefs.edit().putBoolean(KEY_CLEAN_ALARM_V3915, true).apply()
        }
        if (needsBalanceUpgrade || needsKeyPreload || needsAll15StrategiesMigration || needsCleanAlarmMigration) {
            update {
                it.copy(
                    accountBalance = if (needsBalanceUpgrade) 100_000.0 else it.accountBalance,
                    initialCapital = if (needsBalanceUpgrade) 100_000.0 else it.initialCapital,
                    dailyLossLimitPercent = 100.0,
                    maxDrawdownLimitPercent = 100.0,
                    useFixedDollarRisk = true,
                    fixedRiskUsd = 5.0,
                    alarmTonePreset = if (needsCleanAlarmMigration) "CUSTOM_ONLY" else it.alarmTonePreset,
                    entryAlarmSoundName = it.entryAlarmSoundName.ifBlank { "Ding-Dong" },
                    tpAlarmSoundName = it.tpAlarmSoundName.ifBlank { "Red Dwarf" },
                    slAlarmSoundName = it.slAlarmSoundName.ifBlank { "Latch" },
                    newsAiApiKey = if (it.newsAiApiKey.isBlank()) com.aurum.edge.data.AvalAiAdvisor.PRECONFIGURED_USER_KEY else it.newsAiApiKey,
                    enabledStrategyIds = if (it.runAllStrategiesSimultaneously) (it.enabledStrategyIds + all24StrategyIds) else it.enabledStrategyIds,
                )
            }
        }
    }

    companion object {
        /** Dedicated keys are a symbol→key JSON map; unknown keys are ignored, never guessed. */
        private val symbolKeySerializer = MapSerializer(String.serializer(), String.serializer())
        private val customStrategiesSerializer = ListSerializer(CustomStrategySlot.serializer())
        private val KEY_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val KEY_CLEAN_ALARM_V3915 = "clean_alarm_migrated_v3915"
        private const val KEY_API = "td_api_key"
        private const val KEY_TD_KEYS = "td_symbol_keys_json"
        private const val KEY_SYMBOL = "symbol"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_RISK = "risk_percent"
        private const val KEY_BALANCE = "balance"
        private const val KEY_INITIAL_CAPITAL = "initial_capital"
        private const val KEY_DAILY_LOSS_LIMIT = "daily_loss_limit_percent"
        private const val KEY_MAX_DRAWDOWN_LIMIT = "max_drawdown_limit_percent"
        private const val KEY_MIN_CONF = "min_confidence"
        private const val KEY_SPREAD = "spread_price"
        private const val KEY_COMMISSION = "commission_per_oz"
        private const val KEY_MONITOR = "background_monitor"
        private const val KEY_NOTIFY = "notify_signal"
        private const val KEY_ALERT_SOUND_URI = "verified_alert_sound_uri"
        private const val KEY_ALERT_SOUND_NAME = "verified_alert_sound_name"
        private const val KEY_ALARM_VOLUME_PCT = "alarm_volume_pct_v3913"
        private const val KEY_ALARM_TONE_PRESET = "alarm_tone_preset_v3913"
        private const val KEY_ALARM_VIBRATION_ENABLED = "alarm_vibration_enabled_v3913"
        private const val KEY_ENTRY_ALARM_SOUND_URI = "entry_alarm_sound_uri_v3913"
        private const val KEY_ENTRY_ALARM_SOUND_NAME = "entry_alarm_sound_name_v3913"
        private const val KEY_TP_ALARM_SOUND_URI = "tp_alarm_sound_uri_v3913"
        private const val KEY_TP_ALARM_SOUND_NAME = "tp_alarm_sound_name_v3913"
        private const val KEY_SL_ALARM_SOUND_URI = "sl_alarm_sound_uri_v3913"
        private const val KEY_SL_ALARM_SOUND_NAME = "sl_alarm_sound_name_v3913"
        private const val KEY_MAX_LEVERAGE_CAP = "max_leverage_cap_v3913"
        private const val KEY_NEWS_URL = "news_base_url"
        private const val KEY_TICK_ARCHIVE_URL = "tick_archive_url"
        private const val KEY_TICK_ARCHIVE_TOKEN = "tick_archive_token"
        private const val KEY_NEWS_PAUSE = "pause_on_news"
        // Historical preference key must not be renamed: an existing opt-in survives an upgrade.
        private const val KEY_AUTO_PAPER = "auto_paper_nine_conditions"
        private const val KEY_AUTO_DOWNLOAD_UPDATES = "auto_download_updates"
        private const val KEY_AUTO_DOWNLOAD_MIGRATED = "auto_download_updates_migrated_2_0_2"
        private const val KEY_AVAL_AI_ENABLED = "aval_ai_pretrade_enabled"
        private const val KEY_NEWS_AI_KEY = "news_ai_client_key"
        private const val KEY_NEWS_AI_URL = "news_ai_client_base_url"
        private const val KEY_NEWS_AI_MODEL = "news_ai_client_model"
        private const val KEY_NEWS_AI_FORMAT = "news_ai_format"
        private const val KEY_SIGNAL_PROFILE = "signal_profile"
        private const val KEY_SIGNAL_MOMENTUM_VOLUME = "signal_momentum_volume"
        private const val KEY_SIGNAL_FLAT_SPAN_B = "signal_flat_span_b"
        private const val KEY_SIGNAL_RANGE_CHOP = "signal_range_chop_filter"
        private const val KEY_SIGNAL_HIGHER_TIMEFRAME = "signal_higher_timeframe_filter"
        private const val KEY_SIGNAL_FAKE_BREAKOUT = "signal_fake_breakout_filter"
        private const val KEY_SIGNAL_DYNAMIC_SPREAD = "signal_dynamic_spread_filter"
        private const val KEY_SIGNAL_RISKY_TIMING = "signal_risky_timing_filter"
        private const val KEY_SIGNAL_STRUCTURE_RISK = "signal_structure_risk_filter"
        private const val KEY_SIGNAL_COOLDOWN = "signal_cooldown_filter"
        private const val KEY_SIGNAL_CHIKOU = "signal_chikou_confirmation"
        private const val KEY_ACTIVE_STRATEGY = "active_strategy"
        private const val KEY_BTC_STRATEGY_ID = "btc_exclusive_strategy_id"
        private const val KEY_RUN_ALL_STRATEGIES = "btc_run_all_strategies_simultaneously"
        private const val KEY_ENABLED_STRATEGY_IDS = "btc_enabled_strategy_ids_csv"
        private const val KEY_TARGET_CAPITAL = "target_capital_usd"
        private const val KEY_USE_FIXED_DOLLAR_RISK = "use_fixed_dollar_risk"
        private const val KEY_FIXED_RISK_USD = "fixed_risk_usd"
        private const val KEY_BALANCE_100K_MIGRATED_V3903 = "balance_100k_migrated_v3903"
        private const val KEY_ALL_15_COMBOS_MIGRATED_V3908 = "all_15_combos_migrated_v3908"
        private const val KEY_BASE_STRATEGY_CONFIG_JSON = "btc_base_strategy_config_json"
        private const val KEY_CUSTOM_STRATEGIES_JSON = "btc_custom_strategies_json"
    }
}
