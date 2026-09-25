package com.aurum.edge.data

import android.content.Context
import android.content.SharedPreferences
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.Interval
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

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun read(): AppSettings = AppSettings(
        apiKey = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
            ?: com.aurum.edge.BuildConfig.DEFAULT_TD_API_KEY,
        symbol = prefs.getString(KEY_SYMBOL, null) ?: "XAU/USD",
        interval = Interval.fromLabel(prefs.getString(KEY_INTERVAL, null) ?: "5m"),
        riskPercent = prefs.getFloat(KEY_RISK, 0.5f).toDouble(),
        accountBalance = prefs.getFloat(KEY_BALANCE, 100f).toDouble(),
        minConfidence = prefs.getFloat(KEY_MIN_CONF, 72f).toDouble(),
        spreadPrice = prefs.getFloat(KEY_SPREAD, 0.30f).toDouble(),
        commissionPerOz = prefs.getFloat(KEY_COMMISSION, 0.05f).toDouble(),
        backgroundMonitor = prefs.getBoolean(KEY_MONITOR, false),
        notifyOnSignal = prefs.getBoolean(KEY_NOTIFY, true),
        alertSoundUri = prefs.getString(KEY_ALERT_SOUND_URI, "").orEmpty(),
        alertSoundName = prefs.getString(KEY_ALERT_SOUND_NAME, "").orEmpty(),
        newsBaseUrl = prefs.getString(KEY_NEWS_URL, "").orEmpty(),
        pauseOnNews = prefs.getBoolean(KEY_NEWS_PAUSE, false),
        autoPaperTrading = prefs.getBoolean(KEY_AUTO_PAPER, false),
        cryptoBaseUrl = prefs.getString(KEY_CRYPTO_URL, "").orEmpty(),
        workspaceId = prefs.getString(KEY_WORKSPACE, "").orEmpty(),
        stockDataKey = prefs.getString(KEY_STOCK_DATA, "").orEmpty(),
    )

    /**
     * Persist the market key and symbol in ONE disk transaction. A successful commit,
     * not merely an in-memory SharedPreferences.apply(), is required before reconnecting.
     * Empty input preserves an existing key; it never silently erases credentials.
     */
    @Synchronized
    fun saveMarketCredentials(keyInput: String, symbolInput: String): Boolean {
        val key = keyInput.trim().ifBlank { read().apiKey }
        if (key.isBlank() || key.any { it.isWhitespace() }) return false
        val symbol = symbolInput.trim().uppercase(java.util.Locale.ROOT).ifBlank { "XAU/USD" }
        val saved = prefs.edit().putString(KEY_API, key).putString(KEY_SYMBOL, symbol).commit()
        if (saved && prefs.getString(KEY_API, null) == key && prefs.getString(KEY_SYMBOL, null) == symbol) {
            _settings.value = read()
            return true
        }
        // Do not report success or restart the feed on a failed disk write.
        return false
    }

    /** A workspace switch must hit disk before any non-Forex screen or monitor starts. */
    @Synchronized
    fun selectWorkspace(id: String): Boolean {
        if (id !in setOf("forex", "crypto", "nobitex", "iran_stocks", "")) return false
        val previous = read()
        val sameSpace = id.isNotBlank() && previous.workspaceId == id
        val saved = prefs.edit().putString(KEY_WORKSPACE, id)
            .putBoolean(KEY_MONITOR, sameSpace && previous.backgroundMonitor)
            .putBoolean(KEY_AUTO_PAPER, sameSpace && id == "forex" && previous.autoPaperTrading)
            .commit()
        if (saved && prefs.getString(KEY_WORKSPACE, null) == id) {
            _settings.value = read()
            return true
        }
        return false
    }

    /** A read-only market-data key, not an Agah/Nobitex trading token. Never prefill the UI. */
    @Synchronized
    fun saveStockDataKey(input: String): Boolean {
        val key = input.trim()
        if (!key.matches(Regex("[A-Za-z0-9_-]{10,80}"))) return false
        val saved = prefs.edit().putString(KEY_STOCK_DATA, key).commit()
        if (saved && prefs.getString(KEY_STOCK_DATA, null) == key) {
            _settings.value = read()
            return true
        }
        return false
    }

    @Synchronized
    fun saveCryptoBaseUrl(url: String): Boolean {
        val saved = prefs.edit().putString(KEY_CRYPTO_URL, url).commit()
        if (saved && prefs.getString(KEY_CRYPTO_URL, null) == url) {
            _settings.value = read()
            return true
        }
        return false
    }

    @Synchronized
    fun clearStockDataKey(): Boolean {
        val saved = prefs.edit().remove(KEY_STOCK_DATA).commit()
        if (saved && prefs.getString(KEY_STOCK_DATA, null) == null) {
            _settings.value = read()
            return true
        }
        return false
    }

    /** Server configuration must also survive process death before we say it was saved. */
    @Synchronized
    fun saveNewsBaseUrl(url: String): Boolean {
        val saved = prefs.edit().putString(KEY_NEWS_URL, url).commit()
        if (saved && prefs.getString(KEY_NEWS_URL, null) == url) {
            _settings.value = read()
            return true
        }
        return false
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_API, next.apiKey.trim())
            .putString(KEY_SYMBOL, next.symbol.trim().ifBlank { "XAU/USD" })
            .putString(KEY_INTERVAL, next.interval.label)
            .putFloat(KEY_RISK, next.riskPercent.toFloat())
            .putFloat(KEY_BALANCE, next.accountBalance.toFloat())
            .putFloat(KEY_MIN_CONF, next.minConfidence.toFloat())
            .putFloat(KEY_SPREAD, next.spreadPrice.toFloat())
            .putFloat(KEY_COMMISSION, next.commissionPerOz.toFloat())
            .putBoolean(KEY_MONITOR, next.backgroundMonitor)
            .putBoolean(KEY_NOTIFY, next.notifyOnSignal)
            .putString(KEY_ALERT_SOUND_URI, next.alertSoundUri)
            .putString(KEY_ALERT_SOUND_NAME, next.alertSoundName)
            .putString(KEY_NEWS_URL, next.newsBaseUrl.trim())
            .putString(KEY_CRYPTO_URL, next.cryptoBaseUrl.trim())
            .putBoolean(KEY_NEWS_PAUSE, next.pauseOnNews)
            .putBoolean(KEY_AUTO_PAPER, next.autoPaperTrading)
            .putString(KEY_WORKSPACE, next.workspaceId)
            .putString(KEY_STOCK_DATA, next.stockDataKey)
            .apply()
        _settings.value = next
    }

    fun setLastSync(symbol: String, interval: Interval, at: Long) {
        prefs.edit().putLong("last_sync_${symbol}_${interval.label}", at).apply()
    }

    fun lastSync(symbol: String, interval: Interval): Long? =
        prefs.getLong("last_sync_${symbol}_${interval.label}", 0L).takeIf { it > 0L }

    companion object {
        private const val KEY_API = "td_api_key"
        private const val KEY_SYMBOL = "symbol"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_RISK = "risk_percent"
        private const val KEY_BALANCE = "balance"
        private const val KEY_MIN_CONF = "min_confidence"
        private const val KEY_SPREAD = "spread_price"
        private const val KEY_COMMISSION = "commission_per_oz"
        private const val KEY_MONITOR = "background_monitor"
        private const val KEY_NOTIFY = "notify_signal"
        private const val KEY_ALERT_SOUND_URI = "verified_alert_sound_uri"
        private const val KEY_ALERT_SOUND_NAME = "verified_alert_sound_name"
        private const val KEY_NEWS_URL = "news_base_url"
        private const val KEY_CRYPTO_URL = "crypto_base_url"
        private const val KEY_NEWS_PAUSE = "pause_on_news"
        private const val KEY_AUTO_PAPER = "auto_paper_nine_conditions"
        private const val KEY_WORKSPACE = "active_workspace"
        private const val KEY_STOCK_DATA = "stock_data_readonly_key"
    }
}
