package com.ddpai.uploader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.ddpai.uploader.data.config.ConfigRepository

class NetworkMonitor(
    private val context: Context,
    private val configRepo: ConfigRepository,
    private val onNetwork: (NetworkType, Network?) -> Unit
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluate(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate(network)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = evaluate(network)
            override fun onLost(network: Network) { onNetwork(NetworkType.NONE, null) }
        }
        callback = cb
        cm.registerNetworkCallback(request, cb)
    }

    fun stop() {
        callback?.let { cm.unregisterNetworkCallback(it) }
        callback = null
    }

    private fun evaluate(network: Network) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            onNetwork(NetworkType.OTHER, null)
            return
        }
        val gatewayConfigured = configRepo.config.value.dashcamGateway
        val gatewayIp = NetworkGateway.extract(cm, network, context)
        val type = when {
            gatewayIp == gatewayConfigured -> NetworkType.DASHCAM_AP
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkType.HOME_WIFI
            else -> NetworkType.OTHER
        }
        onNetwork(type, network)
    }
}
