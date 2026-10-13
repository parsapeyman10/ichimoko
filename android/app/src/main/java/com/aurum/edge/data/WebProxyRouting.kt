package com.aurum.edge.data

import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Chromium is separate from OkHttp. No page may escape through a direct connection while
 * proxy mode is selected. Only the current acknowledged revision can create a WebView. */
object WebProxyRouting {
    enum class Status(val message: String) {
        APPLYING("در حال تنظیم مسیر چارت…"),
        WAITING_TOR("Tor هنوز متصل نشده است؛ چارت منتظر اتصال است، نه به‌روزرسانی WebView."),
        UNSUPPORTED("این WebView از پروکسی پشتیبانی نمی‌کند؛ Android System WebView را به‌روز کنید یا پروکسی را با انتخاب خود خاموش کنید."),
        FAILED("تنظیم مسیر WebView کامل نشد؛ دوباره تلاش کنید. اتصال مستقیم پنهانی انجام نمی‌شود."),
        READY("مسیر چارت آماده است"),
    }
    private val mutableAppliedRevision = MutableStateFlow(-1L)
    val appliedRevision = mutableAppliedRevision.asStateFlow()
    private val mutableStatus = MutableStateFlow(Status.APPLYING)
    val status = mutableStatus.asStateFlow()
    private val retryRequest = MutableStateFlow(0L)
    private var started = false
    private val views = mutableSetOf<WebView>()

    fun retry() { retryRequest.value++ }
    fun track(view: WebView) { views.add(view) }
    fun release(view: WebView) {
        if (views.remove(view)) { view.stopLoading(); view.destroy() }
    }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        AppNetwork.beforeRouteChange = { views.toList().forEach(::release) }
        scope.launch {
            combine(AppNetwork.route, retryRequest) { route, retry -> route to retry }.collectLatest { (route, _) ->
                mutableAppliedRevision.value = -1L
                if (!route.ready) {
                    mutableStatus.value = Status.WAITING_TOR
                    return@collectLatest
                }
                mutableStatus.value = Status.APPLYING
                try {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                        mutableStatus.value = if (route.enabled) Status.UNSUPPORTED else Status.READY
                        if (!route.enabled) mutableAppliedRevision.value = route.revision
                        return@collectLatest
                    }
                    val applied = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val done = Runnable { if (continuation.isActive) continuation.resume(Unit) }
                            val executor = Executor { it.run() }
                            if (route.enabled) {
                                val config = ProxyConfig.Builder()
                                    // AndroidX documents SOCKS, not a socks5 scheme alias.
                                    .addProxyRule(proxyRule(route.socksPort))
                                    .removeImplicitRules().build()
                                ProxyController.getInstance().setProxyOverride(config, executor, done)
                            } else ProxyController.getInstance().clearProxyOverride(executor, done)
                        }
                        true
                    } == true
                    if (route == AppNetwork.route.value) {
                        if (applied) mutableAppliedRevision.value = route.revision
                        mutableStatus.value = if (applied) Status.READY else Status.FAILED
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { mutableStatus.value = Status.FAILED }
            }
        }
    }

    internal fun proxyRule(port: Int): String {
        require(port in 1..65535)
        return "socks://127.0.0.1:$port"
    }
}
