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
    )

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
    }
}
