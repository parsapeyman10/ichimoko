package com.aurum.edge.data

import android.content.Context
import IPtProxy.Controller
import IPtProxy.IPtProxy
import java.io.File

/** Tor CLIENT transport, not a Snowflake relay/proxy for other users. No account,
 * third-party key, remote config, telemetry or transport log files. Public bridge
 * configuration pinned from Orbot; TLS verification is never disabled.
 */
internal class SnowflakeBridge(private val context: Context) {
    private val controller by lazy {
        val dir = File(context.noBackupFilesDir, "snowflake").apply { mkdirs() }
        Controller(dir.path, false, false, "ERROR", null)
    }
    private var running = false

    fun start(amp: Boolean): String {
        val bridges = context.assets.open("snowflake-bridges.txt").bufferedReader().use { it.readLines() }
            .filter { it.startsWith("snowflake ") }
        require(bridges.isNotEmpty())
        fun parameter(key: String) = bridges.first().split(' ').firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=').orEmpty()
        controller.snowflakeIceServers = parameter("ice")
        controller.snowflakeBrokerUrl = if (amp) "https://snowflake-broker.torproject.net/" else parameter("url")
        controller.snowflakeFrontDomains = if (amp) "www.google.com" else parameter("fronts")
        controller.snowflakeAmpCacheUrl = if (amp) "https://cdn.ampproject.org/" else ""
        controller.snowflakeSqsUrl = ""
        controller.snowflakeSqsCreds = ""
        running = true
        controller.start(IPtProxy.Snowflake, null)
        val port = controller.port(IPtProxy.Snowflake).toInt()
        return SnowflakeConfig.torrc(port, bridges, amp)
    }

    fun stop() {
        if (running) runCatching { controller.stop(IPtProxy.Snowflake) }
        running = false
    }
}

internal object SnowflakeConfig {
    fun torrc(port: Int, bridges: List<String>, amp: Boolean): String {
        require(port in 1..65535)
        require(bridges.isNotEmpty() && bridges.all { it.startsWith("snowflake ") && !it.contains('\n') && !it.contains('\r') })
        val rows = bridges.map { bridge ->
            if (!amp) bridge else bridge.split(' ').map { field -> when {
                field.startsWith("url=") -> "url=https://snowflake-broker.torproject.net/"
                field.startsWith("fronts=") -> "fronts=www.google.com"
                else -> field
            } }.joinToString(" ")
        }
        return "SocksPort 127.0.0.1:auto\nHTTPTunnelPort 0\nClientOnly 1\nSafeLogging 1\n" +
            "UseBridges 1\nClientTransportPlugin snowflake socks5 127.0.0.1:$port\n" +
            rows.joinToString("\n", postfix = "\n") { "Bridge $it" }
    }

    fun bootstrapPercent(control: String?): Int? = Regex("(?:^| )PROGRESS=(\\d{1,3})(?: |$)")
        .find(control.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
}
