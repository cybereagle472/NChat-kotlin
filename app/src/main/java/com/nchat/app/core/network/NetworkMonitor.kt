package com.nchat.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this device has a usable internet connection — the honest answer, not a guess.
 *
 * Before this the app only knew whether its *WebSocket* was up, which is a different question:
 * a phone that walks into a lift keeps a `Connected` socket for up to a keep-alive interval,
 * and then spends the next backoff believing it is merely "reconnecting". The one signal that
 * actually changes the moment reality changes is the platform's own network callback, so it is
 * the one the app listens to.
 *
 * "Usable" means `NET_CAPABILITY_INTERNET` **and** `NET_CAPABILITY_VALIDATED`: a captive-portal
 * wifi that accepts connections and answers nothing is exactly the case a naive
 * `activeNetwork != null` check gets wrong, and it is the case that looks to the user like
 * "the app says connected but nothing arrives".
 *
 * Everything here is callback-driven — no polling, no wake-ups, no battery cost while idle.
 */
class NetworkMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull()

    private val _online = MutableStateFlow(read())
    /** True while the device has a validated internet connection. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _transport = MutableStateFlow(label())
    /** "Wi-Fi", "Mobile data", "Ethernet", "VPN" — shown in Settings and the sync check. */
    val transport: StateFlow<String?> = _transport.asStateFlow()

    @Volatile private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()

        override fun onUnavailable() = refresh()

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) = refresh()
    }

    /** Idempotent; safe to call from every entry point. */
    fun start() {
        if (started || manager == null) return
        started = true
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    /** Current value without waiting for a callback (cheap; used by the first read). */
    fun isOnline(): Boolean = read()

    private fun refresh() {
        _online.value = read()
        _transport.value = label()
    }

    private fun read(): Boolean = runCatching {
        val capabilities = active() ?: return true // unreadable: assume online, never freeze
        capabilities.hasInternet()
    }.getOrDefault(true)

    private fun label(): String? = runCatching {
        val capabilities = active() ?: return null
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> null
        }
    }.getOrNull()

    private fun active(): NetworkCapabilities? {
        val connectivity = manager ?: return null
        val network = connectivity.activeNetwork ?: return null
        return connectivity.getNetworkCapabilities(network)
    }

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
