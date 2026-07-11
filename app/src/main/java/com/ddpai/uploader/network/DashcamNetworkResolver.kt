package com.ddpai.uploader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Finds the currently-connected Wi-Fi [Network] whose IPv4 gateway matches the dashcam gateway,
 * without relying on any previously-registered callback. Safe to call from a Worker after process
 * restart. Passes [context] to NetworkGateway so the DhcpInfo fallback covers APs that expose no
 * default route (the fallback self-limits to the active network).
 */
class DashcamNetworkResolver(
    private val cm: ConnectivityManager,
    private val context: Context
) {
    fun resolve(configuredGateway: String): Network? {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (NetworkGateway.extract(cm, n, context) == configuredGateway) return n
        }
        return null
    }
}
