package com.ddpai.uploader.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Finds the currently-connected Wi-Fi [Network] whose IPv4 gateway matches the dashcam gateway,
 * without relying on any previously-registered callback. Safe to call from a Worker after process
 * restart.
 */
class DashcamNetworkResolver(private val cm: ConnectivityManager) {
    fun resolve(configuredGateway: String): Network? {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (NetworkGateway.extract(cm, n) == configuredGateway) return n
        }
        return null
    }
}
