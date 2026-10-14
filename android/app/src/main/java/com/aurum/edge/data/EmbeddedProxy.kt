package com.aurum.edge.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import android.os.SystemClock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.torproject.jni.TorService

/** App-private, bound Tor daemon. It requires no account, server URL, key or VPN permission.
 * Tor itself rebuilds circuits after network changes. A failed daemon bind is retried with
 * capped backoff; an offline network never spins a tight retry loop. Android force-stop,
 * battery restrictions, blocked Tor entry nodes and an offline phone remain real limits.
 */
class EmbeddedProxy(context: Context, settings: SettingsStore) {
    enum class Status(val label: String) {
        OFF("خاموش · اتصال مستقیم"),
        STARTING("در حال اتصال به Tor؛ ممکن است چند دقیقه طول بکشد"),
        ONLINE("مدار Tor آماده است؛ دسترسی هر منبع جداگانه بررسی می‌شود"),
        OFFLINE("اینترنت در دسترس نیست؛ اتصال مجدد خودکار"),
        RETRYING("پروکسی آماده نیست؛ تلاش مجدد خودکار، بدون مسیر مستقیم"),
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(Status.OFF)
    val status = mutableStatus.asStateFlow()
    private val bridge = SnowflakeBridge(app)
    private val mutableDetail = MutableStateFlow("پروکسی خاموش است")
    val detail = mutableDetail.asStateFlow()
    private var service: TorService? = null
    private var bound = false
    private var failed = false
    private var enabled = settings.read().proxyEnabled
    private val restart = MutableStateFlow(0L)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TorService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            failed = true
            if (enabled) AppNetwork.configure(true)
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Never copy Tor exception/log text into analytics, UI or journal records.
            if (intent.action == TorService.ACTION_ERROR ||
                intent.getStringExtra(TorService.EXTRA_STATUS) == TorService.STATUS_OFF) {
                if (enabled) {
                    failed = true
                    AppNetwork.configure(true)
                    mutableStatus.value = Status.RETRYING
                }
            }
        }
    }

    init {
        // Synchronous BEFORE repositories construct or launch any clients.
        AppNetwork.configure(enabled)
        WebProxyRouting.start(scope)
        LocalBroadcastManager.getInstance(app).registerReceiver(receiver, IntentFilter().apply {
            addAction(TorService.ACTION_ERROR)
            addAction(TorService.ACTION_STATUS)
        })
        scope.launch {
            combine(settings.settings.map { it.proxyEnabled }.distinctUntilChanged(), restart) { requested, retry ->
                requested to retry
            }.collectLatest { (requested, _) ->
                unbind()
                enabled = requested
                AppNetwork.configure(requested)
                if (!requested) {
                    unbind()
                    mutableStatus.value = Status.OFF
                    mutableDetail.value = "پروکسی خاموش است؛ مسیر مستقیم یا VPN سیستم"
                    return@collectLatest
                }
                var attempt = 0
                var backoff = 5_000L
                var bootstrapStarted = SystemClock.elapsedRealtime()
                mutableStatus.value = Status.STARTING
                while (true) {
                    if (!hasInternet()) {
                        AppNetwork.configure(true)
                        mutableStatus.value = Status.OFFLINE
                        delay(5_000)
                        continue
                    }
                    if (failed) {
                        attempt++
                        unbind()
                        AppNetwork.configure(true)
                        mutableStatus.value = Status.RETRYING
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    }
                    if (!bound) {
                        failed = false
                        mutableDetail.value = if (attempt % 2 == 0) "Snowflake · اتصال ضدانسداد در حال شروع" else "Snowflake AMP · تلاش با مسیر جایگزین"
                        // Loopback only. No relay/exit service, DNS port or LAN listener.
                        val configured = withContext(Dispatchers.IO) {
                            runCatching {
                                TorService.getTorrc(app).apply { parentFile?.mkdirs() }.writeText(
                                    bridge.start(amp = attempt % 2 != 0)
                                )
                            }.isSuccess
                        }
                        bound = configured && runCatching {
                            app.bindService(Intent(app, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
                        }.getOrDefault(false)
                        if (!bound) {
                            failed = true
                            continue
                        }
                        bootstrapStarted = SystemClock.elapsedRealtime()
                        mutableStatus.value = Status.STARTING
                    }
                    val daemon = service
                    // Query local control state only, never a third-party IP/identity endpoint.
                    val port = if (daemon?.torControlConnection != null) withContext(Dispatchers.IO) {
                        if (runCatching { daemon.getInfo("status/circuit-established") == "1" }.getOrDefault(false))
                            daemon.getSocksPort().takeIf { it in 1..65535 } ?: 0 else 0
                    } else 0
                    val percent = if (daemon?.torControlConnection != null) withContext(Dispatchers.IO) {
                        SnowflakeConfig.bootstrapPercent(runCatching { daemon.getInfo("status/bootstrap-phase") }.getOrNull())
                    } else null
                    mutableDetail.value = "Snowflake${if (attempt % 2 != 0) " AMP" else ""} · " +
                        if (port > 0) "مدار Tor آماده؛ دسترسی Google جدا بررسی می‌شود" else "پیشرفت Tor: ${percent ?: 0}٪؛ آماده نیست"
                    AppNetwork.configure(true, port)
                    if (port > 0) {
                        mutableStatus.value = Status.ONLINE
                        backoff = 5_000L
                        bootstrapStarted = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - bootstrapStarted > 300_000L) {
                        failed = true
                        mutableStatus.value = Status.RETRYING
                    } else if (mutableStatus.value != Status.RETRYING) mutableStatus.value = Status.STARTING
                    delay(5_000)
                }
            }
        }
    }

    /** Called on the UI thread after the durable preference write, before any refresh. */
    fun applyPreference(enabled: Boolean) {
        this.enabled = enabled
        AppNetwork.configure(enabled)
        mutableStatus.value = if (enabled) Status.STARTING else Status.OFF
    }

    fun reconnect() {
        if (enabled) {
            AppNetwork.configure(true)
            mutableStatus.value = Status.STARTING
            restart.value++
        }
        WebProxyRouting.retry()
    }

    private fun hasInternet(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun unbind() {
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        service = null
        failed = false
        bridge.stop()
    }
}
