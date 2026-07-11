package com.ddpai.uploader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.util.Locale

/**
 * Extracts the IPv4 gateway (dotted string) for a link. Primary method: the default-route
 * gateway from LinkProperties. Fallback (matching the original NetworkMonitor behavior): the
 * legacy WifiManager.dhcpInfo gateway — but since DhcpInfo reflects the ACTIVE Wi-Fi only, it is
 * applied solely when a [context] is provided and [network] is the active network, so callers
 * that iterate multiple networks (DashcamNetworkResolver) can never false-match on it.
 */
object NetworkGateway {
    fun extract(cm: ConnectivityManager, network: Network, context: Context? = null): String? {
        cm.getLinkProperties(network)?.routes?.forEach { r ->
            val gw = r.gateway
            if (r.isDefaultRoute && gw is Inet4Address) {
                return gw.hostAddress
            }
        }
        if (context != null && cm.activeNetwork == network) {
            try {
                val wifi = context.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val g = wifi?.dhcpInfo?.gateway ?: return null
                if (g != 0) {
                    return String.format(
                        Locale.US, "%d.%d.%d.%d",
                        g and 0xff, g shr 8 and 0xff, g shr 16 and 0xff, g shr 24 and 0xff
                    )
                }
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }
}
