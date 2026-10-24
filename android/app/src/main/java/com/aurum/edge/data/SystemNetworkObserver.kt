package com.aurum.edge.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities

/** Observe only this app's DEFAULT Android network. Never bind to an underlying Wi-Fi/
 * cellular network, open a bypass socket, start a VPN, query an IP service or collect
 * credentials. VPN inclusion, disconnection and kill-switch policy belong to Android/VPN.
 */
internal class SystemNetworkObserver(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refresh(force = true)
    }

    fun start() {
        refresh()
        // If a device refuses registration, ordinary platform sockets still follow its
        // default route; no private proxy or bypass fallback is installed.
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    fun refresh(force: Boolean = false) {
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val available = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        AppNetwork.observeNetwork(network, available, force)
    }
}
