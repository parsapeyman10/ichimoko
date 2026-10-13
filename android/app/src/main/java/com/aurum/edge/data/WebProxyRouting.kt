package com.aurum.edge.data

import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Chromium has its own transport; OkHttp's proxy does not cover TradingView.
 * A chart is created only AFTER Chromium acknowledges the current proxy revision.
 * Unsupported WebViews are blocked in proxy mode rather than quietly using direct traffic.
 */
object WebProxyRouting {
    private val mutableAppliedRevision = MutableStateFlow(-1L)
    val appliedRevision = mutableAppliedRevision.asStateFlow()
    private var started = false
    private val views = mutableSetOf<WebView>()

    fun track(view: WebView) { views.add(view) }
    fun release(view: WebView) {
        if (views.remove(view)) {
            view.stopLoading()
            view.destroy()
        }
    }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        AppNetwork.beforeRouteChange = {
            // A proxy override alone does not close a page's already-open WebSockets.
            // Destroy old Chromium contexts before acknowledging the new route.
            views.toList().forEach(::release)
        }
        scope.launch {
            AppNetwork.route.collect { route ->
                mutableAppliedRevision.value = -1L
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    if (!route.enabled) mutableAppliedRevision.value = route.revision
                    return@collect
                }
                val applied = runCatching {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val done = Runnable { if (continuation.isActive) continuation.resume(Unit) }
                        val executor = Executor { it.run() }
                        if (route.enabled) {
                            val port = route.socksPort.takeIf { it in 1..65535 } ?: 1
                            val config = ProxyConfig.Builder()
                                .addProxyRule("socks5://127.0.0.1:$port")
                                .removeImplicitRules() // no localhost bypass or DIRECT fallback
                                .build()
                            ProxyController.getInstance().setProxyOverride(config, executor, done)
                        } else ProxyController.getInstance().clearProxyOverride(executor, done)
                    }
                }.isSuccess
                if (applied && route == AppNetwork.route.value) mutableAppliedRevision.value = route.revision
            }
        }
    }
}
